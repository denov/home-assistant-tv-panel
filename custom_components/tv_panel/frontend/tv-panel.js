// The TV Panel page in Home Assistant's sidebar: what each TV shows. The logic is in chips.js (tested in Node).
// The logic, with the same version stamp as this file (panel.py sets it from the files' dates), so an update is never
// half cached. Loaded without a top-level await: Home Assistant creates the element as soon as this script has loaded,
// so the element must be defined by then (see the end of the file).
let chips = null;
const chipsReady = import(new URL(`./chips.js${new URL(import.meta.url).search}`, import.meta.url).href).then((m) => {
  chips = m;
});

const TONES = { warm: "#ff9f43", sky: "#cfe3f2", amber: "#ffc94a", blue: "#2aa7e0", off: "#8a8f98", alarm: "#ff5252" };
// In the lists, on Home Assistant's own background: the TV's pale sky would vanish on a light theme.
const ROW_TONES = { ...TONES, sky: "#5b9bd5" };
const GROUP_NAMES = { cameras: "Cameras", tank: "Tank" };
const APEX_URL = "https://github.com/denov/home-assistant-apex-neptune";
const ENVOY_URL = "https://www.home-assistant.io/integrations/enphase_envoy/";
// The Screens card shows one screen at a time, as tabs; the last one chosen is remembered.
const TAB_KEY = "tv-panel-tab";

function savedTab() {
  try {
    return localStorage.getItem(TAB_KEY);
  } catch (e) {
    return null;
  }
}

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

class TvPanelSettings extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this.s = null; // { chips, cameras, tank, solarText, screens, saved }
    this.tvs = [];
    this.entryId = null;
    this.dirty = false;
    this.status = "";
    this.queries = { chips: "", cameras: "", tank: "" };
    this.drag = null;
    this.editing = null; // the chip whose On OK editor is open
    this.tab = savedTab();
    this.started = false;
    // Home Assistant may set hass, narrow, panel and route before this element was defined. Those land as plain
    // properties on the element and hide the setters below: take them over so the setters run.
    for (const p of ["hass", "narrow", "panel", "route"]) {
      if (Object.prototype.hasOwnProperty.call(this, p)) {
        const v = this[p];
        delete this[p];
        this[p] = v;
      }
    }
  }

  set hass(hass) {
    this._hass = hass;
    if (!this.started) {
      this.started = true;
      chipsReady.then(() => this.load());
    } else if (chips) {
      this.live();
    }
    const menu = this.shadowRoot.querySelector("ha-menu-button");
    if (menu) menu.hass = hass;
  }

  get hass() {
    return this._hass;
  }

  get narrow() {
    return this._narrow;
  }

  set narrow(n) {
    this._narrow = n;
    const menu = this.shadowRoot.querySelector("ha-menu-button");
    if (menu) menu.narrow = n;
  }

  async load(entryId = null) {
    try {
      this.tvs = await this._hass.callWS({ type: "tv_panel/tvs" });
      this.entryId = entryId || this.entryId || this.tvs[0]?.entry_id || null;
      if (this.entryId) {
        const r = await this._hass.callWS({ type: "tv_panel/settings", entry_id: this.entryId });
        this.s = {
          chips: r.chips.map((c) => ({ ...c })),
          cameras: r.cameras.slice(),
          tank: r.tank.slice(),
          solarText: r.solar ? JSON.stringify(r.solar, null, 2) : "",
          screens: JSON.parse(JSON.stringify(chips.screensOf(r))),
          saved: r.saved,
        };
      }
      this.dirty = false;
      this.status = "";
      this.editing = null; // it named a chip by position, which means nothing in another TV's list or after Undo
    } catch (e) {
      this.status = `Couldn't load the settings: ${e.message || e.code || e}`;
    }
    this.render();
  }

  async save() {
    const bad = this.s.chips.findIndex((c) => c.dataError);
    if (bad >= 0) {
      this.status = `Not saved: chip ${bad + 1}'s action data isn't valid JSON (${this.s.chips[bad].dataError}).`;
      this.renderStatus();
      return;
    }
    const solar = chips.parseSolar(this.s.solarText);
    if (!solar.ok) {
      this.status = `Solar layout: ${solar.error}`;
      this.renderStatus();
      return;
    }
    try {
      await this._hass.callWS({ type: "tv_panel/save", entry_id: this.entryId, settings: chips.toSave(this.s) });
      this.s.saved = true;
      this.dirty = false;
      this.status = "Saved. The TV picks it up within about 15 seconds.";
    } catch (e) {
      this.status = `Not saved: ${e.message || e.code || e}`;
    }
    this.render();
  }

  changed(full = true) {
    this.dirty = true;
    this.status = "";
    if (full) this.render();
    else {
      this.renderPreview();
      this.renderStatus();
    }
  }

  // ---- rendering ------------------------------------------------------------------------------------------------

  render() {
    const root = this.shadowRoot;
    const focus = root.activeElement?.dataset?.focus;
    root.innerHTML = `<style>${CSS}</style>
      <div class="top"><div class="toolbar">
        <ha-menu-button></ha-menu-button>
        <div class="title">TV Panel</div>
        ${this.tvs.length > 1 ? `<select data-action="tv">${this.tvs.map((t) =>
          `<option value="${esc(t.entry_id)}" ${t.entry_id === this.entryId ? "selected" : ""}>${esc(t.title)}</option>`).join("")}</select>` : ""}
        <span class="grow"></span>
        <button class="secondary" data-action="revert" ${this.dirty ? "" : "disabled"}>Undo changes</button>
        <button class="primary" data-action="save" ${this.dirty || (this.s && !this.s.saved) ? "" : "disabled"}>Save</button>
      </div>${this.s ? this.onTv() : ""}</div>
      <div class="content">${this.s ? this.body() : this.empty()}</div>`;
    const menu = root.querySelector("ha-menu-button");
    if (menu) {
      menu.hass = this._hass;
      menu.narrow = this._narrow;
    }
    this.previewHtml = null;
    this.renderPreview();
    this.renderStatus();
    for (const box of root.querySelectorAll(".results")) this.renderResults(box.dataset.list);
    // Refit when the page changes width (the sidebar opens, the window or phone turns) and when the row does (an icon
    // draws, a value gets longer). In the next frame, so the refit isn't a change inside the observer's own delivery.
    this.resize?.disconnect();
    const tv = root.querySelector(".tv");
    if (tv && window.ResizeObserver) {
      this.resize = new ResizeObserver(() => requestAnimationFrame(() => this.fitPreview()));
      this.resize.observe(tv);
      this.resize.observe(root.getElementById("preview"));
    }
    if (focus) {
      const el = root.querySelector(`[data-focus="${focus}"]`);
      if (el) {
        el.focus();
        if (el.setSelectionRange && el.value !== undefined) el.setSelectionRange(el.value.length, el.value.length);
      }
    }
    if (!this.wired) this.wire();
  }

  empty() {
    return `<div class="card"><p>${this.status ? esc(this.status) : this.tvs.length ? "Loading…"
      : "No TV is set up yet. Add the TV Panel integration under Settings → Devices &amp; services, then come back here."}</p></div>`;
  }

  /** Pinned under the toolbar, so the TV's row stays in view while the chips below it change. */
  onTv() {
    return `<div class="pin">
      <div class="status" id="status"></div>
      <div class="card preview">
        <div class="head"><h2>On the TV</h2>${this.rowSwitch() ? `<label class="toggle">Widget row
          <input type="checkbox" data-action="row" ${chips.rowOn(this._hass, this.rowSwitch()) ? "checked" : ""}></label>` : ""}</div>
        <div class="tv"><div class="fit"><div class="row" id="preview"></div></div></div>
        <p class="hint" id="row-hint"></p>
      </div>
    </div>`;
  }

  body() {
    const s = this.s;
    const has = (t) => s.chips.some((c) => c.type === t);
    return `
      ${s.saved ? "" : `<div class="note">Not saved yet. This is the TV's current setup, from the <b>tv_widget</b> label and the
        camera order helper. Save to make it the TV's settings.</div>`}
      <div class="card">
        <h2>Chips</h2>
        <p class="hint">In the order they sit on the TV's home screen. Drag a chip, or use the arrows. A label replaces the
          entity's name on the TV.</p>
        <div class="list">${s.chips.map((c, i) => this.chipRow(c, i)).join("")}</div>
        <div class="add">
          ${this.searchBox("chips", "Add a chip: search by name or entity id")}
          ${["cameras", "tank"].filter((t) => !has(t)).map((t) =>
            `<button class="secondary" data-action="add-group" data-type="${t}">Add the ${GROUP_NAMES[t]} chip</button>`).join("")}
        </div>
      </div>

      <div class="card">
        <h2>Screens</h2>
        <p class="hint">What the TV shows full screen: when OK is pressed on a chip, by voice, or from
          <code>tv_panel.show</code>.</p>
        ${this.screensCard()}
      </div>`;
  }

  // ---- the screens --------------------------------------------------------------------------------------------------

  /** The screens being edited (the built-in four when none were ever saved). */
  screens() {
    return chips.screensOf(this.s);
  }

  screen(id) {
    return this.screens().find((x) => x.id === id);
  }

  /** The tab shown: the one chosen while that screen is there, else the first. */
  shownTab() {
    const ids = this.screens().map((x) => x.id);
    return ids.includes(this.tab) ? this.tab : ids[0] || null;
  }

  /** A list on the page: "chips", "cameras", "tank", or a screen's own, "<id>.climates" or "<id>.sensors". */
  listGet(list) {
    if (!list.includes(".")) return this.s[list];
    const [id, key] = list.split(".");
    return this.screen(id)?.[key] || [];
  }

  listSet(list, value) {
    if (!list.includes(".")) {
      this.s[list] = value;
      return;
    }
    const [id, key] = list.split(".");
    const sc = this.screen(id);
    if (sc) sc[key] = value;
  }

  screensCard() {
    const screens = this.screens();
    const tab = this.shownTab();
    const sc = this.screen(tab);
    const add = chips.addableTypes(screens);
    return `<div class="tabbar">
        <div class="tabs" role="tablist" aria-label="The TV's screens">${screens.map((x) =>
          `<button role="tab" id="tab-${esc(x.id)}" data-action="tab" data-tab="${esc(x.id)}" aria-controls="pane-${esc(x.id)}"
            aria-selected="${tab === x.id}" tabindex="${tab === x.id ? 0 : -1}">${esc(chips.screenTitle(x))}</button>`).join("")}</div>
        <select class="add-screen" data-action="add-screen" aria-label="Add a screen"><option value="">Add a screen…</option>
          ${add.map(([t, title]) => `<option value="${t}">${esc(title)}</option>`).join("")}</select>
      </div>
      ${sc ? `<div role="tabpanel" id="pane-${esc(sc.id)}" aria-labelledby="tab-${esc(sc.id)}">${this.pane(sc)}</div>`
        : `<p class="hint">No screens: OK on a chip that would open one only fires the event.</p>`}`;
  }

  /** What the screen shows and needs, its settings, how to open it, and Remove. */
  pane(sc) {
    const how = `<p class="hint">Open it with a chip's OK, or from an automation or a voice command with
        <code>tv_panel.show</code> and <code>screen: ${esc(sc.id)}</code> (see the README). Its link is
        <code>${esc(chips.screenLink(sc))}</code>.</p>`;
    return `${this.paneBody(sc)}${how}
      <div class="remove"><button class="secondary" data-action="remove-screen" data-screen="${esc(sc.id)}">Remove this screen</button></div>`;
  }

  /** A note when what the screen reads isn't in Home Assistant. */
  missing(text) {
    return `<p class="missing">${text}</p>`;
  }

  titleBox(sc) {
    return `<div class="title-box"><label>Title <input data-action="screen-title" data-screen="${esc(sc.id)}"
        data-focus="title-${esc(sc.id)}" value="${esc(sc.title || "")}" placeholder="${esc(chips.screenTitle({ ...sc, title: "" }))}"></label>
      <span class="sub">Its id is <code>${esc(sc.id)}</code>, for automations and voice. Renaming it keeps the id.</span></div>`;
  }

  paneBody(sc) {
    const s = this.s;
    const h = this._hass;
    const ids = Object.keys(h.states);
    if (sc.type === "weather") {
      return `<p class="hint">Now, the next 12 hours and 5 days, from a weather entity with a daily forecast: the TV's
          first weather chip, or else the weather entity that's reporting.</p>
        ${ids.some((e) => e.startsWith("weather.")) ? "" : this.missing("No weather entity found in Home Assistant.")}`;
    }
    if (sc.type === "thermostat") {
      const climates = sc.climates || [];
      const sensors = sc.sensors || [];
      return `<p class="hint">Each thermostat: where it is and where it's set, its mode and what it's doing, humidity and
          the fan when it has them, and the last 24 hours. Any thermostat in Home Assistant works (a <code>climate</code>
          entity), up to ${chips.MAX_CLIMATES}. A thermostat chip opens the first of these screens that lists it.</p>
        ${this.titleBox(sc)}
        <h3>Thermostats</h3>
        <div class="list">${climates.map((e, i) => this.memberRow(`${sc.id}.climates`, e, i)).join("")}</div>
        ${climates.length ? "" : this.missing("Add a thermostat: the screen shows nothing without one.")}
        ${climates.length < chips.MAX_CLIMATES ? `<div class="add">${this.searchBox(`${sc.id}.climates`, "Add a thermostat")}</div>` : ""}
        <h3>Other readings</h3>
        <p class="hint">Optional: temperatures to show beside them, such as other rooms or outside.</p>
        <div class="list">${sensors.map((e, i) => this.memberRow(`${sc.id}.sensors`, e, i)).join("")}</div>
        ${sensors.length < chips.MAX_READINGS ? `<div class="add">${this.searchBox(`${sc.id}.sensors`, "Add a sensor")}</div>` : ""}`;
    }
    if (sc.type === "air_quality") {
      const sensors = sc.sensors || [];
      return `<p class="hint">A tile per sensor: its value, a colour for how good that is (where Home Assistant knows
          what the sensor measures, in a unit with known levels), and the last 24 hours. Up to ${chips.MAX_AIR_SENSORS}
          fit the screen. Any sensor works; the air quality sensors Home Assistant knows about are suggested first.</p>
        ${this.titleBox(sc)}
        <div class="list">${sensors.map((e, i) => this.memberRow(`${sc.id}.sensors`, e, i)).join("")}</div>
        ${sensors.length ? "" : this.missing("Add a sensor: the screen shows nothing without one.")}
        ${sensors.length < chips.MAX_AIR_SENSORS ? `<div class="add">${this.searchBox(`${sc.id}.sensors`, "Add a sensor")}</div>`
          : `<p class="hint">That's ${chips.MAX_AIR_SENSORS}, as many as fit.</p>`}`;
    }
    if (sc.type === "cameras") {
      return `<p class="hint">The cameras the Cameras chip opens, in grid order: left to right, top row first.</p>
        ${ids.some((e) => e.startsWith("camera.")) ? "" : this.missing("No camera entities found in Home Assistant.")}
        <div class="grid">${[0, 1, 2, 3, 4, 5].map((i) => `<div class="slot ${s.cameras[i] ? "" : "free"}">${
          s.cameras[i] ? esc(chips.defaultName(this._hass, s.cameras[i]).replace(/^Camera\s*[-:]\s*/i, "")) : "empty"}</div>`).join("")}</div>
        ${s.cameras.length > 6 ? `<p class="hint">Only the first six fit the grid; Left and Right in the live view reach the rest.</p>` : ""}
        <div class="list">${s.cameras.map((e, i) => this.memberRow("cameras", e, i)).join("")}</div>
        <div class="add">${this.searchBox("cameras", "Add a camera")}</div>`;
    }
    if (sc.type === "tank") {
      return `<p class="hint">The Tank screen shows every Apex probe, from the
          <a href="${APEX_URL}" target="_blank" rel="noreferrer">Neptune Apex integration</a>${s.tank.length ? ""
          : ", which isn't sending any yet: install it, or add its probes below"}. This list sets the tank chip's
          columns, in order.</p>
        <div class="list">${s.tank.map((e, i) => this.memberRow("tank", e, i)).join("")}</div>
        <div class="add">${this.searchBox("tank", "Add an Apex probe")}</div>`;
    }
    const envoy = Object.values(h.entities || {}).some((e) => e.platform === "enphase_envoy");
    return `<p class="hint">The solar screen is built for Enphase: it reads an Enphase Envoy through Home Assistant's
        <a href="${ENVOY_URL}" target="_blank" rel="noreferrer">Enphase Envoy integration</a>, and battery charge and
        discharge sensors by name.</p>
      ${envoy ? "" : this.missing("No Enphase Envoy found in Home Assistant.")}
      <p class="hint">Optional: where each microinverter sits on the roof, as JSON. <code>id</code> is the serial in its
        <code>sensor.inverter_&lt;serial&gt;</code> name:
        <code>{"arrays": [{"name": "Array 1", "panels": [{"id": "inverter serial", "x": 0, "y": 0, "w": 2, "h": 1}]}]}</code>.
        Empty shows the panels in rows.</p>
      <textarea data-action="solar" data-focus="solar" spellcheck="false" rows="10">${esc(s.solarText)}</textarea>
      <div class="error" id="solar-error"></div>`;
  }

  chipRow(c, i) {
    const p = chips.preview(this._hass, c, this.s);
    const isEntity = c.type === "entity";
    const name = isEntity ? chips.defaultName(this._hass, c.entity) : GROUP_NAMES[c.type];
    const sub = isEntity ? c.entity : c.type === "cameras" ? `${this.s.cameras.length} cameras` : `${this.s.tank.length} readings`;
    return `<div class="item ${this.editing === i ? "open" : ""}" draggable="true" data-list="chips" data-index="${i}">
      <span class="handle" title="Drag to move">⋮⋮</span>
      <ha-icon icon="${esc(p.icon)}" style="color:${ROW_TONES[p.tone]}"></ha-icon>
      <div class="name"><div>${esc(name)}</div><div class="sub">${esc(sub)}</div></div>
      ${isEntity ? `<div class="value" data-live="${esc(c.entity)}">${esc(chips.stateText(this._hass, c.entity))}</div>` : ""}
      <div class="controls">
        ${c.type === "tank" ? "" : `<input class="label" data-action="label" data-index="${i}" data-focus="label-${i}"
          placeholder="${esc(p.label || name)}" value="${esc(c.label || "")}" aria-label="Label on the TV">`}
        ${this.okButton(c, i)}
        ${this.arrows("chips", i, this.s.chips.length)}
      </div>
    </div>
    ${this.editing === i ? this.okEditor(c, i) : ""}`;
  }

  /** What OK does, as an icon beside move and remove; it opens the editor, attached under the row. */
  okButton(c, i) {
    const look = chips.okLook(this._hass, c, this.screens());
    const say = chips.describeOk(this._hass, c, this.screens());
    const open = this.editing === i;
    return `<button class="icon ok ${look.none ? "none" : ""} ${look.choose ? "choose" : ""} ${open ? "open" : ""}"
      data-action="ok-edit" data-index="${i}" id="ok-button-${i}" title="${esc(say)}. Click to change."
      aria-label="${esc(say)}. Change what OK does" aria-expanded="${open}"><ha-icon icon="${look.icon}"></ha-icon>${
      look.twice ? `<span class="twice">2×</span>` : ""}</button>`;
  }

  okEditor(c, i) {
    const ok = c.ok || { action: "default" };
    const def = chips.defaultChoice(this._hass, c, this.screens());
    const opt = (v, text) => `<option value="${v}" ${ok.action === v ? "selected" : ""}>${esc(text)}</option>`;
    const states = Object.keys(this._hass.states);
    const pick = (list, value, attrs) => `<select ${attrs}>${value ? "" : `<option value="">Choose…</option>`}${list.map((e) =>
      `<option value="${esc(e)}" ${e === value ? "selected" : ""}>${esc(chips.defaultName(this._hass, e))} (${esc(e)})</option>`).join("")}</select>`;
    let fields = "";
    if (ok.action === "screen") {
      const screens = chips.screenChoices(this.screens());
      const cams = states.filter((e) => e.startsWith("camera.")).sort();
      const now = ok.screen === "camera" ? `camera:${ok.camera}` : ok.screen;
      // A screen just removed (or a camera gone) matches no option: say Choose…, or the browser shows the first option
      // as chosen, and picking that one changes nothing.
      const known = screens.some(([v]) => v === now) || cams.some((e) => now === `camera:${e}`);
      fields = `<label>Screen <select data-action="ok-screen" data-index="${i}">${known ? "" : `<option value="" selected>Choose…</option>`}
        ${screens.map(([v, t]) => `<option value="${esc(v)}" ${now === v ? "selected" : ""}>${esc(t)}</option>`).join("")}
        ${cams.map((e) => `<option value="camera:${esc(e)}" ${now === "camera:" + e ? "selected" : ""}>${esc(chips.defaultName(this._hass, e))}, full screen</option>`).join("")}
      </select></label>`;
    } else if (ok.action === "toggle") {
      const can = states.filter(chips.canToggle).sort();
      fields = `<label>Toggle ${pick(can, ok.entity || "", `data-action="ok-entity" data-index="${i}"`)}</label>`;
    } else if (ok.action === "run") {
      const runs = states.filter((e) => /^(script|scene)\./.test(e)).sort();
      fields = `<label>Script or scene ${pick(runs, ok.entity, `data-action="ok-entity" data-index="${i}"`)}</label>`;
    } else if (ok.action === "perform") {
      const services = Object.entries(this._hass.services || {}).flatMap(([d, svcs]) => Object.keys(svcs).map((v) => `${d}.${v}`)).sort();
      fields = `<label>Action <input list="services-${i}" data-action="ok-perform" data-index="${i}" data-focus="ok-perform-${i}"
          value="${esc(ok.perform_action || "")}" placeholder="light.turn_on"></label>
        <datalist id="services-${i}">${services.map((v) => `<option value="${esc(v)}">`).join("")}</datalist>
        <label>Target <input list="entities-${i}" data-action="ok-target" data-index="${i}" data-focus="ok-target-${i}"
          value="${esc([].concat(ok.target?.entity_id || []).join(", "))}" placeholder="entity id (optional)"></label>
        <datalist id="entities-${i}">${states.sort().map((e) => `<option value="${esc(e)}">`).join("")}</datalist>
        <label class="wide">Data, as JSON (optional) <textarea rows="3" data-action="ok-data" data-index="${i}" data-focus="ok-data-${i}"
          spellcheck="false" placeholder='{"brightness_pct": 30}'>${esc(ok.data ? JSON.stringify(ok.data) : "")}</textarea></label>
        <div class="error" id="ok-error-${i}"></div>`;
    }
    const confirm = ["toggle", "run", "perform"].includes(ok.action)
      ? `<label class="check"><input type="checkbox" data-action="ok-confirm" data-index="${i}" ${ok.confirm ? "checked" : ""}>
          Press OK twice (the TV asks first)</label>` : "";
    return `<div class="ok-editor">
      <div class="say" id="ok-say-${i}">${esc(chips.describeOk(this._hass, c, this.screens()))}</div>
      <label>On OK <select data-action="ok-action" data-index="${i}">
        ${opt("default", def)}${opt("none", "Do nothing")}${opt("screen", "Open a screen")}
        ${opt("toggle", "Toggle something")}${opt("run", "Run a script or scene")}${opt("perform", "Perform an action")}
      </select></label>
      ${fields}${confirm}
      <p class="hint">Whatever you pick, an automation can react to the press too, through the <code>tv_panel_chip_pressed</code> event.</p>
    </div>`;
  }

  memberRow(list, e, i) {
    return `<div class="item" draggable="true" data-list="${list}" data-index="${i}">
      <span class="handle" title="Drag to move">⋮⋮</span>
      <div class="name"><div>${esc(chips.defaultName(this._hass, e))}</div><div class="sub">${esc(e)}</div></div>
      <div class="value" data-live="${esc(e)}">${esc(chips.stateText(this._hass, e))}</div>
      ${this.arrows(list, i, this.listGet(list).length)}
    </div>`;
  }

  arrows(list, i, n) {
    return `<button class="icon" data-action="up" data-list="${list}" data-index="${i}" ${i === 0 ? "disabled" : ""} title="Move up">↑</button>
      <button class="icon" data-action="down" data-list="${list}" data-index="${i}" ${i === n - 1 ? "disabled" : ""} title="Move down">↓</button>
      <button class="icon" data-action="remove" data-list="${list}" data-index="${i}" title="Remove">✕</button>`;
  }

  searchBox(list, placeholder) {
    return `<div class="search"><input type="search" data-action="search" data-list="${esc(list)}" data-focus="search-${esc(list)}"
      placeholder="${esc(placeholder)}" value="${esc(this.queries[list] || "")}"><div class="results" id="results-${esc(list)}"
      data-list="${esc(list)}"></div></div>`;
  }

  results(list) {
    const q = this.queries[list] || "";
    if (list === "chips") {
      return q.trim() ? chips.search(this._hass, q, this.s.chips.filter((c) => c.type === "entity").map((c) => c.entity)) : [];
    }
    if (list === "cameras") return chips.search(this._hass, q, this.s.cameras, "camera");
    if (list === "tank") return chips.search(this._hass, q, this.s.tank, null, chips.isProbe);
    const [id, key] = list.split(".");
    const have = this.listGet(list);
    if (key === "climates") return chips.search(this._hass, q, have, "climate");
    // Air quality: with nothing typed, the sensors Home Assistant knows are about the air, by place.
    if (this.screen(id)?.type === "air_quality" && !q.trim()) return chips.airSuggestions(this._hass, have);
    return chips.search(this._hass, q, have, "sensor");
  }

  renderResults(list) {
    const box = this.shadowRoot.getElementById(`results-${list}`);
    if (!box) return;
    const query = this.queries[list] || "";
    const focused = this.shadowRoot.activeElement?.dataset?.focus === `search-${list}`;
    const found = focused || query ? this.results(list) : [];
    let group = null;
    box.innerHTML = found.map((f) => {
      const head = f.group !== undefined && f.group !== group ? `<div class="group">${esc((group = f.group) || "Elsewhere")}</div>` : "";
      return `${head}<button class="result" data-action="add" data-list="${esc(list)}" data-id="${esc(f.entity_id)}">
        <span class="name"><span>${esc(f.name)}</span><span class="sub">${esc(f.entity_id)}</span></span>
        <span class="value">${esc(f.value)}</span></button>`;
    }).join("") || (query.trim() ? `<div class="sub pad">Nothing matches.</div>` : "");
  }

  rowSwitch() {
    return this.tvs.find((t) => t.entry_id === this.entryId)?.row_switch || null;
  }

  renderPreview() {
    const box = this.shadowRoot.getElementById("preview");
    if (!box || !this.s) return;
    const on = chips.rowOn(this._hass, this.rowSwitch());
    const toggle = this.shadowRoot.querySelector('[data-action="row"]');
    if (toggle) toggle.checked = on;
    const hint = this.shadowRoot.getElementById("row-hint");
    if (hint) hint.textContent = on ? "" : "The widget row is off: the TV shows no chips. It takes effect straight away; no Save needed.";
    let html = "";
    if (on) {
      html = this.s.chips.slice(0, 8).map((c) => {
      const p = chips.preview(this._hass, c, this.s);
      const icon = `<ha-icon icon="${esc(p.icon)}" style="color:${TONES[p.tone]}"></ha-icon>`;
      if (p.columns) {
        return `<div class="chip">${icon}${p.columns.map(([l, v]) =>
          `<div class="col"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div></div>`).join("")}</div>`;
      }
      if (!p.value) return `<div class="chip only" title="${esc(p.label)}">${icon}</div>`;
      return `<div class="chip">${icon}<div class="col"><div class="l">${esc(p.label)}</div><div class="v">${esc(p.value)}</div></div></div>`;
      }).join("") || `<div class="sub">No chips: the row is empty.</div>`;
      if (this.s.chips.length > 8) html += `<div class="sub">The TV shows the first 8.</div>`;
    }
    if (html === this.previewHtml) return;
    this.previewHtml = html;
    box.innerHTML = html;
    this.fitPreview();
  }

  renderStatus() {
    const el = this.shadowRoot.getElementById("status");
    if (el) {
      el.textContent = this.status;
      el.className = this.status ? "status shown" : "status";
    }
    const save = this.shadowRoot.querySelector('[data-action="save"]');
    if (save) save.disabled = !(this.dirty || (this.s && !this.s.saved));
    const revert = this.shadowRoot.querySelector('[data-action="revert"]');
    if (revert) revert.disabled = !this.dirty;
  }

  live() {
    if (!this.s) return;
    for (const el of this.shadowRoot.querySelectorAll("[data-live]")) {
      el.textContent = chips.stateText(this._hass, el.dataset.live);
    }
    this.renderPreview();
  }

  /** A change in a chip's On OK editor. Choosing another kind of action redraws the editor; the rest only the line. */
  okInput(el, action, i) {
    const chip = this.s.chips[i];
    const ok = { ...(chip.ok || { action: "default" }) };
    if (action === "ok-action") {
      chip.ok = chips.switchOk(this._hass, chip, el.value, this.screens());
      delete chip.dataError;
      this.changed();
      return;
    }
    if (action === "ok-screen") {
      delete ok.camera;
      if (el.value.startsWith("camera:")) {
        ok.screen = "camera";
        ok.camera = el.value.slice(7);
      } else ok.screen = el.value;
    } else if (action === "ok-entity") ok.entity = el.value;
    else if (action === "ok-confirm") ok.confirm = el.checked;
    else if (action === "ok-perform") ok.perform_action = el.value.trim();
    else if (action === "ok-target") {
      const ids = el.value.split(",").map((x) => x.trim()).filter(Boolean);
      if (ids.length) ok.target = { entity_id: ids.length === 1 ? ids[0] : ids };
      else delete ok.target;
    } else if (action === "ok-data") {
      const error = this.shadowRoot.getElementById(`ok-error-${i}`);
      try {
        const data = el.value.trim() ? JSON.parse(el.value) : null;
        if (data !== null && (typeof data !== "object" || Array.isArray(data))) throw new Error("Data must be a JSON object.");
        if (data) ok.data = data;
        else delete ok.data;
        delete chip.dataError;
        if (error) error.textContent = "";
      } catch (e) {
        // Kept on the chip (toSave never sends it) so Save refuses until it's fixed, rather than save the old data.
        chip.dataError = e.message;
        if (error) error.textContent = e.message;
        this.changed(false);
        return;
      }
    }
    chip.ok = ok;
    this.sayOk(chip, i);
    this.changed(false);
  }

  /** After a change in the editor: its sentence, and the row's OK button, without redrawing what's being typed in. */
  sayOk(chip, i) {
    const say = this.shadowRoot.getElementById(`ok-say-${i}`);
    if (say) say.textContent = chips.describeOk(this._hass, chip, this.screens());
    const button = this.shadowRoot.getElementById(`ok-button-${i}`);
    if (button) button.outerHTML = this.okButton(chip, i);
  }

  showTab(tab, focus = false) {
    this.tab = tab;
    try {
      localStorage.setItem(TAB_KEY, tab);
    } catch (e) {
      // A private window: the tab just isn't remembered.
    }
    this.render();
    if (focus) this.shadowRoot.getElementById(`tab-${tab}`)?.focus();
  }

  /** Scale the preview row down to fit its box, as the launcher fits the row on the TV: it never scrolls. */
  fitPreview() {
    // The row keeps its TV size inside .fit, which takes the scaled size: nothing is wider than the box.
    const box = this.shadowRoot.querySelector(".tv");
    const row = this.shadowRoot.getElementById("preview");
    if (!box || !row) return;
    const fit = row.parentElement;
    const style = getComputedStyle(box);
    const room = box.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    const k = Math.min(1, room / Math.max(1, row.offsetWidth));
    row.style.transform = k < 1 ? `scale(${k})` : "";
    fit.style.width = `${Math.floor(row.offsetWidth * k)}px`;
    fit.style.height = `${Math.ceil(row.offsetHeight * k)}px`;
  }

  // ---- events ---------------------------------------------------------------------------------------------------

  wire() {
    this.wired = true;
    const root = this.shadowRoot;
    root.addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-action]");
      if (!b || b.disabled) return;
      const { action, list } = b.dataset;
      const i = Number(b.dataset.index);
      if (action === "tab") {
        this.showTab(b.dataset.tab);
        return;
      }
      if (action === "ok-edit") {
        this.editing = this.editing === i ? null : i;
        this.render();
        return;
      }
      if (action === "save") this.save();
      else if (action === "revert") this.load(this.entryId);
      else if (action === "up" || action === "down") {
        this.editing = null;
        this.listSet(list, chips.move(this.listGet(list), i, action === "up" ? i - 1 : i + 1));
        this.changed();
      } else if (action === "remove") {
        this.editing = null;
        this.listSet(list, this.listGet(list).filter((_, j) => j !== i));
        this.changed();
      } else if (action === "remove-screen") {
        const id = b.dataset.screen;
        const inWay = chips.chipsOpening(this._hass, this.s.chips, id);
        const title = chips.screenTitle(this.screen(id));
        this.s.screens = this.screens().filter((x) => x.id !== id);
        this.changed();
        if (inWay.length) {
          // Save refuses too; saying it now saves a surprise.
          this.status = `Removed ${title}, but OK on ${inWay.join(", ")} opens it: change that before you save.`;
          this.renderStatus();
        }
      } else if (action === "add-group") {
        this.s.chips.push({ type: b.dataset.type });
        this.changed();
      } else if (action === "add") {
        const id = b.dataset.id;
        if (list === "chips") {
          // A camera or a probe joins its group, which gets its chip if it has none yet.
          const group = id.startsWith("camera.") ? "cameras" : chips.isProbe(this._hass.states[id] || {}) ? "tank" : null;
          if (group) {
            if (!this.s[group].includes(id)) this.s[group].push(id);
            if (!this.s.chips.some((c) => c.type === group)) this.s.chips.push({ type: group });
          } else this.s.chips.push({ type: "entity", entity: id });
        } else if (!this.listGet(list).includes(id)) this.listSet(list, [...this.listGet(list), id]);
        this.queries[list] = "";
        this.changed();
      }
    });
    root.addEventListener("input", (ev) => {
      const el = ev.target;
      const { action } = el.dataset;
      if (action === "label") {
        this.s.chips[Number(el.dataset.index)].label = el.value;
        this.changed(false);
      } else if (action === "search") {
        this.queries[el.dataset.list] = el.value;
        this.renderResults(el.dataset.list);
      } else if (action && action.startsWith("ok-") && action !== "ok-edit") {
        this.okInput(el, action, Number(el.dataset.index));
      } else if (action === "row") {
        // A switch, not a setting: it changes at once, like the switch on the TV's device page.
        this._hass.callService("switch", el.checked ? "turn_on" : "turn_off", { entity_id: this.rowSwitch() })
          .catch((e) => {
            this.status = `Couldn't switch the widget row: ${e.message || e.code || e}`;
            this.renderStatus();
            this.renderPreview(); // puts the checkbox back to the switch's real state
          });
      } else if (action === "screen-title") {
        const sc = this.screen(el.dataset.screen);
        if (!sc) return;
        sc.title = el.value;
        const tab = root.getElementById(`tab-${sc.id}`);
        if (tab) tab.textContent = chips.screenTitle(sc);
        this.changed(false);
      } else if (action === "solar") {
        this.s.solarText = el.value;
        const r = chips.parseSolar(el.value);
        root.getElementById("solar-error").textContent = r.ok ? "" : r.error;
        this.changed(false);
      }
    });
    root.addEventListener("keydown", (ev) => {
      const t = ev.target.closest?.("[role=tab]");
      if (!t || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(ev.key)) return;
      ev.preventDefault();
      const ids = this.screens().map((x) => x.id);
      const at = ids.indexOf(t.dataset.tab);
      const next = ev.key === "Home" ? 0 : ev.key === "End" ? ids.length - 1
        : (at + (ev.key === "ArrowRight" ? 1 : ids.length - 1)) % ids.length;
      this.showTab(ids[next], true);
    });
    root.addEventListener("focusin", (ev) => {
      if (ev.target.dataset?.action === "search") this.renderResults(ev.target.dataset.list);
    });
    root.addEventListener("change", (ev) => {
      if (ev.target.dataset?.action === "add-screen" && ev.target.value) {
        const sc = chips.newScreen(this.screens(), ev.target.value);
        this.s.screens = [...this.screens(), sc];
        this.changed();
        this.showTab(sc.id, true);
        return;
      }
      if (ev.target.dataset?.action === "tv") {
        if (this.dirty && !confirm("Drop the changes you haven't saved?")) {
          ev.target.value = this.entryId;
          return;
        }
        this.load(ev.target.value);
      }
    });
    root.addEventListener("dragstart", (ev) => {
      const item = ev.target.closest?.(".item");
      if (!item) return;
      this.drag = { list: item.dataset.list, from: Number(item.dataset.index) };
      ev.dataTransfer.effectAllowed = "move";
      ev.dataTransfer.setData("text/plain", "");
      item.classList.add("dragging");
    });
    root.addEventListener("dragover", (ev) => {
      const item = ev.target.closest?.(".item");
      if (this.drag && item && item.dataset.list === this.drag.list) {
        ev.preventDefault();
        for (const el of root.querySelectorAll(".over")) el.classList.remove("over");
        item.classList.add("over");
      }
    });
    root.addEventListener("drop", (ev) => {
      const item = ev.target.closest?.(".item");
      if (!this.drag || !item || item.dataset.list !== this.drag.list) return;
      ev.preventDefault();
      this.listSet(this.drag.list, chips.move(this.listGet(this.drag.list), this.drag.from, Number(item.dataset.index)));
      this.drag = null;
      this.editing = null;
      this.changed();
    });
    root.addEventListener("dragend", () => {
      this.drag = null;
      for (const el of root.querySelectorAll(".over, .dragging")) el.classList.remove("over", "dragging");
    });
  }
}

const CSS = `
  :host { display: block; min-height: 100vh; background: var(--primary-background-color); color: var(--primary-text-color);
    font-family: var(--paper-font-body1_-_font-family, Roboto, sans-serif); }
  .toolbar { display: flex; align-items: center; gap: 8px; height: 56px; padding: 0 12px; box-sizing: border-box;
    background: var(--app-header-background-color, var(--primary-color)); color: var(--app-header-text-color, #fff); }
  .toolbar .title { font-size: 20px; margin-left: 4px; }
  .toolbar select { font: inherit; padding: 4px 8px; border-radius: 6px; }
  .grow { flex: 1; }
  .content { max-width: 900px; margin: 0 auto; padding: 16px; box-sizing: border-box; }
  .card { background: var(--card-background-color); border-radius: var(--ha-card-border-radius, 12px);
    box-shadow: var(--ha-card-box-shadow, none); border: 1px solid var(--divider-color); padding: 16px; margin-bottom: 16px; }
  h2 { font-size: 18px; font-weight: 500; margin: 0 0 8px; }
  .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .toggle { display: flex; align-items: center; gap: 8px; font-size: 14px; cursor: pointer; }
  .toggle input { width: 18px; height: 18px; accent-color: var(--primary-color); }
  .hint, .sub { color: var(--secondary-text-color); font-size: 13px; }
  .hint { margin: 0 0 12px; }
  code { font-size: 12px; overflow-wrap: anywhere; }
  a { color: var(--primary-color); }
  .note { background: rgba(255, 201, 74, 0.15); border: 1px solid rgba(255, 201, 74, 0.5); border-radius: 8px; padding: 10px 12px;
    margin-bottom: 16px; font-size: 14px; }
  .status { display: none; border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; background: var(--secondary-background-color); }
  .status.shown { display: block; }
  .tv { background: radial-gradient(ellipse at 40% 30%, #1d4d1d, #071207 70%); border-radius: 10px; padding: 18px;
    overflow: hidden; box-sizing: border-box; }
  .fit { position: relative; overflow: hidden; max-width: 100%; }
  /* Sized before it draws its icon (it draws a moment later), so the row is measured at its real width. */
  .chip ha-icon { display: inline-flex; flex: none; width: 24px; height: 24px; }
  .top { position: sticky; top: 0; z-index: 4; background: var(--primary-background-color); }
  .pin { max-width: 900px; margin: 0 auto; padding: 16px 16px 0; box-sizing: border-box; }
  .pin .card { margin-bottom: 0; box-shadow: 0 6px 10px -8px rgba(0, 0, 0, 0.5); }
  .pin .hint:empty { display: none; }
  .row { position: absolute; left: 0; top: 0; display: flex; gap: 10px; align-items: stretch; min-height: 52px;
    width: max-content; transform-origin: left top; }
  /* The screens' tabs, and Add a screen at the end of them. */
  .tabbar { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 4px 12px; border-bottom: 1px solid var(--divider-color);
    margin: 4px 0 14px; }
  .tabs { display: flex; flex-wrap: wrap; gap: 4px; flex: 1; min-width: 0; }
  .add-screen { margin-bottom: 6px; }
  h3 { font-size: 15px; font-weight: 500; margin: 16px 0 6px; }
  .missing { color: var(--warning-color, #ffa600); font-size: 14px; margin: 0 0 12px; }
  .remove { display: flex; justify-content: flex-end; margin-top: 12px; }
  .title-box { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; margin-bottom: 4px; }
  .title-box label { display: flex; align-items: center; gap: 8px; font-size: 14px; }
  .results .group { font-size: 12px; font-weight: 500; color: var(--secondary-text-color); padding: 8px 8px 2px;
    text-transform: uppercase; letter-spacing: 0.04em; }
  .tabs button { border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 8px 14px; font-size: 15px;
    color: var(--secondary-text-color); margin-bottom: -1px; }
  .tabs button[aria-selected="true"] { color: var(--primary-color); border-bottom-color: var(--primary-color); }
  .chip { display: flex; align-items: center; gap: 12px; padding: 8px 14px; border-radius: 26px; background: rgba(255,255,255,0.08);
    color: #fff; white-space: nowrap; }
  .chip.only { padding: 8px 12px; }
  .chip .col .l { font-size: 11px; color: rgba(255,255,255,0.7); }
  .chip .col .v { font-size: 15px; }
  .tv .sub { color: rgba(255,255,255,0.6); align-self: center; }
  .list { display: flex; flex-direction: column; gap: 6px; }
  button.link { border: none; padding: 0 4px; color: var(--primary-color); font-size: 13px; }
  /* What OK does, beside move and remove. Dim when it does nothing; amber when it still needs a choice. */
  button.ok { position: relative; display: inline-flex; align-items: center; justify-content: center; }
  button.ok ha-icon { --mdc-icon-size: 18px; width: 18px; height: 18px; display: inline-flex; }
  button.ok.none ha-icon { opacity: 0.45; }
  button.ok.choose { border-color: var(--warning-color, #ffa600); color: var(--warning-color, #ffa600); }
  button.ok.open { border-color: var(--primary-color); color: var(--primary-color); }
  button.ok .twice { position: absolute; right: -6px; top: -7px; font-size: 10px; line-height: 1; padding: 2px 3px;
    border-radius: 6px; background: var(--primary-color); color: var(--text-primary-color, #fff); }
  /* The editor hangs under its row, as one card with it. */
  .item.open { border-radius: 8px 8px 0 0; }
  .ok-editor { display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: flex-end; margin: -6px 0 0; padding: 4px 12px 12px 42px;
    background: var(--secondary-background-color); border-radius: 0 0 8px 8px; border-top: 1px solid var(--divider-color); }
  .ok-editor .say { flex-basis: 100%; padding-top: 6px; font-weight: 500; }
  .ok-editor label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--secondary-text-color); }
  .ok-editor label.check { flex-direction: row; align-items: center; color: var(--primary-text-color); }
  .ok-editor label.wide, .ok-editor .hint, .ok-editor .error { flex-basis: 100%; }
  .ok-editor textarea { font-family: monospace; }
  select { font: inherit; color: var(--primary-text-color); background: var(--card-background-color);
    border: 1px solid var(--divider-color); border-radius: 6px; padding: 6px 8px; max-width: 100%; }
  .item { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 8px; background: var(--secondary-background-color); }
  .item.dragging { opacity: 0.4; }
  .item.over { outline: 2px solid var(--primary-color); }
  .handle { cursor: grab; color: var(--secondary-text-color); letter-spacing: -3px; user-select: none; padding: 0 2px; }
  .name { flex: 1; min-width: 0; display: flex; flex-direction: column; }
  .name > *, .name .sub { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .value { font-variant-numeric: tabular-nums; white-space: nowrap; color: var(--primary-text-color); }
  input, textarea { font: inherit; color: var(--primary-text-color); background: var(--card-background-color);
    border: 1px solid var(--divider-color); border-radius: 6px; padding: 6px 8px; box-sizing: border-box; }
  input.label { width: 150px; }
  .controls { display: flex; align-items: center; gap: 10px; }
  textarea { width: 100%; font-family: monospace; font-size: 13px; }
  .error { color: var(--error-color, #db4437); font-size: 13px; min-height: 1em; margin-top: 4px; }
  button { font: inherit; cursor: pointer; border-radius: 6px; padding: 6px 12px; border: 1px solid var(--divider-color);
    background: transparent; color: inherit; }
  button:disabled { opacity: 0.4; cursor: default; }
  button.primary { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: transparent; }
  .toolbar button.secondary { color: inherit; border-color: rgba(255,255,255,0.5); }
  button.icon { padding: 4px 8px; }
  .add { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; align-items: flex-start; }
  .search { flex: 1; min-width: 240px; }
  .search input { width: 100%; }
  .results { display: flex; flex-direction: column; max-height: 320px; overflow-y: auto; }
  .result { display: flex; align-items: center; gap: 10px; text-align: left; border: none; border-bottom: 1px solid var(--divider-color);
    border-radius: 0; padding: 8px; }
  .result:hover { background: var(--secondary-background-color); }
  .pad { padding: 8px; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; margin-bottom: 12px; }
  .slot { border-radius: 6px; padding: 14px 8px; text-align: center; background: #1b2430; color: #fff; font-size: 14px; }
  .slot.free { background: transparent; border: 1px dashed var(--divider-color); color: var(--secondary-text-color); }
  /* A phone on its side: pinning would take half the screen. */
  @media (max-height: 560px) { .top { position: static; } }
  @media (max-width: 600px) {
    .content { padding: 8px; }
    .pin { padding: 8px 8px 0; }
    .pin .card { padding: 10px 12px; }
    .tv { padding: 10px; }
    .toolbar button.secondary { display: none; }
    /* The name and value keep the first line; the label and the buttons go on a second, under the name. */
    .item:has(.controls) { flex-wrap: wrap; row-gap: 6px; }
    .item .controls { flex-basis: 100%; padding-left: 54px; box-sizing: border-box; }
    .item .controls input.label { flex: 1; width: auto; min-width: 0; }
  }
`;

customElements.define("tv-panel-settings", TvPanelSettings);
