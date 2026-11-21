"""Diagnostics: what a bug report needs, without the TV's address, its user, or the owner's inverter serials."""

import json

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er
from pytest_homeassistant_custom_component.common import MockConfigEntry
from pytest_homeassistant_custom_component.components.diagnostics import get_diagnostics_for_config_entry

from custom_components.tv_panel.const import CONF_USER_ID

from .common import paired_tv

OPTIONS = {
    "chips": [{"type": "entity", "entity": "weather.home"}, {"type": "cameras"}],
    "cameras": ["camera.driveway"],
    "tank": [],
    "solar": {"arrays": [{"name": "Array 1", "panels": [{"id": "482100123456", "x": 0, "y": 0}]}]},
}


async def test_diagnostics(hass: HomeAssistant, hass_client) -> None:
    hass.states.async_set("weather.home", "sunny", {"friendly_name": "Home"})
    entry, _ = await paired_tv(hass, OPTIONS)
    user = await hass.auth.async_get_user(entry.data[CONF_USER_ID])
    await hass.auth.async_update_user(user, group_ids=[GROUP_ID_USER])
    atv = MockConfigEntry(domain="androidtv_remote", data={"host": "192.168.1.50", "name": "Living room TV"})
    atv.add_to_hass(hass)
    er.async_get(hass).async_get_or_create("media_player", "androidtv_remote", "x", config_entry=atv,
                                           suggested_object_id="living_room_tv")
    hass.states.async_set("media_player.living_room_tv", "on")

    diag = await get_diagnostics_for_config_entry(hass, hass_client, entry)

    assert diag["version"] == "0.6.0"
    assert diag["user"] == {"admin": False, "local_only": True, "active": True}
    assert diag["row"] is True
    assert diag["listening"] == 0
    assert diag["settings"]["chips"] == OPTIONS["chips"]
    assert diag["settings"]["cameras"] == ["camera.driveway"]
    # The layout's shape, not the serials.
    assert diag["settings"]["solar"] == {"arrays": 1, "panels": 1}
    assert diag["android_tv_remote"] == {"media_player": "media_player.living_room_tv", "remote": None, "state": "on"}
    assert [i["id"] for i in diag["chips"]["items"]] == ["weather.home", "camera.driveway"]

    text = json.dumps(diag)
    for secret in ("192.168.1.50", entry.data[CONF_USER_ID], "482100123456", "abc123"):
        assert secret not in text, secret


async def test_diagnostics_without_android_tv_remote(hass: HomeAssistant, hass_client) -> None:
    entry, _ = await paired_tv(hass, OPTIONS)
    diag = await get_diagnostics_for_config_entry(hass, hass_client, entry)
    assert diag["android_tv_remote"] is None
