"""/api/tv_panel/config: the TV reads its settings, and only its own."""

from http import HTTPStatus

from homeassistant.core import HomeAssistant

from .common import paired_tv

OPTIONS = {
    "chips": [
        {"type": "entity", "entity": "weather.home"},
        {"type": "cameras"},
        {"type": "entity", "entity": "climate.hall", "label": "Hall"},
    ],
    "cameras": ["camera.b", "camera.a"],
    "tank": [],
    "solar": {"arrays": []},
}


async def _get(hass_client_no_auth, token):
    client = await hass_client_no_auth()
    return await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})


async def test_the_tv_gets_its_settings(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)

    r = await _get(hass_client_no_auth, token)

    assert r.status == HTTPStatus.OK
    body = await r.json()
    # Each chip comes with what OK does, worked out: the TV doesn't guess.
    assert body["chips"] == [
        {"type": "entity", "entity": "weather.home", "ok": {"action": "page", "page": "weather"}},
        {"type": "cameras", "ok": {"action": "page", "page": "cameras"}},
        {"type": "entity", "entity": "climate.hall", "label": "Hall", "ok": {"action": "none"}},
    ]
    assert body["cameras"] == ["camera.b", "camera.a"]
    assert body["tank"] == []
    assert body["solar"] == {"arrays": []}
    assert body["row"] is True
    # App 0.2.0 reads these two: the flat list in chip order, and the grid's order.
    assert body["entities"] == ["weather.home", "camera.b", "camera.a", "climate.hall"]
    assert body["camera_order"] == ["camera.b", "camera.a"]


async def test_02_settings_are_served_in_the_new_shape(hass: HomeAssistant, hass_client_no_auth) -> None:
    hass.states.async_set("camera.a", "idle")
    _, token = await paired_tv(hass, {"entities": ["weather.home", "camera.a"], "camera_order": ["camera.a"]})
    body = await (await _get(hass_client_no_auth, token)).json()
    assert [c["type"] for c in body["chips"]] == ["entity", "cameras"]
    assert body["entities"] == ["weather.home", "camera.a"]


async def test_the_row_switch_turns_the_row_off(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    switch = next(s.entity_id for s in hass.states.async_all("switch"))
    await hass.services.async_call("switch", "turn_off", {"entity_id": switch}, blocking=True)
    assert (await (await _get(hass_client_no_auth, token)).json())["row"] is False


async def test_not_set_up_yet_gives_nulls(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Never saved: the app keeps using labels and helpers."""
    _, token = await paired_tv(hass)
    body = await (await _get(hass_client_no_auth, token)).json()
    assert body == {
        "chips": None, "cameras": None, "tank": None, "solar": None, "entities": None, "camera_order": None,
        "row": True,
    }


async def test_other_users_get_nothing(hass: HomeAssistant, hass_client) -> None:
    """An admin's own token isn't a TV's: no settings, so nothing leaks between TVs."""
    await paired_tv(hass, OPTIONS)
    client = await hass_client()
    assert (await client.get("/api/tv_panel/config")).status == HTTPStatus.NOT_FOUND


async def test_needs_a_token(hass: HomeAssistant, hass_client_no_auth) -> None:
    await paired_tv(hass, OPTIONS)
    client = await hass_client_no_auth()
    assert (await client.get("/api/tv_panel/config")).status == HTTPStatus.UNAUTHORIZED
