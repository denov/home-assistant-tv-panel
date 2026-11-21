"""Messages on the TV: the TV keeps a websocket subscription (tv_panel/messages) and the notify entity sends to it.

No page is opened for a message, so whatever is playing keeps playing. Only when the TV's "Pause for messages" switch
is on does the message ask the TV to pause first.
"""

from homeassistant.core import HomeAssistant, State
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import mock_restore_cache

from .common import paired_tv
from .test_show import _android_tv_remote


def _entity(hass: HomeAssistant, entry, domain: str, unique_end: str) -> str:
    entities = er.async_entries_for_config_entry(er.async_get(hass), entry.entry_id)
    return next(e.entity_id for e in entities if e.domain == domain and e.unique_id.endswith(unique_end))


async def _listen(hass_ws_client, hass: HomeAssistant, token: str):
    ws = await hass_ws_client(hass, access_token=token)
    await ws.send_json({"id": 1, "type": "tv_panel/messages"})
    msg = await ws.receive_json()
    assert msg["success"], msg
    return ws


async def _send(hass: HomeAssistant, entry, **data) -> None:
    notify = _entity(hass, entry, "notify", "_notify")
    await hass.services.async_call("notify", "send_message", {"entity_id": notify, **data}, blocking=True)


async def test_the_tv_listens_and_gets_its_messages(hass: HomeAssistant, hass_ws_client) -> None:
    entry, token = await paired_tv(hass)
    sent = _android_tv_remote(hass)
    ws = await _listen(hass_ws_client, hass, token)

    await _send(hass, entry, title="Garage", message="Left open & 20 min")

    msg = await ws.receive_json()
    assert msg["id"] == 1 and msg["type"] == "event"
    assert msg["event"] == {"title": "Garage", "message": "Left open & 20 min", "pause": False}
    # Nothing is opened on the TV, and no key is pressed: what's playing keeps playing.
    assert sent["play"] == [] and sent["key"] == [] and sent["on"] == []


async def test_a_message_without_a_title(hass: HomeAssistant, hass_ws_client) -> None:
    entry, token = await paired_tv(hass)
    ws = await _listen(hass_ws_client, hass, token)
    await _send(hass, entry, message="Hi")
    assert (await ws.receive_json())["event"] == {"title": None, "message": "Hi", "pause": False}


async def test_pause_for_messages_is_off_until_turned_on(hass: HomeAssistant, hass_ws_client) -> None:
    entry, token = await paired_tv(hass)
    switch = _entity(hass, entry, "switch", "_pause_for_messages")
    assert hass.states.get(switch).state == "off"
    ws = await _listen(hass_ws_client, hass, token)

    await hass.services.async_call("switch", "turn_on", {"entity_id": switch}, blocking=True)
    await _send(hass, entry, message="Someone's at the door")

    assert (await ws.receive_json())["event"]["pause"] is True


async def test_pause_for_messages_keeps_its_state_across_restarts(hass: HomeAssistant) -> None:
    mock_restore_cache(hass, [State("switch.bravia_pause_for_messages", "on")])
    await paired_tv(hass)
    assert hass.states.get("switch.bravia_pause_for_messages").state == "on"


async def test_only_a_tv_may_listen(hass: HomeAssistant, hass_ws_client) -> None:
    await paired_tv(hass)
    ws = await hass_ws_client(hass)  # the owner: not a TV
    await ws.send_json({"id": 1, "type": "tv_panel/messages"})
    msg = await ws.receive_json()
    assert not msg["success"]
    assert msg["error"]["code"] == "not_a_tv"


async def test_each_tv_gets_only_its_own(hass: HomeAssistant, hass_ws_client) -> None:
    living, living_token = await paired_tv(hass)
    bedroom, bedroom_token = await paired_tv(hass, tv_id="def456", host="192.168.1.60", name="Bedroom")
    living_ws = await _listen(hass_ws_client, hass, living_token)
    bedroom_ws = await _listen(hass_ws_client, hass, bedroom_token)

    await _send(hass, bedroom, message="Bedtime")
    await _send(hass, living, message="Dinner")

    assert (await bedroom_ws.receive_json())["event"]["message"] == "Bedtime"
    assert (await living_ws.receive_json())["event"]["message"] == "Dinner"


async def test_a_tv_that_isnt_listening_drops_the_message(hass: HomeAssistant, hass_ws_client) -> None:
    """Off, or an app before 0.6.0: the message is dropped, never sent the old way (a page, which can pause)."""
    entry, token = await paired_tv(hass)
    sent = _android_tv_remote(hass)
    ws = await _listen(hass_ws_client, hass, token)
    await ws.close()
    await hass.async_block_till_done()

    await _send(hass, entry, message="Hi")

    assert sent["play"] == [] and sent["key"] == [] and sent["on"] == []
