"""Select platform for XGIMI Bridge."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import XgimiEntity
from .const import (
    COLOR_TEMP_REV,
    COLOR_TEMPS,
    DOMAIN,
    GAME_MODE_REV,
    GAME_MODES,
    INPUT_SOURCES,
    INPUT_TO_API,
    PICTURE_MODE_REV,
    PICTURE_MODES,
)
from .coordinator import XgimiDataUpdateCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the XGIMI select entities."""
    coordinator: XgimiDataUpdateCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities(
        [
            XgimiInputSourceSelect(coordinator),
            XgimiPresetSelect(coordinator),
            XgimiPictureModeSelect(coordinator),
            XgimiColorTempSelect(coordinator),
            XgimiGameModeSelect(coordinator),
        ]
    )


class XgimiInputSourceSelect(XgimiEntity, SelectEntity):
    """Input source select (Apps, HDMI 1, HDMI 2)."""

    _attr_name = "Input Source"
    _attr_icon = "mdi:video-input-hdmi"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize select entity."""
        super().__init__(coordinator, "select_input_source")
        self._attr_options = list(INPUT_TO_API.keys())

    @property
    def current_option(self) -> str | None:
        """Return currently selected input source."""
        raw = self.coordinator.data.get("picture", {}).get("currentInputSource")
        return INPUT_SOURCES.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Switch input source."""
        api_val = INPUT_TO_API.get(option)
        if not api_val:
            raise HomeAssistantError(f"Unknown input source option: {option}")
        ok = await self.coordinator.async_post_json("/api/input", {"to": api_val})
        if not ok:
            raise HomeAssistantError(f"Failed to switch input source to {option}")


class XgimiPresetSelect(XgimiEntity, SelectEntity):
    """Named preset select (dynamically loaded from /api/presets)."""

    _attr_name = "Preset"
    _attr_icon = "mdi:bookmark-multiple"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize preset select."""
        super().__init__(coordinator, "select_preset")

    @property
    def options(self) -> list[str]:
        """Return list of available preset names."""
        presets = self.coordinator.data.get("presets", [])
        return [
            p.get("name") for p in presets if isinstance(p, dict) and p.get("name")
        ]

    @property
    def current_option(self) -> str | None:
        """Return active preset name if known."""
        cur = self.coordinator.data.get("active_preset")
        return cur if cur in self.options else None

    async def async_select_option(self, option: str) -> None:
        """Apply selected preset."""
        presets = self.coordinator.data.get("presets", [])
        matched = False
        for p in presets:
            if isinstance(p, dict) and p.get("name") == option:
                matched = True
                preset_id = p.get("id")
                if not preset_id:
                    raise HomeAssistantError(f"Preset {option} has no id")
                ok = await self.coordinator.async_post_json(
                    f"/api/presets/{preset_id}/apply"
                )
                if not ok:
                    raise HomeAssistantError(f"Failed to apply preset {option}")
                self.coordinator.data["active_preset"] = option
                self.async_write_ha_state()
                break
        if not matched:
            raise HomeAssistantError(f"Preset not found: {option}")


class XgimiPictureModeSelect(XgimiEntity, SelectEntity):
    """Picture mode selector."""

    _attr_name = "Picture Mode"
    _attr_icon = "mdi:television-guide"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize picture mode select."""
        super().__init__(coordinator, "select_picture_mode")
        self._attr_options = list(PICTURE_MODE_REV.keys())

    @property
    def current_option(self) -> str | None:
        """Return current picture mode."""
        raw = self.coordinator.data.get("picture", {}).get("pictureMode")
        return PICTURE_MODES.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set picture mode."""
        val = PICTURE_MODE_REV.get(option)
        if val is None:
            raise HomeAssistantError(f"Unknown picture mode: {option}")
        ok = await self.coordinator.async_post_json(
            "/api/picture/picture-mode", {"value": val}
        )
        if not ok:
            raise HomeAssistantError(f"Failed to set picture mode to {option}")


class XgimiColorTempSelect(XgimiEntity, SelectEntity):
    """Color temperature selector."""

    _attr_name = "Color Temperature"
    _attr_icon = "mdi:palette"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize color temperature select."""
        super().__init__(coordinator, "select_color_temp")
        self._attr_options = list(COLOR_TEMP_REV.keys())

    @property
    def current_option(self) -> str | None:
        """Return current color temperature."""
        raw = self.coordinator.data.get("picture", {}).get("colorTemp")
        return COLOR_TEMPS.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set color temperature."""
        val = COLOR_TEMP_REV.get(option)
        if val is None:
            raise HomeAssistantError(f"Unknown color temperature: {option}")
        ok = await self.coordinator.async_post_json(
            "/api/picture/color-temp", {"value": val}
        )
        if not ok:
            raise HomeAssistantError(f"Failed to set color temperature to {option}")


class XgimiGameModeSelect(XgimiEntity, SelectEntity):
    """Game mode selector."""

    _attr_name = "Game Mode"
    _attr_icon = "mdi:gamepad-variant"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize game mode select."""
        super().__init__(coordinator, "select_game_mode")
        self._attr_options = list(GAME_MODE_REV.keys())

    @property
    def current_option(self) -> str | None:
        """Return current game mode."""
        raw = self.coordinator.data.get("picture", {}).get("gameMode")
        return GAME_MODES.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set game mode."""
        val = GAME_MODE_REV.get(option)
        if val is None:
            raise HomeAssistantError(f"Unknown game mode: {option}")
        ok = await self.coordinator.async_post_json(
            "/api/picture/game-mode", {"value": val}
        )
        if not ok:
            raise HomeAssistantError(f"Failed to set game mode to {option}")
