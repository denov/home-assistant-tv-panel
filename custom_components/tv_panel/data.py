"""What the TV draws: the chip row, and the live values of the camera, tank, solar, thermostat and air quality screens.

The app used to send its own Jinja templates to /api/template, which only an admin may call. These build the same
answers here, so the TV's user can be a normal one. Each shape is what the app's parser reads: Ha.parseWidgets,
Cameras.parse, Apex.parse and Solar.parse.
"""

from __future__ import annotations

from collections.abc import Mapping
import re
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import ATTR_FRIENDLY_NAME, ATTR_UNIT_OF_MEASUREMENT, STATE_UNKNOWN
from homeassistant.core import HomeAssistant, State
from homeassistant.helpers import area_registry as ar, device_registry as dr, entity_registry as er

from . import model

ENPHASE = "enphase_envoy"
# The Apex alarm colours the tank chip. It has no label, so it's found by its id.
APEX_ALARM = re.compile(r"^binary_sensor[.].*apex_alarm$")
# Besides the probes, the tank screen shows the Trident's reagents and waste, the DOS's volume left, both modes, and
# the alarm.
APEX_OTHERS = re.compile(
    r"^sensor[.].*_(tri_reagent_[a-z]|tri_waste|dos_[a-z0-9]+_volume_left)$"
    r"|^select[.].*_(trident|dos)_mode$"
    r"|^binary_sensor[.].*apex_alarm$"
)
# Battery charge and discharge, from any integration, by name.
BATTERY = re.compile(r"(batter|encharge).*_(charge|discharge)_(energy|power)$")


def _read(hass: HomeAssistant, entity_id: str) -> tuple[str, Mapping[str, Any]]:
    """The state and attributes, or "unknown" and none for an entity Home Assistant doesn't have."""
    state = hass.states.get(entity_id)
    return (state.state, state.attributes) if state is not None else (STATE_UNKNOWN, {})


def _device_id(hass: HomeAssistant, entity_id: str) -> str | None:
    entry = er.async_get(hass).async_get(entity_id)
    return entry.device_id if entry is not None else None


def _device_name(hass: HomeAssistant, entity_id: str) -> str | None:
    """The device's name as the owner sees it: theirs if they renamed it."""
    device_id = _device_id(hass, entity_id)
    device = dr.async_get(hass).async_get(device_id) if device_id else None
    return (device.name_by_user or device.name) if device is not None else None


def _motion(hass: HomeAssistant, entity_id: str) -> bool:
    """A camera sees motion: a motion sensor on its device is on."""
    device_id = _device_id(hass, entity_id) if entity_id.startswith("camera.") else None
    if device_id is None:
        return False
    for entry in er.async_entries_for_device(er.async_get(hass), device_id):
        state = hass.states.get(entry.entity_id)
        if (
            entry.domain == "binary_sensor"
            and state is not None
            and state.attributes.get("device_class") == "motion"
            and state.state == "on"
        ):
            return True
    return False


def _sorted(hass: HomeAssistant, domain: str | None = None) -> list[State]:
    states = hass.states.async_all(domain) if domain else hass.states.async_all()
    return sorted(states, key=lambda s: s.entity_id)


def chips(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    """The chip row: each chip's entity (a group's members where its chip is), and whether the row is on.

    "ordered" says the chips come in the order the owner set; before anything is saved they come from the tv_widget
    label, and the TV sorts them itself.
    """
    items = []
    for e in model.flatten(model.settings(hass, entry.options)):
        state, a = _read(hass, e)
        items.append(
            {
                "id": e,
                "name": a.get(ATTR_FRIENDLY_NAME),
                "device": _device_name(hass, e),
                "state": state,
                "unit": a.get(ATTR_UNIT_OF_MEASUREMENT),
                "icon": a.get("icon"),
                "dc": a.get("device_class"),
                "temp": a.get("temperature"),
                "tunit": a.get("temperature_unit"),
                "apex": a.get("apex_probe_type"),
                # A thermostat: where it is (temp is where it's set), or the range in heat_cool mode.
                "cur": a.get("current_temperature"),
                "lo": a.get("target_temp_low"),
                "hi": a.get("target_temp_high"),
                "action": a.get("hvac_action"),
                "motion": _motion(hass, e),
            }
        )
    alarm = next((s for s in _sorted(hass, "binary_sensor") if APEX_ALARM.search(s.entity_id)), None)
    return {
        "on": "on" if entry.runtime_data.row else "off",
        "ordered": model.saved(entry.options),
        "items": items,
        "alarm": alarm.state if alarm is not None else "",
        "alerts": len(alarm.attributes.get("active_alerts") or []) if alarm is not None else 0,
    }


def cameras(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    """The camera grid: its cameras, each with whether it sees motion, and their order."""
    order = model.settings(hass, entry.options)["cameras"]
    items = []
    for e in order:
        state, a = _read(hass, e)
        items.append({"id": e, "name": a.get(ATTR_FRIENDLY_NAME), "state": state, "motion": _motion(hass, e)})
    return {"items": items, "order": ",".join(order)}


def tank(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    """The tank screen: every Apex probe, then the Trident, the DOS and the alarm, each by entity id."""
    probes = [s for s in _sorted(hass, "sensor") if "apex_probe_type" in s.attributes]
    others = [s for s in _sorted(hass) if APEX_OTHERS.search(s.entity_id)]
    items = []
    for s in probes + others:
        a = s.attributes
        items.append(
            {
                "id": s.entity_id,
                "state": s.state,
                "unit": a.get(ATTR_UNIT_OF_MEASUREMENT),
                "name": a.get(ATTR_FRIENDLY_NAME),
                "type": a.get("apex_probe_type"),
                "changed": s.last_changed.isoformat(),
                "tests": a.get("tests_remaining"),
                "full": a.get("percent_full"),
                "left": a.get("percent_left"),
                "alerts": len(a.get("active_alerts") or []),
            }
        )
    return {"items": items}


def solar(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    """The solar screen: the Enphase Envoy's sensors, grid status and storage mode, and any battery's charge and
    discharge, as [entity id, state, unit]."""
    envoy = [
        e.entity_id
        for e in er.async_get(hass).entities.values()
        if e.platform == ENPHASE and not e.disabled
    ]
    battery = [s.entity_id for s in _sorted(hass, "sensor") if BATTERY.search(s.entity_id)]
    items = []
    for e in sorted(set(envoy + battery)):
        if e.startswith("sensor.") or e.endswith(("_grid_status", "_storage_mode")):
            state, a = _read(hass, e)
            items.append([e, state, a.get(ATTR_UNIT_OF_MEASUREMENT)])
    return {"items": items}


BUILDERS = {"chips": chips, "cameras": cameras, "tank": tank, "solar": solar}

# The standard climate attributes the thermostat screen reads, from any integration's thermostat. Temperatures are in
# Home Assistant's unit.
CLIMATE_ATTRIBUTES = (
    "current_temperature", "temperature", "target_temp_low", "target_temp_high", "hvac_action", "hvac_modes",
    "current_humidity", "target_humidity", "fan_mode", "preset_mode", "min_temp", "max_temp", "target_temp_step",
    "supported_features",
)


def thermostat(hass: HomeAssistant, entry: ConfigEntry, screen: dict[str, Any]) -> dict[str, Any]:
    """A thermostat screen: each thermostat's state and standard attributes, and its other readings."""
    climates = []
    for e in screen["climates"]:
        state, a = _read(hass, e)
        climates.append(
            {
                "id": e,
                "name": a.get(ATTR_FRIENDLY_NAME),
                "state": state,
                "attributes": {k: a[k] for k in CLIMATE_ATTRIBUTES if a.get(k) is not None},
            }
        )
    sensors = []
    for e in screen.get("sensors", []):
        state, a = _read(hass, e)
        sensors.append(
            {
                "id": e,
                "name": a.get(ATTR_FRIENDLY_NAME),
                "state": state,
                "unit": a.get(ATTR_UNIT_OF_MEASUREMENT),
                "dc": a.get("device_class"),
            }
        )
    return {
        "title": model.screen_title(screen),
        "unit": hass.config.units.temperature_unit,
        "climates": climates,
        "sensors": sensors,
    }


def _area(hass: HomeAssistant, entity_id: str) -> str | None:
    """The entity's area, or its device's."""
    entry = er.async_get(hass).async_get(entity_id)
    if entry is None:
        return None
    area_id = entry.area_id
    if area_id is None and entry.device_id is not None:
        device = dr.async_get(hass).async_get(entry.device_id)
        area_id = device.area_id if device is not None else None
    area = ar.async_get(hass).async_get_area(area_id) if area_id else None
    return area.name if area is not None else None


def _without(name: str | None, prefix: str | None) -> str | None:
    """"Air Monitor CO2" without "Air Monitor": the rest, when there's a rest."""
    if name and prefix and name.lower().startswith(prefix.lower() + " ") and len(name) > len(prefix) + 1:
        return name[len(prefix) + 1 :]
    return name


def air_quality(hass: HomeAssistant, entry: ConfigEntry, screen: dict[str, Any]) -> dict[str, Any]:
    """An air quality screen: each sensor with its room first, and what it measures (device and state class). The
    name drops the room and the device from its front ("Air Monitor CO2" on the device "Air Monitor" is "CO2"; "Probe 2
    Office Temperature" on "Probe 2", in the Office, is "Temperature"), as the chips do, so a tile has room for
    what matters."""
    sensors = []
    for e in screen["sensors"]:
        state, a = _read(hass, e)
        area = _area(hass, e)
        name = _without(_without(_without(a.get(ATTR_FRIENDLY_NAME), area), _device_name(hass, e)), area)
        sensors.append(
            {
                "id": e,
                "area": area,
                "name": name,
                "state": state,
                "unit": a.get(ATTR_UNIT_OF_MEASUREMENT),
                "dc": a.get("device_class"),
                "sc": a.get("state_class"),
            }
        )
    return {"title": model.screen_title(screen), "sensors": sensors}


# Those for one of the TV's screens: /data/thermostat?screen=<id>.
SCREEN_BUILDERS = {"thermostat": thermostat, "air_quality": air_quality}
