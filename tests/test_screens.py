"""The TV's screens: the four built-in ones, plus thermostat and air quality screens that can be added and removed."""

from unittest.mock import patch

from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry
import voluptuous as vol

from custom_components.tv_panel import model

from .common import paired_tv

BUILT_IN = [
    {"id": "weather", "type": "weather"},
    {"id": "solar", "type": "solar"},
    {"id": "tank", "type": "tank"},
    {"id": "cameras", "type": "cameras"},
]
UPSTAIRS = {"id": "thermostat", "type": "thermostat", "title": "Upstairs", "climates": ["climate.up"], "sensors": []}
AIR = {"id": "air_quality", "type": "air_quality", "title": "Air", "sensors": ["sensor.den_co2"]}


def _settings(screens=None, chips=None) -> dict:
    s = {"chips": chips or [], "cameras": [], "tank": [], "solar": None}
    if screens is not None:
        s["screens"] = screens
    return s


# ---- the list --------------------------------------------------------------------------------------------------------


async def test_without_a_list_the_tv_has_the_four_it_always_had(hass: HomeAssistant) -> None:
    """Every setting saved before 0.7.0 has no "screens": they're the weather, solar, tank and camera screens."""
    assert model.settings(hass, {"chips": [], "cameras": [], "tank": []})["screens"] == BUILT_IN
    assert model.settings(hass, {})["screens"] == BUILT_IN
    assert model.settings(hass, {"entities": ["weather.home"]})["screens"] == BUILT_IN


async def test_a_saved_list_is_the_list(hass: HomeAssistant) -> None:
    screens = [BUILT_IN[0], UPSTAIRS, AIR]
    assert model.settings(hass, _settings(screens))["screens"] == screens


def test_thermostat_and_air_quality_screens_are_checked() -> None:
    saved = model.validate(_settings([*BUILT_IN, UPSTAIRS, AIR, UPSTAIRS | {"id": "thermostat_2", "title": "Down"}]))
    assert [s["id"] for s in saved["screens"]] == [
        "weather", "solar", "tank", "cameras", "thermostat", "air_quality", "thermostat_2",
    ]
    # Extra sensors are optional; titles are trimmed.
    hall = {"id": "hall", "type": "thermostat", "title": " Hall ", "climates": ["climate.h"]}
    one = model.validate(_settings([hall]))
    assert one["screens"] == [hall | {"title": "Hall", "sensors": []}]


@pytest.mark.parametrize(
    ("screen", "says"),
    [
        (UPSTAIRS | {"climates": []}, "Upstairs: choose a thermostat"),
        (UPSTAIRS | {"climates": ["climate.a", "climate.b", "climate.c", "climate.d", "climate.e"]}, "at most 4"),
        (UPSTAIRS | {"climates": ["sensor.x"]}, "Upstairs"),
        (UPSTAIRS | {"title": " "}, "thermostat: give it a title"),
        (AIR | {"sensors": []}, "Air: choose a sensor"),
        (AIR | {"sensors": [f"sensor.s{i}" for i in range(13)]}, "at most 12"),
        ({"id": "weather", "type": "solar"}, "solar"),
        ({"id": "Up Stairs", "type": "thermostat", "title": "Up", "climates": ["climate.a"]}, "id"),
        ({"id": "x", "type": "radio"}, "type"),
        # A built-in screen's id, or one camera's, would be routed as that everywhere (links, older apps).
        (UPSTAIRS | {"id": "tank"}, "Upstairs: tank is taken"),
        (AIR | {"id": "camera"}, "Air: camera is taken"),
    ],
)
def test_a_bad_screen_says_which_and_what(screen: dict, says: str) -> None:
    with pytest.raises(vol.Invalid, match=says):
        model.validate(_settings([screen]))


def test_each_id_once_and_each_built_in_once() -> None:
    with pytest.raises(vol.Invalid, match="two screens are called thermostat"):
        model.validate(_settings([UPSTAIRS, UPSTAIRS | {"title": "Again"}]))
    with pytest.raises(vol.Invalid, match="weather"):
        model.validate(_settings([BUILT_IN[0], BUILT_IN[0]]))


# ---- what OK opens ---------------------------------------------------------------------------------------------------


async def test_a_thermostat_opens_its_screen_by_default(hass: HomeAssistant) -> None:
    hass.states.async_set("sensor.den_co2", "600", {"device_class": "carbon_dioxide", "unit_of_measurement": "ppm"})
    screens = [*BUILT_IN, UPSTAIRS, AIR, UPSTAIRS | {"id": "thermostat_2", "climates": ["climate.up", "climate.down"]}]
    ok = lambda chip: model.resolve_ok(hass, chip, screens)  # noqa: E731
    # The first thermostat screen that lists it.
    assert ok({"type": "entity", "entity": "climate.up"}) == {"action": "screen", "screen": "thermostat"}
    assert ok({"type": "entity", "entity": "climate.down"}) == {"action": "screen", "screen": "thermostat_2"}
    assert ok({"type": "entity", "entity": "climate.attic"}) == {"action": "none"}
    assert ok({"type": "entity", "entity": "sensor.den_co2"}) == {"action": "screen", "screen": "air_quality"}
    # Without screens given: the built-in ones.
    assert model.resolve_ok(hass, {"type": "tank"}) == {"action": "screen", "screen": "tank"}


async def test_a_removed_screen_is_no_ones_default(hass: HomeAssistant) -> None:
    """A solar chip left at its default does nothing once the solar screen is gone (the press is still an event)."""
    hass.states.async_set("sensor.envoy_production", "1.2", {"device_class": "power"})
    chip = {"type": "entity", "entity": "sensor.envoy_production"}
    assert model.resolve_ok(hass, chip, BUILT_IN) == {"action": "screen", "screen": "solar"}
    without_solar = [s for s in BUILT_IN if s["id"] != "solar"]
    assert model.resolve_ok(hass, chip, without_solar) == {"action": "none"}
    assert model.resolve_ok(hass, {"type": "cameras"}, []) == {"action": "none"}


async def test_removing_a_screen_a_chip_opens_is_refused_by_name(hass: HomeAssistant) -> None:
    hass.states.async_set("sensor.hall", "68", {"friendly_name": "Hall temperature"})
    opens = {"action": "screen", "screen": "thermostat"}
    chips = [{"type": "entity", "entity": "sensor.hall", "label": "Hall", "ok": opens}]
    before = _settings([*BUILT_IN, UPSTAIRS], chips)
    with pytest.raises(vol.Invalid, match="Hall's OK opens the Upstairs screen, which was removed"):
        model.validate(_settings(BUILT_IN, chips), hass=hass, before=before)
    # Without a label, by the entity's name; a screen that never was, by its id.
    unlabelled = [{k: v for k, v in chips[0].items() if k != "label"}]
    with pytest.raises(vol.Invalid, match="Hall temperature's OK opens the thermostat screen, which was removed"):
        model.validate(_settings(BUILT_IN, unlabelled), hass=hass)
    # One camera full screen isn't in the list, and is always there.
    one_camera = {"action": "screen", "screen": "camera", "camera": "camera.a"}
    camera = [{"type": "entity", "entity": "sensor.hall", "ok": one_camera}]
    model.validate(_settings([], camera), hass=hass)


async def test_without_a_list_chips_open_the_built_in_screens(hass: HomeAssistant) -> None:
    """A page from before 0.7.0 (cached in a browser) saves no "screens": nothing is removed."""
    chips = [{"type": "entity", "entity": "sensor.x", "ok": {"action": "screen", "screen": "solar"}}]
    saved = model.validate(_settings(None, chips))
    assert "screens" not in saved
    with pytest.raises(vol.Invalid, match="thermostat"):
        model.validate(_settings(None, [chips[0] | {"ok": {"action": "screen", "screen": "thermostat"}}]))
    # Every screen removed before: none of the built-in ones is there either.
    with pytest.raises(vol.Invalid, match="solar"):
        model.validate(_settings(None, chips), before=_settings([], []))


async def test_a_save_without_screens_keeps_the_tvs_screens(hass: HomeAssistant, hass_ws_client) -> None:
    """A page loaded before the update to 0.7.0 sends no "screens": the thermostat screen added since stays."""
    opens = {"action": "screen", "screen": "thermostat"}
    chips = [{"type": "entity", "entity": "climate.up", "ok": opens}]
    entry, _ = await paired_tv(hass, _settings([*BUILT_IN, UPSTAIRS], chips))
    ws = await hass_ws_client(hass)
    old_page = _settings(None, chips)
    await ws.send_json({"id": 1, "type": "tv_panel/save", "entry_id": entry.entry_id, "settings": old_page})
    assert (await ws.receive_json())["success"]
    assert entry.options["screens"] == [*BUILT_IN, UPSTAIRS]


# ---- the TV, the page and tv_panel.show ------------------------------------------------------------------------------


async def test_the_tv_gets_its_screens_and_what_older_apps_can_open(hass: HomeAssistant, hass_client_no_auth) -> None:
    chips = [
        {"type": "entity", "entity": "climate.up"},
        {"type": "entity", "entity": "weather.home"},
    ]
    _, token = await paired_tv(hass, _settings([BUILT_IN[0], UPSTAIRS], chips))
    client = await hass_client_no_auth()
    body = await (await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})).json()
    assert body["screens"] == [BUILT_IN[0], UPSTAIRS]
    # Apps before 0.7.0 don't have a thermostat screen: to them the chip does nothing (the press is still an event).
    assert body["chips"][0]["ok"] == {"action": "none", "screen": "thermostat"}
    assert body["chips"][1]["ok"] == {"action": "page", "page": "weather", "screen": "weather"}


async def test_never_saved_the_tv_has_the_built_in_screens(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass)
    client = await hass_client_no_auth()
    body = await (await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})).json()
    assert body["screens"] == BUILT_IN
    assert body["chips"] is None


async def test_the_page_saves_screens_and_names_the_chip_in_the_way(hass: HomeAssistant, hass_ws_client) -> None:
    opens = {"action": "screen", "screen": "thermostat"}
    chips = [{"type": "entity", "entity": "climate.up", "label": "Up", "ok": opens}]
    entry, _ = await paired_tv(hass, _settings([*BUILT_IN, UPSTAIRS], chips))
    ws = await hass_ws_client(hass)
    await ws.send_json({"id": 1, "type": "tv_panel/settings", "entry_id": entry.entry_id})
    assert (await ws.receive_json())["result"]["screens"] == [*BUILT_IN, UPSTAIRS]

    await ws.send_json(
        {"id": 2, "type": "tv_panel/save", "entry_id": entry.entry_id, "settings": _settings(BUILT_IN, chips)}
    )
    msg = await ws.receive_json()
    assert not msg["success"]
    assert "Up's OK opens the Upstairs screen, which was removed" in msg["error"]["message"]

    await ws.send_json(
        {"id": 3, "type": "tv_panel/save", "entry_id": entry.entry_id, "settings": _settings([UPSTAIRS, AIR], chips)}
    )
    assert (await ws.receive_json())["success"]
    assert entry.options["screens"] == [UPSTAIRS, AIR]


def _android_tv_remote(hass: HomeAssistant, host: str = "192.168.1.50") -> list:
    atv = MockConfigEntry(domain="androidtv_remote", data={"host": host, "name": "TV"})
    atv.add_to_hass(hass)
    from homeassistant.helpers import entity_registry as er

    er.async_get(hass).async_get_or_create(
        "media_player", "androidtv_remote", f"{host}-mp", config_entry=atv, suggested_object_id="tv"
    )
    hass.states.async_set("media_player.tv", "on")
    calls = []

    async def play(call) -> None:
        calls.append(call)

    hass.services.async_register("media_player", "play_media", play)
    return calls


@pytest.fixture(autouse=True)
def no_wait():
    with patch("custom_components.tv_panel.tv_link.SCREENSAVER_DELAY", 0):
        yield


async def test_show_opens_any_screen_by_id(hass: HomeAssistant) -> None:
    await paired_tv(hass, _settings([BUILT_IN[0], UPSTAIRS]))
    played = _android_tv_remote(hass)
    await hass.services.async_call("tv_panel", "show", {"screen": "thermostat"}, blocking=True)
    # The built-in screens keep the links apps before 0.7.0 know.
    await hass.services.async_call("tv_panel", "show", {"screen": "weather"}, blocking=True)
    assert [c.data["media_content_id"] for c in played] == ["tvpanel://screen/thermostat", "tvpanel://weather"]


async def test_show_names_the_screens_there_are(hass: HomeAssistant) -> None:
    await paired_tv(hass, _settings([BUILT_IN[0], UPSTAIRS]))
    _android_tv_remote(hass)
    with pytest.raises(ServiceValidationError, match="BRAVIA has no screen solar: it has weather, thermostat"):
        await hass.services.async_call("tv_panel", "show", {"screen": "solar"}, blocking=True)
