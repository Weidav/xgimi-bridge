"""Constants for the XGIMI Bridge integration."""

DOMAIN = "xgimi_bridge"

CONF_HOST = "host"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_NAME = "XGIMI Horizon 20"

DEFAULT_PORT = 8080
WAKE_PORT = 8081
CAST_WAKE_PORT = 8010
REMOTE_WAKE_PORT = 6465

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
    1: "Movie",
    9: "Football",
    6: "Office",
    4: "Game",
    3: "User",
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

SLIDERS = [
    ("brightness", "Brightness", "brightness", "mdi:brightness-6"),
    ("contrast", "Contrast", "contrast", "mdi:contrast-circle"),
    ("saturation", "Saturation", "saturation", "mdi:palette"),
    ("sharpness", "Sharpness", "sharpness", "mdi:image-filter-center-focus"),
    ("hue", "Hue", "hue", "mdi:tune"),
    ("wbRed", "White Balance Red Gain", "wb-red", "mdi:square-rounded"),
    ("wbGreen", "White Balance Green Gain", "wb-green", "mdi:square-rounded"),
    ("wbBlue", "White Balance Blue Gain", "wb-blue", "mdi:square-rounded"),
]
