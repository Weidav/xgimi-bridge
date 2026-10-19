"""The XGIMI Bridge Home Assistant Integration."""

from __future__ import annotations

import logging

import aiohttp
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_NAME, Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryNotReady
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import CONF_HOST, DEFAULT_HOST, DEFAULT_NAME, DEFAULT_PORT, DOMAIN
from .coordinator import XgimiDataUpdateCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [
    Platform.SWITCH,
    Platform.BINARY_SENSOR,
    Platform.SELECT,
    Platform.NUMBER,
    Platform.BUTTON,
]


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up XGIMI Bridge from a config entry."""
    host = entry.data.get(CONF_HOST, DEFAULT_HOST)
    name = entry.data.get(CONF_NAME, DEFAULT_NAME)

    coordinator = XgimiDataUpdateCoordinator(hass, host, name)
    coordinator.config_entry_id = entry.entry_id

    # Refresh data, but do not fail setup if projector is off in standby
    try:
        await coordinator.async_config_entry_first_refresh()
    except (ConfigEntryNotReady, TimeoutError, aiohttp.ClientError) as err:
        _LOGGER.debug("Initial refresh warning (projector may be off): %s", err)

    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = coordinator

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    entry.async_on_unload(entry.add_update_listener(async_reload_entry))

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
    return unload_ok


async def async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload config entry after options change."""
    await hass.config_entries.async_reload(entry.entry_id)


class XgimiEntity(CoordinatorEntity[XgimiDataUpdateCoordinator]):
    """Base entity for all XGIMI Bridge devices."""

    _attr_has_entity_name = True

    def __init__(
        self, coordinator: XgimiDataUpdateCoordinator, unique_suffix: str
    ) -> None:
        """Initialize the entity."""
        super().__init__(coordinator)
        self._unique_suffix = unique_suffix
        self._attr_unique_id = f"{coordinator.config_entry_id}_{unique_suffix}"

    @property
    def device_info(self) -> DeviceInfo:
        """Return device information linking all entities to a single XGIMI Device."""
        return DeviceInfo(
            identifiers={(DOMAIN, self.coordinator.config_entry_id)},
            name=self.coordinator.device_name,
            manufacturer="XGIMI",
            model="Horizon 20 (XR14A)",
            sw_version="Android 14",
            configuration_url=f"http://{self.coordinator.host}:{DEFAULT_PORT}",
        )
