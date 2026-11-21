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


PAGES = ("weather", "solar", "tank", "cameras")
# What "toggle" works on: domains with a toggle service, plus locks (press.py locks or unlocks).
# chips.js has the same list, as TOGGLE_DOMAINS.
TOGGLE_DOMAINS = (
    "automation", "climate", "cover", "fan", "humidifier", "input_boolean", "light", "lock", "media_player", "remote",
    "siren", "switch", "valve",
)
_CONFIRM = vol.Optional("confirm", default=False)


def can_toggle(entity_id: str) -> bool:
    return entity_id.split(".")[0] in TOGGLE_DOMAINS


def _toggleable(value: Any) -> str:
    entity_id = cv.entity_id(value)
    if not can_toggle(entity_id):
        raise vol.Invalid(f"{entity_id} can't be toggled")
    return entity_id


def _camera_page(value: dict[str, Any]) -> dict[str, Any]:
    if value["page"] == "camera" and "camera" not in value:
        raise vol.Invalid("choose the camera to open", path=["camera"])
    return value


# What OK on a chip does. Without one (or "default"), what TV Panel always did for that kind of chip: see default_ok.
# Checked by its action, so a mistake names what's missing rather than listing every kind of action.
OK = cv.key_value_schemas(
    "action",
    {
        "default": vol.Schema({vol.Required("action"): "default"}),
        "none": vol.Schema({vol.Required("action"): "none"}),
        "page": vol.All(
            vol.Schema(
                {
                    vol.Required("action"): "page",
                    vol.Required("page"): vol.In((*PAGES, "camera")),
                    vol.Optional("camera"): cv.entity_domain("camera"),
                }
            ),
            _camera_page,
        ),
        "toggle": vol.Schema(
            {vol.Required("action"): "toggle", vol.Optional("entity"): _toggleable, _CONFIRM: cv.boolean}
        ),
        "run": vol.Schema(
            {
                vol.Required("action"): "run",
                vol.Required("entity"): cv.entity_domain(["script", "scene"]),
                _CONFIRM: cv.boolean,
            }
        ),
        "perform": vol.Schema(
            {
                vol.Required("action"): "perform",
                vol.Required("perform_action"): cv.service,
                vol.Optional("target"): {vol.Optional("entity_id"): cv.comp_entity_ids},
                vol.Optional("data"): dict,
                _CONFIRM: cv.boolean,
            }
        ),
    },
)

CHIP = vol.Schema(
    vol.Any(
        {
            vol.Required("type"): "entity",
            vol.Required("entity"): cv.entity_id,
            vol.Optional("label"): _label,
            vol.Optional("ok"): OK,
        },
        {vol.Required("type"): vol.In(GROUPS), vol.Optional("label"): _label, vol.Optional("ok"): OK},
    )
)


def chip_key(chip: dict[str, Any]) -> str:
    """The key the TV knows a chip by: its entity id, or "cameras" and "apex" for the groups."""
    return chip["entity"] if chip["type"] == "entity" else "apex" if chip["type"] == "tank" else "cameras"


def is_garage(hass: HomeAssistant, entity_id: str) -> bool:
    """As the app decides (Ha.widget): the device class, or "garage" in the id or the name."""
    state = hass.states.get(entity_id)
    return (state is not None and state.attributes.get("device_class") == "garage") or "garage" in (
        entity_id + _name(hass, entity_id)
    ).lower()


def is_solar(hass: HomeAssistant, entity_id: str) -> bool:
    state = hass.states.get(entity_id)
    return (
        state is not None
        and state.attributes.get("device_class") in ("power", "energy")
        and any(w in entity_id for w in ("production", "solar", "pv"))
    )


def default_ok(hass: HomeAssistant, chip: dict[str, Any]) -> dict[str, Any]:
    """What OK did before it could be set: the chip's page, the garage door's toggle (pressed twice), or nothing.
    chips.js defaultOk() mirrors it for the page."""
    if chip["type"] == "cameras":
        return {"action": "page", "page": "cameras"}
    if chip["type"] == "tank":
        return {"action": "page", "page": "tank"}
    e = chip["entity"]
    domain = e.split(".")[0]
    if domain == "weather":
        return {"action": "page", "page": "weather"}
    if domain == "camera":
        return {"action": "page", "page": "cameras"}
    if _is_probe(hass, e):
        return {"action": "page", "page": "tank"}
    if domain == "sensor" and is_solar(hass, e):
        return {"action": "page", "page": "solar"}
    if domain == "cover" and is_garage(hass, e):
        return {"action": "toggle", "entity": e, "confirm": True}
    return {"action": "none"}


def resolve_ok(hass: HomeAssistant, chip: dict[str, Any]) -> dict[str, Any]:
    """What OK on this chip does, in full: its own setting with the gaps filled, or the default."""
    ok = chip.get("ok")
    if not ok or ok.get("action") == "default":
        return default_ok(hass, chip)
    ok = dict(ok)
    if ok["action"] == "toggle" and "entity" not in ok:
        # Nothing chosen: the chip's own entity, if it can be toggled. A group, a sensor or weather can't.
        if chip["type"] != "entity" or not can_toggle(chip["entity"]):
            return {"action": "none"}
        ok["entity"] = chip["entity"]
    if ok["action"] in ("toggle", "run", "perform"):
        ok.setdefault("confirm", False)
    return ok


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
        if chip.get("ok", {}).get("action") == "default":
            del chip["ok"]
        ok = chip.get("ok", {})
        if ok.get("action") == "toggle" and "entity" not in ok and not (
            chip["type"] == "entity" and can_toggle(chip["entity"])
        ):
            what = chip.get("entity", chip["type"])
            raise vol.Invalid(f"{what} can't be toggled: choose what to toggle")
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
