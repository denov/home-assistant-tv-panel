"""The TV's settings and the shapes they've had.

Now (0.3.0):
    chips:   [{"type": "entity", "entity": "climate.hall", "label": "Hall"}, {"type": "cameras"}, {"type": "tank"}]
    cameras: camera entity ids in grid order (also which cameras the Cameras chip opens)
    tank:    Apex probe entity ids in column order
    solar:   the solar page's panel layout, or None
A chip's "label" is optional: without it the TV uses the entity's name.

0.2.0 saved a flat "entities" list and a "camera_order"; before the integration there was the tv_widget label and
input_text.tv_camera_order. Both still read, as the new shape.
"""

from __future__ import annotations

from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv, entity_registry as er, label_registry as lr
import voluptuous as vol

OLD_LABEL = "tv_widget"
OLD_CAMERA_ORDER = "input_text.tv_camera_order"
# Apex probes that go in the tank chip (Apex.PAGE_TYPES in the app).
TANK_PROBES = {"Temp", "pH", "Cond", "alk", "ca", "mg"}
GROUPS = ("cameras", "tank")
KEYS = ("chips", "cameras", "tank", "solar")


def saved(options: dict[str, Any]) -> bool:
    """Whether anything was ever saved: if not, the TV keeps reading the labels and helpers itself."""
    return "chips" in options or "entities" in options


def settings(hass: HomeAssistant, options: dict[str, Any]) -> dict[str, Any]:
    """The settings in the current shape, whatever shape they were saved in."""
    if "chips" in options:
        return {
            "chips": list(options["chips"]),
            "cameras": list(options.get("cameras") or []),
            "tank": list(options.get("tank") or []),
            "solar": options.get("solar"),
        }
    if "entities" in options:
        entities = list(options["entities"])
        order = list(options.get("camera_order") or [])
    else:
        entities = labelled_entities(hass)
        order = old_camera_order(hass, sorted(e for e in entities if e.startswith("camera.")))
    return _from_flat(hass, entities, order, options.get("solar"))


def _from_flat(hass: HomeAssistant, entities: list[str], order: list[str], solar: Any) -> dict[str, Any]:
    chips: list[dict[str, Any]] = []
    cameras = [e for e in entities if e.startswith("camera.")]
    tank = [e for e in entities if _is_probe(hass, e)]
    for e in entities:
        group = "cameras" if e in cameras else "tank" if e in tank else None
        if group is None:
            chips.append({"type": "entity", "entity": e})
        elif {"type": group} not in chips:
            chips.append({"type": group})
    rest = sorted((c for c in cameras if c not in order), key=lambda c: camera_name(hass, c))
    return {
        "chips": chips,
        "cameras": [c for c in order if c in cameras] + rest,
        "tank": sorted(tank, key=lambda e: _name(hass, e).lower()),
        "solar": solar,
    }


def flatten(s: dict[str, Any]) -> list[str]:
    """The entities the TV's chip template reads, in chip order; a group's members where its chip is."""
    out: list[str] = []
    for chip in s.get("chips") or []:
        out += [chip["entity"]] if chip["type"] == "entity" else list(s.get(chip["type"]) or [])
    return out


def _label(value: Any) -> str | None:
    if value is None:
        return None
    text = cv.string(value).strip()
    return text or None


CHIP = vol.Schema(
    vol.Any(
        {vol.Required("type"): "entity", vol.Required("entity"): cv.entity_id, vol.Optional("label"): _label},
        {vol.Required("type"): vol.In(GROUPS), vol.Optional("label"): _label},
    )
)


def _solar(value: Any) -> Any:
    if value is None:
        return None
    if not isinstance(value, dict) or not isinstance(value.get("arrays", []), list):
        raise vol.Invalid("the solar layout needs a list of arrays")
    return value


SCHEMA = vol.Schema(
    {
        vol.Required("chips"): [CHIP],
        vol.Required("cameras"): [cv.entity_domain("camera")],
        vol.Required("tank"): [cv.entity_id],
        vol.Optional("solar", default=None): _solar,
    }
)


def validate(data: dict[str, Any]) -> dict[str, Any]:
    """Settings as the page sends them, cleaned: labels trimmed, an empty label dropped."""
    s = SCHEMA(data)
    chips = []
    for chip in s["chips"]:
        chip = {k: v for k, v in chip.items() if not (k == "label" and v is None)}
        if chip["type"] in GROUPS and any(c["type"] == chip["type"] for c in chips):
            raise vol.Invalid(f"only one {chip['type']} chip")
        chips.append(chip)
    s["chips"] = chips
    return s


def _name(hass: HomeAssistant, entity_id: str) -> str:
    state = hass.states.get(entity_id)
    return (state.attributes.get("friendly_name") if state else None) or entity_id.split(".", 1)[1]


def _is_probe(hass: HomeAssistant, entity_id: str) -> bool:
    state = hass.states.get(entity_id)
    return state is not None and state.attributes.get("apex_probe_type") in TANK_PROBES


def labelled_entities(hass: HomeAssistant) -> list[str]:
    """Entities carrying the old tv_widget label (by label id or name)."""
    labels = lr.async_get(hass)
    label = labels.async_get_label(OLD_LABEL) or labels.async_get_label_by_name(OLD_LABEL)
    if label is None:
        return []
    return [e.entity_id for e in er.async_entries_for_label(er.async_get(hass), label.label_id)]


def camera_name(hass: HomeAssistant, entity_id: str) -> str:
    """"front door" from "Camera - Front Door", as the TV shows it."""
    name = _name(hass, entity_id)
    for prefix in ("camera - ", "camera: "):
        if name.lower().startswith(prefix):
            name = name[len(prefix) :]
    return name.strip().lower()


def old_camera_order(hass: HomeAssistant, cameras: list[str]) -> list[str]:
    """The helper's names and ids as camera entity ids, in its order. Names that match no camera are dropped."""
    state = hass.states.get(OLD_CAMERA_ORDER)
    if state is None or state.state in ("unknown", "unavailable"):
        return []
    out: list[str] = []
    for want in (w.strip().lower() for w in state.state.split(",")):
        for cam in cameras:
            if cam not in out and want in (cam.lower(), camera_name(hass, cam)):
                out.append(cam)
                break
    return out
