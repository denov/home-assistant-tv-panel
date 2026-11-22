"""What the TV reads, each for that TV's own user only.

GET /api/tv_panel/config       the TV's settings
GET /api/tv_panel/data/<name>  what it draws: the chip row ("chips") and the "cameras", "tank" and "solar" screens
GET /api/tv_panel/data/<type>?screen=<id>  one of its "thermostat" or "air_quality" screens
"""

from __future__ import annotations

from http import HTTPStatus
from ipaddress import ip_address
import logging
from typing import Any

from aiohttp import web
from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import User
from homeassistant.components.http import KEY_HASS, KEY_HASS_USER, HomeAssistantView
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST
from homeassistant.core import HomeAssistant

from . import data, model
from .const import CONF_USER_ID, DOMAIN

_LOGGER = logging.getLogger(__name__)
NOT_A_TV = "Not a TV Panel TV"


def tv_entry(hass: HomeAssistant, user: User) -> ConfigEntry | None:
    """The TV this user is: each TV signs in as its own user, so one TV never sees another's settings."""
    return next(
        (e for e in hass.config_entries.async_loaded_entries(DOMAIN) if e.data.get(CONF_USER_ID) == user.id), None
    )


def _keep_address(hass: HomeAssistant, entry: ConfigEntry, remote: str | None) -> None:
    """The TV's address as its requests show it. The router can give the TV a new one, and tv_panel.show finds the
    TV's Android TV Remote by address. Only a LAN address counts: not this machine (a proxy on it), nor the internet.
    """
    if not remote or remote == entry.data.get(CONF_HOST):
        return
    try:
        ip = ip_address(remote)
    except ValueError:
        return
    if ip.is_loopback or not ip.is_private:
        return
    _LOGGER.info("%s is at %s now (was %s)", entry.title, remote, entry.data.get(CONF_HOST))
    hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_HOST: remote})


def _for_tv(ok: dict[str, Any]) -> dict[str, Any]:
    """A chip's OK as the TV reads it. Apps before 0.7.0 open a chip only when its OK says "page" (and which, in
    "page"), so a built-in screen goes as both: the one place the old word stays. A screen those apps don't have
    goes as "none" to them, so OK only fires the event; 0.7.0 reads "screen" first."""
    if ok["action"] != "screen":
        return ok
    if ok["screen"] in (*model.BUILT_IN_SCREENS, model.CAMERA):
        return ok | {"action": "page", "page": ok["screen"]}
    return {"action": "none", "screen": ok["screen"]}


class TvPanelConfigView(HomeAssistantView):
    """Never saved: every setting is null, and the app keeps using the labels and helpers it used before. "entities"
    and "camera_order" are what app 0.2.0 reads. The TV asks every few seconds, so this is also where its address is
    kept current.
    """

    url = "/api/tv_panel/config"
    name = "api:tv_panel:config"
    requires_auth = True

    async def get(self, request: web.Request) -> web.Response:
        hass = request.app[KEY_HASS]
        entry = tv_entry(hass, request[KEY_HASS_USER])
        if entry is None:
            return self.json_message(NOT_A_TV, HTTPStatus.NOT_FOUND)
        _keep_address(hass, entry, request.remote)
        row = entry.runtime_data.row
        if not model.saved(entry.options):
            never = {k: None for k in (*model.KEYS, "entities", "camera_order")}
            return self.json(never | {"row": row, "screens": model.settings(hass, entry.options)["screens"]})
        s = model.settings(hass, entry.options)
        chips = [
            {k: v for k, v in c.items() if k != "ok"} | {"ok": _for_tv(model.resolve_ok(hass, c, s["screens"]))}
            for c in s["chips"]
        ]
        return self.json(s | {"chips": chips, "entities": model.flatten(s), "camera_order": s["cameras"], "row": row})


class TvPanelDataView(HomeAssistantView):
    """Built here rather than by the app's own templates, because /api/template is for admins only.

    TVs paired before 0.6.0 got an admin user for those templates. An app that asks here doesn't need it any more, so
    its user becomes a normal one.
    """

    url = "/api/tv_panel/data/{name}"
    name = "api:tv_panel:data"
    requires_auth = True

    async def get(self, request: web.Request, name: str) -> web.Response:
        hass = request.app[KEY_HASS]
        user = request[KEY_HASS_USER]
        entry = tv_entry(hass, user)
        if entry is None:
            return self.json_message(NOT_A_TV, HTTPStatus.NOT_FOUND)
        build = data.BUILDERS.get(name)
        for_screen = data.SCREEN_BUILDERS.get(name)
        if build is None and for_screen is None:
            names = ", ".join((*data.BUILDERS, *data.SCREEN_BUILDERS))
            return self.json_message(f"No {name}: there's {names}", HTTPStatus.NOT_FOUND)
        screen = None
        if for_screen is not None:
            # Only a screen of this TV's own, of that type.
            want = request.query.get("screen")
            screens = model.settings(hass, entry.options)["screens"]
            screen = next((s for s in screens if s["id"] == want and s["type"] == name), None)
            if screen is None:
                return self.json_message(f"{entry.title} has no {name} screen {want}", HTTPStatus.NOT_FOUND)
        if user.is_admin:
            await hass.auth.async_update_user(user, group_ids=[GROUP_ID_USER])
            _LOGGER.info("%s: the TV's user is a normal user now, as the app no longer needs admin", entry.title)
        return self.json(build(hass, entry) if build is not None else for_screen(hass, entry, screen))
