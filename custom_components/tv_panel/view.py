"""GET /api/tv_panel/config: a TV's settings, for that TV's own user only."""

from __future__ import annotations

from http import HTTPStatus

from aiohttp import web
from homeassistant.components.http import KEY_HASS, KEY_HASS_USER, HomeAssistantView

from .const import CONF_USER_ID, DOMAIN, OPT_CAMERA_ORDER, OPT_ENTITIES, OPT_SOLAR


class TvPanelConfigView(HomeAssistantView):
    """The TV finds its entry by the user it signs in as, so one TV never sees another's settings.

    A setting that was never saved is null: the app then keeps using the labels and helpers it used before.
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
            return self.json(
                {
                    "entities": entry.options.get(OPT_ENTITIES),
                    "camera_order": entry.options.get(OPT_CAMERA_ORDER),
                    "solar": entry.options.get(OPT_SOLAR),
                    "row": entry.runtime_data.row,
                }
            )
        return self.json_message("Not a TV Panel TV", HTTPStatus.NOT_FOUND)
