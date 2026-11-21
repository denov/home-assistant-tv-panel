"""Pairing a TV: typed in or discovered, right and wrong codes, and removal signing the TV out."""

from ipaddress import ip_address

from homeassistant import config_entries
from homeassistant.core import HomeAssistant
from homeassistant.core_config import async_process_ha_core_config
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from pytest_homeassistant_custom_component.common import MockConfigEntry
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMocker

from custom_components.tv_panel.const import CONF_USER_ID, DOMAIN

TV = "http://192.168.1.50:8766"
INFO = {"id": "abc123", "name": "BRAVIA 4K VH2", "model": "BRAVIA 4K VH2", "paired": False}


async def _setup(hass: HomeAssistant) -> None:
    # A real Home Assistant has its owner already. Without one, the TV's user would become the owner.
    await hass.auth.async_create_user("Owner")
    await async_process_ha_core_config(hass, {"internal_url": "http://192.168.1.10:8123"})


async def _tv_users(hass: HomeAssistant):
    return [u for u in await hass.auth.async_get_users() if u.name.startswith("TV Panel")]


async def test_typed_in_and_paired(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _setup(hass)
    aioclient_mock.get(f"{TV}/info", json=INFO)
    aioclient_mock.post(f"{TV}/pair", json={"id": "abc123", "name": "BRAVIA 4K VH2", "url": "http://192.168.1.10:8123"})

    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    assert result["type"] is FlowResultType.FORM and result["step_id"] == "user"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"host": "192.168.1.50:8766"})
    assert result["type"] is FlowResultType.FORM and result["step_id"] == "pair"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "482 913"})

    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert result["title"] == "BRAVIA 4K VH2"
    assert result["result"].unique_id == "abc123"
    sent = aioclient_mock.mock_calls[-1][2]
    assert sent["code"] == "482913"
    assert sent["url"] == "http://192.168.1.10:8123"

    users = await _tv_users(hass)
    assert len(users) == 1
    assert users[0].local_only
    assert users[0].id == result["data"][CONF_USER_ID]
    # The token the TV got works.
    assert hass.auth.async_validate_access_token(sent["token"]) is not None


async def test_wrong_code_leaves_no_user(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _setup(hass)
    aioclient_mock.get(f"{TV}/info", json=INFO)
    aioclient_mock.post(f"{TV}/pair", status=403, json={"error": "invalid_code"})

    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"host": "192.168.1.50"})
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "000000"})

    assert result["type"] is FlowResultType.FORM
    assert result["errors"] == {"base": "invalid_code"}
    assert await _tv_users(hass) == []


async def test_tv_not_reachable(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _setup(hass)
    aioclient_mock.get(f"{TV}/info", exc=TimeoutError())
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": config_entries.SOURCE_USER})
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"host": "192.168.1.50"})
    assert result["errors"] == {"base": "cannot_connect"}


async def test_discovered(hass: HomeAssistant, aioclient_mock: AiohttpClientMocker) -> None:
    await _setup(hass)
    aioclient_mock.post(f"{TV}/pair", json={"id": "abc123", "name": "BRAVIA 4K VH2"})
    info = ZeroconfServiceInfo(
        ip_address=ip_address("192.168.1.50"),
        ip_addresses=[ip_address("192.168.1.50")],
        hostname="tv.local.",
        name="TV Panel BRAVIA._tvpanel._tcp.local.",
        port=8766,
        type="_tvpanel._tcp.local.",
        properties={"id": "abc123", "name": "BRAVIA 4K VH2"},
    )
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info
    )
    assert result["type"] is FlowResultType.FORM and result["step_id"] == "pair"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "123456"})
    assert result["type"] is FlowResultType.CREATE_ENTRY

    # Seen again at a new address: no second entry, the address follows.
    info.ip_address = ip_address("192.168.1.51")
    info.ip_addresses = [ip_address("192.168.1.51")]
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=info
    )
    assert result["type"] is FlowResultType.ABORT and result["reason"] == "already_configured"
    assert hass.config_entries.async_entries(DOMAIN)[0].data["host"] == "192.168.1.51"


async def test_removing_signs_the_tv_out(hass: HomeAssistant) -> None:
    await _setup(hass)
    user = await hass.auth.async_create_user("TV Panel (x)", local_only=True)
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id="abc123",
        data={"host": "192.168.1.50", "port": 8766, "name": "x", "tv_id": "abc123", CONF_USER_ID: user.id},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    await hass.config_entries.async_remove(entry.entry_id)
    assert await hass.auth.async_get_user(user.id) is None
