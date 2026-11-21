"""POST /api/tv_panel/press {"key": ...[, "event_only": true, "action": "page"|"none"]}: OK on one of the TV's chips.

The integration does what the chip's settings say (resolve_ok), as the TV's own user, and fires
tv_panel_chip_pressed for every press, so automations can act on any chip. A page the TV opens itself: the answer
just names it. The TV only ever says which chip; what that does is set here, by an admin.
"""

from __future__ import annotations

import asyncio
from http import HTTPStatus
import logging
from typing import Any

from aiohttp import web
from homeassistant.components.http import KEY_HASS, KEY_HASS_USER, HomeAssistantView
from homeassistant.core import Context, HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
import voluptuous as vol

from . import model
from .view import NOT_A_TV, tv_entry

EVENT = "tv_panel_chip_pressed"

# How long a press waits for its action before answering. The TV gives up after 6 s, and Home Assistant cancels a
# request whose client went away, so the action runs as a task of its own and isn't cut off: a long script carries on.
PRESS_WAIT = 4.0

_LOGGER = logging.getLogger(__name__)


def _name(hass: HomeAssistant, entity_id: str, label: str | None = None) -> str:
    state = hass.states.get(entity_id)
    return label or (state.attributes.get("friendly_name") if state else None) or entity_id


def _toggle(hass: HomeAssistant, entity_id: str) -> tuple[str, str, str]:
    """The service, and what it's about to do, as each domain's toggle decides: a moving cover stops, a closed one
    opens; a lock has no toggle, so it locks or unlocks; anything else turns off unless it's off (a thermostat in
    "heat" or a player "playing" is on)."""
    domain = entity_id.split(".")[0]
    state = hass.states.get(entity_id)
    now = state.state if state else ""
    if domain == "lock":
        return ("lock", "unlock", "unlocking") if now == "locked" else ("lock", "lock", "locking")
    if domain in ("cover", "valve"):
        doing = "stopping" if now in ("opening", "closing") else "opening" if now == "closed" else "closing"
        return domain, "toggle", doing
    return domain, "toggle", "turning on" if now == "off" else "turning off"


def _log_failure(task: asyncio.Task) -> None:
    if not task.cancelled() and (err := task.exception()) is not None:
        _LOGGER.warning("A TV Panel press failed after it answered: %s", err)


async def async_press(hass: HomeAssistant, chip: dict[str, Any], context: Context) -> dict[str, Any]:
    ok = model.resolve_ok(hass, chip)
    action = ok["action"]
    if action == "page":
        return {"ok": True, "page": ok["page"]} | ({"camera": ok["camera"]} if "camera" in ok else {})
    if action == "none":
        return {"ok": True}
    if action == "toggle":
        name = _name(hass, ok["entity"], chip.get("label") if ok["entity"] == chip.get("entity") else None)
        domain, service, doing = _toggle(hass, ok["entity"])
        data: dict[str, Any] = {"entity_id": [ok["entity"]]}
        done = waiting = f"{name}: {doing}"
    elif action == "run":
        name = _name(hass, ok["entity"])
        domain, service = ok["entity"].split(".")[0], "turn_on"
        data = {"entity_id": [ok["entity"]]}
        done = waiting = f"{name}: started"
    else:
        name = chip.get("label") or "That"
        domain, service = ok["perform_action"].split(".", 1)
        data = dict(ok.get("data") or {})
        target = ok.get("target") or {}
        if "entity_id" in target:
            data["entity_id"] = cv.comp_entity_ids(target["entity_id"])
        done, waiting = "Done", "Started"
    task = hass.async_create_task(
        hass.services.async_call(domain, service, data, blocking=True, context=context), "tv_panel press"
    )
    finished, _ = await asyncio.wait({task}, timeout=PRESS_WAIT)
    if not finished:
        task.add_done_callback(_log_failure)
        return {"ok": True, "message": waiting}
    try:
        task.result()
    except (HomeAssistantError, vol.Invalid) as err:
        return {"ok": False, "message": f"{name}: {err}"}
    except Exception as err:
        _LOGGER.exception("A TV Panel press of %s.%s failed", domain, service)
        return {"ok": False, "message": f"{name}: {err}"}
    return {"ok": True, "message": done}


class TvPanelPressView(HomeAssistantView):
    """Only a TV's own user can press, and only its own chips."""

    url = "/api/tv_panel/press"
    name = "api:tv_panel:press"
    requires_auth = True

    async def post(self, request: web.Request) -> web.Response:
        hass = request.app[KEY_HASS]
        user = request[KEY_HASS_USER]
        try:
            body = await request.json()
            key = str(body["key"])
        except (ValueError, KeyError, TypeError):
            return self.json_message("Send {\"key\": ...}", HTTPStatus.BAD_REQUEST)
        # The TV opened a page itself, or the chip does nothing: it only reports the press, for automations. What
        # the chip is set to here is not done, since the TV may not have caught up with a change to it yet.
        event_only = body.get("event_only") is True
        entry = tv_entry(hass, user)
        if entry is None:
            return self.json_message(NOT_A_TV, HTTPStatus.NOT_FOUND)
        chip = next((c for c in model.settings(hass, entry.options)["chips"] if model.chip_key(c) == key), None)
        if chip is None:
            return self.json_message("No such chip", HTTPStatus.NOT_FOUND)
        context = Context(user_id=user.id)
        if event_only:
            action = body.get("action") if body.get("action") in ("page", "none") else "page"
        else:
            action = model.resolve_ok(hass, chip)["action"]
        hass.bus.async_fire(
            EVENT, {"entry_id": entry.entry_id, "tv": entry.title, "chip": key, "action": action}, context=context
        )
        if event_only:
            return self.json({"ok": True})
        return self.json(await async_press(hass, chip, context))
