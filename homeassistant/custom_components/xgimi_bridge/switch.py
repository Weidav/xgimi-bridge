"""Switch platform for XGIMI Bridge."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import XgimiEntity
from .const import DOMAIN
from .coordinator import XgimiDataUpdateCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the XGIMI Power Switch."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([XgimiPowerSwitch(coordinator)])


class XgimiPowerSwitch(XgimiEntity, SwitchEntity):
    """Power switch for XGIMI Projector with WoWLAN wake support."""

    _attr_name = "Power"
    _attr_icon = "mdi:projector"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize the power switch."""
        super().__init__(coordinator, "power_switch")

    @property
    def is_on(self) -> bool:
        """Return true if projector is on/awake."""
        return bool(self.coordinator.data.get("awake", False))

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Turn on the projector."""
        ok = await self.coordinator.async_turn_on()
        if not ok:
            raise HomeAssistantError(f"Failed to turn on {self.name}")

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Turn off the projector."""
        ok = await self.coordinator.async_turn_off()
        if not ok:
            raise HomeAssistantError(f"Failed to turn off {self.name}")
