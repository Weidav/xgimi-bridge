"""Number platform for XGIMI Bridge (picture & white-balance sliders)."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import XgimiEntity
from .const import DOMAIN, SLIDERS
from .coordinator import XgimiDataUpdateCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the XGIMI slider number entities."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    entities = [
        XgimiSlider(coordinator, key, name, endpoint, icon)
        for key, name, endpoint, icon in SLIDERS
    ]
    async_add_entities(entities)


class XgimiSlider(XgimiEntity, NumberEntity):
    """0-100 slider for picture knobs and RGB white balance gains."""

    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_mode = NumberMode.SLIDER

    def __init__(
        self,
        coordinator: XgimiDataUpdateCoordinator,
        key: str,
        name: str,
        endpoint: str,
        icon: str,
    ) -> None:
        """Initialize the slider."""
        super().__init__(coordinator, f"slider_{key}")
        self._key = key
        self._attr_name = name
        self._endpoint = endpoint
        self._attr_icon = icon

    @property
    def native_value(self) -> float | None:
        """Return the current slider value."""
        val = self.coordinator.data.get("picture", {}).get(self._key)
        if isinstance(val, (int, float)):
            return float(val)
        return None

    async def async_set_native_value(self, value: float) -> None:
        """Set a new value on the projector."""
        int_val = round(value)
        await self.coordinator.async_post_json(
            f"/api/picture/{self._endpoint}",
            {"value": int_val},
        )
