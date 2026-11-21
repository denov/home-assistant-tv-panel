"""Configure: which chips, in what order, the camera grid's order and the solar layout."""

from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers import entity_registry as er, label_registry as lr

from custom_components.tv_panel.const import OPT_CAMERA_ORDER, OPT_ENTITIES, OPT_SOLAR

from .common import paired_tv


def _default(result, key):
    """The default a field of the options form shows."""
    for field in result["data_schema"].schema:
        if str(field) == key:
            return field.default() if callable(field.default) else field.default
    raise KeyError(key)


async def test_first_open_starts_from_labels_and_helpers(hass: HomeAssistant) -> None:
    """Before the integration, chips were the tv_widget labels and the camera order an input_text."""
    label = lr.async_get(hass).async_create("tv_widget")
    reg = er.async_get(hass)
    for platform, uid, eid in (
        ("demo", "w", "weather.home"),
        ("demo", "c1", "camera.front_door"),
        ("demo", "c2", "camera.driveway"),
    ):
        entity = reg.async_get_or_create(eid.split(".")[0], platform, uid, suggested_object_id=eid.split(".")[1])
        reg.async_update_entity(entity.entity_id, labels={label.label_id})
    hass.states.async_set("camera.front_door", "idle", {"friendly_name": "Camera - Front Door"})
    hass.states.async_set("camera.driveway", "idle", {"friendly_name": "Camera - Driveway"})
    hass.states.async_set("input_text.tv_camera_order", "Driveway, front door")

    entry, _ = await paired_tv(hass)
    result = await hass.config_entries.options.async_init(entry.entry_id)

    assert result["type"] is FlowResultType.FORM
    assert sorted(_default(result, OPT_ENTITIES)) == ["camera.driveway", "camera.front_door", "weather.home"]
    # Names in the helper become entity ids, in the helper's order.
    assert _default(result, OPT_CAMERA_ORDER) == ["camera.driveway", "camera.front_door"]


async def test_saving_keeps_the_order_given(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    result = await hass.config_entries.options.async_init(entry.entry_id)
    layout = {"arrays": [{"name": "Array 1", "panels": [{"id": "1", "x": 0, "y": 0}]}]}
    result = await hass.config_entries.options.async_configure(
        result["flow_id"],
        {
            OPT_ENTITIES: ["sensor.solar", "weather.home"],
            OPT_CAMERA_ORDER: ["camera.b", "camera.a"],
            OPT_SOLAR: layout,
        },
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert entry.options[OPT_ENTITIES] == ["sensor.solar", "weather.home"]
    assert entry.options[OPT_CAMERA_ORDER] == ["camera.b", "camera.a"]
    assert entry.options[OPT_SOLAR] == layout

    # Opened again, it shows what was saved.
    result = await hass.config_entries.options.async_init(entry.entry_id)
    assert _default(result, OPT_ENTITIES) == ["sensor.solar", "weather.home"]
    assert _default(result, OPT_SOLAR) == layout
