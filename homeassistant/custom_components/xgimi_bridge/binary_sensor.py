"""Binary sensor platform for XGIMI Bridge."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import XgimiEntity
from .const import DOMAIN
from .coordinator import XgimiDataUpdateCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the XGIMI binary sensors."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([XgimiPowerBinarySensor(coordinator)])


class XgimiPowerBinarySensor(XgimiEntity, BinarySensorEntity):
    """Binary sensor for projector awake/power state."""

    _attr_name = "Power State"
    _attr_device_class = BinarySensorDeviceClass.POWER

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize binary sensor."""
        super().__init__(coordinator, "power_binary_sensor")

    @property
    def is_on(self) -> bool:
        """Return true if projector is awake."""
        return bool(self.coordinator.data.get("awake", False))
