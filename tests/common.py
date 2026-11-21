"""Helpers shared by the tests: a paired TV entry with a user and token of its own."""

from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.core import HomeAssistant
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.tv_panel.const import CONF_TV_ID, CONF_USER_ID, DOMAIN


async def paired_tv(hass: HomeAssistant, options: dict | None = None) -> tuple[MockConfigEntry, str]:
    """A set-up TV entry and the TV's access token."""
    # A real Home Assistant has its owner already. Without one, the TV's user would become the owner.
    await hass.auth.async_create_user("Owner")
    user = await hass.auth.async_create_user("TV Panel (BRAVIA)", local_only=True)
    refresh = await hass.auth.async_create_refresh_token(
        user, client_name="TV Panel", token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
    )
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="abc123",
        title="BRAVIA",
        data={"host": "192.168.1.50", "port": 8766, "name": "BRAVIA", CONF_TV_ID: "abc123", CONF_USER_ID: user.id},
        options=options or {},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry, hass.auth.async_create_access_token(refresh)
