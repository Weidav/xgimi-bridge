"""Constants for the XGIMI Bridge integration."""

DOMAIN = "xgimi_bridge"

CONF_HOST = "host"
CONF_MAC = "mac"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_NAME = "XGIMI Horizon 20"

DEFAULT_PORT = 8080
WAKE_PORT = 8081
CAST_WAKE_PORT = 8010
REMOTE_WAKE_PORT = 6465
WOL_WAKE_PORT = 9
WOL_DISCARD_PORT = 7
MDNS_WAKE_PORT = 5353

INPUT_SOURCES = {
    "E_INPUT_SOURCE_STORAGE": "Apps",
    "E_INPUT_SOURCE_HDMI1": "HDMI 1",
    "E_INPUT_SOURCE_HDMI2": "HDMI 2",
}
INPUT_TO_API = {
    "Apps": "STORAGE",
    "HDMI 1": "HDMI1",
    "HDMI 2": "HDMI2",
}

PICTURE_MODES = {
    8: "Standard",
    1: "Movie",
    0: "Vivid",
    4: "Game",
    9: "Sports",
    6: "Office",
    27: "Filmmaker Mode",
    30: "ISF Day",
    31: "ISF Night",
}
PICTURE_MODE_REV = {v: k for k, v in PICTURE_MODES.items()}

COLOR_TEMPS = {
    0: "System",
    1: "Warm",
    2: "Standard",
    3: "Cold",
    4: "User",
}
COLOR_TEMP_REV = {v: k for k, v in COLOR_TEMPS.items()}

GAME_MODES = {
    0: "Off",
    1: "Standard",
    2: "Boost",
}
GAME_MODE_REV = {v: k for k, v in GAME_MODES.items()}

from typing import NamedTuple


class SliderConfig(NamedTuple):
    """Configuration definition for a numeric slider entity."""

    key: str
    name: str
    endpoint: str
    icon: str


SLIDERS: list[SliderConfig] = [
    SliderConfig("brightness", "Brightness", "brightness", "mdi:brightness-6"),
    SliderConfig("contrast", "Contrast", "contrast", "mdi:contrast-circle"),
    SliderConfig("saturation", "Saturation", "saturation", "mdi:palette"),
    SliderConfig("sharpness", "Sharpness", "sharpness", "mdi:image-filter-center-focus"),
    SliderConfig("hue", "Hue", "hue", "mdi:tune"),
    SliderConfig("wbRed", "White Balance Red Gain", "wb-red", "mdi:square-rounded"),
    SliderConfig("wbGreen", "White Balance Green Gain", "wb-green", "mdi:square-rounded"),
    SliderConfig("wbBlue", "White Balance Blue Gain", "wb-blue", "mdi:square-rounded"),
]

# Shared keycode mapping across Web and Home Assistant
KEYCODES: dict[str, tuple[int, str, str]] = {
    "autofocus": (140, "Auto Focus", "mdi:camera-iris"),
    "settings": (176, "Settings Dashboard", "mdi:cog"),
    "home": (3, "Home", "mdi:home"),
    "back": (4, "Back", "mdi:keyboard-backspace"),
    "menu": (82, "Menu", "mdi:menu"),
    "volume_up": (24, "Volume Up", "mdi:volume-high"),
    "volume_down": (25, "Volume Down", "mdi:volume-medium"),
    "volume_mute": (164, "Mute", "mdi:volume-mute"),
    "assist": (219, "Assistant", "mdi:microphone"),
}
