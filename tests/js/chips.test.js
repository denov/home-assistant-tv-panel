// The TV Panel page's logic: run with `npm test` (plain Node, no packages).
import assert from "assert";
import * as chips from "../../custom_components/tv_panel/frontend/chips.js";

const hass = {
  states: {
    "weather.home": { entity_id: "weather.home", state: "partlycloudy",
      attributes: { friendly_name: "Home", temperature: 50, temperature_unit: "°F" } },
    "climate.hall": { entity_id: "climate.hall", state: "heat",
      attributes: { friendly_name: "Hallway Thermostat", current_temperature: 68, temperature: 70, hvac_action: "heating" } },
    "climate.den": { entity_id: "climate.den", state: "heat_cool",
      attributes: { friendly_name: "Den", current_temperature: 71.5, target_temp_low: 68, target_temp_high: 74 } },
    "sensor.hall_temperature": { entity_id: "sensor.hall_temperature", state: "68.4",
      attributes: { friendly_name: "Hallway Thermostat Temperature", unit_of_measurement: "°F", device_class: "temperature" } },
    "sensor.envoy_current_power_production": { entity_id: "sensor.envoy_current_power_production", state: "1.234",
      attributes: { friendly_name: "Envoy Current power production", unit_of_measurement: "kW", device_class: "power" } },
    "sensor.apex_temp": { entity_id: "sensor.apex_temp", state: "78.9",
      attributes: { friendly_name: "Apex Temp", unit_of_measurement: "°F", apex_probe_type: "Temp" } },
    "sensor.apex_ph": { entity_id: "sensor.apex_ph", state: "8.29", attributes: { friendly_name: "Apex pH", apex_probe_type: "pH" } },
    "cover.garage_door": { entity_id: "cover.garage_door", state: "closed",
      attributes: { friendly_name: "Garage Door", device_class: "garage" } },
    "camera.front": { entity_id: "camera.front", state: "idle", attributes: { friendly_name: "Camera - Front Door" } },
    "camera.back": { entity_id: "camera.back", state: "recording", attributes: { friendly_name: "Camera - Back Yard" } },
  },
  entities: {
    "sensor.envoy_current_power_production": { device_id: "envoy" },
    "sensor.apex_temp": { device_id: "apex" },
    "sensor.apex_ph": { device_id: "apex" },
  },
  devices: { envoy: { name: "Envoy" }, apex: { name: "Apex", name_by_user: null } },
};

const tests = {
  "a thermostat shows where it is and where it's set"() {
    assert.deepStrictEqual(chips.preview(hass, { type: "entity", entity: "climate.hall" }, {}),
      { icon: "mdi:thermostat", label: "Hallway Thermostat", value: "68° → 70°", tone: "warm" });
    assert.strictEqual(chips.preview(hass, { type: "entity", entity: "climate.den" }, {}).value, "71.5° → 68–74°");
  },
  "a chip's own label wins"() {
    assert.strictEqual(chips.preview(hass, { type: "entity", entity: "climate.hall", label: "Hall" }, {}).label, "Hall");
  },
  "the device name is dropped from the front, and long solar names read Solar"() {
    const solar = chips.preview(hass, { type: "entity", entity: "sensor.envoy_current_power_production" }, {});
    assert.strictEqual(solar.label, "Solar");
    assert.strictEqual(solar.value, "1.234 kW");
    assert.strictEqual(chips.defaultName(hass, "sensor.apex_temp"), "Temp");
  },
  "weather reads its condition and temperature"() {
    assert.deepStrictEqual(chips.preview(hass, { type: "entity", entity: "weather.home" }, {}),
      { icon: "mdi:weather-partly-cloudy", label: "Partly cloudy", value: "50 °F", tone: "sky" });
  },
  "a garage door and the cameras are icons alone"() {
    assert.strictEqual(chips.preview(hass, { type: "entity", entity: "cover.garage_door" }, {}).value, "");
    const cams = chips.preview(hass, { type: "cameras" }, { cameras: ["camera.front", "camera.back"] });
    assert.strictEqual(cams.value, "");
    assert.strictEqual(cams.icon, "mdi:cctv");
  },
  "the tank shows its columns in the set order"() {
    const tank = chips.preview(hass, { type: "tank" }, { tank: ["sensor.apex_ph", "sensor.apex_temp"] });
    assert.deepStrictEqual(tank.columns, [["pH", "8.29"], ["Temp", "78.9 °F"]]);
    assert.strictEqual(tank.icon, "mdi:fish");
  },
  "a missing entity says so"() {
    const gone = chips.preview(hass, { type: "entity", entity: "sensor.gone" }, {});
    assert.strictEqual(gone.value, "—");
    assert.strictEqual(gone.tone, "off");
  },
  "search finds by name or id, shows the live value and leaves out what's listed"() {
    const found = chips.search(hass, "hallway", ["climate.hall"]);
    assert.deepStrictEqual(found.map((f) => f.entity_id), ["sensor.hall_temperature"]);
    assert.strictEqual(found[0].value, "68.4 °F");
    assert.strictEqual(found[0].name, "Hallway Thermostat Temperature");
    assert.deepStrictEqual(chips.search(hass, "", [], "camera").map((f) => f.entity_id), ["camera.back", "camera.front"]);
    assert.deepStrictEqual(chips.search(hass, "", [], null, chips.isProbe).map((f) => f.entity_id),
      ["sensor.apex_ph", "sensor.apex_temp"]);
  },
  "move puts an item at its new place"() {
    assert.deepStrictEqual(chips.move(["a", "b", "c", "d"], 0, 2), ["b", "c", "a", "d"]);
    assert.deepStrictEqual(chips.move(["a", "b", "c"], 2, 0), ["c", "a", "b"]);
    assert.deepStrictEqual(chips.move(["a", "b"], 1, 5), ["a", "b"]);
  },
  "the solar layout is JSON or empty"() {
    assert.deepStrictEqual(chips.parseSolar(""), { ok: true, value: null });
    assert.deepStrictEqual(chips.parseSolar('{"arrays": []}'), { ok: true, value: { arrays: [] } });
    assert.strictEqual(chips.parseSolar("{nope").ok, false);
    assert.strictEqual(chips.parseSolar('{"arrays": 3}').ok, false);
  },
  "saving sends only what the integration takes, with empty labels dropped"() {
    const out = chips.toSave({
      chips: [{ type: "entity", entity: "climate.hall", label: "  " }, { type: "tank", label: "Reef" }],
      cameras: ["camera.front"], tank: ["sensor.apex_ph"], solarText: "", saved: false,
    });
    assert.deepStrictEqual(out, {
      chips: [{ type: "entity", entity: "climate.hall" }, { type: "tank", label: "Reef" }],
      cameras: ["camera.front"], tank: ["sensor.apex_ph"], solar: null,
    });
  },
};

let failed = 0;
for (const [name, t] of Object.entries(tests)) {
  try { t(); console.log("ok  ", name); } catch (e) { failed++; console.log("FAIL", name, "\n", e.message); }
}
console.log(`${Object.keys(tests).length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
