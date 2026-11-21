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


async def test_default_ok_is_what_the_tv_did_before(hass: HomeAssistant) -> None:
    """With no "ok" of its own, a chip does what TV Panel always did for its kind of entity."""
    hass.states.async_set("cover.garage_door", "closed", {"device_class": "garage", "friendly_name": "Garage Door"})
    hass.states.async_set("cover.blind", "open", {"friendly_name": "Blind"})
    hass.states.async_set(
        "sensor.envoy_current_power_production", "1.2", {"device_class": "power", "unit_of_measurement": "kW"}
    )
    hass.states.async_set("sensor.hall_temperature", "68")

    def ok(chip):
        return model.resolve_ok(hass, chip)

    assert ok({"type": "entity", "entity": "weather.home"}) == {"action": "page", "page": "weather"}
    assert ok({"type": "cameras"}) == {"action": "page", "page": "cameras"}
    assert ok({"type": "tank"}) == {"action": "page", "page": "tank"}
    assert ok({"type": "entity", "entity": "sensor.envoy_current_power_production"}) == {
        "action": "page",
        "page": "solar",
    }
    assert ok({"type": "entity", "entity": "cover.garage_door"}) == {
        "action": "toggle",
        "entity": "cover.garage_door",
        "confirm": True,
    }
    assert ok({"type": "entity", "entity": "cover.blind"}) == {"action": "none"}
    assert ok({"type": "entity", "entity": "sensor.hall_temperature"}) == {"action": "none"}


async def test_gates_and_doors_toggle_on_two_presses_by_default(hass: HomeAssistant) -> None:
    """Like the garage: anything that opens a way in asks twice. Locks, blinds and windows do nothing by default."""
    hass.states.async_set("cover.front", "closed", {"device_class": "gate", "friendly_name": "Front"})
    hass.states.async_set("cover.side", "closed", {"device_class": "door", "friendly_name": "Side"})
    hass.states.async_set("cover.driveway_gate", "closed", {"friendly_name": "Driveway Gate"})
    hass.states.async_set("cover.hall_window", "closed", {"device_class": "window", "friendly_name": "Hall window"})
    hass.states.async_set("cover.navigator", "closed", {"device_class": "shade", "friendly_name": "Navigator shade"})
    hass.states.async_set("lock.front_door", "locked", {"friendly_name": "Front Door"})

    def ok(entity):
        return model.resolve_ok(hass, {"type": "entity", "entity": entity})

    for e in ("cover.front", "cover.side", "cover.driveway_gate"):
        assert ok(e) == {"action": "toggle", "entity": e, "confirm": True}, e
    for e in ("cover.hall_window", "cover.navigator", "lock.front_door"):
        assert ok(e) == {"action": "none"}, e


async def test_an_ok_of_its_own_wins_and_fills_in(hass: HomeAssistant) -> None:
    # A toggle without an entity toggles the chip's own; confirm defaults to off.
    assert model.resolve_ok(hass, {"type": "entity", "entity": "light.hall", "ok": {"action": "toggle"}}) == {
        "action": "toggle",
        "entity": "light.hall",
        "confirm": False,
    }
    assert model.resolve_ok(hass, {"type": "entity", "entity": "weather.home", "ok": {"action": "none"}}) == {
        "action": "none"
    }
    run = {"action": "run", "entity": "script.good_night", "confirm": True}
    assert model.resolve_ok(hass, {"type": "tank", "ok": run}) == run


def test_validate_takes_the_ok_choices() -> None:
    oks = [
        {"action": "default"},
        {"action": "none"},
        {"action": "page", "page": "solar"},
        {"action": "page", "page": "camera", "camera": "camera.driveway"},
        {"action": "toggle", "entity": "light.hall", "confirm": True},
        {"action": "run", "entity": "scene.movie"},
        {
            "action": "perform",
            "perform_action": "light.turn_on",
            "target": {"entity_id": "light.hall"},
            "data": {"brightness_pct": 30},
        },
    ]
    chips = [{"type": "entity", "entity": f"sensor.s{i}", "ok": ok} for i, ok in enumerate(oks)]
    out = model.validate({"chips": chips, "cameras": [], "tank": []})["chips"]
    # "default" is the same as no "ok" at all.
    assert "ok" not in out[0]
    assert out[1]["ok"] == {"action": "none"}
    assert out[5]["ok"] == {"action": "run", "entity": "scene.movie", "confirm": False}
    assert out[6]["ok"]["data"] == {"brightness_pct": 30}

    for bad in (
        {"action": "page", "page": "nope"},
        {"action": "page", "page": "camera"},
        {"action": "run", "entity": "light.hall"},
        {"action": "perform", "perform_action": "not a service"},
        {"action": "explode"},
    ):
        with pytest.raises(vol.Invalid):
            model.validate({"chips": [{"type": "entity", "entity": "sensor.x", "ok": bad}], "cameras": [], "tank": []})


async def test_only_what_can_be_toggled_is_toggled(hass: HomeAssistant) -> None:
    # A sensor chip set to "toggle" with nothing chosen does nothing, rather than call sensor.toggle.
    assert model.resolve_ok(hass, {"type": "entity", "entity": "sensor.x", "ok": {"action": "toggle"}}) == {
        "action": "none"
    }
    for bad in (
        {"type": "entity", "entity": "sensor.x", "ok": {"action": "toggle"}},
        {"type": "entity", "entity": "weather.home", "ok": {"action": "toggle", "entity": "sensor.y"}},
        {"type": "tank", "ok": {"action": "toggle"}},
    ):
        with pytest.raises(vol.Invalid, match="toggle"):
            model.validate({"chips": [bad], "cameras": [], "tank": []})
    ok = model.validate(
        {"chips": [{"type": "entity", "entity": "lock.front", "ok": {"action": "toggle"}}], "cameras": [], "tank": []}
    )
    assert ok["chips"][0]["ok"] == {"action": "toggle", "confirm": False}


def test_a_half_set_action_says_what_is_missing() -> None:
    """Not "value must be one of ['default', 'none']" for every mistake."""
    for ok, missing in (
        ({"action": "page"}, "page"),
        ({"action": "page", "page": "camera"}, "camera"),
        ({"action": "run"}, "entity"),
        ({"action": "perform", "perform_action": "light"}, "perform_action"),
    ):
        with pytest.raises(vol.Invalid, match=missing):
            model.validate({"chips": [{"type": "entity", "entity": "sensor.x", "ok": ok}], "cameras": [], "tank": []})


def test_the_page_toggles_what_the_integration_toggles() -> None:
    """chips.js keeps its own copy of TOGGLE_DOMAINS for the editor: it must not drift."""
    import json
    from pathlib import Path
    import re

    js = (Path(model.__file__).parent / "frontend" / "chips.js").read_text(encoding="utf-8")
    found = re.search(r"export const TOGGLE_DOMAINS = (\[[^\]]*\]);", js)
    assert found
    assert json.loads(found.group(1)) == list(model.TOGGLE_DOMAINS)
