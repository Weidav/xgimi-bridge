"""Button platform for XGIMI Bridge."""

from __future__ import annotations

from homeassistant.components.button import ButtonDeviceClass, ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
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
    """Set up the XGIMI button entities."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        [
            XgimiKeyButton(
                coordinator, "autofocus", "Auto Focus", 140, "mdi:camera-iris"
            ),
            XgimiKeyButton(
                coordinator, "settings", "Settings Dashboard", 176, "mdi:cog"
            ),
            XgimiKeyButton(coordinator, "home", "Home", 3, "mdi:home"),
            XgimiKeyButton(coordinator, "back", "Back", 4, "mdi:keyboard-backspace"),
            XgimiKeyButton(coordinator, "menu", "Menu", 82, "mdi:menu"),
            XgimiKeyButton(
                coordinator, "volume_up", "Volume Up", 24, "mdi:volume-high"
            ),
            XgimiKeyButton(
                coordinator, "volume_down", "Volume Down", 25, "mdi:volume-medium"
            ),
            XgimiKeyButton(coordinator, "volume_mute", "Mute", 164, "mdi:volume-mute"),
            XgimiPowerActionButton(
                coordinator, "lamp_off", "Lamp Off", "led-off", "mdi:lightbulb-off"
            ),
            XgimiPowerActionButton(
                coordinator, "lamp_on", "Lamp On", "led-on", "mdi:lightbulb-on"
            ),
            XgimiRestartButton(coordinator),
        ]
    )


class XgimiKeyButton(XgimiEntity, ButtonEntity):
    """Button to inject a remote keycode."""

    def __init__(
        self,
        coordinator: XgimiDataUpdateCoordinator,
        suffix: str,
        name: str,
        key_code: int,
        icon: str,
    ) -> None:
        """Initialize button entity."""
        super().__init__(coordinator, f"btn_{suffix}")
        self._attr_name = name
        self._key_code = key_code
        self._attr_icon = icon

    async def async_press(self) -> None:
        """Handle button press."""
        ok = await self.coordinator.async_inject_key(self._key_code)
        if not ok:
            raise HomeAssistantError(f"Failed to inject key {self._key_code} on {self.name}")


class XgimiPowerActionButton(XgimiEntity, ButtonEntity):
    """Button to trigger power actions (led-off, led-on)."""

    def __init__(
        self,
        coordinator: XgimiDataUpdateCoordinator,
        suffix: str,
        name: str,
        action: str,
        icon: str,
    ) -> None:
        """Initialize action button."""
        super().__init__(coordinator, f"btn_{suffix}")
        self._attr_name = name
        self._action = action
        self._attr_icon = icon

    async def async_press(self) -> None:
        """Handle button press."""
        ok = await self.coordinator.async_post_json(f"/api/power/{self._action}")
        if not ok:
            raise HomeAssistantError(f"Power action {self._action} failed on {self.name}")


class XgimiRestartButton(XgimiEntity, ButtonEntity):
    """Button to reboot the projector."""

    _attr_name = "Restart Projector"
    _attr_device_class = ButtonDeviceClass.RESTART
    _attr_entity_category = EntityCategory.CONFIG
    _attr_icon = "mdi:restart"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize restart button."""
        super().__init__(coordinator, "btn_restart")

    async def async_press(self) -> None:
        """Reboot projector."""
        ok = await self.coordinator.async_post_json("/api/power/restart")
        if not ok:
            raise HomeAssistantError(f"Restart failed on {self.name}")
