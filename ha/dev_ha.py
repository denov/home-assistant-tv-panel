"""Get a development Home Assistant ready for TV Panel: stand-in entities and a paired stand-in TV.

    ha/.venv/bin/python ha/dev_ha.py <config.json> [--tv-host host.docker.internal] [--save dev-tv.json] [--starter]
                                     [--throwaway]

<config.json> holds {"url", "token"} for an admin of a throwaway Home Assistant, such as a local Docker one. Never
point this at your real one: it writes states and pairs a TV that doesn't exist. It refuses any address but this
machine's (localhost, 127.0.0.1) unless you add --throwaway.

1. It sets stand-in states for the kinds of entity TV Panel draws (weather, a garage door, a gate, a lock, a
   thermostat, cameras, solar, Apex probes when there are none), so the settings page and its preview have something
   to show. They are plain states, not entities: they can't be toggled, and a restart clears them, so run it again.
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
    "climate.hallway": ("heat", {"friendly_name": "Hallway Thermostat", "current_temperature": 68, "temperature": 70,
                                 "hvac_action": "heating", "temperature_unit": "°F"}),
    "camera.front_door": ("idle", {"friendly_name": "Front Door"}),
    "camera.driveway": ("idle", {"friendly_name": "Driveway"}),
    "binary_sensor.front_door_motion": ("off", {"friendly_name": "Front Door Motion", "device_class": "motion"}),
    "sensor.envoy_current_power_production": ("1.234", {"friendly_name": "Envoy Current power production",
                                                        "unit_of_measurement": "kW", "device_class": "power"}),
    "sensor.hall_temperature": ("68.4", {"friendly_name": "Hall Temperature", "unit_of_measurement": "°F",
                                         "device_class": "temperature"}),
}
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
    todo = {**STATES, **({} if have_apex else APEX)}
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
