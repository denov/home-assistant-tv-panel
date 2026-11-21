"""Configure: the chips in order, the camera grid's order and the solar panel layout.

The first time it opens, it starts from how TV Panel was set up before the integration: entities labelled
tv_widget, and input_text.tv_camera_order (camera names or ids, comma-separated).
"""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er, label_registry as lr
from homeassistant.helpers.selector import EntitySelector, EntitySelectorConfig, ObjectSelector
import voluptuous as vol

from .const import OPT_CAMERA_ORDER, OPT_ENTITIES, OPT_SOLAR

OLD_LABEL = "tv_widget"
OLD_CAMERA_ORDER = "input_text.tv_camera_order"


def labelled_entities(hass: HomeAssistant) -> list[str]:
    """Entities carrying the old tv_widget label (by label id or name)."""
    labels = lr.async_get(hass)
    label = labels.async_get_label(OLD_LABEL) or labels.async_get_label_by_name(OLD_LABEL)
    if label is None:
        return []
    return [e.entity_id for e in er.async_entries_for_label(er.async_get(hass), label.label_id)]


def camera_name(hass: HomeAssistant, entity_id: str) -> str:
    """"Front Door" from "Camera - Front Door", as the TV shows it."""
    state = hass.states.get(entity_id)
    name = (state.attributes.get("friendly_name") if state else None) or entity_id.split(".", 1)[1]
    for prefix in ("camera - ", "camera: "):
        if name.lower().startswith(prefix):
            name = name[len(prefix) :]
    return name.strip().lower()


def old_camera_order(hass: HomeAssistant, cameras: list[str]) -> list[str]:
    """The helper's names and ids as camera entity ids, in its order. Names that match no camera are dropped."""
    state = hass.states.get(OLD_CAMERA_ORDER)
    if state is None or state.state in ("unknown", "unavailable"):
        return []
    out: list[str] = []
    for want in (w.strip().lower() for w in state.state.split(",")):
        for cam in cameras:
            if cam not in out and want in (cam.lower(), camera_name(hass, cam)):
                out.append(cam)
                break
    return out


class TvPanelOptionsFlow(OptionsFlow):
    """One form: the order of the chips list is the order on the TV."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        options = self.config_entry.options
        if OPT_ENTITIES in options:
            entities = options[OPT_ENTITIES]
            camera_order = options.get(OPT_CAMERA_ORDER, [])
        else:
            entities = labelled_entities(self.hass)
            cameras = sorted(e for e in entities if e.startswith("camera."))
            camera_order = old_camera_order(self.hass, cameras)
        schema: dict[Any, Any] = {
            vol.Optional(OPT_ENTITIES, default=entities): EntitySelector(EntitySelectorConfig(multiple=True)),
            vol.Optional(OPT_CAMERA_ORDER, default=camera_order): EntitySelector(
                EntitySelectorConfig(domain="camera", multiple=True)
            ),
        }
        solar = options.get(OPT_SOLAR)
        schema[vol.Optional(OPT_SOLAR, default=solar) if solar else vol.Optional(OPT_SOLAR)] = ObjectSelector()
        return self.async_show_form(step_id="init", data_schema=vol.Schema(schema))
