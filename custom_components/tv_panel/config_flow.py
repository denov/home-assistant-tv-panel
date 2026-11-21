"""Adding a TV: found by zeroconf or typed in, then paired with the code the TV shows."""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
import voluptuous as vol

from .const import CONF_TV_ID, CONF_USER_ID, DEFAULT_PORT, DOMAIN
from .options import TvPanelOptionsFlow
from .pairing import PairError, async_info, async_pair

CONF_CODE = "code"


class TvPanelConfigFlow(ConfigFlow, domain=DOMAIN):
    """One entry per TV, keyed by the id the app makes for itself."""

    VERSION = 1

    @staticmethod
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        """Configure: the chips, the camera grid's order and the solar layout."""
        return TvPanelOptionsFlow()

    def __init__(self) -> None:
        self._host = ""
        self._port = DEFAULT_PORT
        self._name = "TV"

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """The TV announces itself while its TV Panel page is open."""
        tv_id = discovery_info.properties.get("id")
        if not tv_id:
            return self.async_abort(reason="cannot_connect")
        self._host = discovery_info.host
        self._port = discovery_info.port or DEFAULT_PORT
        self._name = discovery_info.properties.get("name") or "TV"
        await self.async_set_unique_id(tv_id)
        # A TV that's already set up but moved address: follow it.
        self._abort_if_unique_id_configured(updates={CONF_HOST: self._host, CONF_PORT: self._port})
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_pair()

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Typed in: the address the TV shows on its TV Panel page."""
        errors: dict[str, str] = {}
        if user_input is not None:
            host, _, port = user_input[CONF_HOST].strip().partition(":")
            self._host = host
            self._port = int(port) if port.isdigit() else DEFAULT_PORT
            try:
                info = await async_info(self.hass, self._host, self._port)
            except PairError as err:
                errors["base"] = str(err)
            else:
                self._name = info.get("name") or "TV"
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_configured(updates={CONF_HOST: self._host, CONF_PORT: self._port})
                return await self.async_step_pair()
        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema({vol.Required(CONF_HOST): str}),
            errors=errors,
        )

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """The six-digit code on the TV."""
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                answer, user_id = await async_pair(
                    self.hass, self._host, self._port, user_input[CONF_CODE], self._name
                )
            except PairError as err:
                errors["base"] = str(err)
            else:
                return self.async_create_entry(
                    title=answer.get("name") or self._name,
                    data={
                        CONF_HOST: self._host,
                        CONF_PORT: self._port,
                        CONF_NAME: answer.get("name") or self._name,
                        CONF_TV_ID: self.unique_id,
                        CONF_USER_ID: user_id,
                    },
                )
        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required(CONF_CODE): str}),
            description_placeholders={"name": self._name},
            errors=errors,
        )
