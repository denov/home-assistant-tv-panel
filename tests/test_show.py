"""tv_panel.show: Home Assistant opens a screen on the TV.

It goes through Home Assistant's Android TV Remote integration for the same TV (found by its address), which sends
the TV Panel app a tvpanel:// link. A URL played on its media player opens the link without turning the TV on.
Messages don't go this way (tests/test_messages.py).
"""

from unittest.mock import patch

from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import device_registry as dr, entity_registry as er
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry
import voluptuous as vol

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


async def _show(hass: HomeAssistant, **data) -> None:
    await hass.services.async_call("tv_panel", "show", data, blocking=True)


async def test_show_opens_a_screen_and_closes_the_screensaver(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass)
    await _show(hass, screen="weather")
    assert [(c.data["entity_id"], c.data["media_content_type"], c.data["media_content_id"]) for c in sent["play"]] == [
        (PLAYER, "url", "tvpanel://weather")
    ]
    # A screen opened while the screensaver shows lands behind it; any ordinary key closes it, and screens ignore MENU.
    assert [(c.data["entity_id"], c.data["command"]) for c in sent["key"]] == [(REMOTE, "MENU")]
    assert sent["on"] == []


@pytest.mark.parametrize(
    ("data", "link"),
    [
        ({"screen": "solar"}, "tvpanel://solar"),
        ({"screen": "tank"}, "tvpanel://apex"),
        ({"screen": "cameras"}, "tvpanel://cameras"),
        ({"screen": "camera", "camera": "camera.front_door"}, "tvpanel://cameras/camera.front_door"),
    ],
)
async def test_each_screen_has_its_link(hass: HomeAssistant, data: dict, link: str) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass)
    await _show(hass, **data)
    assert sent["play"][0].data["media_content_id"] == link


async def test_page_is_the_old_name_for_screen(hass: HomeAssistant) -> None:
    """Automations written for 0.5.0 and 0.6.0 say page: they keep working."""
    await paired_tv(hass)
    sent = _android_tv_remote(hass)
    await _show(hass, page="solar")
    assert sent["play"][0].data["media_content_id"] == "tvpanel://solar"


@pytest.mark.parametrize("data", [{}, {"screen": "solar", "page": "weather"}])
async def test_show_needs_one_screen(hass: HomeAssistant, data: dict) -> None:
    await paired_tv(hass)
    _android_tv_remote(hass)
    with pytest.raises(vol.Invalid, match="screen"):
        await _show(hass, **data)


async def test_one_camera_needs_the_camera(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    _android_tv_remote(hass)
    with pytest.raises(ServiceValidationError, match="camera"):
        await _show(hass, screen="camera")


async def test_a_tv_in_standby_stays_off_unless_woken(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    sent = _android_tv_remote(hass, state="off")
    await _show(hass, screen="weather")
    assert sent["play"] == [] and sent["key"] == [] and sent["on"] == []

    # wake: turn the TV on with the screen as its first app.
    await _show(hass, screen="cameras", wake=True)
    assert [(c.data["entity_id"], c.data["activity"]) for c in sent["on"]] == [(REMOTE, "tvpanel://cameras")]
    assert sent["play"] == []


async def test_without_android_tv_remote_it_says_what_to_add(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    _android_tv_remote(hass, host="192.168.1.99")  # another TV
    with pytest.raises(HomeAssistantError, match="Android TV Remote"):
        await _show(hass, screen="weather")


async def test_a_device_picks_the_tv(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    sent = _android_tv_remote(hass)
    tv = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)[0]
    await _show(hass, screen="solar", device_id=tv.id)
    assert len(sent["play"]) == 1

    other = MockConfigEntry(domain="demo")
    other.add_to_hass(hass)
    lamp = dr.async_get(hass).async_get_or_create(config_entry_id=other.entry_id, identifiers={("demo", "lamp")})
    with pytest.raises(ServiceValidationError, match="TV Panel") as err:
        await _show(hass, screen="solar", device_id=lamp.id)
    assert "None" not in str(err.value)
    # The owner's name for it, when they gave one.
    dr.async_get(hass).async_update_device(lamp.id, name_by_user="Desk lamp")
    with pytest.raises(ServiceValidationError, match="Desk lamp isn't a TV Panel TV"):
        await _show(hass, screen="solar", device_id=lamp.id)


async def test_with_two_tvs_one_without_android_tv_remote_doesnt_stop_the_other(hass: HomeAssistant) -> None:
    await paired_tv(hass)
    bedroom, _ = await paired_tv(hass, tv_id="def456", host="192.168.1.60", name="Bedroom")
    sent = _android_tv_remote(hass)  # for the first TV only

    # Every TV: the one that can be reached shows it, and the automation goes on.
    await _show(hass, screen="weather")
    assert len(sent["play"]) == 1

    # The TV without it, chosen by name: that's an error.
    tv = dr.async_entries_for_config_entry(dr.async_get(hass), bedroom.entry_id)[0]
    with pytest.raises(HomeAssistantError, match="Android TV Remote"):
        await _show(hass, screen="weather", device_id=tv.id)


async def test_a_tv_that_isnt_running_says_so(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    _android_tv_remote(hass)
    tv = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)[0]
    await hass.config_entries.async_unload(entry.entry_id)
    with pytest.raises(ServiceValidationError, match="BRAVIA isn't ready"):
        await _show(hass, screen="weather", device_id=tv.id)
