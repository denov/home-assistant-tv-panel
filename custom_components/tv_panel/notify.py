"""The TV's notify entity: notify.send_message shows the message on the TV, over whatever is playing.

The TV Panel app shows it as a toast. A TV in standby is left off and the message is dropped.
"""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import TvPanelConfigEntry
from .const import CONF_TV_ID, DOMAIN
from .tv_link import async_open, notify_link


async def async_setup_entry(
    hass: HomeAssistant, entry: TvPanelConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([TvNotify(entry)])


class TvNotify(NotifyEntity):
    """Messages on the TV's screen."""

    _attr_has_entity_name = True
    _attr_name = None
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, entry: TvPanelConfigEntry) -> None:
        self._entry = entry
        tv_id = entry.data[CONF_TV_ID]
        self._attr_unique_id = f"{tv_id}_notify"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, tv_id)}, name=entry.data.get(CONF_NAME))

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        await async_open(self.hass, self._entry, notify_link(message, title))
