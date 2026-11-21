"""Configure now points at the TV Panel page in the sidebar, where the settings are edited."""

from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType

from .common import paired_tv


async def test_configure_points_at_the_page(hass: HomeAssistant) -> None:
    entry, _ = await paired_tv(hass)
    result = await hass.config_entries.options.async_init(entry.entry_id)
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "use_page"
    assert result["description_placeholders"] == {"url": "/tv-panel"}
