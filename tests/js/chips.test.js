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
    // Rounded as the TV's Ha.reading() does: "1.234" is longer than it needs to be.
    assert.strictEqual(solar.value, "1.23 kW");
    assert.strictEqual(chips.defaultName(hass, "sensor.apex_temp"), "Temp");
  },
  "readings are kept as written unless they're longer than they need to be"() {
    for (const [raw, shown] of [["8.30", "8.30"], ["78.9", "78.9"], ["1.234", "1.23"], ["1344.0", "1344"], ["67", "67"],
      ["0.5", "0.5"], ["123.45", "123"], ["-2.179", "-2.18"]]) {
      assert.strictEqual(chips.reading(raw), shown, raw);
    }
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
  "the widget row is on unless its switch says off"() {
    const h = { states: { "switch.tv_row": { state: "off", attributes: {} }, "switch.tv2_row": { state: "on", attributes: {} } } };
    assert.strictEqual(chips.rowOn(h, "switch.tv_row"), false);
    assert.strictEqual(chips.rowOn(h, "switch.tv2_row"), true);
    // No switch yet (the TV's entry is still setting up): the TV shows its row.
    assert.strictEqual(chips.rowOn(h, null), true);
    assert.strictEqual(chips.rowOn(h, "switch.gone"), true);
  },
  "what OK does by default, as the integration decides"() {
    const d = (chip) => chips.resolveOk(hass, chip);
    assert.deepStrictEqual(d({ type: "entity", entity: "weather.home" }), { action: "page", page: "weather" });
    assert.deepStrictEqual(d({ type: "entity", entity: "sensor.envoy_current_power_production" }), { action: "page", page: "solar" });
    assert.deepStrictEqual(d({ type: "cameras" }), { action: "page", page: "cameras" });
    assert.deepStrictEqual(d({ type: "tank" }), { action: "page", page: "tank" });
    assert.deepStrictEqual(d({ type: "entity", entity: "cover.garage_door" }),
      { action: "toggle", entity: "cover.garage_door", confirm: true });
    assert.deepStrictEqual(d({ type: "entity", entity: "climate.hall" }), { action: "none" });
    assert.deepStrictEqual(d({ type: "entity", entity: "climate.hall", ok: { action: "toggle" } }),
      { action: "toggle", entity: "climate.hall", confirm: false });
    assert.deepStrictEqual(d({ type: "tank", ok: { action: "toggle" } }), { action: "none" });
  },
  "each chip says what OK does"() {
    const say = (chip) => chips.describeOk(hass, chip);
    assert.strictEqual(say({ type: "entity", entity: "weather.home" }), "OK opens the weather page");
    assert.strictEqual(say({ type: "cameras" }), "OK opens the camera grid");
    assert.strictEqual(say({ type: "tank" }), "OK opens the tank page");
    // The chip's own entity goes by the chip's name on the TV ("Garage"), or its label.
    assert.strictEqual(say({ type: "entity", entity: "cover.garage_door" }), "OK twice opens or closes Garage");
    assert.strictEqual(say({ type: "entity", entity: "cover.garage_door", label: "Big door" }), "OK twice opens or closes Big door");
    // Another entity goes by its own name.
    assert.strictEqual(say({ type: "entity", entity: "weather.home", ok: { action: "toggle", entity: "cover.garage_door" } }),
      "OK opens or closes Garage Door");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall" }), "OK does nothing");
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "page", page: "camera", camera: "camera.front" } }),
      "OK opens Camera - Front Door full screen");
    hass.states["script.good_night"] = { entity_id: "script.good_night", state: "off", attributes: { friendly_name: "Good night" } };
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "run", entity: "script.good_night", confirm: true } }),
      "OK twice runs Good night");
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "perform", perform_action: "light.turn_on" } }),
      "OK performs light.turn_on");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall", ok: { action: "toggle" } }), "OK toggles Hallway Thermostat");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall", label: "Hall", ok: { action: "toggle" } }), "OK toggles Hall");
  },
  "choosing another kind of action keeps what matters from the one it replaces"() {
    const garage = { type: "entity", entity: "cover.garage_door" };
    // The garage's default asks twice: an explicit toggle must too, or one OK opens the door.
    assert.deepStrictEqual(chips.switchOk(hass, garage, "toggle"), { action: "toggle", entity: "cover.garage_door", confirm: true });
    assert.deepStrictEqual(chips.switchOk(hass, garage, "run"), { action: "run", confirm: true });
    // A sensor can't be toggled: nothing is chosen for it, so the page asks.
    assert.deepStrictEqual(chips.switchOk(hass, { type: "entity", entity: "sensor.hall_temperature" }, "toggle"), { action: "toggle" });
    assert.deepStrictEqual(chips.switchOk(hass, garage, "none"), { action: "none" });
    assert.deepStrictEqual(chips.resolveOk(hass, { type: "entity", entity: "sensor.hall_temperature", ok: { action: "toggle" } }),
      { action: "none" });
  },
  "a half-set action says what's still to choose"() {
    const say = (ok) => chips.describeOk(hass, { type: "entity", entity: "sensor.x", ok });
    assert.strictEqual(say({ action: "page" }), "OK opens a page: choose which");
    assert.strictEqual(say({ action: "page", page: "camera" }), "OK opens a camera: choose which");
    assert.strictEqual(say({ action: "run" }), "OK runs a script or scene: choose which");
    assert.strictEqual(say({ action: "perform" }), "OK performs an action: choose which");
    assert.strictEqual(say({ action: "toggle" }), "OK does nothing: choose what to toggle");
  },
  "the Default choice reads as a sentence"() {
    assert.strictEqual(chips.defaultChoice(hass, { type: "entity", entity: "cover.garage_door" }),
      "Default: opens or closes Garage (press twice)");
    assert.strictEqual(chips.defaultChoice(hass, { type: "entity", entity: "weather.home", ok: { action: "none" } }),
      "Default: opens the weather page");
  },
  "toggles cover the integration's domains"() {
    assert.deepStrictEqual(chips.TOGGLE_DOMAINS, ["automation", "climate", "cover", "fan", "humidifier", "input_boolean",
      "light", "lock", "media_player", "remote", "siren", "switch", "valve"]);
  },
  "saving sends only what the integration takes, with empty labels dropped"() {
    const out = chips.toSave({
      chips: [{ type: "entity", entity: "climate.hall", label: "  ", ok: { action: "default" } },
        { type: "tank", label: "Reef", ok: { action: "page", page: "solar" } }],
      cameras: ["camera.front"], tank: ["sensor.apex_ph"], solarText: "", saved: false,
    });
    assert.deepStrictEqual(out, {
      // "default" is the same as no ok at all; any other is kept.
      chips: [{ type: "entity", entity: "climate.hall" }, { type: "tank", label: "Reef", ok: { action: "page", page: "solar" } }],
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
