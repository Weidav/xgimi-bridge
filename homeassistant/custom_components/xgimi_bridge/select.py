"""Select platform for XGIMI Bridge."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
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
    _attr_options = list(INPUT_TO_API.keys())

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize select entity."""
        super().__init__(coordinator, "select_input_source")

    @property
    def current_option(self) -> str | None:
        """Return currently selected input source."""
        raw = self.coordinator.data.get("picture", {}).get("currentInputSource")
        return INPUT_SOURCES.get(raw, "Apps")

    async def async_select_option(self, option: str) -> None:
        """Switch input source."""
        api_val = INPUT_TO_API.get(option)
        if api_val:
            await self.coordinator.async_post_json("/api/input", {"to": api_val})


class XgimiPresetSelect(XgimiEntity, SelectEntity):
    """Named preset select (dynamically loaded from /api/presets)."""

    _attr_name = "Preset"
    _attr_icon = "mdi:bookmark-multiple"

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize preset select."""
        super().__init__(coordinator, "select_preset")
        self._current_preset_name: str | None = None

    @property
    def options(self) -> list[str]:
        """Return list of available preset names."""
        presets = self.coordinator.data.get("presets", [])
        names = [p.get("name") for p in presets if isinstance(p, dict) and p.get("name")]
        return names if names else ["Balanced"]

    @property
    def current_option(self) -> str | None:
        """Return active preset name if known."""
        return self._current_preset_name

    async def async_select_option(self, option: str) -> None:
        """Apply selected preset."""
        presets = self.coordinator.data.get("presets", [])
        for p in presets:
            if isinstance(p, dict) and p.get("name") == option:
                preset_id = p.get("id")
                if preset_id:
                    if await self.coordinator.async_post_json(f"/api/presets/{preset_id}/apply"):
                        self._current_preset_name = option
                        self.async_write_ha_state()
                break


class XgimiPictureModeSelect(XgimiEntity, SelectEntity):
    """Picture mode selector."""

    _attr_name = "Picture Mode"
    _attr_icon = "mdi:television-guide"
    _attr_options = list(PICTURE_MODE_REV.keys())

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize picture mode select."""
        super().__init__(coordinator, "select_picture_mode")

    @property
    def current_option(self) -> str | None:
        """Return current picture mode."""
        raw = self.coordinator.data.get("picture", {}).get("pictureMode")
        return PICTURE_MODES.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set picture mode."""
        val = PICTURE_MODE_REV.get(option)
        if val is not None:
            await self.coordinator.async_post_json("/api/picture/picture-mode", {"value": val})


class XgimiColorTempSelect(XgimiEntity, SelectEntity):
    """Color temperature selector."""

    _attr_name = "Color Temperature"
    _attr_icon = "mdi:palette"
    _attr_options = list(COLOR_TEMP_REV.keys())

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize color temperature select."""
        super().__init__(coordinator, "select_color_temp")

    @property
    def current_option(self) -> str | None:
        """Return current color temperature."""
        raw = self.coordinator.data.get("picture", {}).get("colorTemp")
        return COLOR_TEMPS.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set color temperature."""
        val = COLOR_TEMP_REV.get(option)
        if val is not None:
            await self.coordinator.async_post_json("/api/picture/color-temp", {"value": val})


class XgimiGameModeSelect(XgimiEntity, SelectEntity):
    """Game mode selector."""

    _attr_name = "Game Mode"
    _attr_icon = "mdi:gamepad-variant"
    _attr_options = list(GAME_MODE_REV.keys())

    def __init__(self, coordinator: XgimiDataUpdateCoordinator) -> None:
        """Initialize game mode select."""
        super().__init__(coordinator, "select_game_mode")

    @property
    def current_option(self) -> str | None:
        """Return current game mode."""
        raw = self.coordinator.data.get("picture", {}).get("gameMode")
        return GAME_MODES.get(raw)

    async def async_select_option(self, option: str) -> None:
        """Set game mode."""
        val = GAME_MODE_REV.get(option)
        if val is not None:
            await self.coordinator.async_post_json("/api/picture/game-mode", {"value": val})
