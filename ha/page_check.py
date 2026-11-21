"""Check the TV Panel page in a real browser: load it the way a refresh does, three times, and fail if it stays blank.

    ha/.venv/bin/python ha/page_check.py <config.json> [--shot page.png]

<config.json> holds {"url", "token"} (an admin's long-lived token). Chrome runs headless with a throwaway profile,
signed in by putting the token where Home Assistant's frontend keeps its own; the profile is deleted afterwards. Set
CHROME to use another binary than google-chrome.

It exists because the page once rendered only when opened from the sidebar: Home Assistant set its properties before
the element was defined, and a refresh showed nothing. It also fails when the preview of the TV's row scrolls, is
clipped after a state change redraws it, or scrolls out of view.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.request

import websocket

PORT = 9223
PROBE = """(() => {
  const found = [];
  const walk = (root) => { for (const el of root.querySelectorAll('*')) {
    if (el.localName === 'tv-panel-settings') found.push(el);
    if (el.shadowRoot) walk(el.shadowRoot); } };
  walk(document);
  const el = found[0];
  const root = el && el.shadowRoot;
  return JSON.stringify({
    element: !!el,
    rendered: root ? root.innerHTML.length : 0,
    chips: root ? root.querySelectorAll('.item[data-list=chips]').length : 0,
    error: root ? (root.getElementById('status') || {}).textContent || '' : '',
    // The preview of the TV's row fits its box, as the launcher fits the row: it never scrolls.
    overflow: (() => { const tv = root && root.querySelector('.tv');
      return tv ? Math.max(0, Math.round(tv.scrollWidth - tv.clientWidth)) : -1; })(),
    tabs: root ? [...root.querySelectorAll('[role=tab]')].map((t) => t.textContent.trim()) : [],
  });
})()"""


SCROLL = """(() => {
  const walk = (root) => { for (const el of root.querySelectorAll('*')) {
    if (el.localName === 'tv-panel-settings') return el;
    if (el.shadowRoot) { const f = walk(el.shadowRoot); if (f) return f; } } return null; };
  const el = walk(document);
  el && el.shadowRoot.querySelector(%s)?.scrollIntoView({block: 'end'});
})()"""


# After the loads: redraw the preview as every state change in Home Assistant does, then scroll to the bottom. The row
# must still fit (it once clipped: measured before its icons drew) and stay in view while the chips are edited.
LIVE = """(async () => {
  const walk = (root) => { for (const el of root.querySelectorAll('*')) {
    if (el.localName === 'tv-panel-settings') return el;
    if (el.shadowRoot) { const f = walk(el.shadowRoot); if (f) return f; } } return null; };
  const el = walk(document);
  if (!el) return JSON.stringify({});
  const root = el.shadowRoot;
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const clip = () => Math.round(root.getElementById('preview').getBoundingClientRect().right
    - root.querySelector('.fit').getBoundingClientRect().right);
  el.live();
  await wait(1500);
  const clipped = clip();
  root.querySelector('.content').lastElementChild.scrollIntoView({block: 'end'});
  await wait(800);
  const tv = root.querySelector('.tv').getBoundingClientRect();
  return JSON.stringify({clipped, scrolled: Math.round(window.scrollY || document.scrollingElement.scrollTop),
    tvTop: Math.round(tv.top), tvBottom: Math.round(tv.bottom), height: innerHeight});
})()"""


class Page:
    """One Chrome tab over the DevTools protocol."""

    def __init__(self, ws_url: str) -> None:
        self.ws = websocket.create_connection(ws_url, timeout=30)
        self.n = 0

    def call(self, method: str, **params):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": method, "params": params}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def look(self, wait: float) -> dict:
        time.sleep(wait)
        return json.loads(self.call("Runtime.evaluate", expression=PROBE, returnByValue=True)["result"]["value"])


def _tab() -> str:
    for _ in range(50):
        try:
            targets = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/list", timeout=1).read())
            return next(t for t in targets if t["type"] == "page")["webSocketDebuggerUrl"]
        except (OSError, StopIteration, ValueError):
            time.sleep(0.2)
    raise SystemExit("Chrome didn't start")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("config")
    parser.add_argument("--path", default="/tv-panel")
    parser.add_argument("--wait", type=float, default=9, help="seconds to let the page load each time")
    parser.add_argument("--shot", help="save a screenshot of the last load here")
    parser.add_argument("--width", type=int, default=1280, help="window width, e.g. 400 for a phone")
    parser.add_argument("--show", help="scroll this CSS selector (inside the page) into view before the screenshot")
    parser.add_argument("--click", help="click this CSS selector (inside the page) before the screenshot, e.g. a tab")
    args = parser.parse_args()

    with open(args.config, encoding="utf-8") as f:
        cfg = json.load(f)
    url = cfg["url"].rstrip("/")
    profile = tempfile.mkdtemp(prefix="tv-panel-chrome-")
    chrome = subprocess.Popen(
        [
            os.environ.get("CHROME", "google-chrome"),
            "--headless=new",
            f"--remote-debugging-port={PORT}",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--disable-gpu",
            f"--window-size={args.width},900",
            "about:blank",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        page = Page(_tab())
        tokens = {
            "access_token": cfg["token"],
            "token_type": "Bearer",
            "expires_in": 1800,
            "hassUrl": url,
            "clientId": url + "/",
            "expires": int(time.time() * 1000) + 10 * 365 * 86_400_000,
            "refresh_token": "",
        }
        page.call("Page.enable")
        page.call(
            "Page.addScriptToEvaluateOnNewDocument",
            source=f"if (location.origin === {json.dumps(url)}) "
            f"localStorage.setItem('hassTokens', {json.dumps(json.dumps(tokens))});",
        )
        failed = False
        for label, step in (
            ("load", lambda: page.call("Page.navigate", url=url + args.path)),
            ("reload", lambda: page.call("Page.reload")),
            ("hard reload", lambda: page.call("Page.reload", ignoreCache=True)),
        ):
            step()
            seen = page.look(args.wait)
            ok = seen["element"] and seen["rendered"] > 0 and seen["overflow"] == 0
            failed |= not ok
            print(f"{'ok  ' if ok else 'FAIL'} {label}: {seen}")
        after = json.loads(
            page.call("Runtime.evaluate", expression=LIVE, awaitPromise=True, returnByValue=True)["result"]["value"]
        )
        ok = bool(after) and after["clipped"] <= 1 and after["tvTop"] >= 0 and after["tvBottom"] <= after["height"]
        failed |= not ok
        print(f"{'ok  ' if ok else 'FAIL'} after a state change and a scroll to the bottom: {after}")
        if args.shot:
            if args.click:
                page.call("Runtime.evaluate", expression=(SCROLL % json.dumps(args.click)).replace(
                    "?.scrollIntoView({block: 'end'})", "?.click()"))
                time.sleep(0.5)
            if args.show:
                page.call(
                    "Runtime.evaluate",
                    expression=SCROLL % json.dumps(args.show),
                )
                time.sleep(0.5)
            with open(args.shot, "wb") as f:
                f.write(base64.b64decode(page.call("Page.captureScreenshot", format="png")["data"]))
            print("screenshot", args.shot)
        page.ws.close()
        return 1 if failed else 0
    finally:
        chrome.terminate()
        chrome.wait(10)
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
