# TV Panel

Brought to you by [Fullstack Systems](https://fullstacksystems.com).

<a href="https://www.buymeacoffee.com/denov" target="_blank" rel="noreferrer noopener"><img src="https://img.buymeacoffee.com/button-api/?text=Buy%20me%20a%20coffee&emoji=&slug=denov&button_colour=FFDD00&font_colour=000000&font_family=Bree&outline_colour=000000&coffee_colour=ffffff" alt="Buy me a coffee" height="40"></a>

Home Assistant on an Android TV's home screen: a row of live chips under the clock (weather, cameras, garage door, a
reef tank, solar), full pages for weather, solar, the tank and cameras that automations and voice can open, and
messages from Home Assistant on the screen.

The reef tank chip and page are made for our [Neptune Apex integration](https://github.com/denov/home-assistant-apex-neptune): with it installed, the tank's
probes, Trident results, dosing and alarm show up on the TV with nothing to set up, and its feed buttons can be
pressed from the remote (see [What OK does](#what-ok-does)).

Two parts, both in this repo:

- **The TV Panel integration** for Home Assistant (`custom_components/tv_panel`). It pairs the TV: the TV shows a
  code, you type it into Home Assistant, and the TV gets its own local-only user and token. Removing the integration
  signs the TV out.
- **The TV Panel app** for Android TV / Google TV (`app/`, package `com.fullstacksystems.tvpanel`). It draws the pages,
  and serves the chips to the launcher.

The pages work with any launcher. The **chip row** needs a launcher that reads TV Panel's widget provider:
[TV Panel Launcher](https://github.com/denov/tv-panel-launcher), our fork of the
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher (see [Launcher](#launcher)).

## Installation

### Installation via HACS

[![Open your Home Assistant instance and open a repository inside the Home Assistant Community Store.](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=denov&repository=home-assistant-tv-panel&category=Integration)

1. Use the button above to open the repository in HACS. HACS offers to add it as a custom repository. You can also do
   that by hand: **HACS** → **⋮** → **Custom repositories**, paste `https://github.com/denov/home-assistant-tv-panel`
   and choose type **Integration**.
1. Download **TV Panel** in HACS.
1. Restart Home Assistant.
1. Install the app on the TV (see [The app on the TV](#the-app-on-the-tv)) and open it. It shows a six-digit code and
   the TV's address.
1. Set up the integration from the UI, type the address the TV shows, then enter the code. (The app also announces
   itself over mDNS while it's open, so Home Assistant may offer the TV under **Settings** → **Devices & services**;
   that doesn't work on every TV and network yet.)

<a href="https://my.home-assistant.io/redirect/config_flow_start/?domain=tv_panel" target="_blank" rel="noreferrer noopener"><img src="https://my.home-assistant.io/badges/config_flow_start.svg" alt="Add Integration"></a>

### Manual Installation

1. Download `tv_panel.zip` from the [latest release](https://github.com/denov/home-assistant-tv-panel/releases/latest).
2. Unzip it into `custom_components/tv_panel` in your Home Assistant configuration directory.
3. Restart Home Assistant.
4. Open the app on the TV and set up the integration using the UI, as above.

### The app on the TV

Download `tv-panel-<version>.apk` from the [latest release](https://github.com/denov/home-assistant-tv-panel/releases/latest)
and install it, either over ADB (`adb install -r tv-panel-<version>.apk`) or on the TV with a downloader or file
manager app that you've allowed to install unknown apps in the TV's settings. Use the same version as the
integration. Or build it yourself (see [Build](#build)).

The release APK and a self-built one are signed with different keys, and Android won't install one over the other.
To switch, uninstall the app first and pair it again.

### Pairing

Home Assistant gives the TV its local URL (Settings > System > Network). If that's a name the TV can't look up, such
as `homeassistant.local`, the TV uses the address the pairing came from instead.

## Configure in Home Assistant

Choose **TV Panel** in the sidebar (admins only). With more than one TV, pick it at the top. The page shows a preview
of the TV's row, live from your Home Assistant, and these settings:

| Section | What it does |
|---|---|
| **Chips** | What the TV's home screen shows, in order. Drag a chip, or use the arrows. Search shows each entity's current value, so it's easy to pick the right one. A chip's **label** replaces the entity's name on the TV. |
| **Cameras** | The cameras the Cameras chip opens, in grid order: left to right, top row first. |
| **Tank** | The Apex readings on the tank chip, one column each, in this order. |
| **Solar panel layout** | Optional: where each inverter sits on the roof (see [Solar panel layout](#solar-panel-layout)). |

Adding a camera or an Apex probe as a chip puts it in its group, and adds the group's chip if it isn't there yet.
A thermostat (a `climate` entity) shows where it is and where it's set: "68° → 70°", or "69° → 69–78°" for a range.
Without a label, a chip uses the entity's name, with the device name dropped from the front ("Apex Temp" → "Temp").
Press **Save**; the TV picks it up within about 15 seconds. **Configure** on the integration links to the same page.

### What OK does

Under each chip, the page says what OK on it does: "OK opens the weather page", "OK twice opens or closes Garage
Door", "OK does nothing". **Change** sets it:

| On OK | |
|---|---|
| **Default** | Weather, solar, the tank and the cameras open their pages. A garage door, gate or door (a cover) opens or closes when OK is pressed twice. Anything else, locks included, does nothing. |
| **Do nothing** | The chip only shows its value. |
| **Open a page** | Weather, solar, the tank, the camera grid, or one camera full screen. |
| **Toggle something** | A light, switch, fan, cover, lock and so on: the chip's own entity or another. |
| **Run a script or scene** | |
| **Perform an action** | Any action, such as `light.turn_on`, with an optional target and data (JSON). |

Toggles, scripts and actions can ask first: **Press OK twice** makes the TV show "Press OK again" and wait 3 seconds
for the second press. The TV doesn't call these itself: it tells the integration which chip was pressed, and the
integration does what the chip's settings say, as the TV's user, then shows the result on the TV ("Garage Door:
opening").

For example, with the [Neptune Apex integration](https://github.com/denov/home-assistant-apex-neptune), feed the tank from the couch: set the Tank chip to
**Perform an action**, `button.press` with the target `button.apex_feed_a` (your Feed A button), and tick **Press OK
twice**, since a feed cycle may run an auto feeder. One OK on the TV asks; the second starts Feed A, and the TV says so.
The chip still shows the readings, and the tank page still opens
[by voice](#by-voice-assist).

Every press also fires the event `tv_panel_chip_pressed`, with `chip` (the entity id, or `cameras` / `apex` for the
groups), `tv`, `entry_id` and `action`, so an automation can act on any chip, even one set to do nothing:

```yaml
triggers:
  - trigger: event
    event_type: tv_panel_chip_pressed
    event_data:
      chip: sensor.hall_temperature
```

The TV's device also has a **Widget row** switch: off hides the row. The page has it at the top, and automations can
flip it like any switch.

The first time, the page starts from the older setup: entities labelled **tv_widget**, and
`input_text.tv_camera_order`. The switch starts from `input_boolean.tv_widgets`. Until you save, the app keeps using
those, and it uses them too on a TV set up without the integration (see [Helper scripts](#helper-scripts)).

The **Solar** chip (an Enphase Envoy power sensor) opens a solar page:
- **Now:** grid, solar, battery and home, each with today's kWh.
- **Panels:** every panel on your own layout. Left, Right or OK switches between watts now and kWh today.
- **Today:** a chart per 15 minutes.
- **Totals:** today, this week and this month.

The **Tank** chip (Neptune Apex probes from the [Neptune Apex integration](https://github.com/denov/home-assistant-apex-neptune): temperature, pH, salinity and Trident alkalinity, calcium and magnesium, merged into one chip with a column per reading; the fish turns red while the Apex alarm is on) opens a reef tank page:
- **Water:** both probes of each kind.
- **Trident:** the latest results.
- **Reagents & dosing:** reagents with tests left, waste % full, and DOS containers.
- **Trends:** temperature, pH and salinity over 24 hours; alkalinity, calcium and magnesium over 7 days.

It finds the probes by the `apex_probe_type` attribute the Neptune Apex integration gives them, and the Trident,
DOS and alarm by that integration's entity names, so there's nothing to set up.

**Cameras:** add cameras on the page and they become one icon-only chip. It shows a camera icon, or an
orange motion icon while any of their devices' motion sensors is on. OK opens a 3×2 grid:
- Each tile is a snapshot refreshed about every second.
- Slots without a camera are drawn empty.
- OK on a tile opens it full screen and live, from HA's MJPEG stream.
- Full screen, Left and Right go to the other cameras, and Back returns to the grid.

**Garage door:** add a garage cover as a chip and it's an icon-only chip: blue when shut, orange when open or
moving, grey when Home Assistant can't see it. OK asks "Press OK again to open the garage"; a second OK within 3 s
calls `cover.toggle`.

Set the grid's order with an optional text helper, `input_text.tv_camera_order`. It takes camera names or entity
IDs separated by commas, for example `Front Door, Driveway, Back Yard`, filled left to right with the top row first.
Cameras it doesn't list follow by name, and without the helper they all go by name. `ha/dashboard.py` creates it and
puts it on the TV Widgets dashboard. "Camera - " is dropped from the front of names.

The panel layout is set in Configure (see [Solar panel layout](#solar-panel-layout)). Without one, the panels are
shown in rows.

Order: weather first, then cameras, covers and locks, then everything else grouped by device and sorted by name. At most 8 chips;
the row shrinks to fit the screen. Weather chips open the weather page (now, next 12 hours, 5 days) from the
entity's `weather.get_forecasts`. Changes show up on the TV within about 15 seconds.

## Build

JDK 17, Android SDK platform 34, build-tools 34. `install.sh` sideloads over ADB with `tools/tv.py` from a
[TV Panel Launcher](https://github.com/denov/tv-panel-launcher) checkout next to this one (or a CleanYourTV one). A
build here is signed with your machine's debug key; only the release workflow has the release key.

```bash
./gradlew testReleaseUnitTest assembleRelease      # JVM tests and the APK
TV_HOST=<tv ip> ./install.sh                       # install or update the app
TV_HOST=<tv ip> ./install.sh path/to/config.json   # without the integration: {"url": "http://<ha ip>:8123", "token": "..."}
```

Pairing through the integration replaces the config file. If you use one anyway, keep it out of git; the app copies
it into its own storage and deletes the copy on the TV.

## Launcher

The app publishes its chips at `content://com.fullstacksystems.tvpanel.widgets/widgets`, with the columns `key`,
`label`, `value`, `icon` (a 72 px PNG) and `open` (0 or 1). Only [TV Panel Launcher](https://github.com/denov/tv-panel-launcher)
(`com.fullstacksystems.tvhome`), CleanYourTV's launcher (`com.gathin.tvhome`) and the ADB shell get rows. A launcher
draws them like this:

- A row with an empty value is its icon alone.
- A label and value that are tab-separated lists of the same length are drawn as columns, each label over its value.
- OK on a chip with `open = 1` sends `com.gathin.tvhome.action.OPEN_WIDGET` to this app, with the chip's `key` as an
  extra. TV Panel opens the page, or carries out the chip's action (see [What OK does](#what-ok-does)).

The app only fetches while the launcher asks, at most every 12 s, and has no background service.

## Solar panel layout

Paste it into the page's **Solar panel layout** box, as JSON:

```json
{"arrays": [{"name": "Array 1", "panels": [{"id": "<inverter serial>", "x": 0, "y": 0, "w": 2, "h": 1}]}]}
```

Positions are in grid units: portrait is `w 1, h 2` (the default) and landscape is `w 2, h 1`. Arrays without
`x`/`y` sit left to right with their tops aligned. Inverters that aren't listed go in an "Other" group. A layout in a
pushed `config.json` (`"solar": {...}`) is used when the page has none.

## Helper scripts

For a setup without the integration, `ha/` has small scripts that use Home Assistant's websocket API:

```bash
python3 -m venv ha/.venv && ha/.venv/bin/pip install -r ha/requirements.txt
ha/.venv/bin/python ha/label.py <config.json> sensor.foo weather.home    # add the tv_widget label
ha/.venv/bin/python ha/label.py <config.json> -cover.bar                 # take it off
ha/.venv/bin/python ha/dashboard.py <config.json> ha/card.md.j2          # (re)build the "TV Widgets" dashboard
```

`dashboard.py` creates `input_boolean.tv_widgets` (and turns it on), and `input_text.tv_camera_order` when it's
missing. It overwrites the `tv-widgets` dashboard.

Icons: [Material Design Icons](https://pictogrammers.com/library/mdi/) (Pictogrammers Free License).

## Open a page from Home Assistant

`tv_panel.show` opens a page on the TV from any automation or script: `weather`, `solar`, `tank`, `cameras` (the
grid) or `camera` (one camera full screen, with `camera`). For example, show the front door when the doorbell rings:

```yaml
triggers:
  - trigger: state
    entity_id: binary_sensor.doorbell
    to: "on"
actions:
  - action: tv_panel.show
    data:
      page: camera
      camera: camera.front_door
```

A TV that's off stays off, unless you add `wake: true`. If the screensaver is showing, it's closed so the page can be
seen. With more than one TV, pick them with `device_id`; without it, every TV shows the page.

It goes through Home Assistant's **Android TV Remote** integration, set up for the same TV (the address you paired
TV Panel with), so add that first.

### By voice (Assist)

Make a script that calls `tv_panel.show`, expose it to Assist, and give it aliases such as "show weather" (Settings >
Voice assistants > Expose):

```yaml
alias: TV Weather
sequence:
  - action: tv_panel.show
    data:
      page: weather
      wake: true
```

Saying the alias runs it. An alias has to be said word for word. For looser wording, such as "show me the weather
forecast", add an automation with a sentence trigger, `show [me] [the] weather [forecast]`, that runs the script. A
new command replaces the page already showing, and Back returns to what was there before.

The pages are also deep links (`tvpanel://weather`, `tvpanel://solar`, `tvpanel://apex`, `tvpanel://cameras` and
`tvpanel://cameras/<camera entity id>`), which `remote.turn_on` on the Android TV Remote entity opens with
`activity: tvpanel://weather`.

## Messages on the TV

Each TV has a notify entity, named after the TV. `notify.send_message` shows its message on the TV, over whatever is
playing, for a few seconds:

```yaml
actions:
  - action: notify.send_message
    target:
      entity_id: notify.living_room_tv
    data:
      title: Garage
      message: The door has been open for 20 minutes
```

The TV shows "Garage: The door has been open for 20 minutes". A TV that's off isn't turned on, and the message is
dropped. Like `tv_panel.show`, it needs the Android TV Remote integration for the same TV.

## License

[AGPL-3.0-or-later](LICENSE), with a commercial license available (see [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md)).
