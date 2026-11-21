"""tv_panel.show: open a page or one camera on the TV, from any automation or script."""

from __future__ import annotations

import asyncio
import logging

from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv, device_registry as dr
import voluptuous as vol

from .const import DOMAIN
from .tv_link import CAMERA, PAGES, async_open, page_link

_LOGGER = logging.getLogger(__name__)

SHOW = "show"
SHOW_SCHEMA = vol.Schema(
    {
        vol.Required("page"): vol.In([*PAGES, CAMERA]),
        vol.Optional("camera"): cv.entity_domain("camera"),
        vol.Optional("wake", default=False): cv.boolean,
        vol.Optional("device_id"): vol.All(cv.ensure_list, [cv.string]),
    }
)


def _entries(hass: HomeAssistant, device_ids: list[str] | None) -> list:
    """The TVs to show it on: the chosen devices, or every paired TV."""
    entries = hass.config_entries.async_entries(DOMAIN)
    loaded = [e for e in entries if e.state is ConfigEntryState.LOADED]
    if not device_ids:
        if not loaded:
            raise ServiceValidationError("No TV is paired with TV Panel.")
        return loaded
    devices = dr.async_get(hass)
    chosen = []
    for device_id in device_ids:
        device = devices.async_get(device_id)
        name = (device.name_by_user or device.name or device_id) if device else device_id
        entry = next((e for e in entries if device is not None and e.entry_id in device.config_entries), None)
        if entry is None:
            raise ServiceValidationError(f"{name} isn't a TV Panel TV.")
        if entry.state is not ConfigEntryState.LOADED:
            raise ServiceValidationError(f"{name} isn't ready: TV Panel hasn't started for it.")
        chosen.append(entry)
    return chosen


async def _show(call: ServiceCall) -> None:
    """On every TV it's for. Without devices chosen, a TV that can't be reached is logged, as long as one was."""
    page = call.data["page"]
    if page == CAMERA and not call.data.get("camera"):
        raise ServiceValidationError("Choose the camera to show full screen.")
    link = page_link(page, call.data.get("camera"))
    entries = _entries(call.hass, call.data.get("device_id"))
    results = await asyncio.gather(
        *(async_open(call.hass, e, link, wake=call.data["wake"], close_screensaver=True) for e in entries),
        return_exceptions=True,
    )
    failed = [(e, r) for e, r in zip(entries, results, strict=True) if isinstance(r, BaseException)]
    for _, err in failed:
        if not isinstance(err, HomeAssistantError):
            raise err
    if failed and (call.data.get("device_id") or len(failed) == len(entries)):
        raise failed[0][1]
    for entry, err in failed:
        _LOGGER.warning("Not shown on %s: %s", entry.title, err)


def async_register(hass: HomeAssistant) -> None:
    hass.services.async_register(DOMAIN, SHOW, _show, schema=SHOW_SCHEMA)
