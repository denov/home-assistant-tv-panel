// The TV Panel page in Home Assistant's sidebar: what each TV shows. The logic is in chips.js (tested in Node).
// Same version stamp as this file (panel.py sets it from the files' dates), so an update is never half cached.
const chips = await import(new URL(`./chips.js${new URL(import.meta.url).search}`, import.meta.url).href);

const TONES = { warm: "#ff9f43", sky: "#cfe3f2", amber: "#ffc94a", blue: "#2aa7e0", off: "#8a8f98", alarm: "#ff5252" };
const GROUP_NAMES = { cameras: "Cameras", tank: "Tank" };

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

class TvPanelSettings extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this.s = null; // { chips, cameras, tank, solarText, saved }
    this.tvs = [];
    this.entryId = null;
    this.dirty = false;
    this.status = "";
    this.queries = { chips: "", cameras: "", tank: "" };
    this.drag = null;
    this.started = false;
  }

  set hass(hass) {
    this._hass = hass;
    if (!this.started) {
      this.started = true;
      this.load();
    } else {
      this.live();
    }
    const menu = this.shadowRoot.querySelector("ha-menu-button");
    if (menu) menu.hass = hass;
  }

  get hass() {
    return this._hass;
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
          saved: r.saved,
        };
      }
      this.dirty = false;
      this.status = "";
    } catch (e) {
      this.status = `Couldn't load the settings: ${e.message || e.code || e}`;
    }
    this.render();
  }

  async save() {
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
      <div class="toolbar">
        <ha-menu-button></ha-menu-button>
        <div class="title">TV Panel</div>
        ${this.tvs.length > 1 ? `<select data-action="tv">${this.tvs.map((t) =>
          `<option value="${esc(t.entry_id)}" ${t.entry_id === this.entryId ? "selected" : ""}>${esc(t.title)}</option>`).join("")}</select>` : ""}
        <span class="grow"></span>
        <button class="secondary" data-action="revert" ${this.dirty ? "" : "disabled"}>Undo changes</button>
        <button class="primary" data-action="save" ${this.dirty || (this.s && !this.s.saved) ? "" : "disabled"}>Save</button>
      </div>
      <div class="content">${this.s ? this.body() : this.empty()}</div>`;
    const menu = root.querySelector("ha-menu-button");
    if (menu) {
      menu.hass = this._hass;
      menu.narrow = this._narrow;
    }
    this.renderPreview();
    this.renderStatus();
    for (const list of Object.keys(this.queries)) this.renderResults(list);
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

  body() {
    const s = this.s;
    const has = (t) => s.chips.some((c) => c.type === t);
    return `
      <div class="status" id="status"></div>
      ${s.saved ? "" : `<div class="note">Not saved yet. This is the TV's current setup, from the <b>tv_widget</b> label and the
        camera order helper. Save to make it the TV's settings.</div>`}
      <div class="card">
        <h2>On the TV</h2>
        <div class="tv"><div class="row" id="preview"></div></div>
      </div>

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
        <h2>Cameras</h2>
        <p class="hint">The cameras the Cameras chip opens, in grid order: left to right, top row first.</p>
        <div class="grid">${[0, 1, 2, 3, 4, 5].map((i) => `<div class="slot ${s.cameras[i] ? "" : "free"}">${
          s.cameras[i] ? esc(chips.defaultName(this._hass, s.cameras[i]).replace(/^Camera\s*[-:]\s*/i, "")) : "empty"}</div>`).join("")}</div>
        ${s.cameras.length > 6 ? `<p class="hint">Only the first six fit the grid; Left and Right in the live view reach the rest.</p>` : ""}
        <div class="list">${s.cameras.map((e, i) => this.memberRow("cameras", e, i)).join("")}</div>
        <div class="add">${this.searchBox("cameras", "Add a camera")}</div>
      </div>

      <div class="card">
        <h2>Tank</h2>
        <p class="hint">The Apex readings on the tank chip, one column each, in this order.</p>
        <div class="list">${s.tank.map((e, i) => this.memberRow("tank", e, i)).join("")}</div>
        <div class="add">${this.searchBox("tank", "Add an Apex probe")}</div>
      </div>

      <div class="card">
        <h2>Solar panel layout</h2>
        <p class="hint">Optional. Where each inverter sits on the roof, as JSON:
          <code>{"arrays": [{"name": "Array 1", "panels": [{"id": "inverter serial", "x": 0, "y": 0, "w": 2, "h": 1}]}]}</code>.
          Empty shows the panels in rows.</p>
        <textarea data-action="solar" data-focus="solar" spellcheck="false" rows="10">${esc(s.solarText)}</textarea>
        <div class="error" id="solar-error"></div>
      </div>`;
  }

  chipRow(c, i) {
    const p = chips.preview(this._hass, c, this.s);
    const isEntity = c.type === "entity";
    const name = isEntity ? chips.defaultName(this._hass, c.entity) : GROUP_NAMES[c.type];
    const sub = isEntity ? c.entity : c.type === "cameras" ? `${this.s.cameras.length} cameras` : `${this.s.tank.length} readings`;
    return `<div class="item" draggable="true" data-list="chips" data-index="${i}">
      <span class="handle" title="Drag to move">⋮⋮</span>
      <ha-icon icon="${esc(p.icon)}" style="color:${TONES[p.tone]}"></ha-icon>
      <div class="name"><div>${esc(name)}</div><div class="sub">${esc(sub)}</div></div>
      ${isEntity ? `<div class="value" data-live="${esc(c.entity)}">${esc(chips.stateText(this._hass, c.entity))}</div>` : ""}
      ${c.type === "tank" ? "" : `<input class="label" data-action="label" data-index="${i}" data-focus="label-${i}"
        placeholder="${esc(p.label || name)}" value="${esc(c.label || "")}" aria-label="Label on the TV">`}
      ${this.arrows("chips", i, this.s.chips.length)}
    </div>`;
  }

  memberRow(list, e, i) {
    return `<div class="item" draggable="true" data-list="${list}" data-index="${i}">
      <span class="handle" title="Drag to move">⋮⋮</span>
      <div class="name"><div>${esc(chips.defaultName(this._hass, e))}</div><div class="sub">${esc(e)}</div></div>
      <div class="value" data-live="${esc(e)}">${esc(chips.stateText(this._hass, e))}</div>
      ${this.arrows(list, i, this.s[list].length)}
    </div>`;
  }

  arrows(list, i, n) {
    return `<button class="icon" data-action="up" data-list="${list}" data-index="${i}" ${i === 0 ? "disabled" : ""} title="Move up">↑</button>
      <button class="icon" data-action="down" data-list="${list}" data-index="${i}" ${i === n - 1 ? "disabled" : ""} title="Move down">↓</button>
      <button class="icon" data-action="remove" data-list="${list}" data-index="${i}" title="Remove">✕</button>`;
  }

  searchBox(list, placeholder) {
    return `<div class="search"><input type="search" data-action="search" data-list="${list}" data-focus="search-${list}"
      placeholder="${esc(placeholder)}" value="${esc(this.queries[list])}"><div class="results" id="results-${list}"></div></div>`;
  }

  results(list) {
    const q = this.queries[list];
    if (!q.trim() && list === "chips") return [];
    if (list === "chips") return chips.search(this._hass, q, this.s.chips.filter((c) => c.type === "entity").map((c) => c.entity));
    if (list === "cameras") return chips.search(this._hass, q, this.s.cameras, "camera");
    return chips.search(this._hass, q, this.s.tank, null, chips.isProbe);
  }

  renderResults(list) {
    const box = this.shadowRoot.getElementById(`results-${list}`);
    if (!box) return;
    const focused = this.shadowRoot.activeElement?.dataset?.focus === `search-${list}`;
    const found = focused || this.queries[list] ? this.results(list) : [];
    box.innerHTML = found.map((f) => `<button class="result" data-action="add" data-list="${list}" data-id="${esc(f.entity_id)}">
      <span class="name"><span>${esc(f.name)}</span><span class="sub">${esc(f.entity_id)}</span></span>
      <span class="value">${esc(f.value)}</span></button>`).join("")
      || (this.queries[list].trim() ? `<div class="sub pad">Nothing matches.</div>` : "");
  }

  renderPreview() {
    const box = this.shadowRoot.getElementById("preview");
    if (!box || !this.s) return;
    box.innerHTML = this.s.chips.slice(0, 8).map((c) => {
      const p = chips.preview(this._hass, c, this.s);
      const icon = `<ha-icon icon="${esc(p.icon)}" style="color:${TONES[p.tone]}"></ha-icon>`;
      if (p.columns) {
        return `<div class="chip">${icon}${p.columns.map(([l, v]) =>
          `<div class="col"><div class="l">${esc(l)}</div><div class="v">${esc(v)}</div></div>`).join("")}</div>`;
      }
      if (!p.value) return `<div class="chip only" title="${esc(p.label)}">${icon}</div>`;
      return `<div class="chip">${icon}<div class="col"><div class="l">${esc(p.label)}</div><div class="v">${esc(p.value)}</div></div></div>`;
    }).join("") || `<div class="sub">No chips: the row is empty.</div>`;
    if (this.s.chips.length > 8) box.insertAdjacentHTML("beforeend", `<div class="sub">The TV shows the first 8.</div>`);
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

  // ---- events ---------------------------------------------------------------------------------------------------

  wire() {
    this.wired = true;
    const root = this.shadowRoot;
    root.addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-action]");
      if (!b || b.disabled) return;
      const { action, list } = b.dataset;
      const i = Number(b.dataset.index);
      if (action === "save") this.save();
      else if (action === "revert") this.load(this.entryId);
      else if (action === "up" || action === "down") {
        this.s[list] = chips.move(this.s[list], i, action === "up" ? i - 1 : i + 1);
        this.changed();
      } else if (action === "remove") {
        this.s[list] = this.s[list].filter((_, j) => j !== i);
        this.changed();
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
        } else this.s[list].push(id);
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
      } else if (action === "solar") {
        this.s.solarText = el.value;
        const r = chips.parseSolar(el.value);
        root.getElementById("solar-error").textContent = r.ok ? "" : r.error;
        this.changed(false);
      }
    });
    root.addEventListener("focusin", (ev) => {
      if (ev.target.dataset?.action === "search") this.renderResults(ev.target.dataset.list);
    });
    root.addEventListener("change", (ev) => {
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
      this.s[this.drag.list] = chips.move(this.s[this.drag.list], this.drag.from, Number(item.dataset.index));
      this.drag = null;
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
  .hint, .sub { color: var(--secondary-text-color); font-size: 13px; }
  .hint { margin: 0 0 12px; }
  code { font-size: 12px; word-break: break-all; }
  .note { background: rgba(255, 201, 74, 0.15); border: 1px solid rgba(255, 201, 74, 0.5); border-radius: 8px; padding: 10px 12px;
    margin-bottom: 16px; font-size: 14px; }
  .status { display: none; border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; background: var(--secondary-background-color); }
  .status.shown { display: block; }
  .tv { background: radial-gradient(ellipse at 40% 30%, #1d4d1d, #071207 70%); border-radius: 10px; padding: 18px; overflow-x: auto; }
  .row { display: flex; gap: 10px; align-items: stretch; min-height: 52px; }
  .chip { display: flex; align-items: center; gap: 12px; padding: 8px 14px; border-radius: 26px; background: rgba(255,255,255,0.08);
    color: #fff; white-space: nowrap; }
  .chip.only { padding: 8px 12px; }
  .chip .col .l { font-size: 11px; color: rgba(255,255,255,0.7); }
  .chip .col .v { font-size: 15px; }
  .tv .sub { color: rgba(255,255,255,0.6); align-self: center; }
  .list { display: flex; flex-direction: column; gap: 6px; }
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
  @media (max-width: 600px) {
    .content { padding: 8px; }
    input.label { width: 100px; }
    .toolbar button.secondary { display: none; }
  }
`;

customElements.define("tv-panel-settings", TvPanelSettings);
