"""The TV's settings and the shapes they've had.

Now (0.7.0):
    chips:   [{"type": "entity", "entity": "climate.hall", "label": "Hall"}, {"type": "cameras"}, {"type": "tank"}]
    cameras: camera entity ids in grid order (also which cameras the Cameras chip opens)
    tank:    Apex probe entity ids in column order
    solar:   the solar screen's panel layout, or None
    screens: the TV's full-screen views, in order: [{"id": "weather", "type": "weather"}, ...,
             {"id": "thermostat", "type": "thermostat", "title": "Upstairs", "climates": [...], "sensors": [...]},
             {"id": "air_quality", "type": "air_quality", "title": "Air", "sensors": [...]}]
A chip's "label" is optional: without it the TV uses the entity's name. Its "ok" is what OK on it does (OK below).

The four built-in screens (weather, solar, tank, cameras) appear at most once, with their type as their id, and keep
their settings in the top-level keys, where apps before 0.7.0 read them. Thermostat and air quality screens can be
added more than once; each has an id made once when it's added, which chips, tv_panel.show and voice links use.
Without "screens" (anything saved before 0.7.0) the TV has the four built-in ones.

0.2.0 saved a flat "entities" list and a "camera_order"; before the integration there was the tv_widget label and
input_text.tv_camera_order. Both still read, as the new shape. Before 0.7.0 a chip's OK opened a "page"; that reads
as a "screen" now (upgrade_ok).
"""

from __future__ import annotations

import re
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv, entity_registry as er, label_registry as lr
import voluptuous as vol

OLD_LABEL = "tv_widget"
OLD_CAMERA_ORDER = "input_text.tv_camera_order"
# Apex probes that go in the tank chip (Apex.CHIP_TYPES in the app).
TANK_PROBES = {"Temp", "pH", "Cond", "alk", "ca", "mg"}
GROUPS = ("cameras", "tank")
KEYS = ("chips", "cameras", "tank", "solar", "screens")

BUILT_IN_SCREENS = ("weather", "solar", "tank", "cameras")
SCREEN_TYPES = (*BUILT_IN_SCREENS, "thermostat", "air_quality")
SCREEN_TITLES = {"weather": "Weather", "solar": "Solar", "tank": "Tank", "cameras": "Cameras"}
MAX_CLIMATES = 4
# What fits 1080p without scrolling (AirQualityActivity).
MAX_AIR_SENSORS = 12
# A thermostat screen's other readings, along its bottom.
MAX_READINGS = 8
# A screen's id: it goes in links (tvpanel://screen/<id>) and in tv_panel.show.
SCREEN_ID = re.compile(r"^[a-z0-9_]+$")
CAMERA = "camera"


def default_screens() -> list[dict[str, Any]]:
    """The screens the TV has always had."""
    return [{"id": t, "type": t} for t in BUILT_IN_SCREENS]


def screen_title(screen: dict[str, Any]) -> str:
    return screen.get("title") or SCREEN_TITLES.get(screen["type"], screen["id"])


def saved(options: dict[str, Any]) -> bool:
    """Whether anything was ever saved: if not, the TV keeps reading the labels and helpers itself."""
    return "chips" in options or "entities" in options


def settings(hass: HomeAssistant, options: dict[str, Any]) -> dict[str, Any]:
    """The settings in the current shape, whatever shape they were saved in."""
    screens = [dict(sc) for sc in options["screens"]] if "screens" in options else default_screens()
    if "chips" in options:
        return {
            "chips": [_upgrade_chip(c) for c in options["chips"]],
            "cameras": list(options.get("cameras") or []),
            "tank": list(options.get("tank") or []),
            "solar": options.get("solar"),
            "screens": screens,
        }
    if "entities" in options:
        entities = list(options["entities"])
        order = list(options.get("camera_order") or [])
    else:
        entities = labelled_entities(hass)
        order = old_camera_order(hass, sorted(e for e in entities if e.startswith("camera.")))
    return _from_flat(hass, entities, order, options.get("solar")) | {"screens": screens}


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


def _camera_screen(value: dict[str, Any]) -> dict[str, Any]:
    if value["screen"] == CAMERA and "camera" not in value:
        raise vol.Invalid("choose the camera to open", path=["camera"])
    return value


def upgrade_ok(ok: Any) -> Any:
    """{"action": "page", "page": ...}, as 0.4.0 to 0.6.0 saved it, as the screen it means. Anything else as is."""
    if not isinstance(ok, dict) or ok.get("action") != "page":
        return ok
    out = {k: v for k, v in ok.items() if k != "page"} | {"action": "screen"}
    if "page" in ok:
        out["screen"] = ok["page"]
    return out


def _upgrade_chip(chip: dict[str, Any]) -> dict[str, Any]:
    return chip | {"ok": upgrade_ok(chip["ok"])} if "ok" in chip else chip


# What OK on a chip does. Without one (or "default"), what TV Panel always did for that kind of chip: see default_ok.
# Checked by its action, so a mistake names what's missing rather than listing every kind of action.
OK = vol.All(
    upgrade_ok,
    cv.key_value_schemas(
        "action",
        {
            "default": vol.Schema({vol.Required("action"): "default"}),
            "none": vol.Schema({vol.Required("action"): "none"}),
            "screen": vol.All(
                vol.Schema(
                    {
                        vol.Required("action"): "screen",
                        vol.Required("screen"): vol.All(cv.string, vol.Match(SCREEN_ID, "not a screen id")),
                        vol.Optional("camera"): cv.entity_domain("camera"),
                    }
                ),
                _camera_screen,
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
    ),
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


def asks_twice(hass: HomeAssistant, entity_id: str) -> bool:
    """A cover that opens a way in (a garage door, a gate or a door) toggles on two presses by default. Blinds,
    shades and windows don't; neither do locks. chips.js asksTwice() mirrors it."""
    state = hass.states.get(entity_id)
    dc = state.attributes.get("device_class") if state is not None else None
    if dc in ("garage", "gate", "door") or is_garage(hass, entity_id):
        return True
    words = re.split(r"[^a-z]+", (entity_id + " " + _name(hass, entity_id)).lower())
    return not dc and "gate" in words


def is_solar(hass: HomeAssistant, entity_id: str) -> bool:
    state = hass.states.get(entity_id)
    return (
        state is not None
        and state.attributes.get("device_class") in ("power", "energy")
        and any(w in entity_id for w in ("production", "solar", "pv"))
    )


def default_ok(
    hass: HomeAssistant, chip: dict[str, Any], screens: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    """What OK does unless it's set: the chip's screen, a garage door's, gate's or door's toggle (pressed twice), or
    nothing. A climate entity opens the first thermostat screen that lists it, and a sensor the first air quality
    screen that lists it. A screen that was removed is no one's default: the chip does nothing. chips.js defaultOk()
    mirrors it for the sidebar page."""
    screens = default_screens() if screens is None else screens
    ids = {sc["id"] for sc in screens}

    def screen(sid: str) -> dict[str, Any]:
        return {"action": "screen", "screen": sid} if sid in ids else {"action": "none"}

    def listed(kind: str, key: str, entity_id: str) -> dict[str, Any] | None:
        sid = next((sc["id"] for sc in screens if sc["type"] == kind and entity_id in sc.get(key, [])), None)
        return None if sid is None else screen(sid)

    if chip["type"] == "cameras":
        return screen("cameras")
    if chip["type"] == "tank":
        return screen("tank")
    e = chip["entity"]
    domain = e.split(".")[0]
    if domain == "weather":
        return screen("weather")
    if domain == "camera":
        return screen("cameras")
    if domain == "climate":
        return listed("thermostat", "climates", e) or {"action": "none"}
    if _is_probe(hass, e):
        return screen("tank")
    if domain == "sensor" and is_solar(hass, e):
        return screen("solar")
    if domain == "sensor" and (air := listed("air_quality", "sensors", e)) is not None:
        return air
    if domain == "cover" and asks_twice(hass, e):
        return {"action": "toggle", "entity": e, "confirm": True}
    return {"action": "none"}


def resolve_ok(
    hass: HomeAssistant, chip: dict[str, Any], screens: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    """What OK on this chip does, in full: its own setting with the gaps filled, or the default."""
    ok = chip.get("ok")
    if not ok or ok.get("action") == "default":
        return default_ok(hass, chip, screens)
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


def _entities(name: str, domain: str, value: Any, least: int, most: int, what: str) -> list[str]:
    items = list(value or []) if isinstance(value, list | tuple) or value is None else [value]
    if len(items) < least:
        raise vol.Invalid(f"{name}: choose a {what}")
    if len(items) > most:
        raise vol.Invalid(f"{name}: at most {most} {what}s")
    try:
        return [cv.entity_domain(domain)(e) for e in items]
    except vol.Invalid as err:
        raise vol.Invalid(f"{name}: {err}") from err


def _screen(value: Any) -> dict[str, Any]:
    """One screen, checked by its type. A mistake names the screen (by its title, or its id) and what's wrong."""
    if not isinstance(value, dict):
        raise vol.Invalid("a screen needs an id and a type")
    sid, kind = value.get("id"), value.get("type")
    title = cv.string(value["title"]).strip() if value.get("title") is not None else ""
    name = title or str(sid)
    if not isinstance(sid, str) or not SCREEN_ID.match(sid):
        raise vol.Invalid(f"{name}: a screen's id is lower-case letters, digits and _, not {sid!r}")
    if kind not in SCREEN_TYPES:
        raise vol.Invalid(f"{name}: no screen type {kind}: there's {', '.join(SCREEN_TYPES)}")
    if kind in BUILT_IN_SCREENS:
        if sid != kind:
            raise vol.Invalid(f"{name}: the {kind} screen's id is {kind}")
        return {"id": sid, "type": kind}
    # Links, tv_panel.show and older apps treat these ids as the built-in screens (or one camera), whatever the type.
    if sid in BUILT_IN_SCREENS or sid == CAMERA:
        raise vol.Invalid(f"{name}: {sid} is taken, by a built-in screen")
    if not title:
        raise vol.Invalid(f"{sid}: give it a title")
    out = {"id": sid, "type": kind, "title": title}
    if kind == "thermostat":
        out["climates"] = _entities(name, "climate", value.get("climates"), 1, MAX_CLIMATES, "thermostat")
        out["sensors"] = _entities(name, "sensor", value.get("sensors"), 0, MAX_READINGS, "sensor")
    else:
        out["sensors"] = _entities(name, "sensor", value.get("sensors"), 1, MAX_AIR_SENSORS, "sensor")
    return out


SCHEMA = vol.Schema(
    {
        vol.Required("chips"): [CHIP],
        vol.Required("cameras"): [cv.entity_domain("camera")],
        vol.Required("tank"): [cv.entity_id],
        vol.Optional("solar", default=None): _solar,
        vol.Optional("screens"): [_screen],
    }
)


def chip_name(hass: HomeAssistant | None, chip: dict[str, Any]) -> str:
    """A chip as the person knows it: its label, its entity's name, or its group."""
    if chip.get("label"):
        return chip["label"]
    if chip["type"] != "entity":
        return SCREEN_TITLES[chip["type"]]
    return _name(hass, chip["entity"]) if hass is not None else chip["entity"]


def validate(
    data: dict[str, Any], hass: HomeAssistant | None = None, before: dict[str, Any] | None = None
) -> dict[str, Any]:
    """Settings as the sidebar page sends them, cleaned: labels trimmed, an empty label dropped. A chip set to open a
    screen that isn't there is refused by name; `before` (the settings as they were) names a screen just removed.

    A page from before 0.7.0 sends no "screens": then nothing is saved for them, and the TV keeps its screens.
    """
    s = SCHEMA(data)
    seen: set[str] = set()
    for sc in s.get("screens") or []:
        if sc["id"] in seen:
            raise vol.Invalid(f"two screens are called {sc['id']}")
        seen.add(sc["id"])
    if "screens" in s:
        screens = s["screens"]
    elif before is not None and before.get("screens") is not None:
        # An empty list is every screen removed, not the built-in four.
        screens = before["screens"]
    else:
        screens = default_screens()
    there = {sc["id"] for sc in screens} | {CAMERA}
    titles = {sc["id"]: screen_title(sc) for sc in (before or {}).get("screens") or []}
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
        if ok.get("action") == "screen" and ok["screen"] not in there:
            gone = titles.get(ok["screen"], ok["screen"])
            raise vol.Invalid(f"{chip_name(hass, chip)}'s OK opens the {gone} screen, which was removed")
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
