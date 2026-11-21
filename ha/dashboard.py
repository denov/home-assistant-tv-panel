"""Create input_boolean.tv_widgets (and turn it on), input_text.tv_camera_order (left alone when it exists) and the
"TV Widgets" dashboard. Re-run it to update the dashboard.

Usage: python ha/dashboard.py <config.json> ha/card.md.j2
"""

import sys

from hass_ws import Hass

with open(sys.argv[2]) as f:
    card = f.read()
ha = Hass(sys.argv[1])

CAMERA_HELP = (
    "**Camera order:** camera names (or entity ids), separated by commas, left to right and top row first: "
    "`Front Door, Driveway, Back Yard`. Cameras not listed follow by name; leave it empty for all by name. "
    '"Camera - " is dropped from the front of names.'
)
WIDGET_HELP = (
    "**Add a widget:** open the entity → settings (cog) → *Labels* → **tv_widget**.\n\n"
    "**Remove one:** select its name above to open its settings, and take the label off.\n\n"
    "**Rename a chip:** rename the entity; the device name is dropped from the front on the TV.\n\n"
    "Weather comes first, then cameras (all in one chip that opens the camera grid), covers and locks, then the "
    "rest by device and name. Changes reach the TV within about 15 seconds."
)
# 1. the row switch, created on
helpers = ha.call(type="input_boolean/list")
if not any(h.get("name") == "tv_widgets" or h.get("id") == "tv_widgets" for h in helpers):
    h = ha.call(type="input_boolean/create", name="tv_widgets", icon="mdi:television")
    print("created helper", h)
ha.call(
    type="call_service",
    domain="input_boolean",
    service="turn_on",
    target={"entity_id": "input_boolean.tv_widgets"},
)
st = ha.call(type="get_states")
state = next((s["state"] for s in st if s["entity_id"] == "input_boolean.tv_widgets"), "missing")
print("input_boolean.tv_widgets =", state)
# 2. the camera grid's order: camera names or entity ids, comma-separated (empty: by name)
texts = ha.call(type="input_text/list")
if not any(h.get("id") == "tv_camera_order" or h.get("name") == "tv_camera_order" for h in texts):
    h = ha.call(type="input_text/create", name="tv_camera_order", icon="mdi:cctv", min=0, max=255, mode="text")
    print("created helper", h)
# 3. its own dashboard, so no existing dashboard is touched
dash = ha.call(type="lovelace/dashboards/list")
if any(d["url_path"] == "tv-widgets" for d in dash):
    print("dashboard tv-widgets exists; updating its config only")
else:
    ha.call(
        type="lovelace/dashboards/create",
        url_path="tv-widgets",
        title="TV Widgets",
        icon="mdi:television",
        show_in_sidebar=True,
        require_admin=False,
        mode="storage",
    )
    print("created dashboard tv-widgets")
config = {"views": [{"title": "TV Widgets", "path": "tv", "icon": "mdi:television", "cards": [
    {
        "type": "tile",
        "entity": "input_boolean.tv_widgets",
        "name": "Widget row on the TV",
        "icon": "mdi:television",
        "vertical": False,
        "tap_action": {"action": "toggle"},
        "icon_tap_action": {"action": "toggle"},
        "hold_action": {"action": "more-info"},
    },
    {"type": "markdown", "title": "Widgets on the TV", "content": card},
    {
        "type": "entities",
        "title": "Camera grid",
        "entities": [{"entity": "input_text.tv_camera_order", "name": "Order"}],
    },
    {"type": "markdown", "content": CAMERA_HELP},
    {"type": "markdown", "content": WIDGET_HELP},
]}]}
ha.call(type="lovelace/config/save", url_path="tv-widgets", config=config)
print("saved dashboard config")
ha.close()
