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
    assert.deepStrictEqual(d({ type: "entity", entity: "weather.home" }), { action: "screen", screen: "weather" });
    assert.deepStrictEqual(d({ type: "entity", entity: "sensor.envoy_current_power_production" }), { action: "screen", screen: "solar" });
    assert.deepStrictEqual(d({ type: "cameras" }), { action: "screen", screen: "cameras" });
    assert.deepStrictEqual(d({ type: "tank" }), { action: "screen", screen: "tank" });
    assert.deepStrictEqual(d({ type: "entity", entity: "cover.garage_door" }),
      { action: "toggle", entity: "cover.garage_door", confirm: true });
    assert.deepStrictEqual(d({ type: "entity", entity: "climate.hall" }), { action: "none" });
    assert.deepStrictEqual(d({ type: "entity", entity: "climate.hall", ok: { action: "toggle" } }),
      { action: "toggle", entity: "climate.hall", confirm: false });
    assert.deepStrictEqual(d({ type: "tank", ok: { action: "toggle" } }), { action: "none" });
  },
  "gates and doors ask twice by default, like the garage; locks, blinds and windows don't"() {
    const h = { ...hass, states: { ...hass.states } };
    const add = (id, name, dc) => { h.states[id] = { entity_id: id, state: "closed",
      attributes: { friendly_name: name, ...(dc ? { device_class: dc } : {}) } }; };
    add("cover.front", "Front", "gate");
    add("cover.side", "Side", "door");
    add("cover.driveway_gate", "Driveway Gate");
    add("cover.hall_window", "Hall window", "window");
    add("cover.navigator", "Navigator shade", "shade");
    add("lock.front_door", "Front Door");
    // An empty device class counts as none (a template cover), as in model.py.
    h.states["cover.side_gate"] = { entity_id: "cover.side_gate", state: "closed",
      attributes: { friendly_name: "Side Gate", device_class: "" } };
    const d = (entity) => chips.resolveOk(h, { type: "entity", entity });
    for (const e of ["cover.front", "cover.side", "cover.driveway_gate", "cover.side_gate"]) {
      assert.deepStrictEqual(d(e), { action: "toggle", entity: e, confirm: true }, e);
    }
    for (const e of ["cover.hall_window", "cover.navigator", "lock.front_door"]) {
      assert.deepStrictEqual(d(e), { action: "none" }, e);
    }
    assert.strictEqual(chips.defaultChoice(h, { type: "entity", entity: "cover.front" }), "Default: opens or closes Front (press twice)");
  },
  "a list shows states in words, as the TV and Home Assistant say them"() {
    // Weather as the TV's chip says it, not the raw "partlycloudy".
    assert.strictEqual(chips.stateText(hass, "weather.home"), "Partly cloudy");
    // Other words in Home Assistant's own translation, when the page has it.
    const h = { ...hass, formatEntityState: (st) => (st.state === "heat_cool" ? "Heat/Cool" : st.state) };
    assert.strictEqual(chips.stateText(h, "climate.den"), "Heat/Cool");
    assert.strictEqual(chips.stateText(hass, "climate.den"), "Heat cool");
    // Numbers keep the TV's formatting and unit.
    assert.strictEqual(chips.stateText(h, "sensor.hall_temperature"), "68.4 °F");
  },
  "the preview shows a chip as the TV draws it, not in Home Assistant's words"() {
    const h = {
      ...hass,
      states: {
        ...hass.states,
        "binary_sensor.hall_motion": { entity_id: "binary_sensor.hall_motion", state: "on",
          attributes: { friendly_name: "Hall Motion", device_class: "motion" } },
        "select.storage_mode": { entity_id: "select.storage_mode", state: "self_consumption",
          attributes: { friendly_name: "Storage" } },
      },
      formatEntityState: (st) => ({ on: "Detected", self_consumption: "Self-consumption" })[st.state] || st.state,
    };
    // The TV can't translate: it says "On", so the preview does too. The page's lists may say "Detected".
    assert.strictEqual(chips.preview(h, { type: "entity", entity: "binary_sensor.hall_motion" }).value, "On");
    assert.strictEqual(chips.stateText(h, "binary_sensor.hall_motion"), "Detected");
    assert.strictEqual(chips.preview(h, { type: "entity", entity: "select.storage_mode" }).value, "Self consumption");
    assert.strictEqual(chips.preview(h, { type: "entity", entity: "sensor.hall_temperature" }).value, "68.4 °F");
  },
  "a weather with no reading previews as the TV draws it: a grey cloud"() {
    const h = { ...hass, states: { ...hass.states, "weather.nws": { entity_id: "weather.nws", state: "unknown",
      attributes: { friendly_name: "NWS" } } } };
    const p = chips.preview(h, { type: "entity", entity: "weather.nws" });
    assert.deepStrictEqual([p.icon, p.tone, p.label, p.value], ["mdi:weather-cloudy", "off", "NWS", "—"]);
  },
  "searching formats only the rows it shows"() {
    const states = {};
    for (let i = 0; i < 500; i++) {
      states[`sensor.s${i}`] = { entity_id: `sensor.s${i}`, state: "x", attributes: { friendly_name: `Sensor ${i}` } };
    }
    let formatted = 0;
    const h = { ...hass, states, formatEntityState: (st) => { formatted++; return st.state; } };
    assert.strictEqual(chips.search(h, "sensor 499", []).length, 1);
    assert.ok(formatted <= 1, `formatted ${formatted} states`);
    chips.search(h, "", []);
    assert.ok(formatted <= 41, `formatted ${formatted} states`);
  },
  "each chip says what OK does"() {
    const say = (chip) => chips.describeOk(hass, chip);
    assert.strictEqual(say({ type: "entity", entity: "weather.home" }), "OK opens the weather screen");
    assert.strictEqual(say({ type: "cameras" }), "OK opens the camera grid");
    assert.strictEqual(say({ type: "tank" }), "OK opens the tank screen");
    // The chip's own entity goes by the chip's name on the TV ("Garage"), or its label.
    assert.strictEqual(say({ type: "entity", entity: "cover.garage_door" }), "OK twice opens or closes Garage");
    assert.strictEqual(say({ type: "entity", entity: "cover.garage_door", label: "Big door" }), "OK twice opens or closes Big door");
    // Another entity goes by its own name.
    assert.strictEqual(say({ type: "entity", entity: "weather.home", ok: { action: "toggle", entity: "cover.garage_door" } }),
      "OK opens or closes Garage Door");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall" }), "OK does nothing");
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "screen", screen: "camera", camera: "camera.front" } }),
      "OK opens Camera - Front Door full screen");
    hass.states["script.good_night"] = { entity_id: "script.good_night", state: "off", attributes: { friendly_name: "Good night" } };
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "run", entity: "script.good_night", confirm: true } }),
      "OK twice runs Good night");
    assert.strictEqual(say({ type: "entity", entity: "sensor.x", ok: { action: "perform", perform_action: "light.turn_on" } }),
      "OK performs light.turn_on");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall", ok: { action: "toggle" } }), "OK toggles Hallway Thermostat");
    assert.strictEqual(say({ type: "entity", entity: "climate.hall", label: "Hall", ok: { action: "toggle" } }), "OK toggles Hall");
  },
  "the OK button shows what OK does at a glance"() {
    const look = (chip) => chips.okLook(hass, chip);
    // A screen, a toggle (asking twice), a script, an action, nothing: one icon each.
    assert.deepStrictEqual(look({ type: "entity", entity: "weather.home" }),
      { icon: "mdi:open-in-app", twice: false, choose: false, none: false });
    assert.deepStrictEqual(look({ type: "entity", entity: "cover.garage_door" }),
      { icon: "mdi:toggle-switch-outline", twice: true, choose: false, none: false });
    assert.strictEqual(look({ type: "entity", entity: "sensor.x", ok: { action: "run", entity: "script.good_night" } }).icon,
      "mdi:play-circle-outline");
    assert.strictEqual(look({ type: "entity", entity: "sensor.x", ok: { action: "perform", perform_action: "light.turn_on" } }).icon,
      "mdi:flash-outline");
    assert.deepStrictEqual(look({ type: "entity", entity: "climate.hall" }),
      { icon: "mdi:gesture-tap", twice: false, choose: false, none: true });
    // Half set up ("choose which"): it says so, so it isn't missed with the editor closed.
    assert.strictEqual(look({ type: "entity", entity: "sensor.x", ok: { action: "screen" } }).choose, true);
    assert.strictEqual(look({ type: "entity", entity: "sensor.x", ok: { action: "run" } }).choose, true);
    assert.strictEqual(look({ type: "entity", entity: "sensor.x", ok: { action: "toggle" } }).choose, true);
  },
  "choosing another kind of action keeps what matters from the one it replaces"() {
    const garage = { type: "entity", entity: "cover.garage_door" };
    // The garage's default asks twice: an explicit toggle must too, or one OK opens the door.
    assert.deepStrictEqual(chips.switchOk(hass, garage, "toggle"), { action: "toggle", entity: "cover.garage_door", confirm: true });
    assert.deepStrictEqual(chips.switchOk(hass, garage, "run"), { action: "run", confirm: true });
    // A sensor can't be toggled: nothing is chosen for it, so the page asks.
    assert.deepStrictEqual(chips.switchOk(hass, { type: "entity", entity: "sensor.hall_temperature" }, "toggle"), { action: "toggle" });
    assert.deepStrictEqual(chips.switchOk(hass, garage, "none"), { action: "none" });
    // A screen never asks twice (the integration refuses confirm on one, and the page has no box to untick).
    assert.deepStrictEqual(chips.switchOk(hass, garage, "screen"), { action: "screen" });
    assert.deepStrictEqual(chips.resolveOk(hass, { type: "entity", entity: "sensor.hall_temperature", ok: { action: "toggle" } }),
      { action: "none" });
  },
  "a half-set action says what's still to choose"() {
    const say = (ok) => chips.describeOk(hass, { type: "entity", entity: "sensor.x", ok });
    assert.strictEqual(say({ action: "screen" }), "OK opens a screen: choose which");
    assert.strictEqual(say({ action: "screen", screen: "camera" }), "OK opens a camera: choose which");
    assert.strictEqual(say({ action: "run" }), "OK runs a script or scene: choose which");
    assert.strictEqual(say({ action: "perform" }), "OK performs an action: choose which");
    assert.strictEqual(say({ action: "toggle" }), "OK does nothing: choose what to toggle");
  },
  "the Default choice reads as a sentence"() {
    assert.strictEqual(chips.defaultChoice(hass, { type: "entity", entity: "cover.garage_door" }),
      "Default: opens or closes Garage (press twice)");
    assert.strictEqual(chips.defaultChoice(hass, { type: "entity", entity: "weather.home", ok: { action: "none" } }),
      "Default: opens the weather screen");
  },
  "toggles cover the integration's domains"() {
    assert.deepStrictEqual(chips.TOGGLE_DOMAINS, ["automation", "climate", "cover", "fan", "humidifier", "input_boolean",
      "light", "lock", "media_player", "remote", "siren", "switch", "valve"]);
  },
  "without a list, the screens are the four built-in ones"() {
    assert.deepStrictEqual(chips.DEFAULT_SCREENS, [{ id: "weather", type: "weather" }, { id: "solar", type: "solar" },
      { id: "tank", type: "tank" }, { id: "cameras", type: "cameras" }]);
    assert.deepStrictEqual(chips.screensOf({}), chips.DEFAULT_SCREENS);
    assert.deepStrictEqual(chips.screensOf({ screens: [] }), []);
  },
  "only screens that can still be added are offered"() {
    const types = (screens) => chips.addableTypes(screens).map(([t]) => t);
    assert.deepStrictEqual(types(chips.DEFAULT_SCREENS), ["thermostat", "air_quality"]);
    assert.deepStrictEqual(types([{ id: "weather", type: "weather" }]), ["solar", "tank", "cameras", "thermostat", "air_quality"]);
  },
  "a new screen gets an id once, from its title"() {
    const up = chips.newScreen(chips.DEFAULT_SCREENS, "thermostat");
    assert.deepStrictEqual(up, { id: "thermostat", type: "thermostat", title: "Thermostat", climates: [], sensors: [] });
    assert.strictEqual(chips.newScreen([...chips.DEFAULT_SCREENS, up], "thermostat").id, "thermostat_2");
    assert.deepStrictEqual(chips.newScreen([], "air_quality"), { id: "air_quality", type: "air_quality", title: "Air quality", sensors: [] });
    assert.deepStrictEqual(chips.newScreen([], "solar"), { id: "solar", type: "solar" });
    // Renaming it later keeps the id: chips, automations and voice use it.
    assert.strictEqual(chips.screenTitle({ ...up, title: "Upstairs" }), "Upstairs");
    assert.strictEqual(chips.screenTitle({ id: "tank", type: "tank" }), "Tank");
  },
  "each screen has its link"() {
    assert.strictEqual(chips.screenLink({ id: "tank", type: "tank" }), "tvpanel://apex");
    assert.strictEqual(chips.screenLink({ id: "weather", type: "weather" }), "tvpanel://weather");
    assert.strictEqual(chips.screenLink({ id: "thermostat_2", type: "thermostat" }), "tvpanel://screen/thermostat_2");
  },
  "a thermostat or air sensor opens the screen that lists it"() {
    const up = { id: "thermostat", type: "thermostat", title: "Upstairs", climates: ["climate.hall"], sensors: [] };
    const air = { id: "air", type: "air_quality", title: "Air", sensors: ["sensor.den_co2"] };
    const screens = [...chips.DEFAULT_SCREENS, up, air];
    const ok = (chip) => chips.defaultOk(hass, chip, screens);
    assert.deepStrictEqual(ok({ type: "entity", entity: "climate.hall" }), { action: "screen", screen: "thermostat" });
    assert.deepStrictEqual(ok({ type: "entity", entity: "climate.den" }), { action: "none" });
    assert.deepStrictEqual(ok({ type: "entity", entity: "sensor.den_co2" }), { action: "screen", screen: "air" });
    assert.strictEqual(chips.describeOk(hass, { type: "entity", entity: "climate.hall" }, screens), "OK opens the Upstairs screen");
    // Without a list: the built-in ones.
    assert.deepStrictEqual(chips.defaultOk(hass, { type: "tank" }), { action: "screen", screen: "tank" });
  },
  "a removed screen is no one's default, and a chip set to open it says so"() {
    const noSolar = chips.DEFAULT_SCREENS.filter((s) => s.id !== "solar");
    const solarChip = { type: "entity", entity: "sensor.envoy_current_power_production" };
    assert.deepStrictEqual(chips.defaultOk(hass, solarChip, noSolar), { action: "none" });
    const set = { ...solarChip, label: "Sun", ok: { action: "screen", screen: "solar" } };
    assert.strictEqual(chips.describeOk(hass, set, noSolar), "OK opens a screen that was removed: choose another");
    assert.strictEqual(chips.okLook(hass, set, noSolar).choose, true);
    // So removing it can say which chips are in the way.
    assert.deepStrictEqual(chips.chipsOpening(hass, [set, solarChip], "solar"), ["Sun"]);
  },
  "the OK editor offers the screens there are"() {
    const up = { id: "thermostat", type: "thermostat", title: "Upstairs", climates: [], sensors: [] };
    assert.deepStrictEqual(chips.screenChoices([{ id: "weather", type: "weather" }, { id: "cameras", type: "cameras" }, up]),
      [["weather", "Weather"], ["cameras", "Camera grid"], ["thermostat", "Upstairs"]]);
  },
  "air quality suggests the sensors Home Assistant knows about, by device"() {
    const h = { ...hass, states: { ...hass.states,
      "sensor.den_co2": { entity_id: "sensor.den_co2", state: "612", attributes: { friendly_name: "Den CO2", device_class: "carbon_dioxide" } },
      "sensor.den_pm25": { entity_id: "sensor.den_pm25", state: "4", attributes: { friendly_name: "Den PM2.5", device_class: "pm25" } },
      "sensor.attic_voc": { entity_id: "sensor.attic_voc", state: "90", attributes: { friendly_name: "Attic VOC", device_class: "volatile_organic_compounds" } },
    }, entities: { ...hass.entities, "sensor.den_co2": { device_id: "den" }, "sensor.den_pm25": { device_id: "den" } },
    devices: { ...hass.devices, den: { name: "Den monitor" } } };
    const found = chips.airSuggestions(h, ["sensor.den_pm25"]);
    assert.deepStrictEqual(found.map((f) => [f.group, f.entity_id]), [["", "sensor.attic_voc"], ["Den monitor", "sensor.den_co2"]]);
    // The temperature sensor isn't suggested first, but search finds any sensor.
    assert.ok(!found.some((f) => f.entity_id === "sensor.hall_temperature"));
  },
  "saving sends the screens as the integration takes them"() {
    const up = { id: "thermostat", type: "thermostat", title: " Upstairs ", climates: ["climate.hall"], sensors: [] };
    const out = chips.toSave({ chips: [], cameras: [], tank: [], solarText: "", screens: [{ id: "weather", type: "weather" }, up] });
    assert.deepStrictEqual(out.screens, [{ id: "weather", type: "weather" },
      { id: "thermostat", type: "thermostat", title: "Upstairs", climates: ["climate.hall"], sensors: [] }]);
    // A title cleared on the page is the one its box shows greyed out, not one the integration refuses.
    const cleared = chips.toSave({ chips: [], cameras: [], tank: [], solarText: "", screens: [{ ...up, title: "  " }] });
    assert.strictEqual(cleared.screens[0].title, "Thermostat");
  },
  "saving sends only what the integration takes, with empty labels dropped"() {
    const out = chips.toSave({
      chips: [{ type: "entity", entity: "climate.hall", label: "  ", ok: { action: "default" } },
        { type: "tank", label: "Reef", ok: { action: "screen", screen: "solar" } }],
      cameras: ["camera.front"], tank: ["sensor.apex_ph"], solarText: "", saved: false, screens: chips.DEFAULT_SCREENS,
    });
    assert.deepStrictEqual(out, {
      // "default" is the same as no ok at all; any other is kept.
      chips: [{ type: "entity", entity: "climate.hall" }, { type: "tank", label: "Reef", ok: { action: "screen", screen: "solar" } }],
      cameras: ["camera.front"], tank: ["sensor.apex_ph"], solar: null, screens: chips.DEFAULT_SCREENS,
    });
  },
};

let failed = 0;
for (const [name, t] of Object.entries(tests)) {
  try { t(); console.log("ok  ", name); } catch (e) { failed++; console.log("FAIL", name, "\n", e.message); }
}
console.log(`${Object.keys(tests).length - failed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
