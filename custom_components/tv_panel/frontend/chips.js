// The TV Panel page's logic, kept apart from the page so it can be tested in Node (tests/js/chips.test.js).
// preview() follows what the TV app draws (Ha.java): change both together.

const MISSING = new Set(["", "unavailable", "unknown"]);
const TANK_PROBES = new Set(["Temp", "pH", "Cond", "alk", "ca", "mg"]);

const CONDITIONS = {
  "clear-night": ["Clear", "weather-night"],
  cloudy: ["Cloudy", "weather-cloudy"],
  exceptional: ["Alert", "weather-sunny-alert"],
  fog: ["Fog", "weather-fog"],
  hail: ["Hail", "weather-hail"],
  lightning: ["Thunder", "weather-lightning"],
  "lightning-rainy": ["Storms", "weather-lightning-rainy"],
  partlycloudy: ["Partly cloudy", "weather-partly-cloudy"],
  pouring: ["Heavy rain", "weather-pouring"],
  rainy: ["Rain", "weather-rainy"],
  snowy: ["Snow", "weather-snowy"],
  "snowy-rainy": ["Sleet", "weather-snowy-rainy"],
  sunny: ["Sunny", "weather-sunny"],
  windy: ["Windy", "weather-windy"],
  "windy-variant": ["Windy", "weather-windy-variant"],
};

const capitalise = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);

/** Readable precision, as the TV: 2.179 → 2.18, 78.8 → 78.8, 1344.0 → 1344. */
export function number(raw) {
  const v = Number(raw);
  if (raw === null || raw === undefined || raw === "" || Number.isNaN(v)) return String(raw ?? "");
  const a = Math.abs(v);
  const s = v.toFixed(a >= 100 ? 0 : a >= 10 ? 1 : 2);
  return s.includes(".") ? s.replace(/0+$/, "").replace(/\.$/, "") : s;
}

/** A sensor's state as Home Assistant wrote it ("8.30" stays), rounded only when longer than number() (Ha.reading). */
export function reading(state) {
  const s = String(state).trim();
  const dot = s.indexOf(".");
  if (dot < 0 || /e/i.test(s)) return number(s);
  const rounded = number(s);
  const places = rounded.includes(".") ? rounded.length - rounded.indexOf(".") - 1 : 0;
  return s.length - dot - 1 <= Math.max(places, 2) && Math.abs(Number(s)) < 100 ? s : rounded;
}

function deviceName(hass, entityId) {
  const id = hass.entities?.[entityId]?.device_id;
  const d = id && hass.devices?.[id];
  return d ? d.name_by_user || d.name || "" : "";
}

function friendly(hass, entityId) {
  return hass.states[entityId]?.attributes?.friendly_name || "";
}

/** The chip's name without a label of its own: the entity's name, less the device name at its front. */
export function defaultName(hass, entityId) {
  let n = friendly(hass, entityId).trim();
  const d = deviceName(hass, entityId).trim();
  if (d && n.length > d.length && n.toLowerCase().startsWith(d.toLowerCase() + " ")) n = n.slice(d.length + 1).trim();
  return n || capitalise(entityId.split(".")[1].replace(/_/g, " "));
}

/** A state as the TV draws it on a chip (Ha.widget): a number formatted, or the word capitalised, and the unit. */
export function tvValue(hass, entityId) {
  const s = hass.states[entityId];
  if (!s || MISSING.has(s.state)) return "—";
  const unit = s.attributes.unit_of_measurement;
  const v = Number.isNaN(Number(s.state)) ? capitalise(s.state.replace(/_/g, " ")) : reading(s.state);
  return unit ? `${v} ${unit}` : v;
}

/**
 * A state as the page's lists show it: a number as the TV formats it, with its unit; weather in the TV chip's words;
 * anything else in Home Assistant's own words ("Heat/Cool"), when the page has them. Not for the preview, which shows
 * what the TV draws (tvValue): the TV can't translate.
 */
export function stateText(hass, entityId) {
  const s = hass.states[entityId];
  if (!s || MISSING.has(s.state) || !Number.isNaN(Number(s.state))) return tvValue(hass, entityId);
  if (entityId.startsWith("weather.") && CONDITIONS[s.state]) return CONDITIONS[s.state][0];
  const words = typeof hass.formatEntityState === "function" ? hass.formatEntityState(s) : "";
  if (!words || words === s.state) return tvValue(hass, entityId);
  const unit = s.attributes.unit_of_measurement;
  return unit && !words.endsWith(unit) ? `${words} ${unit}` : words;
}

function degrees(a, key) {
  return a[key] === null || a[key] === undefined ? "" : number(a[key]) + "°";
}

/** "68° → 70°", "71.5° → 68–74°" for a range, "66° · Off" when off. */
export function thermostat(s) {
  const a = s.attributes;
  const cur = degrees(a, "current_temperature");
  const set = degrees(a, "target_temp_low") && degrees(a, "target_temp_high")
    ? `${number(a.target_temp_low)}–${degrees(a, "target_temp_high")}` : degrees(a, "temperature");
  if (s.state === "off" || !set) {
    const mode = s.state === "off" ? "Off" : capitalise(s.state.replace(/_/g, " "));
    return cur ? `${cur} · ${mode}` : mode;
  }
  return cur ? `${cur} → ${set}` : set;
}

export const isProbe = (s) => TANK_PROBES.has(s.attributes?.apex_probe_type);

/**
 * How a chip looks on the TV: an mdi icon, a label, a value ("" draws the icon alone) and a tone (warm, sky, amber,
 * blue, off, alarm). The tank chip has columns instead of a label and value.
 */
export function preview(hass, chip, settings) {
  if (chip.type === "cameras") {
    // The TV turns it into an orange motion icon while a camera's motion sensor is on; the preview doesn't.
    return { icon: "mdi:cctv", label: chip.label || "Cameras", value: "", tone: "blue" };
  }
  if (chip.type === "tank") {
    const columns = (settings.tank || []).map((e) => [defaultName(hass, e), tvValue(hass, e)]);
    return { icon: "mdi:fish", label: "", value: "", tone: "blue", columns };
  }
  const id = chip.entity;
  const s = hass.states[id];
  const domain = id.split(".")[0];
  let name = defaultName(hass, id);
  const missing = !s || MISSING.has(s.state);
  const out = (icon, label, value, tone) => ({ icon, label: chip.label || label, value, tone: missing ? "off" : tone });
  if (domain === "weather") {
    // No reading (Ha.noCondition): a grey cloud, as on the TV.
    const [text, icon] = missing ? [name, "weather-cloudy"]
      : CONDITIONS[s.state] || [capitalise(s.state.replace(/-/g, " ")), "weather-sunny"];
    const t = s?.attributes?.temperature;
    const unit = s?.attributes?.temperature_unit;
    const value = missing || t === undefined || t === null ? "—" : `${number(t)}${unit ? " " + unit : "°"}`;
    return out(`mdi:${icon}`, missing ? name : text, value, s?.state === "sunny" ? "amber" : "sky");
  }
  if (domain === "climate") {
    return out("mdi:thermostat", name, missing ? "—" : thermostat(s), s?.attributes?.hvac_action === "heating" ? "warm" : "blue");
  }
  if (domain === "cover") {
    const garage = s?.attributes?.device_class === "garage" || id.includes("garage") || /garage/i.test(name);
    const shut = s?.state === "closed";
    if (garage) {
      if (/^door$/i.test(name) || /^garage door$/i.test(name)) name = "Garage";
      return out(shut ? "mdi:garage" : "mdi:garage-open", name, "", shut ? "blue" : "warm");
    }
    return out("mdi:door-closed", name, missing ? "—" : capitalise(s.state), shut ? "blue" : "warm");
  }
  if (domain === "camera") return out("mdi:cctv", name, "", "blue");
  if (domain === "lock") {
    const locked = s?.state === "locked";
    return out(locked ? "mdi:lock" : "mdi:lock-open-variant", name, missing ? "—" : capitalise(s.state),
      locked ? "blue" : "warm");
  }
  const dc = s?.attributes?.device_class || "";
  const solar = (dc === "power" || dc === "energy") && /production|solar|pv/.test(id);
  if (solar && name.length > 14) name = "Solar";
  const icon = solar ? "mdi:solar-power" : s?.attributes?.icon
    || { temperature: "mdi:thermometer", humidity: "mdi:water-percent", power: "mdi:flash", energy: "mdi:flash" }[dc]
    || "mdi:gauge";
  return out(icon, name, tvValue(hass, id), solar ? "amber" : "blue");
}

/**
 * Entities to add: matching the words typed (in the name or the id), with their live value, leaving out those listed
 * already. Optionally only one domain, or only what `keep(state)` accepts. At most 40, by name.
 */
export function search(hass, query, exclude, domain = null, keep = null) {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const skip = new Set(exclude);
  return Object.values(hass.states)
    .filter((s) => !skip.has(s.entity_id))
    .filter((s) => !domain || s.entity_id.startsWith(domain + "."))
    .filter((s) => !keep || keep(s))
    .map((s) => ({ entity_id: s.entity_id, name: friendly(hass, s.entity_id) || s.entity_id }))
    .filter((r) => words.every((w) => r.name.toLowerCase().includes(w) || r.entity_id.includes(w)))
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, 40)
    // Words come last: Home Assistant's formatting is slow over thousands of states, at every key typed.
    .map((r) => ({ ...r, value: stateText(hass, r.entity_id) }));
}

// ---- the TV's screens (model.py's screens: change both together) ----------------------------------------------

/** Every kind of screen: its title, and whether it's one of the four built in (at most one each, its type its id). */
export const SCREEN_TYPES = {
  weather: { title: "Weather" },
  solar: { title: "Solar" },
  tank: { title: "Tank" },
  cameras: { title: "Cameras" },
  thermostat: { title: "Thermostat", extra: true },
  air_quality: { title: "Air quality", extra: true },
};
export const DEFAULT_SCREENS = ["weather", "solar", "tank", "cameras"].map((t) => ({ id: t, type: t }));
/** model.py's MAX_CLIMATES, MAX_AIR_SENSORS, MAX_READINGS and MAX_PER_TILE (sensors side by side on one tile). */
export const MAX_CLIMATES = 4;
export const MAX_AIR_SENSORS = 12;
export const MAX_READINGS = 8;
export const MAX_PER_TILE = 3;
/** The built-in screens' links, which apps before 0.7.0 know too (tv_link.py). */
const OLD_LINKS = { weather: "weather", solar: "solar", tank: "apex", cameras: "cameras" };
/** Sensors an air quality screen suggests with nothing typed; search finds any other sensor, such as humidity. */
export const AIR_CLASSES = ["aqi", "pm1", "pm25", "pm10", "carbon_dioxide", "carbon_monoxide", "volatile_organic_compounds",
  "volatile_organic_compounds_parts", "nitrogen_dioxide", "nitrogen_monoxide", "ozone", "sulphur_dioxide"];

/** The settings' screens: without a list (saved before 0.7.0), the built-in four. */
export const screensOf = (s) => (Array.isArray(s?.screens) ? s.screens : DEFAULT_SCREENS);

export const screenTitle = (s) => (s.title && String(s.title).trim()) || SCREEN_TYPES[s.type]?.title || s.id;

/** tvpanel://weather for a built-in screen, tvpanel://screen/<id> for the rest. */
export const screenLink = (s) => (OLD_LINKS[s.id] ? `tvpanel://${OLD_LINKS[s.id]}` : `tvpanel://screen/${s.id}`);

/** The kinds of screen that can still be added: a built-in one only when it isn't there. As [type, title]. */
export function addableTypes(screens) {
  return Object.entries(SCREEN_TYPES).filter(([t, info]) => info.extra || !screens.some((s) => s.type === t))
    .map(([t, info]) => [t, info.title]);
}

/** A new screen of this type. Its id is made once, here, from its title ("thermostat", "thermostat_2"), and never
 * changes: chips, tv_panel.show and voice links use it. */
export function newScreen(screens, type) {
  if (!SCREEN_TYPES[type].extra) return { id: type, type };
  const title = SCREEN_TYPES[type].title;
  const base = title.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
  let id = base;
  for (let n = 2; screens.some((s) => s.id === id); n++) id = `${base}_${n}`;
  return type === "thermostat" ? { id, type, title, climates: [], sensors: [] } : { id, type, title, sensors: [] };
}

// A screen's sensors are tiles, one sensor a tile, except that each sensor in the screen's `joined` shares the tile of
// the sensor before it (model.py's tiles()). The flag is the entity's: it stays with it when it moves, and only counts
// below the first sensor.

/** The sensors as their tiles, [[id, ...], ...]. */
export function tiles(sensors, joined) {
  const on = new Set(joined || []);
  const out = [];
  for (const e of sensors || []) {
    if (out.length && on.has(e)) out[out.length - 1].push(e);
    else out.push([e]);
  }
  return out;
}

/** Whether sensor i can join the tile above: not the first, and the tile it makes (with the sensors joined to it)
 * holds at most MAX_PER_TILE. */
export function canJoin(sensors, joined, i) {
  if (i <= 0 || i >= sensors.length) return false;
  const on = new Set([...(joined || []), sensors[i]]);
  let first = i;
  while (first > 0 && on.has(sensors[first])) first--;
  let last = i;
  while (last + 1 < sensors.length && on.has(sensors[last + 1])) last++;
  return last - first + 1 <= MAX_PER_TILE;
}

/** `joined` after the list changed from `before` to `after` (a sensor removed, or `moved` moved): a joined sensor
 * that stayed where it was keeps its join only while the sensor now above it was on its tile, so the rest of a tile
 * stays together when one of it leaves instead of joining the tile above. The one that moved keeps its own flag. */
export function keepTiles(before, after, joined, moved) {
  const tileOf = new Map();
  tiles(before, joined).forEach((t, n) => t.forEach((e) => tileOf.set(e, n)));
  return (joined || []).filter((e) => {
    const i = after.indexOf(e);
    return e === moved || (i > 0 && tileOf.get(after[i - 1]) === tileOf.get(e));
  });
}

/** `joined` with this sensor joined to the tile above, or back on a tile of its own. */
export function toggleJoined(joined, id) {
  const list = joined || [];
  return list.includes(id) ? list.filter((e) => e !== id) : [...list, id];
}

/** `joined` as tv_panel/save takes it: sensors still listed, not the first, once each, in the list's order. */
export function cleanJoined(sensors, joined) {
  const on = new Set(joined || []);
  return [...new Set((sensors || []).slice(1))].filter((e) => on.has(e) && e !== sensors[0]);
}

/** The OK editor's screens, as [id, title]: the camera grid by that name. */
export function screenChoices(screens) {
  return screens.map((s) => [s.id, s.type === "cameras" ? "Camera grid" : screenTitle(s)]);
}

/** The chips whose own OK opens this screen, by the name the page shows them by: in the way of removing it. */
export function chipsOpening(hass, list, id) {
  return list.filter((c) => c.ok?.action === "screen" && c.ok.screen === id)
    .map((c) => (c.label && c.label.trim()) || (c.type === "entity" ? defaultName(hass, c.entity) : SCREEN_TYPES[c.type].title));
}

/** Where an entity is: its area, else its device's area, else its device. "" for none. */
function placeOf(hass, entityId) {
  const e = hass.entities?.[entityId];
  const d = e?.device_id && hass.devices?.[e.device_id];
  const area = e?.area_id || d?.area_id;
  return (area && hass.areas?.[area]?.name) || (d ? d.name_by_user || d.name || "" : "");
}

/** Sensors Home Assistant knows to be about the air, not listed yet, by place then name (each with its group). */
export function airSuggestions(hass, exclude) {
  const skip = new Set(exclude);
  return Object.values(hass.states)
    .filter((s) => s.entity_id.startsWith("sensor.") && !skip.has(s.entity_id))
    .filter((s) => AIR_CLASSES.includes(s.attributes?.device_class))
    .map((s) => ({ entity_id: s.entity_id, name: friendly(hass, s.entity_id) || s.entity_id, group: placeOf(hass, s.entity_id) }))
    .sort((a, b) => a.group.localeCompare(b.group) || a.name.localeCompare(b.name))
    .slice(0, 40)
    .map((r) => ({ ...r, value: stateText(hass, r.entity_id) }));
}

// ---- what OK does (model.py's default_ok / resolve_ok: change both together) ----------------------------------

/** What "toggle" works on: model.py's TOGGLE_DOMAINS (a test checks they match). */
export const TOGGLE_DOMAINS = ["automation", "climate", "cover", "fan", "humidifier", "input_boolean", "light", "lock",
  "media_player", "remote", "siren", "switch", "valve"];
export const canToggle = (id) => TOGGLE_DOMAINS.includes(String(id).split(".")[0]);

const isGarage = (hass, id) => hass.states[id]?.attributes?.device_class === "garage"
  || (id + friendly(hass, id)).toLowerCase().includes("garage");
/** A cover that opens a way in (a garage door, a gate or a door) toggles on two presses by default. model.py's
 * asks_twice() decides the same. */
const asksTwice = (hass, id) => {
  const dc = hass.states[id]?.attributes?.device_class;
  if (["garage", "gate", "door"].includes(dc) || isGarage(hass, id)) return true;
  return !dc && `${id} ${friendly(hass, id)}`.toLowerCase().split(/[^a-z]+/).includes("gate");
};
const isSolar = (hass, id) => ["power", "energy"].includes(hass.states[id]?.attributes?.device_class)
  && /production|solar|pv/.test(id);

/**
 * What OK does unless it's set: the chip's screen, a garage door's, gate's or door's toggle (pressed twice), or
 * nothing. A thermostat opens the first thermostat screen that lists it, a sensor the first air quality screen that
 * lists it. A screen that was removed is no one's default.
 */
export function defaultOk(hass, chip, screens = DEFAULT_SCREENS) {
  const screen = (id) => (screens.some((s) => s.id === id) ? { action: "screen", screen: id } : { action: "none" });
  const listed = (type, key, id) => {
    const s = screens.find((x) => x.type === type && (x[key] || []).includes(id));
    return s ? screen(s.id) : null;
  };
  if (chip.type === "cameras") return screen("cameras");
  if (chip.type === "tank") return screen("tank");
  const id = chip.entity;
  const domain = id.split(".")[0];
  if (domain === "weather") return screen("weather");
  if (domain === "camera") return screen("cameras");
  if (domain === "climate") return listed("thermostat", "climates", id) || { action: "none" };
  if (hass.states[id] && isProbe(hass.states[id])) return screen("tank");
  if (domain === "sensor" && isSolar(hass, id)) return screen("solar");
  const air = domain === "sensor" && listed("air_quality", "sensors", id);
  if (air) return air;
  if (domain === "cover" && asksTwice(hass, id)) return { action: "toggle", entity: id, confirm: true };
  return { action: "none" };
}

/** What OK on this chip does, in full: its own setting with the gaps filled, or the default. */
export function resolveOk(hass, chip, screens = DEFAULT_SCREENS) {
  const ok = chip.ok;
  if (!ok || ok.action === "default") return defaultOk(hass, chip, screens);
  const out = { ...ok };
  if (out.action === "toggle" && !out.entity) {
    // Nothing chosen: the chip's own entity, if it can be toggled. A group, a sensor or weather can't.
    if (chip.type !== "entity" || !canToggle(chip.entity)) return { action: "none" };
    out.entity = chip.entity;
  }
  if (["toggle", "run", "perform"].includes(out.action) && out.confirm === undefined) out.confirm = false;
  return out;
}

const SCREEN_NAMES = { weather: "the weather screen", solar: "the solar screen", tank: "the tank screen",
  cameras: "the camera grid" };

/** "OK opens the weather screen", "OK twice opens or closes Garage Door", "OK does nothing". */
export function describeOk(hass, chip, screens = DEFAULT_SCREENS) {
  const ok = resolveOk(hass, chip, screens);
  const ok_ = ok.confirm ? "OK twice" : "OK";
  // The chip's own entity by the name the TV shows for the chip (its label, or "Garage"); another by its own name.
  const name = (id) => (chip.type === "entity" && id === chip.entity && preview(hass, chip, {}).label)
    || friendly(hass, id) || id;
  switch (ok.action) {
    case "screen": {
      if (ok.screen === "camera") return ok.camera ? `OK opens ${name(ok.camera)} full screen` : "OK opens a camera: choose which";
      if (!ok.screen) return "OK opens a screen: choose which";
      const s = screens.find((x) => x.id === ok.screen);
      if (!s) return "OK opens a screen that was removed: choose another";
      return `OK opens ${SCREEN_NAMES[s.type] || `the ${screenTitle(s)} screen`}`;
    }
    case "toggle": {
      const d = ok.entity.split(".")[0];
      const verb = d === "cover" ? "opens or closes" : d === "lock" ? "locks or unlocks" : "toggles";
      return `${ok_} ${verb} ${name(ok.entity)}`;
    }
    case "run":
      return ok.entity ? `${ok_} runs ${name(ok.entity)}` : `${ok_} runs a script or scene: choose which`;
    case "perform":
      return ok.perform_action ? `${ok_} performs ${ok.perform_action}` : `${ok_} performs an action: choose which`;
    default:
      return chip.ok?.action === "toggle" ? "OK does nothing: choose what to toggle" : "OK does nothing";
  }
}

const OK_ICONS = { screen: "mdi:open-in-app", toggle: "mdi:toggle-switch-outline", run: "mdi:play-circle-outline",
  perform: "mdi:flash-outline" };

/**
 * The chip row's OK button: an icon for what OK does (a screen, a toggle, a script or scene, an action, nothing),
 * whether it asks twice, and whether it's half set up ("choose which") so that shows with the editor closed.
 */
export function okLook(hass, chip, screens = DEFAULT_SCREENS) {
  const ok = resolveOk(hass, chip, screens);
  return {
    icon: OK_ICONS[ok.action] || "mdi:gesture-tap",
    twice: Boolean(ok.confirm),
    choose: /choose (which|what to toggle|another)$/.test(describeOk(hass, chip, screens)),
    none: ok.action === "none",
  };
}

/** The editor's Default choice: "Default: opens or closes Garage (press twice)". */
export function defaultChoice(hass, chip, screens = DEFAULT_SCREENS) {
  const say = describeOk(hass, { ...chip, ok: undefined }, screens);
  const twice = say.startsWith("OK twice ");
  return `Default: ${say.replace(/^OK (twice )?/, "")}${twice ? " (press twice)" : ""}`;
}

/**
 * The chip's ok after choosing another kind of action. It keeps asking twice if what it replaces did (the garage's
 * default does), and a toggle starts on the chip's own entity only when that can be toggled.
 */
export function switchOk(hass, chip, action, screens = DEFAULT_SCREENS) {
  if (action === "default" || action === "none") return { action };
  const before = resolveOk(hass, chip, screens);
  const next = { action };
  // Only a press asks twice: a screen has no confirm (the integration refuses one, and the editor has no box for it).
  if (before.confirm && action !== "screen") next.confirm = true;
  if (action === "toggle" && chip.type === "entity" && canToggle(chip.entity)) next.entity = chip.entity;
  return next;
}

/** Whether the TV shows its row: on unless the TV's Widget row switch is off. */
export function rowOn(hass, switchId) {
  return hass.states[switchId]?.state !== "off";
}

/** A copy of the list with one item moved; out of range leaves it as it was. */
export function move(list, from, to) {
  if (from < 0 || from >= list.length || to < 0 || to >= list.length) return list.slice();
  const out = list.slice();
  const [item] = out.splice(from, 1);
  out.splice(to, 0, item);
  return out;
}

/** The solar layout typed on the page: empty is none; otherwise JSON with a list of arrays. */
export function parseSolar(text) {
  if (!text.trim()) return { ok: true, value: null };
  try {
    const value = JSON.parse(text);
    if (typeof value !== "object" || value === null || Array.isArray(value) || !Array.isArray(value.arrays ?? [])) {
      return { ok: false, error: "The layout needs an \"arrays\" list." };
    }
    return { ok: true, value };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

/** A screen as tv_panel/save takes it: only its type's fields, the title trimmed (an empty one is its type's, as the
 * title box shows it), and `joined` only when a sensor shares a tile. */
function screenToSave(s) {
  if (!SCREEN_TYPES[s.type]?.extra) return { id: s.id, type: s.type };
  const out = { id: s.id, type: s.type, title: String(s.title || "").trim() || SCREEN_TYPES[s.type].title };
  if (s.type === "thermostat") out.climates = (s.climates || []).slice();
  out.sensors = (s.sensors || []).slice();
  const joined = cleanJoined(out.sensors, s.joined);
  if (joined.length) out.joined = joined;
  return out;
}

/** What tv_panel/save takes. */
export function toSave(state) {
  return {
    screens: screensOf(state).map(screenToSave),
    chips: state.chips.map((c) => {
      const out = c.type === "entity" ? { type: "entity", entity: c.entity } : { type: c.type };
      if (c.label && c.label.trim()) out.label = c.label.trim();
      if (c.ok && c.ok.action !== "default") out.ok = c.ok;
      return out;
    }),
    cameras: state.cameras.slice(),
    tank: state.tank.slice(),
    solar: parseSolar(state.solarText).value,
  };
}
