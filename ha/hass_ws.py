"""A small Home Assistant websocket client for the scripts in ha/."""

import json

import websocket


class Hass:
    """Connect with a config file holding {"url", "token"}, then call(type=..., ...) for each command."""

    def __init__(self, config_path: str, timeout: int = 15) -> None:
        with open(config_path) as f:
            cfg = json.load(f)
        self.ws = websocket.create_connection(cfg["url"].replace("http", "ws", 1) + "/api/websocket", timeout=timeout)
        self.ws.recv()
        self.ws.send(json.dumps({"type": "auth", "access_token": cfg["token"]}))
        if json.loads(self.ws.recv())["type"] != "auth_ok":
            raise SystemExit("Home Assistant refused the token")
        self.n = 0

    def call(self, **msg):
        self.n += 1
        msg["id"] = self.n
        self.ws.send(json.dumps(msg))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.n:
                if not r.get("success"):
                    raise SystemExit(f"{msg['type']}: {r.get('error')}")
                return r.get("result")

    def close(self) -> None:
        self.ws.close()
