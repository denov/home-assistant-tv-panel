"""The TV Panel page in the sidebar (admins only), served from frontend/ in this integration."""

from __future__ import annotations

from pathlib import Path

from homeassistant.components import panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.core import HomeAssistant

URL_PATH = "tv-panel"
STATIC = "/tv_panel_static"
FRONTEND = Path(__file__).parent / "frontend"


async def _async_static(hass: HomeAssistant) -> None:
    await hass.http.async_register_static_paths([StaticPathConfig(STATIC, str(FRONTEND), cache_headers=False)])


def _version() -> str:
    """Changes with the page's code, so a browser doesn't keep an old copy after an update."""
    return str(int(max(p.stat().st_mtime for p in FRONTEND.glob("*.js"))))


async def async_register(hass: HomeAssistant) -> None:
    """Without the frontend (as in tests, or a headless setup) there is no sidebar to add to."""
    if "frontend" not in hass.config.components:
        return
    await _async_static(hass)
    version = await hass.async_add_executor_job(_version)
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path=URL_PATH,
        webcomponent_name="tv-panel-settings",
        sidebar_title="TV Panel",
        sidebar_icon="mdi:television-guide",
        module_url=f"{STATIC}/tv-panel.js?v={version}",
        require_admin=True,
    )
