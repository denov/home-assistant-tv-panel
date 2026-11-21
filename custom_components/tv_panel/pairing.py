"""Talking to the TV app's pairing server (PairServer.java), and making the TV's Home Assistant user and token."""

from __future__ import annotations

from datetime import timedelta
from typing import Any

import aiohttp
from homeassistant.auth.const import GROUP_ID_ADMIN, GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN, User
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import CLIENT_NAME, USER_NAME

TIMEOUT = aiohttp.ClientTimeout(total=15)
# The TV keeps its token until the integration is removed, which deletes the user and so the token.
TOKEN_LIFETIME = timedelta(days=3650)


class PairError(Exception):
    """Pairing failed; str() is the config flow's error key."""


async def async_info(hass: HomeAssistant, host: str, port: int) -> dict[str, Any]:
    """GET /info: the TV's id and name. Raises PairError("cannot_connect")."""
    session = async_get_clientsession(hass)
    try:
        async with session.get(f"http://{host}:{port}/info", timeout=TIMEOUT) as r:
            if r.status != 200:
                raise PairError("cannot_connect")
            info = await r.json(content_type=None)
    except (aiohttp.ClientError, TimeoutError, ValueError) as err:
        raise PairError("cannot_connect") from err
    if not isinstance(info, dict) or not info.get("id"):
        raise PairError("cannot_connect")
    return info


def ha_url(hass: HomeAssistant) -> str:
    """The address the TV should use: the local one if there is one."""
    try:
        return get_url(hass, allow_cloud=False, prefer_external=False)
    except NoURLAvailableError as err:
        raise PairError("no_url") from err


async def async_create_user(hass: HomeAssistant, tv_name: str) -> tuple[User, str]:
    """A normal user for this TV, with a long-lived token. Local network only, and no way to log in with it."""
    user = await hass.auth.async_create_user(f"{USER_NAME} ({tv_name})", group_ids=[GROUP_ID_USER], local_only=True)
    refresh = await hass.auth.async_create_refresh_token(
        user,
        client_name=f"{CLIENT_NAME} ({tv_name})",
        token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN,
        access_token_expiration=TOKEN_LIFETIME,
    )
    return user, hass.auth.async_create_access_token(refresh)


async def async_remove_user(hass: HomeAssistant, user_id: str | None) -> None:
    """Delete the TV's user, which revokes its token."""
    if not user_id:
        return
    user = await hass.auth.async_get_user(user_id)
    if user is not None:
        await hass.auth.async_remove_user(user)


async def async_pair(
    hass: HomeAssistant, host: str, port: int, code: str, tv_name: str
) -> tuple[dict[str, Any], str]:
    """Send the code, the address and a new token to the TV. Returns the TV's answer and the new user's id.

    The user is removed again if the TV says no, so failed tries leave nothing behind.
    """
    url = ha_url(hass)
    user, token = await async_create_user(hass, tv_name)
    session = async_get_clientsession(hass)
    try:
        async with session.post(
            f"http://{host}:{port}/pair",
            json={"code": code.replace(" ", ""), "url": url, "token": token},
            timeout=TIMEOUT,
        ) as r:
            answer = await r.json(content_type=None)
            status = r.status
    except (aiohttp.ClientError, TimeoutError, ValueError) as err:
        await async_remove_user(hass, user.id)
        raise PairError("cannot_connect") from err
    if status != 200 or not isinstance(answer, dict):
        await async_remove_user(hass, user.id)
        error = answer.get("error") if isinstance(answer, dict) else None
        raise PairError(error if error in ("invalid_code", "cannot_reach_ha", "token_refused") else "cannot_connect")
    if answer.get("needs_admin", True):
        # App 0.5.0 and older render their own templates through /api/template, which only an admin may call. They
        # don't say "needs_admin": false. Once updated, the app asks /api/tv_panel/data instead and the user drops back.
        await hass.auth.async_update_user(user, group_ids=[GROUP_ID_ADMIN])
    return answer, user.id
