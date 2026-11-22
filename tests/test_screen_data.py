"""/api/tv_panel/data/thermostat and /data/air_quality?screen=<id>: one of the TV's own screens, live.

Any thermostat (any climate entity) and any sensor: only Home Assistant's standard attributes are read. Temperatures
are in Home Assistant's own unit, which the answer says.
"""

from http import HTTPStatus

from homeassistant.core import HomeAssistant
from homeassistant.helpers import area_registry as ar, device_registry as dr, entity_registry as er
from homeassistant.util.unit_system import METRIC_SYSTEM, US_CUSTOMARY_SYSTEM
from pytest_homeassistant_custom_component.common import MockConfigEntry

from .common import paired_tv

SCREENS = [
    {"id": "weather", "type": "weather"},
    {"id": "thermostat", "type": "thermostat", "title": "Upstairs", "climates": ["climate.up", "climate.gone"],
     "sensors": ["sensor.outside"]},
    {"id": "air_quality", "type": "air_quality", "title": "Air", "sensors": ["sensor.den_co2", "sensor.voc"]},
]
OPTIONS = {"chips": [], "cameras": [], "tank": [], "solar": None, "screens": SCREENS}


async def _get(hass_client_no_auth, token: str, path: str):
    client = await hass_client_no_auth()
    return await client.get(f"/api/tv_panel/data/{path}", headers={"Authorization": f"Bearer {token}"})


async def test_a_thermostat_screen_as_home_assistant_has_it(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.config.units = US_CUSTOMARY_SYSTEM
    hass.states.async_set("climate.up", "heat_cool", {
        "friendly_name": "Upstairs", "current_temperature": 71.5, "target_temp_low": 68, "target_temp_high": 76,
        "hvac_action": "cooling", "hvac_modes": ["off", "heat", "cool", "heat_cool"], "current_humidity": 41,
        "fan_mode": "auto", "preset_mode": "home", "min_temp": 45, "max_temp": 95, "supported_features": 410,
        "icon": "mdi:not-needed",
    })
    hass.states.async_set("sensor.outside", "52.1", {"friendly_name": "Outside", "unit_of_measurement": "°F",
                                                    "device_class": "temperature"})
    _, token = await paired_tv(hass, OPTIONS)

    r = await _get(hass_client_no_auth, token, "thermostat?screen=thermostat")

    assert r.status == HTTPStatus.OK
    body = await r.json()
    assert body["title"] == "Upstairs"
    assert body["unit"] == "°F"
    up, gone = body["climates"]
    assert up == {
        "id": "climate.up", "name": "Upstairs", "state": "heat_cool", "area": None,
        # Only the standard climate attributes the screen reads.
        "attributes": {
            "current_temperature": 71.5, "target_temp_low": 68, "target_temp_high": 76, "hvac_action": "cooling",
            "hvac_modes": ["off", "heat", "cool", "heat_cool"], "current_humidity": 41, "fan_mode": "auto",
            "preset_mode": "home", "min_temp": 45, "max_temp": 95, "supported_features": 410,
        },
    }
    # One Home Assistant doesn't have (removed since): unknown, so the screen can say so.
    assert gone == {"id": "climate.gone", "name": None, "state": "unknown", "area": None, "attributes": {}}
    assert body["sensors"] == [
        {"id": "sensor.outside", "area": None, "name": "Outside", "label": "Outside", "state": "52.1", "unit": "°F",
         "dc": "temperature"}
    ]
    # One sensor, one tile.
    assert body["tiles"] == [["sensor.outside"]]


async def test_a_thermostat_screens_readings_room_first(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Its readings are tiles, as on the air quality screen: the room, then the label, the name without the room and
    the device ("Probe 2 Office Temperature" on "Probe 2", in the Office, is "Temperature"). The name stays whole, as
    the app 0.7.0 reads it."""
    office = ar.async_get(hass).async_create("Office")
    source = MockConfigEntry(domain="probe")
    source.add_to_hass(hass)
    probe = dr.async_get(hass).async_get_or_create(
        config_entry_id=source.entry_id, identifiers={("probe", "2")}, name="Probe 2"
    )
    dr.async_get(hass).async_update_device(probe.id, area_id=office.id)
    er.async_get(hass).async_get_or_create(
        "sensor", "probe", "t", config_entry=source, device_id=probe.id, suggested_object_id="outside"
    )
    hass.states.async_set("sensor.outside", "72.7", {"friendly_name": "Probe 2 Office Temperature",
                                                    "unit_of_measurement": "°F", "device_class": "temperature"})
    _, token = await paired_tv(hass, OPTIONS)

    body = await (await _get(hass_client_no_auth, token, "thermostat?screen=thermostat")).json()

    assert [(s["area"], s["label"], s["name"]) for s in body["sensors"]] == [
        ("Office", "Temperature", "Probe 2 Office Temperature")
    ]


async def test_a_thermostat_says_where_it_is(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Its own area, else its device's."""
    office = ar.async_get(hass).async_create("Office")
    source = MockConfigEntry(domain="hvac")
    source.add_to_hass(hass)
    device = dr.async_get(hass).async_get_or_create(
        config_entry_id=source.entry_id, identifiers={("hvac", "1")}, name="Office Thermostat"
    )
    dr.async_get(hass).async_update_device(device.id, area_id=office.id)
    er.async_get(hass).async_get_or_create(
        "climate", "hvac", "1", config_entry=source, device_id=device.id, suggested_object_id="up"
    )
    hass.states.async_set("climate.up", "heat", {"friendly_name": "Office Thermostat"})
    _, token = await paired_tv(hass, OPTIONS)

    body = await (await _get(hass_client_no_auth, token, "thermostat?screen=thermostat")).json()

    assert [(c["id"], c["area"]) for c in body["climates"]] == [("climate.up", "Office"), ("climate.gone", None)]


async def test_sensors_side_by_side_are_one_tile(hass: HomeAssistant, hass_client_no_auth) -> None:
    """"tiles" groups the screen's sensors by "joined", in order; "sensors" still lists each one."""
    office = ["sensor.office_temperature", "sensor.office_humidity", "sensor.office_co2", "sensor.porch_aqi"]
    screens = [
        SCREENS[1] | {"sensors": office[:2], "joined": office[1:2]},
        SCREENS[2] | {"sensors": office, "joined": office[1:3]},
        {"id": "thermostat_2", "type": "thermostat", "title": "Down", "climates": ["climate.up"], "sensors": []},
    ]
    _, token = await paired_tv(hass, {**OPTIONS, "screens": screens})

    up = await (await _get(hass_client_no_auth, token, "thermostat?screen=thermostat")).json()
    air = await (await _get(hass_client_no_auth, token, "air_quality?screen=air_quality")).json()
    down = await (await _get(hass_client_no_auth, token, "thermostat?screen=thermostat_2")).json()

    assert up["tiles"] == [["sensor.office_temperature", "sensor.office_humidity"]]
    assert [s["id"] for s in up["sensors"]] == office[:2]
    assert air["tiles"] == [["sensor.office_temperature", "sensor.office_humidity", "sensor.office_co2"],
                            ["sensor.porch_aqi"]]
    assert [s["id"] for s in air["sensors"]] == office
    # No sensors, no tiles: the key is there all the same.
    assert down["tiles"] == []


async def test_temperatures_in_home_assistants_unit(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.config.units = METRIC_SYSTEM
    _, token = await paired_tv(hass, OPTIONS)
    body = await (await _get(hass_client_no_auth, token, "thermostat?screen=thermostat")).json()
    assert body["unit"] == "°C"


async def test_an_air_quality_screen_room_first(hass: HomeAssistant, hass_client_no_auth) -> None:
    den = ar.async_get(hass).async_create("Den")
    source = MockConfigEntry(domain="airthings")
    source.add_to_hass(hass)
    reg = er.async_get(hass)
    reg.async_get_or_create("sensor", "airthings", "co2", config_entry=source, suggested_object_id="den_co2")
    reg.async_update_entity("sensor.den_co2", area_id=den.id)
    hass.states.async_set("sensor.den_co2", "612", {"friendly_name": "Den CO2", "unit_of_measurement": "ppm",
                                                   "device_class": "carbon_dioxide", "state_class": "measurement"})
    hass.states.async_set("sensor.voc", "112", {"friendly_name": "VOC index"})
    _, token = await paired_tv(hass, OPTIONS)

    body = await (await _get(hass_client_no_auth, token, "air_quality?screen=air_quality")).json()

    assert body["title"] == "Air"
    assert body["tiles"] == [["sensor.den_co2"], ["sensor.voc"]]
    co2, voc = body["sensors"]
    # The room, then the name without the room at its front.
    assert co2 == {"id": "sensor.den_co2", "area": "Den", "name": "CO2", "state": "612", "unit": "ppm",
                   "dc": "carbon_dioxide", "sc": "measurement"}
    assert voc == {"id": "sensor.voc", "area": None, "name": "VOC index", "state": "112", "unit": None, "dc": None,
                   "sc": None}


async def test_an_air_quality_tile_drops_the_device_name_too(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Home Assistant names an entity "<device> <name>": "Air Monitor CO2" on the device "Air Monitor" is "CO2",
    which a tile has room for. The chips drop it the same way."""
    living = ar.async_get(hass).async_create("Living Room")
    source = MockConfigEntry(domain="sgp30")
    source.add_to_hass(hass)
    device = dr.async_get(hass).async_get_or_create(
        config_entry_id=source.entry_id, identifiers={("sgp30", "1")}, name="Air Monitor"
    )
    dr.async_get(hass).async_update_device(device.id, area_id=living.id)
    er.async_get(hass).async_get_or_create(
        "sensor", "sgp30", "co2", config_entry=source, device_id=device.id, suggested_object_id="den_co2"
    )
    hass.states.async_set("sensor.den_co2", "1286", {"friendly_name": "Air Monitor CO2", "unit_of_measurement": "ppm",
                                                    "device_class": "carbon_dioxide"})
    # The room after the device: "Probe 2 Office Temperature" on the device "Probe 2", in the Office.
    office = ar.async_get(hass).async_create("Office")
    probe = dr.async_get(hass).async_get_or_create(
        config_entry_id=source.entry_id, identifiers={("sgp30", "2")}, name="Probe 2"
    )
    dr.async_get(hass).async_update_device(probe.id, area_id=office.id)
    er.async_get(hass).async_get_or_create(
        "sensor", "sgp30", "t", config_entry=source, device_id=probe.id, suggested_object_id="voc"
    )
    hass.states.async_set("sensor.voc", "72.7", {"friendly_name": "Probe 2 Office Temperature"})
    _, token = await paired_tv(hass, OPTIONS)

    body = await (await _get(hass_client_no_auth, token, "air_quality?screen=air_quality")).json()

    assert [(s["area"], s["name"]) for s in body["sensors"]] == [("Living Room", "CO2"), ("Office", "Temperature")]


async def test_only_the_tvs_own_screens(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    # No such screen, a screen of another type, and no screen named.
    wrong = ("thermostat?screen=upstairs", "thermostat?screen=air_quality", "thermostat", "air_quality?screen=weather")
    for path in wrong:
        r = await _get(hass_client_no_auth, token, path)
        assert r.status == HTTPStatus.NOT_FOUND, path
    # Another TV's screens are another TV's.
    _, other_token = await paired_tv(hass, {**OPTIONS, "screens": []}, tv_id="other", host="192.168.1.51",
                                         name="Other")
    r = await _get(hass_client_no_auth, other_token, "thermostat?screen=thermostat")
    assert r.status == HTTPStatus.NOT_FOUND
