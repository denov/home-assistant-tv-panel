"""TV Panel: Home Assistant on an Android TV's home screen, with pages for weather, solar, a reef tank and cameras.

The integration pairs the TV (its own local-only user and token), holds the TV's settings and a switch for the widget
row. The settings (the chips, the cameras, the tank, the solar layout) are edited on the TV Panel page in the sidebar,
and the TV reads them from /api/tv_panel/config.
Removing the integration deletes the TV's user, which signs the TV out.
"""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_NAME, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.typing import ConfigType

from .const import CONF_TV_ID, CONF_USER_ID, DOMAIN
from .pairing import async_remove_user
from .panel import async_register as async_register_panel
from .view import TvPanelConfigView
from .websocket import async_register as async_register_websocket

PLATFORMS = [Platform.SWITCH]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


@dataclass
class TvPanelData:
    """What the TV reads besides the options: whether the widget row is on (the switch keeps it here)."""

    row: bool = True


type TvPanelConfigEntry = ConfigEntry[TvPanelData]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """The TV's settings endpoint (each TV with its own token), and the page that edits the settings."""
    hass.http.register_view(TvPanelConfigView())
    async_register_websocket(hass)
    await async_register_panel(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: TvPanelConfigEntry) -> bool:
    """Show the TV as a device, with its widget row switch."""
    entry.runtime_data = TvPanelData()
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, entry.data[CONF_TV_ID])},
        name=entry.data.get(CONF_NAME) or entry.title,
        manufacturer="TV Panel",
        model=entry.data.get(CONF_NAME),
    )
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: TvPanelConfigEntry) -> bool:
    """Stop the switch."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Sign the TV out: its user and token go."""
    await async_remove_user(hass, entry.data.get(CONF_USER_ID))
