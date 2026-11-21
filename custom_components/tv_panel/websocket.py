"""The TV Panel page's API (admins only): tv_panel/tvs, tv_panel/settings and tv_panel/save."""

from __future__ import annotations

from typing import Any

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback
import voluptuous as vol

from . import model
from .const import DOMAIN


@callback
def async_register(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, ws_tvs)
    websocket_api.async_register_command(hass, ws_settings)
    websocket_api.async_register_command(hass, ws_save)


def _entry(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]):
    entry = hass.config_entries.async_get_entry(msg["entry_id"])
    if entry is None or entry.domain != DOMAIN:
        connection.send_error(msg["id"], websocket_api.ERR_NOT_FOUND, "No such TV")
        return None
    return entry


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "tv_panel/tvs"})
@callback
def ws_tvs(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    connection.send_result(
        msg["id"], [{"entry_id": e.entry_id, "title": e.title} for e in hass.config_entries.async_entries(DOMAIN)]
    )


@websocket_api.require_admin
@websocket_api.websocket_command({vol.Required("type"): "tv_panel/settings", vol.Required("entry_id"): str})
@callback
def ws_settings(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    if (entry := _entry(hass, connection, msg)) is None:
        return
    connection.send_result(msg["id"], model.settings(hass, entry.options) | {"saved": model.saved(entry.options)})


@websocket_api.require_admin
@websocket_api.websocket_command(
    {vol.Required("type"): "tv_panel/save", vol.Required("entry_id"): str, vol.Required("settings"): dict}
)
@callback
def ws_save(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    if (entry := _entry(hass, connection, msg)) is None:
        return
    try:
        settings = model.validate(msg["settings"])
    except vol.Invalid as err:
        connection.send_error(msg["id"], websocket_api.ERR_INVALID_FORMAT, str(err))
        return
    hass.config_entries.async_update_entry(entry, options=settings)
    connection.send_result(msg["id"])
