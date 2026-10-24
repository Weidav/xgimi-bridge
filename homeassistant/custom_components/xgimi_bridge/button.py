"""Button platform for XGIMI Bridge."""

from __future__ import annotations

from homeassistant.components.button import ButtonDeviceClass, ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import XgimiEntity
from .const import DOMAIN, KEYCODES
from .coordinator import XgimiDataUpdateCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the XGIMI button entities."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    key_buttons = [
        XgimiKeyButton(coordinator, suffix, name, code, icon)
        for suffix, (code, name, icon) in KEYCODES.items()
    ]
    async_add_entities(
        key_buttons
        + [
            XgimiPowerActionButton(
                coordinator, "lamp_off", "Lamp Off", "led-off", "mdi:lightbulb-off"
            ),
            XgimiPowerActionButton(
                coordinator, "lamp_on", "Lamp On", "led-on", "mdi:lightbulb-on"
            ),
            XgimiRestartButton(coordinator),
        ]
    )


class XgimiActionButton(XgimiEntity, ButtonEntity):
    """Base button entity executing a bridge action."""

    def __init__(
        self,
        coordinator: XgimiDataUpdateCoordinator,
        suffix: str,
        name: str | None = None,
        icon: str | None = None,
        device_class: ButtonDeviceClass | None = None,
        entity_category: EntityCategory | None = None,
    ) -> None:
        """Initialize action button."""
        super().__init__(coordinator, f"btn_{suffix}")
        if name is not None:
            self._attr_name = name
        if icon is not None:
            self._attr_icon = icon
        if device_class is not None:
            self._attr_device_class = device_class
        if entity_category is not None:
            self._attr_entity_category = entity_category

    async def _execute_press(self) -> bool:
        """Execute the action, returning True on success."""
        raise NotImplementedError

    def _failure_message(self) -> str:
        """Format the error message when action fails."""
        return f"Action failed on {self.name}"

    async def async_press(self) -> None:
        """Handle button press."""
        ok = await self._execute_press()
        if not ok:
            raise HomeAssistantError(self._failure_message())


class XgimiKeyButton(XgimiActionButton):
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
        super().__init__(coordinator, suffix, name=name, icon=icon)
        self._key_code = key_code

    async def _execute_press(self) -> bool:
        return await self.coordinator.async_inject_key(self._key_code)

    def _failure_message(self) -> str:
        return f"Failed to inject key {self._key_code} on {self.name}"


class XgimiPowerActionButton(XgimiActionButton):
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
        super().__init__(coordinator, suffix, name=name, icon=icon)
        self._action = action

    async def _execute_press(self) -> bool:
        return await self.coordinator.async_post_json(f"/api/power/{self._action}")

    def _failure_message(self) -> str:
        return f"Power action {self._action} failed on {self.name}"


class XgimiRestartButton(XgimiActionButton):
    """Button to reboot the projector."""

    _attr_name = "Restart Projector"
    _attr_device_class = ButtonDeviceClass.RESTART
    _attr_entity_category = EntityCategory.CONFIG
    _attr_icon = "mdi:restart"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize restart button."""
        super().__init__(coordinator, "restart")

    async def _execute_press(self) -> bool:
        return await self.coordinator.async_post_json("/api/power/restart")

    def _failure_message(self) -> str:
        return f"Restart failed on {self.name}"
