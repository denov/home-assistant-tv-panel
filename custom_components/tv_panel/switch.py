"""The TV's switches.

Widget row: off hides the chips on the TV's home screen. It replaces input_boolean.tv_widgets.
Pause for messages: on, a message pauses what's playing before it shows. Off by default.
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import CONF_NAME, STATE_OFF, STATE_ON, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.helpers.restore_state import RestoreEntity

from . import TvPanelConfigEntry
from .const import CONF_TV_ID, DOMAIN

# The helper the app used before the integration. Its state is where a new switch starts.
OLD_HELPER = "input_boolean.tv_widgets"


async def async_setup_entry(
    hass: HomeAssistant, entry: TvPanelConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([WidgetRowSwitch(entry), PauseForMessagesSwitch(entry)])


class WidgetRowSwitch(SwitchEntity, RestoreEntity):
    """On: the TV shows its chips. The state lives in the entry's runtime data, which the settings endpoint reads."""

    _attr_has_entity_name = True
    _attr_translation_key = "widget_row"
    _attr_icon = "mdi:television-guide"

    def __init__(self, entry: TvPanelConfigEntry) -> None:
        self._entry = entry
        tv_id = entry.data[CONF_TV_ID]
        self._attr_unique_id = f"{tv_id}_widget_row"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, tv_id)}, name=entry.data.get(CONF_NAME))

    @property
    def is_on(self) -> bool:
        return self._entry.runtime_data.row

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        last = await self.async_get_last_state()
        if last is not None and last.state in (STATE_ON, STATE_OFF):
            self._entry.runtime_data.row = last.state == STATE_ON
            return
        old = self.hass.states.get(OLD_HELPER)
        if old is not None and old.state in (STATE_ON, STATE_OFF):
            self._entry.runtime_data.row = old.state == STATE_ON

    async def async_turn_on(self, **kwargs: Any) -> None:
        self._entry.runtime_data.row = True
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        self._entry.runtime_data.row = False
        self.async_write_ha_state()


class PauseForMessagesSwitch(SwitchEntity, RestoreEntity):
    """Off (the default): a message shows over whatever is playing, which keeps playing. On: the TV pauses first."""

    _attr_has_entity_name = True
    _attr_translation_key = "pause_for_messages"
    _attr_icon = "mdi:pause-circle-outline"
    _attr_entity_category = EntityCategory.CONFIG

    def __init__(self, entry: TvPanelConfigEntry) -> None:
        self._entry = entry
        tv_id = entry.data[CONF_TV_ID]
        self._attr_unique_id = f"{tv_id}_pause_for_messages"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, tv_id)}, name=entry.data.get(CONF_NAME))

    @property
    def is_on(self) -> bool:
        return self._entry.runtime_data.pause

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        last = await self.async_get_last_state()
        if last is not None and last.state in (STATE_ON, STATE_OFF):
            self._entry.runtime_data.pause = last.state == STATE_ON

    async def async_turn_on(self, **kwargs: Any) -> None:
        self._entry.runtime_data.pause = True
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        self._entry.runtime_data.pause = False
        self.async_write_ha_state()
