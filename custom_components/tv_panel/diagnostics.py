"""Diagnostics for a bug report: the TV's settings, its user, whether it listens for messages, the Android TV Remote
it uses, and what its chips read.

Left out: the TV's address, id and user id, and the solar layout's inverter serials (only its shape stays).
"""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.loader import async_get_integration

from . import data, model
from .const import CONF_USER_ID, DOMAIN
from .messages import listening
from .tv_link import find_remote


def _solar_shape(solar: Any) -> Any:
    if not isinstance(solar, dict):
        return solar
    arrays = [a for a in solar.get("arrays") or [] if isinstance(a, dict)]
    return {"arrays": len(arrays), "panels": sum(len(a.get("panels") or []) for a in arrays)}


async def async_get_config_entry_diagnostics(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    integration = await async_get_integration(hass, DOMAIN)
    user = await hass.auth.async_get_user(entry.data.get(CONF_USER_ID) or "")
    settings = model.settings(hass, entry.options)
    remote = find_remote(hass, entry)
    player = hass.states.get(remote.media_player) if remote and remote.media_player else None
    return {
        "version": str(integration.version),
        "user": None
        if user is None
        else {"admin": user.is_admin, "local_only": user.local_only, "active": user.is_active},
        "saved": model.saved(entry.options),
        "row": entry.runtime_data.row,
        "settings": settings | {"solar": _solar_shape(settings.get("solar"))},
        "android_tv_remote": None
        if remote is None
        else {
            "media_player": remote.media_player,
            "remote": remote.remote,
            "state": player.state if player is not None else None,
        },
        # Connections from the TV listening for messages: 0 when it's off, or its app is older than 0.6.0.
        "listening": listening(hass, entry),
        "chips": data.chips(hass, entry),
    }
