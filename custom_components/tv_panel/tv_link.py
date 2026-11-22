"""Open TV Panel's screens on the TV, through Home Assistant's Android TV Remote integration.

The TV Panel app takes tvpanel:// links: tvpanel://weather, tvpanel://cameras/camera.front_door. The Android TV Remote
integration set up for the same TV (found by its address) sends them, as a URL played on its media player. That
doesn't turn the TV on: to wake it, remote.turn_on first, then the link once the TV is on (see WAKE_SETTLE).
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, STATE_OFF, STATE_UNAVAILABLE, STATE_UNKNOWN
from homeassistant.core import Event, EventStateChangedData, HomeAssistant, State, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event

_LOGGER = logging.getLogger(__name__)

ANDROID_TV_REMOTE = "androidtv_remote"

# The built-in screens' links, which apps before 0.7.0 know too. The tank screen's is the Apex one the app always had.
# Any other screen is tvpanel://screen/<id>.
SCREENS = {"weather": "weather", "solar": "solar", "tank": "apex", "cameras": "cameras"}
CAMERA = "camera"

# A screen opened while the screensaver shows lands behind it. Any ordinary key closes the screensaver (WAKEUP
# doesn't), and TV Panel's screens ignore MENU: so MENU, once the screen has had time to open.
SCREENSAVER_DELAY = 1.0

# A TV woken with wake: how long to wait for Android TV Remote to see it on, and then for the TV's own home screen,
# which opens as it comes on and lands on top of anything opened sooner. remote.turn_on with the link as its activity
# sent both at once, and the BRAVIA's home screen covered the screen 50 ms later.
WAKE_TIMEOUT = 15.0
WAKE_SETTLE = 3.0


@dataclass
class Remote:
    """The Android TV Remote integration's entities for one TV."""

    media_player: str | None
    remote: str | None


def screen_link(screen: str, camera: str | None = None) -> str:
    if screen == CAMERA:
        return f"tvpanel://cameras/{camera}"
    return f"tvpanel://{SCREENS[screen]}" if screen in SCREENS else f"tvpanel://screen/{screen}"


def find_remote(hass: HomeAssistant, entry: ConfigEntry) -> Remote | None:
    """The Android TV Remote entities for the TV this entry paired, matched by address. None without one."""
    host = entry.data.get(CONF_HOST)
    reg = er.async_get(hass)
    for atv in hass.config_entries.async_entries(ANDROID_TV_REMOTE):
        if atv.data.get(CONF_HOST) != host:
            continue
        entities = [e for e in er.async_entries_for_config_entry(reg, atv.entry_id) if not e.disabled]
        return Remote(
            next((e.entity_id for e in entities if e.domain == "media_player"), None),
            next((e.entity_id for e in entities if e.domain == "remote"), None),
        )
    return None


def _on(state: State | None) -> bool:
    return state is not None and state.state not in (STATE_OFF, STATE_UNAVAILABLE, STATE_UNKNOWN)


async def _until_on(hass: HomeAssistant, entity_id: str) -> bool:
    """Wait for the TV to come on, up to WAKE_TIMEOUT. False if it didn't."""
    on = asyncio.Event()

    @callback
    def changed(event: Event[EventStateChangedData]) -> None:
        if _on(event.data["new_state"]):
            on.set()

    unsub = async_track_state_change_event(hass, [entity_id], changed)
    try:
        if _on(hass.states.get(entity_id)):
            return True
        async with asyncio.timeout(WAKE_TIMEOUT):
            await on.wait()
        return True
    except TimeoutError:
        return False
    finally:
        unsub()


async def async_open(
    hass: HomeAssistant, entry: ConfigEntry, link: str, *, wake: bool = False, close_screensaver: bool = False
) -> bool:
    """Send the TV a link. A TV in standby is left alone unless wake is set. False when nothing was sent."""
    remote = find_remote(hass, entry)
    if remote is None or remote.media_player is None:
        raise HomeAssistantError(
            f"{entry.title}: TV Panel reaches the TV through Home Assistant's Android TV Remote integration. "
            f"Add it for this TV ({entry.data.get(CONF_HOST)})."
        )
    if not _on(hass.states.get(remote.media_player)):
        if not wake or remote.remote is None:
            _LOGGER.debug("%s is off: not sending %s", entry.title, link)
            return False
        await hass.services.async_call("remote", "turn_on", {"entity_id": remote.remote}, blocking=True)
        if not await _until_on(hass, remote.media_player):
            raise HomeAssistantError(f"{entry.title} didn't turn on within {WAKE_TIMEOUT:.0f} seconds.")
        await asyncio.sleep(WAKE_SETTLE)
    await hass.services.async_call(
        "media_player",
        "play_media",
        {"entity_id": remote.media_player, "media_content_type": "url", "media_content_id": link},
        blocking=True,
    )
    if close_screensaver and remote.remote is not None:
        await asyncio.sleep(SCREENSAVER_DELAY)
        await hass.services.async_call(
            "remote", "send_command", {"entity_id": remote.remote, "command": "MENU"}, blocking=True
        )
    return True
