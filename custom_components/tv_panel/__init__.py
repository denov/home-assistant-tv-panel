"""TV Panel: Home Assistant on an Android TV's home screen, with screens for weather, solar, a reef tank and
cameras.

The integration pairs the TV (its own local-only user and token), and holds the TV's settings and its switches (the
widget row, and pausing for messages). tv_panel.show opens a screen on the TV through the Android TV Remote integration
for the same TV. The notify entity shows messages over the TV's own connection (messages.py), without opening
anything. The settings (the chips, the cameras, the tank, the solar layout) are edited on the TV Panel page in the
sidebar, and the TV reads them from /api/tv_panel/config and what it draws from /api/tv_panel/data.
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
from .messages import async_register as async_register_messages
from .pairing import async_remove_user
from .panel import async_register as async_register_panel
from .press import TvPanelPressView
from .services import async_register as async_register_services
from .view import TvPanelConfigView, TvPanelDataView
from .websocket import async_register as async_register_websocket

PLATFORMS = [Platform.NOTIFY, Platform.SWITCH]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


@dataclass
class TvPanelData:
    """The switches' states: whether the widget row is on, and whether a message pauses what's playing."""

    row: bool = True
    pause: bool = False


type TvPanelConfigEntry = ConfigEntry[TvPanelData]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """What the TV reads (each TV with its own token), the sidebar page that edits the settings, and
    tv_panel.show."""
    hass.http.register_view(TvPanelConfigView())
    hass.http.register_view(TvPanelDataView())
    hass.http.register_view(TvPanelPressView())
    async_register_websocket(hass)
    async_register_messages(hass)
    async_register_services(hass)
    await async_register_panel(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: TvPanelConfigEntry) -> bool:
    """Show the TV as a device, with its widget row switch and notify entity."""
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
    """Stop the switch and the notify entity."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Sign the TV out: its user and token go."""
    await async_remove_user(hass, entry.data.get(CONF_USER_ID))
