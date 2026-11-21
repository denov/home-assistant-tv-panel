"""TV Panel: Home Assistant on an Android TV's home screen, with pages for weather, solar, a reef tank and cameras.

This first version pairs the TV: it gives the TV app its own Home Assistant user and token, so nobody has to paste a
token over ADB. Removing the integration deletes that user, which signs the TV out.
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr

from .const import CONF_TV_ID, CONF_USER_ID, DOMAIN
from .pairing import async_remove_user


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Show the TV as a device."""
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, entry.data[CONF_TV_ID])},
        name=entry.data.get(CONF_NAME) or entry.title,
        manufacturer="TV Panel",
        model=entry.data.get(CONF_NAME),
    )
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Nothing runs yet, so there is nothing to stop."""
    return True


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Sign the TV out: its user and token go."""
    await async_remove_user(hass, entry.data.get(CONF_USER_ID))
