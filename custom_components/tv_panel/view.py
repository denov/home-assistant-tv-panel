"""GET /api/tv_panel/config: a TV's settings, for that TV's own user only."""

from __future__ import annotations

from http import HTTPStatus

from aiohttp import web
from homeassistant.components.http import KEY_HASS, KEY_HASS_USER, HomeAssistantView

from . import model
from .const import CONF_USER_ID, DOMAIN


class TvPanelConfigView(HomeAssistantView):
    """The TV finds its entry by the user it signs in as, so one TV never sees another's settings.

    Never saved: every setting is null, and the app keeps using the labels and helpers it used before. "entities" and
    "camera_order" are what app 0.2.0 reads.
    """

    url = "/api/tv_panel/config"
    name = "api:tv_panel:config"
    requires_auth = True

    async def get(self, request: web.Request) -> web.Response:
        hass = request.app[KEY_HASS]
        user = request[KEY_HASS_USER]
        for entry in hass.config_entries.async_loaded_entries(DOMAIN):
            if entry.data.get(CONF_USER_ID) != user.id:
                continue
            row = entry.runtime_data.row
            if not model.saved(entry.options):
                return self.json({k: None for k in (*model.KEYS, "entities", "camera_order")} | {"row": row})
            s = model.settings(hass, entry.options)
            chips = [{k: v for k, v in c.items() if k != "ok"} | {"ok": model.resolve_ok(hass, c)} for c in s["chips"]]
            return self.json(
                s | {"chips": chips, "entities": model.flatten(s), "camera_order": s["cameras"], "row": row}
            )
        return self.json_message("Not a TV Panel TV", HTTPStatus.NOT_FOUND)
