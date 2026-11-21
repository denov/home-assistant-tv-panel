"""tv_panel.show and the TV's notify entity: Home Assistant opens a page or shows a message on the TV.

Both go through Home Assistant's Android TV Remote integration for the same TV (found by its address), which sends
the TV Panel app a tvpanel:// link. A URL played on its media player opens the link without turning the TV on.
"""

from unittest.mock import patch

from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import device_registry as dr, entity_registry as er
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from .common import paired_tv

PLAYER = "media_player.living_room_tv"
REMOTE = "remote.living_room_tv"


def _record(hass: HomeAssistant, domain: str, service: str) -> list[ServiceCall]:
    calls: list[ServiceCall] = []

    async def handle(call: ServiceCall) -> None:
        calls.append(call)

    hass.services.async_register(domain, service, handle)
    return calls


def _android_tv_remote(hass: HomeAssistant, host: str = "192.168.1.50", state: str = "on") -> dict:
    """The Android TV Remote integration's entry for the TV, its two entities, and what they're asked to do."""
    atv = MockConfigEntry(domain="androidtv_remote", data={"host": host, "name": "Living room TV"})
    atv.add_to_hass(hass)
    reg = er.async_get(hass)
    for domain in ("media_player", "remote"):
        reg.async_get_or_create(domain, "androidtv_remote", f"{host}-{domain}", config_entry=atv,
                                suggested_object_id="living_room_tv")
    hass.states.async_set(PLAYER, state)
    hass.states.async_set(REMOTE, state)
    return {
        "play": _record(hass, "media_player", "play_media"),
        "key": _record(hass, "remote", "send_command"),
        "on": _record(hass, "remote", "turn_on"),
    }


@pytest.fixture(autouse=True)
def no_wait():
    with patch("custom_components.tv_panel.tv_link.SCREENSAVER_DELAY", 0):
        yield


def _notify(hass: HomeAssistant, entry) -> str:
    entities = er.async_entries_for_config_entry(er.async_get(hass), entry.entry_id)
    return next(e.entity_id for e in entities if e.domain == "notify")


async def _show(hass: HomeAssistant, **data) -> None:
    await hass.services.async_call("tv_panel", "show", data, blocking=True)


async def test_show_opens_a_page_and_closes_the_screensaver(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass)
    await _show(hass, page="weather")
    assert [(c.data["entity_id"], c.data["media_content_type"], c.data["media_content_id"]) for c in sent["play"]] == [
        (PLAYER, "url", "tvpanel://weather")
    ]
    # A page opened while the screensaver shows lands behind it; any ordinary key closes it, and the pages ignore MENU.
    assert [(c.data["entity_id"], c.data["command"]) for c in sent["key"]] == [(REMOTE, "MENU")]
    assert sent["on"] == []


@pytest.mark.parametrize(
    ("data", "link"),
    [
        ({"page": "solar"}, "tvpanel://solar"),
        ({"page": "tank"}, "tvpanel://apex"),
        ({"page": "cameras"}, "tvpanel://cameras"),
        ({"page": "camera", "camera": "camera.front_door"}, "tvpanel://cameras/camera.front_door"),
    ],
)
async def test_each_page_has_its_link(hass: HomeAssistant, data: dict, link: str) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass)
    await _show(hass, **data)
    assert sent["play"][0].data["media_content_id"] == link


async def test_one_camera_needs_the_camera(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    _android_tv_remote(hass)
    with pytest.raises(ServiceValidationError, match="camera"):
        await _show(hass, page="camera")


async def test_a_tv_in_standby_stays_off_unless_woken(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass, state="off")
    await _show(hass, page="weather")
    assert sent["play"] == [] and sent["key"] == [] and sent["on"] == []

    # wake: turn the TV on with the page as its first app.
    await _show(hass, page="cameras", wake=True)
    assert [(c.data["entity_id"], c.data["activity"]) for c in sent["on"]] == [(REMOTE, "tvpanel://cameras")]
    assert sent["play"] == []


async def test_without_android_tv_remote_it_says_what_to_add(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    _android_tv_remote(hass, host="192.168.1.99")  # another TV
    with pytest.raises(HomeAssistantError, match="Android TV Remote"):
        await _show(hass, page="weather")


async def test_a_device_picks_the_tv(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    sent = _android_tv_remote(hass)
    tv = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)[0]
    await _show(hass, page="solar", device_id=tv.id)
    assert len(sent["play"]) == 1

    other = MockConfigEntry(domain="demo")
    other.add_to_hass(hass)
    lamp = dr.async_get(hass).async_get_or_create(config_entry_id=other.entry_id, identifiers={("demo", "lamp")})
    with pytest.raises(ServiceValidationError, match="TV Panel"):
        await _show(hass, page="solar", device_id=lamp.id)


async def test_the_tv_has_a_notify_entity_that_shows_a_message(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    sent = _android_tv_remote(hass)
    notify = _notify(hass, entry)
    message = {"entity_id": notify, "title": "Garage", "message": "Left open & 20 min"}
    await hass.services.async_call("notify", "send_message", message, blocking=True)
    assert [c.data["media_content_id"] for c in sent["play"]] == [
        "tvpanel://notify?message=Left%20open%20%26%2020%20min&title=Garage"
    ]
    # A message must not end the screensaver or touch what's playing: no keys.
    assert sent["key"] == []


async def test_a_message_to_a_tv_in_standby_is_dropped(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    sent = _android_tv_remote(hass, state="off")
    notify = _notify(hass, entry)
    await hass.services.async_call("notify", "send_message", {"entity_id": notify, "message": "Hi"}, blocking=True)
    assert sent["play"] == [] and sent["on"] == []
