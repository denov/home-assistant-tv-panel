"""The settings model: chips (single entities, or the Cameras and Tank groups), each with an optional label; the
cameras in grid order; the tank's probes in column order; the solar layout."""

from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er, label_registry as lr
import pytest
import voluptuous as vol

from custom_components.tv_panel import model


def _probe(hass: HomeAssistant, eid: str, name: str, kind: str) -> None:
    hass.states.async_set(eid, "1", {"friendly_name": name, "apex_probe_type": kind})


async def test_settings_from_02_options_become_chips(hass: HomeAssistant) -> None:
    """0.2.0 saved a flat list: the cameras and the probes become one group each, where the first of them was."""
    hass.states.async_set("camera.a", "idle", {"friendly_name": "Camera - A"})
    hass.states.async_set("camera.b", "idle", {"friendly_name": "Camera - B"})
    _probe(hass, "sensor.apex_temp", "Apex Temp", "Temp")
    _probe(hass, "sensor.apex_ph", "Apex pH", "pH")
    options = {
        "entities": ["weather.home", "camera.a", "sensor.apex_temp", "camera.b", "sensor.apex_ph", "cover.garage"],
        "camera_order": ["camera.b"],
        "solar": {"arrays": []},
    }

    s = model.settings(hass, options)

    assert s["chips"] == [
        {"type": "entity", "entity": "weather.home"},
        {"type": "cameras"},
        {"type": "tank"},
        {"type": "entity", "entity": "cover.garage"},
    ]
    # The order first, then the rest by name, as the grid did.
    assert s["cameras"] == ["camera.b", "camera.a"]
    # The tank's columns were by label: pH before Temp.
    assert s["tank"] == ["sensor.apex_ph", "sensor.apex_temp"]
    assert s["solar"] == {"arrays": []}


async def test_settings_never_saved_start_from_the_labels(hass: HomeAssistant) -> None:
    label = lr.async_get(hass).async_create("tv_widget")
    reg = er.async_get(hass)
    for eid in ("weather.home", "camera.front_door"):
        e = reg.async_get_or_create(eid.split(".")[0], "demo", eid, suggested_object_id=eid.split(".")[1])
        reg.async_update_entity(e.entity_id, labels={label.label_id})
    hass.states.async_set("camera.front_door", "idle", {"friendly_name": "Camera - Front Door"})

    s = model.settings(hass, {})

    assert {"type": "cameras"} in s["chips"]
    assert {"type": "entity", "entity": "weather.home"} in s["chips"]
    assert s["cameras"] == ["camera.front_door"]
    assert model.saved({}) is False


async def test_settings_already_in_the_new_shape_are_kept(hass: HomeAssistant) -> None:
    options = {
        "chips": [{"type": "entity", "entity": "climate.hall", "label": "Hall"}, {"type": "tank"}],
        "cameras": [],
        "tank": ["sensor.apex_temp"],
        "solar": None,
    }
    assert model.settings(hass, options) == options
    assert model.saved(options) is True


def test_flatten_gives_the_entities_in_chip_order() -> None:
    """What the TV's chip template iterates: groups expand where they sit, in their own order."""
    s = {
        "chips": [{"type": "entity", "entity": "weather.home"}, {"type": "tank"}, {"type": "cameras"}],
        "cameras": ["camera.b", "camera.a"],
        "tank": ["sensor.apex_ph", "sensor.apex_temp"],
    }
    assert model.flatten(s) == ["weather.home", "sensor.apex_ph", "sensor.apex_temp", "camera.b", "camera.a"]


def test_a_group_without_its_chip_is_not_on_the_row() -> None:
    s = {"chips": [{"type": "entity", "entity": "weather.home"}], "cameras": ["camera.a"], "tank": []}
    assert model.flatten(s) == ["weather.home"]


def test_validate_cleans_and_rejects() -> None:
    good = model.validate(
        {
            "chips": [
                {"type": "entity", "entity": "climate.hall", "label": "  Hall  "},
                {"type": "entity", "entity": "sensor.x", "label": ""},
                {"type": "cameras"},
            ],
            "cameras": ["camera.a"],
            "tank": [],
            "solar": None,
        }
    )
    # Labels are trimmed; an empty one means "the entity's name".
    assert good["chips"][0] == {"type": "entity", "entity": "climate.hall", "label": "Hall"}
    assert good["chips"][1] == {"type": "entity", "entity": "sensor.x"}

    for bad in (
        {"chips": [{"type": "nope"}], "cameras": [], "tank": []},
        {"chips": [{"type": "entity", "entity": "not an id"}], "cameras": [], "tank": []},
        {"chips": [{"type": "cameras"}, {"type": "cameras"}], "cameras": [], "tank": []},
        {"chips": [], "cameras": ["sensor.not_a_camera"], "tank": []},
        {"chips": [], "cameras": [], "tank": [], "solar": {"arrays": "x"}},
    ):
        with pytest.raises(vol.Invalid):
            model.validate(bad)
