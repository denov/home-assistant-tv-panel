"""The widget row switch, on the TV's device: it replaces input_boolean.tv_widgets."""

from homeassistant.core import HomeAssistant, State
from homeassistant.helpers import device_registry as dr, entity_registry as er
from pytest_homeassistant_custom_component.common import mock_restore_cache

from .common import paired_tv


async def test_on_by_default_and_on_the_tv(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    entities = er.async_entries_for_config_entry(er.async_get(hass), entry.entry_id)
    entities = [e for e in entities if e.domain == "switch"]
    assert len(entities) == 1
    assert hass.states.get(entities[0].entity_id).state == "on"
    device = dr.async_get(hass).async_get(entities[0].device_id)
    assert device.name == "BRAVIA"


async def test_starts_from_the_old_helper(hass: HomeAssistant) -> None:
    hass.states.async_set("input_boolean.tv_widgets", "off")
    entry, _ = await paired_tv(hass)
    entities = er.async_entries_for_config_entry(er.async_get(hass), entry.entry_id)
    switch = next(e.entity_id for e in entities if e.domain == "switch")
    assert hass.states.get(switch).state == "off"


async def test_keeps_its_state_across_restarts(hass: HomeAssistant) -> None:
    mock_restore_cache(hass, [State("switch.bravia_widget_row", "off")])
    await paired_tv(hass)
    assert hass.states.get("switch.bravia_widget_row").state == "off"
