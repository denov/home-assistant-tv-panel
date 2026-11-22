"""Get a development Home Assistant ready for TV Panel: stand-in entities and a paired stand-in TV.

    ha/.venv/bin/python ha/dev_ha.py <config.json> [--tv-host host.docker.internal] [--save dev-tv.json] [--starter]
                                     [--throwaway]

<config.json> holds {"url", "token"} for an admin of a throwaway Home Assistant, such as a local Docker one. Never
point this at your real one: it writes states and pairs a TV that doesn't exist. It refuses any address but this
machine's (localhost, 127.0.0.1) unless you add --throwaway.

1. It sets stand-in states for the kinds of entity TV Panel draws (weather, a garage door, a gate, a lock, cameras,
   solar, Apex probes when there are none), three thermostats unlike each other (heat and cool with a range, heat
   only, and one that doesn't say what it's doing), and air quality sensors (CO2, PM2.5, a VOC index without a device
   class, and an AQI without a state class), so the settings page, its preview and the screens have something to
   show. They are plain states, not entities: they can't be toggled, and a restart clears them, so run it again.
   Temperatures are in this Home Assistant's own unit, as a real thermostat's are.
2. If no TV is paired, it pretends to be one: it answers the pairing calls the integration makes (/info and /pair, as
   the app's PairServer does) on --port, and walks the integration's config flow over the REST API. Home Assistant
   must be able to reach this machine as --tv-host: host.docker.internal from Docker Desktop. --save writes the TV's
   own {"url", "token"}, to call /api/tv_panel/config and /api/tv_panel/press as the TV does.
3. A TV with no saved settings gets starter chips from those stand-ins (--starter: every TV, replacing its settings).
"""

from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CODE = "123456"
# Home Assistants this may write to without --throwaway: one on this machine.
LOCAL = {"localhost", "127.0.0.1", "::1"}
TV = {"id": "dev-tv", "name": "Dev TV"}

STATES = {
    "weather.home": ("partlycloudy", {"friendly_name": "Home", "temperature": 52, "temperature_unit": "°F",
                                      "humidity": 68, "wind_speed": 3, "wind_speed_unit": "mph"}),
    "cover.garage_door": ("closed", {"friendly_name": "Garage Door", "device_class": "garage"}),
    "cover.front_gate": ("closed", {"friendly_name": "Front Gate", "device_class": "gate"}),
    "cover.hall_blind": ("open", {"friendly_name": "Hall Blind", "device_class": "blind"}),
    "lock.front_door": ("locked", {"friendly_name": "Front Door"}),
    "light.hall": ("on", {"friendly_name": "Hall"}),
    "script.good_night": ("off", {"friendly_name": "Good night"}),
    "camera.front_door": ("idle", {"friendly_name": "Front Door"}),
    "camera.driveway": ("idle", {"friendly_name": "Driveway"}),
    "binary_sensor.front_door_motion": ("off", {"friendly_name": "Front Door Motion", "device_class": "motion"}),
    "sensor.envoy_current_power_production": ("1.234", {"friendly_name": "Envoy Current power production",
                                                        "unit_of_measurement": "kW", "device_class": "power"}),
    "sensor.hall_temperature": ("68.4", {"friendly_name": "Hall Temperature", "unit_of_measurement": "°F",
                                         "device_class": "temperature"}),
}
# Air quality: one of each kind the screen treats differently.
AIR = {
    "sensor.den_co2": ("612", {"friendly_name": "Den CO2", "unit_of_measurement": "ppm",
                               "device_class": "carbon_dioxide", "state_class": "measurement"}),
    "sensor.den_pm25": ("8.2", {"friendly_name": "Den PM2.5", "unit_of_measurement": "µg/m³",
                                "device_class": "pm25", "state_class": "measurement"}),
    # A VOC index (Sensirion's, 1 to 500) has no device class: it shows, without a colour.
    "sensor.den_voc_index": ("112", {"friendly_name": "Den VOC index", "state_class": "measurement"}),
    # No state class: no long-term statistics, so its graph comes from history.
    "sensor.porch_aqi": ("42", {"friendly_name": "Porch AQI", "device_class": "aqi"}),
}
# Three thermostats unlike each other, in °F; converted when this Home Assistant is metric.
CLIMATE = {
    # Heat and cool, with a range, a fan and presets: like an ecobee or a Nest.
    "climate.upstairs": ("heat_cool", {
        "friendly_name": "Upstairs", "hvac_modes": ["off", "heat", "cool", "heat_cool"], "current_temperature": 71.5,
        "target_temp_low": 68, "target_temp_high": 76, "current_humidity": 41, "hvac_action": "cooling",
        "fan_mode": "auto", "fan_modes": ["auto", "on"], "preset_mode": "home",
        "preset_modes": ["home", "away", "sleep"], "min_temp": 45, "max_temp": 95, "target_temp_step": 1,
        "supported_features": 410}),
    # Heat only, as a generic_thermostat or a Z-Wave one is.
    "climate.hallway": ("heat", {
        "friendly_name": "Hallway Thermostat", "hvac_modes": ["off", "heat"], "current_temperature": 68,
        "temperature": 70, "hvac_action": "heating", "min_temp": 45, "max_temp": 90, "supported_features": 385}),
    # No hvac_action: plenty of integrations don't say what the system is doing.
    "climate.garage_heater": ("heat", {
        "friendly_name": "Garage Heater", "hvac_modes": ["off", "heat"], "current_temperature": 55, "temperature": 50,
        "min_temp": 40, "max_temp": 80, "supported_features": 385}),
}
TEMPERATURES = ("current_temperature", "temperature", "target_temp_low", "target_temp_high", "min_temp", "max_temp")


def climates(celsius: bool) -> dict:
    """The stand-in thermostats in this Home Assistant's unit: climate attributes are always in the system's."""
    if not celsius:
        return CLIMATE
    out = {}
    for entity_id, (state, attributes) in CLIMATE.items():
        a = dict(attributes)
        for key in TEMPERATURES:
            if key in a:
                a[key] = round((a[key] - 32) * 5 / 9 * 2) / 2
        if "target_temp_step" in a:
            a["target_temp_step"] = 0.5
        out[entity_id] = (state, a)
    return out


# Only when the Home Assistant has no Apex of its own.
APEX = {
    "sensor.apex_temp": ("78.9", {"friendly_name": "Apex Temp", "unit_of_measurement": "°F",
                                  "apex_probe_type": "Temp"}),
    "sensor.apex_ph": ("8.21", {"friendly_name": "Apex pH", "apex_probe_type": "pH"}),
    "sensor.apex_salt": ("34.9", {"friendly_name": "Apex Salinity", "unit_of_measurement": "ppt",
                                  "apex_probe_type": "Cond"}),
}


class Ha:
    def __init__(self, url: str, token: str) -> None:
        self.url, self.token = url.rstrip("/"), token

    def call(self, method: str, path: str, body: dict | None = None):
        req = urllib.request.Request(
            self.url + path,
            data=None if body is None else json.dumps(body).encode(),
            method=method,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path}: {e.code} {e.read().decode()[:300]}") from e


def seed(ha: Ha) -> None:
    states = ha.call("GET", "/api/states")
    have_apex = any("apex_probe_type" in s["attributes"] for s in states)
    celsius = ha.call("GET", "/api/config")["unit_system"]["temperature"] == "°C"
    todo = {**STATES, **climates(celsius), **AIR, **({} if have_apex else APEX)}
    for entity_id, (state, attributes) in todo.items():
        ha.call("POST", f"/api/states/{entity_id}", {"state": state, "attributes": attributes})
    print(f"set {len(todo)} stand-in states" + (" (this Home Assistant has its own Apex)" if have_apex else ""))


class _Tv(BaseHTTPRequestHandler):
    paired: dict | None = None

    def _answer(self, status: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        self._answer(200, TV) if self.path == "/info" else self._answer(404, {})

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        if self.path != "/pair":
            return self._answer(404, {})
        if body.get("code") != CODE:
            return self._answer(403, {"error": "invalid_code"})
        type(self).paired = body
        self._answer(200, {"name": TV["name"]})

    def log_message(self, *args) -> None:
        pass


def pair(ha: Ha, tv_host: str, port: int, save: str | None) -> None:
    entries = ha.call("GET", "/api/config/config_entries/entry?domain=tv_panel")
    if entries:
        print(f"a TV is paired already: {entries[0]['title']}")
        return
    server = HTTPServer(("0.0.0.0", port), _Tv)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        flow = ha.call("POST", "/api/config/config_entries/flow", {"handler": "tv_panel"})
        # WSL forwards a port that just opened only after a moment (Docker Desktop reaches this machine that way), so
        # the first tries can fail.
        for _ in range(10):
            step = ha.call("POST", f"/api/config/config_entries/flow/{flow['flow_id']}", {"host": f"{tv_host}:{port}"})
            if step.get("step_id") == "pair":
                break
            time.sleep(1)
        else:
            ha.call("DELETE", f"/api/config/config_entries/flow/{flow['flow_id']}")
            raise SystemExit(f"Home Assistant didn't reach the stand-in TV at {tv_host}:{port}: {step.get('errors')}")
        done = ha.call("POST", f"/api/config/config_entries/flow/{flow['flow_id']}", {"code": CODE})
        if done.get("type") != "create_entry":
            raise SystemExit(f"Pairing failed: {done.get('errors') or done}")
    finally:
        server.shutdown()
    print(f"paired {done['title']}")
    if save and _Tv.paired:
        fd = os.open(save, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"url": ha.url, "token": _Tv.paired["token"]}, f)
        print(f"the TV's own url and token: {save}")


STARTER = {
    "chips": [
        {"type": "entity", "entity": "weather.home"},
        {"type": "entity", "entity": "cover.garage_door"},
        {"type": "entity", "entity": "cover.front_gate"},
        {"type": "entity", "entity": "climate.hallway", "label": "Hall"},
        {"type": "cameras"},
        {"type": "tank"},
        {"type": "entity", "entity": "sensor.envoy_current_power_production"},
    ],
    "cameras": ["camera.front_door", "camera.driveway"],
    "tank": [],
    "solar": None,
    "screens": [
        {"id": "weather", "type": "weather"},
        {"id": "solar", "type": "solar"},
        {"id": "tank", "type": "tank"},
        {"id": "cameras", "type": "cameras"},
        {"id": "thermostat", "type": "thermostat", "title": "Thermostats",
         "climates": ["climate.upstairs", "climate.hallway", "climate.garage_heater"],
         "sensors": ["sensor.hall_temperature"]},
        {"id": "air_quality", "type": "air_quality", "title": "Air",
         "sensors": ["sensor.den_co2", "sensor.den_pm25", "sensor.den_voc_index", "sensor.porch_aqi"]},
    ],
}


def settle(config: str, force: bool = False) -> None:
    """Starter chips for a TV with no saved settings (or any, with force), so the preview has a row to show."""
    from hass_ws import Hass

    ws = Hass(config)
    try:
        for tv in ws.call(type="tv_panel/tvs"):
            if not force and ws.call(type="tv_panel/settings", entry_id=tv["entry_id"]).get("saved"):
                print(f"{tv['title']} has settings already")
                continue
            # The tank chip shows the probes it's given: the first of each kind, as the owner would pick them.
            probes: dict[str, str] = {}
            for st in sorted(ws.call(type="get_states"), key=lambda st: st["entity_id"]):
                kind = st["attributes"].get("apex_probe_type")
                if kind in ("Temp", "pH", "Cond", "alk") and kind not in probes:
                    probes[kind] = st["entity_id"]
            ws.call(type="tv_panel/save", entry_id=tv["entry_id"], settings={**STARTER, "tank": list(probes.values())})
            print(f"saved starter chips for {tv['title']}")
    finally:
        ws.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("config")
    parser.add_argument("--tv-host", default="host.docker.internal", help="how Home Assistant reaches this machine")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--save", help="write the stand-in TV's {url, token} here")
    parser.add_argument("--starter", action="store_true", help="replace the TVs' saved settings with the starter chips")
    parser.add_argument("--throwaway", action="store_true", help="the Home Assistant isn't on this machine, but is one")
    args = parser.parse_args()
    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)
    host = urllib.parse.urlsplit(cfg["url"]).hostname
    if host not in LOCAL and not args.throwaway:
        raise SystemExit(
            f"{host} isn't this machine. This writes stand-in states over real entities' ids and pairs a TV that "
            "doesn't exist: only for a throwaway Home Assistant. Add --throwaway if that's what it is."
        )
    ha = Ha(cfg["url"], cfg["token"])
    seed(ha)
    pair(ha, args.tv_host, args.port, args.save)
    settle(args.config, args.starter)


if __name__ == "__main__":
    main()
