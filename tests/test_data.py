"""/api/tv_panel/data/<name>: what the TV draws, worked out by the integration.

The app used to send its own templates to /api/template, which only an admin may call. The integration now builds the
same answers itself, so the TV's user doesn't have to be an admin. The shapes are the ones the app's parsers read
(Ha.parseWidgets, Cameras.parse, Apex.parse, Solar.parse).
"""

from datetime import datetime
from http import HTTPStatus

from freezegun import freeze_time
from homeassistant.auth.const import GROUP_ID_ADMIN, GROUP_ID_USER
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr, entity_registry as er, label_registry as lr
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.tv_panel.const import CONF_USER_ID

from .common import paired_tv

OPTIONS = {
    "chips": [
        {"type": "entity", "entity": "weather.home"},
        {"type": "cameras"},
        {"type": "entity", "entity": "climate.hall", "label": "Hall"},
        {"type": "tank"},
    ],
    "cameras": ["camera.driveway", "camera.front_door"],
    "tank": ["sensor.apex_temp"],
    "solar": None,
}


async def _get(hass_client_no_auth, token: str, name: str):
    client = await hass_client_no_auth()
    return await client.get(f"/api/tv_panel/data/{name}", headers={"Authorization": f"Bearer {token}"})


async def _data(hass_client_no_auth, token: str, name: str):
    r = await _get(hass_client_no_auth, token, name)
    assert r.status == HTTPStatus.OK, await r.text()
    return await r.json()


def _camera_with_motion_sensor(hass: HomeAssistant, camera: str, motion: str, device_name: str) -> str:
    """A camera and its motion sensor on one device, as an Axis or UniFi camera has them."""
    source = MockConfigEntry(domain="axis")
    source.add_to_hass(hass)
    device = dr.async_get(hass).async_get_or_create(
        config_entry_id=source.entry_id, identifiers={("axis", camera)}, name=device_name
    )
    reg = er.async_get(hass)
    for entity_id in (camera, motion):
        domain, object_id = entity_id.split(".")
        reg.async_get_or_create(domain, "axis", entity_id, config_entry=source, device_id=device.id,
                                suggested_object_id=object_id)
    return device.id


async def test_the_chip_row_in_chip_order(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("weather.home", "sunny", {"friendly_name": "Home", "temperature": 52,
                                                     "temperature_unit": "°F"})
    hass.states.async_set("climate.hall", "heat_cool", {
        "friendly_name": "Hall Thermostat", "current_temperature": 69, "target_temp_low": 68, "target_temp_high": 78,
        "hvac_action": "idle"})
    _camera_with_motion_sensor(hass, "camera.front_door", "binary_sensor.front_door_motion", "Front Door")
    hass.states.async_set("camera.front_door", "idle", {"friendly_name": "Front Door"})
    hass.states.async_set("binary_sensor.front_door_motion", "on", {"device_class": "motion"})
    hass.states.async_set("camera.driveway", "idle", {"friendly_name": "Driveway"})
    hass.states.async_set("sensor.apex_temp", "78.9", {"friendly_name": "Apex Temp", "unit_of_measurement": "°F",
                                                       "apex_probe_type": "Temp"})
    hass.states.async_set("binary_sensor.apex_alarm", "on", {"active_alerts": ["Temp high", "pH low"]})
    _, token = await paired_tv(hass, OPTIONS)

    body = await _data(hass_client_no_auth, token, "chips")

    assert body["on"] == "on"
    assert body["ordered"] is True
    assert body["alarm"] == "on" and body["alerts"] == 2
    # A group's members stand where its chip is.
    assert [i["id"] for i in body["items"]] == [
        "weather.home", "camera.driveway", "camera.front_door", "climate.hall", "sensor.apex_temp"
    ]
    weather, driveway, front_door, hall, temp = body["items"]
    assert weather == {
        "id": "weather.home", "name": "Home", "device": None, "state": "sunny", "unit": None, "icon": None,
        "dc": None, "temp": 52, "tunit": "°F", "apex": None, "cur": None, "lo": None, "hi": None, "action": None,
        "motion": False,
    }
    assert (hall["cur"], hall["lo"], hall["hi"], hall["action"]) == (69, 68, 78, "idle")
    assert front_door["device"] == "Front Door" and front_door["motion"] is True
    assert driveway["device"] is None and driveway["motion"] is False
    assert (temp["state"], temp["unit"], temp["apex"]) == ("78.9", "°F", "Temp")


async def test_a_device_renamed_by_the_owner_goes_by_its_new_name(hass: HomeAssistant, hass_client_no_auth) -> None:
    device_id = _camera_with_motion_sensor(hass, "camera.front_door", "binary_sensor.front_door_motion", "AXIS M3106")
    dr.async_get(hass).async_update_device(device_id, name_by_user="Porch")
    _, token = await paired_tv(hass, OPTIONS)
    items = (await _data(hass_client_no_auth, token, "chips"))["items"]
    assert next(i for i in items if i["id"] == "camera.front_door")["device"] == "Porch"


async def test_an_entity_home_assistant_doesnt_have_reads_unknown(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, {**OPTIONS, "chips": [{"type": "entity", "entity": "sensor.gone"}]})
    items = (await _data(hass_client_no_auth, token, "chips"))["items"]
    assert items[0]["state"] == "unknown" and items[0]["name"] is None


async def test_the_row_switch_off_says_off(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    switch = "switch.bravia_widget_row"
    await hass.services.async_call("switch", "turn_off", {"entity_id": switch}, blocking=True)
    assert (await _data(hass_client_no_auth, token, "chips"))["on"] == "off"


async def test_never_saved_the_labelled_entities_unordered(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Before Configure is saved, the tv_widget label picks the chips, and the TV sorts them itself."""
    label = lr.async_get(hass).async_create("tv_widget")
    reg = er.async_get(hass)
    for entity_id in ("sensor.hall_temperature", "cover.garage_door"):
        domain, object_id = entity_id.split(".")
        entry = reg.async_get_or_create(domain, "demo", object_id, suggested_object_id=object_id)
        reg.async_update_entity(entry.entity_id, labels={label.label_id})
        hass.states.async_set(entity_id, "1")
    _, token = await paired_tv(hass)
    body = await _data(hass_client_no_auth, token, "chips")
    assert body["ordered"] is False
    assert sorted(i["id"] for i in body["items"]) == ["cover.garage_door", "sensor.hall_temperature"]


async def test_the_cameras_in_grid_order(hass: HomeAssistant, hass_client_no_auth) -> None:
    _camera_with_motion_sensor(hass, "camera.front_door", "binary_sensor.front_door_motion", "Front Door")
    hass.states.async_set("camera.front_door", "idle", {"friendly_name": "Camera - Front Door"})
    hass.states.async_set("binary_sensor.front_door_motion", "off", {"device_class": "motion"})
    hass.states.async_set("camera.driveway", "unavailable", {"friendly_name": "Driveway"})
    _, token = await paired_tv(hass, OPTIONS)

    body = await _data(hass_client_no_auth, token, "cameras")

    assert body["order"] == "camera.driveway,camera.front_door"
    assert body["items"] == [
        {"id": "camera.driveway", "name": "Driveway", "state": "unavailable", "motion": False},
        {"id": "camera.front_door", "name": "Camera - Front Door", "state": "idle", "motion": False},
    ]


@freeze_time("2026-11-21 18:30:00+00:00")
async def test_the_tank_every_probe_and_the_trident_and_doser(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("sensor.apex_temp", "78.9", {"friendly_name": "Apex Temp", "unit_of_measurement": "°F",
                                                       "apex_probe_type": "Temp"})
    hass.states.async_set("sensor.apex_orp", "350", {"apex_probe_type": "ORP"})
    hass.states.async_set("sensor.apex_tri_reagent_a", "61", {"percent_left": 61, "tests_remaining": 120})
    hass.states.async_set("sensor.apex_dos_1_volume_left", "800", {"percent_full": 80})
    hass.states.async_set("select.apex_trident_mode", "auto")
    hass.states.async_set("binary_sensor.apex_alarm", "on", {"active_alerts": ["Temp high"]})
    hass.states.async_set("sensor.living_room_temperature", "70")
    _, token = await paired_tv(hass, OPTIONS)

    items = (await _data(hass_client_no_auth, token, "tank"))["items"]

    # The probes first, then the rest, each by entity id.
    assert [i["id"] for i in items] == [
        "sensor.apex_orp", "sensor.apex_temp",
        "binary_sensor.apex_alarm", "select.apex_trident_mode", "sensor.apex_dos_1_volume_left",
        "sensor.apex_tri_reagent_a",
    ]
    temp = items[1]
    assert temp == {
        "id": "sensor.apex_temp", "state": "78.9", "unit": "°F", "name": "Apex Temp", "type": "Temp",
        "changed": datetime.fromisoformat("2026-11-21T18:30:00+00:00").isoformat(), "tests": None, "full": None,
        "left": None, "alerts": 0,
    }
    reagent = items[5]
    assert (reagent["left"], reagent["tests"]) == (61, 120)
    assert items[4]["full"] == 80
    assert items[2]["alerts"] == 1


async def test_solar_the_envoy_and_any_battery(hass: HomeAssistant, hass_client_no_auth) -> None:
    reg = er.async_get(hass)
    envoy = MockConfigEntry(domain="enphase_envoy")
    envoy.add_to_hass(hass)
    for entity_id, state, unit in (
        ("sensor.envoy_current_power_production", "1234", "W"),
        ("sensor.inverter_1001", "250", "W"),
        ("binary_sensor.enpower_grid_status", "on", None),
        ("select.enpower_storage_mode", "self_consumption", None),
        ("switch.enpower_charge_from_grid", "off", None),
    ):
        domain, object_id = entity_id.split(".")
        reg.async_get_or_create(domain, "enphase_envoy", object_id, config_entry=envoy, suggested_object_id=object_id)
        hass.states.async_set(entity_id, state, {"unit_of_measurement": unit} if unit else {})
    hass.states.async_set("sensor.powerwall_battery_charge_power", "500", {"unit_of_measurement": "W"})
    hass.states.async_set("sensor.hall_temperature", "70", {"unit_of_measurement": "°F"})
    _, token = await paired_tv(hass, OPTIONS)

    items = (await _data(hass_client_no_auth, token, "solar"))["items"]

    assert sorted(items) == sorted([
        ["sensor.envoy_current_power_production", "1234", "W"],
        ["sensor.inverter_1001", "250", "W"],
        ["binary_sensor.enpower_grid_status", "on", None],
        ["select.enpower_storage_mode", "self_consumption", None],
        ["sensor.powerwall_battery_charge_power", "500", "W"],
    ])


async def test_without_an_envoy_solar_is_empty(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    assert (await _data(hass_client_no_auth, token, "solar")) == {"items": []}


async def test_only_a_tv_and_only_what_there_is(hass: HomeAssistant, hass_client_no_auth, hass_access_token) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    assert (await _get(hass_client_no_auth, token, "nothing")).status == HTTPStatus.NOT_FOUND
    # The owner's own token isn't a TV.
    assert (await _get(hass_client_no_auth, hass_access_token, "chips")).status == HTTPStatus.NOT_FOUND


@pytest.mark.parametrize("name", ["chips", "cameras", "tank", "solar"])
async def test_a_tv_paired_as_admin_drops_to_a_normal_user(
    hass: HomeAssistant, hass_client_no_auth, name: str
) -> None:
    """TVs paired before 0.6.0 got an admin user, because the app rendered its own templates. An app that asks for
    its data here no longer needs that."""
    entry, token = await paired_tv(hass, OPTIONS)
    user = await hass.auth.async_get_user(entry.data[CONF_USER_ID])
    await hass.auth.async_update_user(user, group_ids=[GROUP_ID_ADMIN])
    assert user.is_admin

    await _data(hass_client_no_auth, token, name)

    user = await hass.auth.async_get_user(entry.data[CONF_USER_ID])
    assert not user.is_admin
    assert [g.id for g in user.groups] == [GROUP_ID_USER]
    assert user.local_only
