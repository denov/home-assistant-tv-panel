"""POST /api/tv_panel/press: OK on a chip. The integration does what that chip's settings say, as the TV's user, and
fires tv_panel_chip_pressed so automations can act on any press."""

import asyncio
from http import HTTPStatus
from unittest.mock import patch

from homeassistant.core import HomeAssistant, ServiceCall

from .common import paired_tv

CHIPS = {
    "chips": [
        {"type": "entity", "entity": "cover.garage_door"},
        {"type": "entity", "entity": "light.hall", "label": "Hall", "ok": {"action": "toggle"}},
        {"type": "entity", "entity": "sensor.x", "ok": {"action": "run", "entity": "script.good_night"}},
        {
            "type": "entity",
            "entity": "sensor.y",
            "ok": {"action": "perform", "perform_action": "light.turn_on", "target": {"entity_id": "light.den"},
                   "data": {"brightness_pct": 30}},
        },
        {"type": "entity", "entity": "lock.front", "ok": {"action": "toggle"}},
        {"type": "entity", "entity": "weather.home"},
        {"type": "tank"},
        {"type": "entity", "entity": "sensor.z"},
    ],
    "cameras": [],
    "tank": ["sensor.apex_temp"],
    "solar": None,
}


def _record(hass: HomeAssistant, domain: str, service: str) -> list[ServiceCall]:
    calls: list[ServiceCall] = []

    async def handle(call: ServiceCall) -> None:
        calls.append(call)

    hass.services.async_register(domain, service, handle)
    return calls


async def _press(hass_client_no_auth, token, key):
    client = await hass_client_no_auth()
    return await client.post("/api/tv_panel/press", json={"key": key}, headers={"Authorization": f"Bearer {token}"})


async def test_the_garage_door_toggles_as_the_tvs_user(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("cover.garage_door", "closed", {"device_class": "garage", "friendly_name": "Garage Door"})
    calls = _record(hass, "cover", "toggle")
    entry, token = await paired_tv(hass, CHIPS)

    r = await _press(hass_client_no_auth, token, "cover.garage_door")

    assert r.status == HTTPStatus.OK
    assert await r.json() == {"ok": True, "message": "Garage Door: opening"}
    assert calls[0].data["entity_id"] == ["cover.garage_door"]
    assert calls[0].context.user_id == entry.data["user_id"]


async def test_a_toggle_of_its_own(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("light.hall", "off", {"friendly_name": "Hall light"})
    calls = _record(hass, "light", "toggle")
    _, token = await paired_tv(hass, CHIPS)
    body = await (await _press(hass_client_no_auth, token, "light.hall")).json()
    assert body == {"ok": True, "message": "Hall: turning on"}
    assert len(calls) == 1


async def test_a_lock_has_no_toggle_so_it_locks_or_unlocks(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("lock.front", "locked", {"friendly_name": "Front door"})
    calls = _record(hass, "lock", "unlock")
    _, token = await paired_tv(hass, CHIPS)
    body = await (await _press(hass_client_no_auth, token, "lock.front")).json()
    assert body == {"ok": True, "message": "Front door: unlocking"}
    assert calls[0].data["entity_id"] == ["lock.front"]


async def test_running_a_script(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("script.good_night", "off", {"friendly_name": "Good night"})
    calls = _record(hass, "script", "turn_on")
    _, token = await paired_tv(hass, CHIPS)
    body = await (await _press(hass_client_no_auth, token, "sensor.x")).json()
    assert body == {"ok": True, "message": "Good night: started"}
    assert calls[0].data["entity_id"] == ["script.good_night"]


async def test_performing_an_action(hass: HomeAssistant, hass_client_no_auth) -> None:
    calls = _record(hass, "light", "turn_on")
    _, token = await paired_tv(hass, CHIPS)
    body = await (await _press(hass_client_no_auth, token, "sensor.y")).json()
    assert body == {"ok": True, "message": "Done"}
    assert calls[0].data["brightness_pct"] == 30
    assert calls[0].data["entity_id"] == ["light.den"]


async def test_a_screen_is_opened_by_the_tv_but_the_press_is_still_an_event(
    hass: HomeAssistant, hass_client_no_auth
) -> None:
    entry, token = await paired_tv(hass, CHIPS)
    events = []
    hass.bus.async_listen("tv_panel_chip_pressed", events.append)

    body = await (await _press(hass_client_no_auth, token, "apex")).json()
    await hass.async_block_till_done()

    assert body == {"ok": True, "screen": "tank"}
    assert events[0].data == {
        "entry_id": entry.entry_id,
        "tv": "BRAVIA",
        "chip": "apex",
        "action": "screen",
    }
    assert events[0].context.user_id == entry.data["user_id"]


async def test_a_chip_that_does_nothing_is_still_an_event(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, CHIPS)
    events = []
    hass.bus.async_listen("tv_panel_chip_pressed", events.append)
    body = await (await _press(hass_client_no_auth, token, "sensor.z")).json()
    await hass.async_block_till_done()
    assert body == {"ok": True}
    assert events[0].data["action"] == "none"


async def test_a_failed_action_says_why(hass: HomeAssistant, hass_client_no_auth) -> None:
    """No light.toggle service here: the TV gets a message to show, not an error page."""
    hass.states.async_set("light.hall", "off")
    _, token = await paired_tv(hass, CHIPS)
    body = await (await _press(hass_client_no_auth, token, "light.hall")).json()
    assert body["ok"] is False
    assert body["message"].startswith("Hall: ")


async def test_an_unknown_chip(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, CHIPS)
    assert (await _press(hass_client_no_auth, token, "light.not_a_chip")).status == HTTPStatus.NOT_FOUND


async def test_only_a_tv_can_press(hass: HomeAssistant, hass_client) -> None:
    await paired_tv(hass, CHIPS)
    client = await hass_client()
    r = await client.post("/api/tv_panel/press", json={"key": "light.hall"})
    assert r.status == HTTPStatus.NOT_FOUND


async def test_a_long_action_keeps_going_and_the_tv_hears_at_once(hass: HomeAssistant, hass_client_no_auth) -> None:
    """The TV gives up after 6 s and Home Assistant cancels a request whose client went away: a long script must not
    be cut off halfway, so the press answers after a short wait and the action carries on."""
    release = asyncio.Event()
    finished = []

    async def slow(call: ServiceCall) -> None:
        await release.wait()
        finished.append(call)

    hass.services.async_register("light", "turn_on", slow)
    _, token = await paired_tv(hass, CHIPS)
    with patch("custom_components.tv_panel.press.PRESS_WAIT", 0.05):
        body = await (await _press(hass_client_no_auth, token, "sensor.y")).json()
    assert body == {"ok": True, "message": "Started"}
    assert not finished
    release.set()
    await hass.async_block_till_done()
    assert len(finished) == 1


async def test_a_press_only_for_the_event_does_nothing_else(hass: HomeAssistant, hass_client_no_auth) -> None:
    """The TV opened a screen (or the chip does nothing) and only reports the press. Even if the chip was changed to a
    toggle a moment ago, and the TV hasn't caught up, nothing is toggled."""
    hass.states.async_set("cover.garage_door", "closed", {"device_class": "garage"})
    calls = _record(hass, "cover", "toggle")
    _, token = await paired_tv(hass, CHIPS)
    events = []
    hass.bus.async_listen("tv_panel_chip_pressed", events.append)
    client = await hass_client_no_auth()

    r = await client.post(
        "/api/tv_panel/press",
        json={"key": "cover.garage_door", "event_only": True, "action": "screen"},
        headers={"Authorization": f"Bearer {token}"},
    )
    await hass.async_block_till_done()

    assert await r.json() == {"ok": True}
    assert calls == []
    assert events[0].data["action"] == "screen"


async def test_an_older_app_says_page_and_the_event_says_screen(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Apps before 0.7.0 report a screen they opened as "page". Automations see one word for it."""
    _, token = await paired_tv(hass, CHIPS)
    events = []
    hass.bus.async_listen("tv_panel_chip_pressed", events.append)
    client = await hass_client_no_auth()
    for action in ("page", "none"):
        await client.post(
            "/api/tv_panel/press",
            json={"key": "apex", "event_only": True, "action": action},
            headers={"Authorization": f"Bearer {token}"},
        )
    await hass.async_block_till_done()
    assert [e.data["action"] for e in events] == ["screen", "none"]


async def test_toggle_words_follow_what_toggle_does(hass: HomeAssistant, hass_client_no_auth) -> None:
    """A thermostat is "heat", not "on": toggling it turns it off. A moving cover stops."""
    chips = {
        "chips": [
            {"type": "entity", "entity": "climate.hall", "label": "Hall", "ok": {"action": "toggle"}},
            {"type": "entity", "entity": "cover.blind", "label": "Blind", "ok": {"action": "toggle"}},
        ],
        "cameras": [],
        "tank": [],
    }
    hass.states.async_set("climate.hall", "heat")
    hass.states.async_set("cover.blind", "opening")
    _record(hass, "climate", "toggle")
    _record(hass, "cover", "toggle")
    _, token = await paired_tv(hass, chips)
    assert (await (await _press(hass_client_no_auth, token, "climate.hall")).json())["message"] == "Hall: turning off"
    assert (await (await _press(hass_client_no_auth, token, "cover.blind")).json())["message"] == "Blind: stopping"


async def test_an_unexpected_error_is_still_a_message(hass: HomeAssistant, hass_client_no_auth) -> None:
    async def broken(call: ServiceCall) -> None:
        raise ValueError("bad value")

    hass.services.async_register("light", "turn_on", broken)
    _, token = await paired_tv(hass, CHIPS)
    r = await _press(hass_client_no_auth, token, "sensor.y")
    assert r.status == HTTPStatus.OK
    assert await r.json() == {"ok": False, "message": "That: bad value"}
