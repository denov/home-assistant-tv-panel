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

/** A state as the TV shows it in a list: the value and its unit. */
export function stateText(hass, entityId) {
  const s = hass.states[entityId];
  if (!s || MISSING.has(s.state)) return "—";
  const unit = s.attributes.unit_of_measurement;
  const v = Number.isNaN(Number(s.state)) ? capitalise(s.state.replace(/_/g, " ")) : s.state;
  return unit ? `${v} ${unit}` : v;
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
    const columns = (settings.tank || []).map((e) => [defaultName(hass, e), stateText(hass, e)]);
    return { icon: "mdi:fish", label: "", value: "", tone: "blue", columns };
  }
  const id = chip.entity;
  const s = hass.states[id];
  const domain = id.split(".")[0];
  let name = defaultName(hass, id);
  const missing = !s || MISSING.has(s.state);
  const out = (icon, label, value, tone) => ({ icon, label: chip.label || label, value, tone: missing ? "off" : tone });
  if (domain === "weather") {
    const [text, icon] = CONDITIONS[s?.state] || [capitalise((s?.state || "").replace(/-/g, " ")), "weather-sunny"];
    const t = s?.attributes?.temperature;
    const unit = s?.attributes?.temperature_unit;
    const value = missing || t === undefined || t === null ? "—" : `${number(t)}${unit ? " " + unit : "°"}`;
    return out(`mdi:${icon}`, missing ? name : text, value, s?.state === "sunny" ? "amber" : "sky");
  }
  if (domain === "climate") {
    return out("mdi:thermostat", name, missing ? "—" : thermostat(s), s.attributes.hvac_action === "heating" ? "warm" : "blue");
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
  return out(icon, name, stateText(hass, id), solar ? "amber" : "blue");
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
    .map((s) => ({ entity_id: s.entity_id, name: friendly(hass, s.entity_id) || s.entity_id, value: stateText(hass, s.entity_id) }))
    .filter((r) => words.every((w) => r.name.toLowerCase().includes(w) || r.entity_id.includes(w)))
    .sort((a, b) => a.name.localeCompare(b.name))
    .slice(0, 40);
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

/** What tv_panel/save takes. */
export function toSave(state) {
  return {
    chips: state.chips.map((c) => {
      const out = c.type === "entity" ? { type: "entity", entity: c.entity } : { type: c.type };
      if (c.label && c.label.trim()) out.label = c.label.trim();
      return out;
    }),
    cameras: state.cameras.slice(),
    tank: state.tank.slice(),
    solar: parseSolar(state.solarText).value,
  };
}
