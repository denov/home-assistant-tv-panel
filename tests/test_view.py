"""/api/tv_panel/config: the TV reads its settings, and only its own."""

from http import HTTPStatus

from homeassistant.core import HomeAssistant

from custom_components.tv_panel.const import OPT_CAMERA_ORDER, OPT_ENTITIES, OPT_SOLAR

from .common import paired_tv

OPTIONS = {
    OPT_ENTITIES: ["weather.home", "sensor.solar"],
    OPT_CAMERA_ORDER: ["camera.b"],
    OPT_SOLAR: {"arrays": []},
}


async def test_the_tv_gets_its_settings(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    client = await hass_client_no_auth()

    r = await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})

    assert r.status == HTTPStatus.OK
    assert await r.json() == {
        "entities": ["weather.home", "sensor.solar"],
        "camera_order": ["camera.b"],
        "solar": {"arrays": []},
        "row": True,
    }


async def test_the_row_switch_turns_the_row_off(hass: HomeAssistant, hass_client_no_auth) -> None:
    _, token = await paired_tv(hass, OPTIONS)
    switch = next(s.entity_id for s in hass.states.async_all("switch"))
    await hass.services.async_call("switch", "turn_off", {"entity_id": switch}, blocking=True)
    client = await hass_client_no_auth()

    r = await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})

    assert (await r.json())["row"] is False


async def test_not_set_up_yet_gives_nulls(hass: HomeAssistant, hass_client_no_auth) -> None:
    """Configure never saved: the app keeps using labels and helpers."""
    _, token = await paired_tv(hass)
    client = await hass_client_no_auth()
    body = await (await client.get("/api/tv_panel/config", headers={"Authorization": f"Bearer {token}"})).json()
    assert body == {"entities": None, "camera_order": None, "solar": None, "row": True}


async def test_other_users_get_nothing(hass: HomeAssistant, hass_client) -> None:
    """An admin's own token isn't a TV's: no settings, so nothing leaks between TVs."""
    await paired_tv(hass, OPTIONS)
    client = await hass_client()
    r = await client.get("/api/tv_panel/config")
    assert r.status == HTTPStatus.NOT_FOUND


async def test_needs_a_token(hass: HomeAssistant, hass_client_no_auth) -> None:
    await paired_tv(hass, OPTIONS)
    client = await hass_client_no_auth()
    assert (await client.get("/api/tv_panel/config")).status == HTTPStatus.UNAUTHORIZED
