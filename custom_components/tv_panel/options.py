"""Configure: the settings moved to the TV Panel page in the sidebar, which can show live values and a preview."""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow

from .panel import URL_PATH


class TvPanelOptionsFlow(OptionsFlow):
    """Points at the page."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        return self.async_abort(reason="use_page", description_placeholders={"url": f"/{URL_PATH}"})
