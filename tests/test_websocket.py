"""The TV Panel page's API: list the TVs, read a TV's settings, save them. Admins only."""

from unittest.mock import patch

from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component

from custom_components.tv_panel.const import DOMAIN

from .common import paired_tv


async def test_lists_the_tvs(hass: HomeAssistant, hass_ws_client) -> None:
    entry, _ = await paired_tv(hass)
    ws = await hass_ws_client(hass)
    await ws.send_json({"id": 1, "type": "tv_panel/tvs"})
    msg = await ws.receive_json()
    assert msg["success"]
    assert msg["result"] == [{"entry_id": entry.entry_id, "title": "BRAVIA"}]


async def test_reads_settings_with_a_saved_flag(hass: HomeAssistant, hass_ws_client) -> None:
    entry, _ = await paired_tv(hass, {"entities": ["weather.home"]})
    ws = await hass_ws_client(hass)
    await ws.send_json({"id": 1, "type": "tv_panel/settings", "entry_id": entry.entry_id})
    msg = await ws.receive_json()
    assert msg["success"]
    assert msg["result"]["chips"] == [{"type": "entity", "entity": "weather.home"}]
    assert msg["result"]["saved"] is True


async def test_saves_settings(hass: HomeAssistant, hass_ws_client) -> None:
    entry, _ = await paired_tv(hass)
    ws = await hass_ws_client(hass)
    settings = {
        "chips": [{"type": "entity", "entity": "climate.hall", "label": " Hall "}, {"type": "cameras"}],
        "cameras": ["camera.a"],
        "tank": [],
        "solar": None,
    }
    await ws.send_json({"id": 1, "type": "tv_panel/save", "entry_id": entry.entry_id, "settings": settings})
    msg = await ws.receive_json()
    assert msg["success"], msg
    assert entry.options["chips"][0] == {"type": "entity", "entity": "climate.hall", "label": "Hall"}
    assert entry.options["cameras"] == ["camera.a"]
    # The 0.2.0 keys are gone once the new shape is saved.
    assert "entities" not in entry.options


async def test_refuses_bad_settings(hass: HomeAssistant, hass_ws_client) -> None:
    entry, _ = await paired_tv(hass)
    ws = await hass_ws_client(hass)
    bad = {"chips": [{"type": "nope"}], "cameras": [], "tank": [], "solar": None}
    await ws.send_json({"id": 1, "type": "tv_panel/save", "entry_id": entry.entry_id, "settings": bad})
    msg = await ws.receive_json()
    assert not msg["success"]
    assert msg["error"]["code"] == "invalid_format"
    assert entry.options == {}


async def test_unknown_tv(hass: HomeAssistant, hass_ws_client) -> None:
    await paired_tv(hass)
    ws = await hass_ws_client(hass)
    await ws.send_json({"id": 1, "type": "tv_panel/settings", "entry_id": "nope"})
    msg = await ws.receive_json()
    assert msg["error"]["code"] == "not_found"


async def test_admins_only(hass: HomeAssistant, hass_ws_client, hass_admin_user) -> None:
    entry, _ = await paired_tv(hass)
    hass_admin_user.groups = []
    ws = await hass_ws_client(hass)
    await ws.send_json({"id": 1, "type": "tv_panel/settings", "entry_id": entry.entry_id})
    msg = await ws.receive_json()
    assert msg["error"]["code"] == "unauthorized"


async def test_the_page_is_in_the_sidebar_when_the_frontend_is(hass: HomeAssistant) -> None:
    hass.config.components.add("frontend")
    with (
        patch("custom_components.tv_panel.panel.panel_custom.async_register_panel") as register,
        patch("custom_components.tv_panel.panel._async_static") as static,
    ):
        assert await async_setup_component(hass, DOMAIN, {})
        await hass.async_block_till_done()
    static.assert_called_once()
    kwargs = register.call_args.kwargs
    assert kwargs["frontend_url_path"] == "tv-panel"
    assert kwargs["require_admin"] is True
    assert kwargs["module_url"].startswith("/tv_panel_static/tv-panel.js")
