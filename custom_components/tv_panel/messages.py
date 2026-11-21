"""Messages on the TV, without opening anything on it.

The TV Panel app keeps a websocket open to Home Assistant, signed in as the TV's own user, and subscribes with
tv_panel/messages. The notify entity sends each message down that subscription and the app shows it as a toast, so
whatever is playing keeps playing. Only with the TV's "Pause for messages" switch on does the message ask the app to
pause playback first.

The listeners are kept by entry id in hass.data rather than in the entry's runtime data, so a reload of the entry
doesn't leave the TV's connection subscribed to nothing.
"""

from __future__ import annotations

from collections.abc import Callable
import logging
from typing import Any

from homeassistant.components import websocket_api
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.util.hass_dict import HassKey
import voluptuous as vol

from .view import tv_entry

_LOGGER = logging.getLogger(__name__)

LISTENERS: HassKey[dict[str, set[Callable[[dict[str, Any]], None]]]] = HassKey("tv_panel_listeners")


def _listeners(hass: HomeAssistant, entry_id: str) -> set[Callable[[dict[str, Any]], None]]:
    return hass.data.setdefault(LISTENERS, {}).setdefault(entry_id, set())


def listening(hass: HomeAssistant, entry: ConfigEntry) -> int:
    """How many connections from this TV are listening (normally one; none when it's off or its app is older)."""
    return len(_listeners(hass, entry.entry_id))


@callback
def async_send(hass: HomeAssistant, entry: ConfigEntry, message: str, title: str | None) -> int:
    """Send a message to the TV. Returns how many connections got it: 0 means it was dropped."""
    payload = {"title": title, "message": message, "pause": entry.runtime_data.pause}
    listeners = list(_listeners(hass, entry.entry_id))
    for deliver in listeners:
        deliver(payload)
    if not listeners:
        _LOGGER.debug("%s isn't listening (off, or a TV Panel app before 0.6.0): message dropped", entry.title)
    return len(listeners)


@websocket_api.websocket_command({vol.Required("type"): "tv_panel/messages"})
@callback
def ws_messages(hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]) -> None:
    """The TV's own subscription. Any other user is refused: one TV never sees another's messages."""
    entry = tv_entry(hass, connection.user)
    if entry is None:
        connection.send_error(msg["id"], "not_a_tv", "Only a TV Panel TV listens for its messages")
        return
    listeners = _listeners(hass, entry.entry_id)

    @callback
    def deliver(payload: dict[str, Any]) -> None:
        connection.send_message(websocket_api.event_message(msg["id"], payload))

    @callback
    def stop() -> None:
        listeners.discard(deliver)

    listeners.add(deliver)
    connection.subscriptions[msg["id"]] = stop
    connection.send_result(msg["id"])


@callback
def async_register(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, ws_messages)
