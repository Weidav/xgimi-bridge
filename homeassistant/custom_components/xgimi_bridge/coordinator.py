"""DataUpdateCoordinator for XGIMI Bridge."""

from __future__ import annotations

import asyncio
from datetime import timedelta
import logging
from typing import Any

import aiohttp

from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator

from .const import CAST_WAKE_PORT, DEFAULT_PORT, DOMAIN, REMOTE_WAKE_PORT, WAKE_PORT

_LOGGER = logging.getLogger(__name__)
SCAN_INTERVAL = timedelta(seconds=10)


class XgimiDataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Coordinator to fetch picture settings and presets from XGIMI Bridge."""

    def __init__(self, hass: HomeAssistant, host: str, name: str) -> None:
        """Initialize the coordinator."""
        super().__init__(
            hass,
            _LOGGER,
            name=f"{name} Coordinator",
            update_interval=SCAN_INTERVAL,
        )
        self.host = host
        self.device_name = name
        self.session = async_get_clientsession(hass)
        self._last_data: dict[str, Any] = {
            "awake": False,
            "online": False,
            "picture": {},
            "presets": [],
        }

    async def _async_update_data(self) -> dict[str, Any]:
        """Fetch picture data and presets from projector bridge."""
        pic_url = f"http://{self.host}:{DEFAULT_PORT}/api/picture"
        presets_url = f"http://{self.host}:{DEFAULT_PORT}/api/presets"

        try:
            async with self.session.get(pic_url, timeout=aiohttp.ClientTimeout(total=3.0)) as resp:
                if resp.status == 200:
                    pic = await resp.json()
                    self._last_data["picture"] = pic
                    self._last_data["awake"] = bool(pic.get("awake", True))
                    self._last_data["online"] = True
                else:
                    self._last_data["awake"] = False
                    self._last_data["online"] = False
        except Exception:
            # Standby or network drop: do not mark entity unavailable so user can turn on
            self._last_data["awake"] = False
            self._last_data["online"] = False

        if self._last_data["online"]:
            try:
                async with self.session.get(presets_url, timeout=aiohttp.ClientTimeout(total=3.0)) as resp:
                    if resp.status == 200:
                        presets = await resp.json()
                        if isinstance(presets, list):
                            self._last_data["presets"] = presets
            except Exception:
                pass

        return self._last_data

    async def async_turn_on(self) -> None:
        """Wake projector from WoWLAN standby and turn display on."""
        # Step 1: TCP knock on WoWLAN ports to wake the MediaTek CPU from STR suspend
        for port in (CAST_WAKE_PORT, REMOTE_WAKE_PORT):
            try:
                conn = asyncio.open_connection(self.host, port)
                _, writer = await asyncio.wait_for(conn, timeout=0.8)
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

        await asyncio.sleep(0.8)

        # Step 2: Hit shell-UID wake listener on port 8081 (KeyDispatcher)
        woke = False
        wake_url = f"http://{self.host}:{WAKE_PORT}/wake"
        for _ in range(4):
            try:
                async with self.session.post(wake_url, timeout=aiohttp.ClientTimeout(total=1.2)) as resp:
                    if resp.status == 200:
                        woke = True
                        break
            except Exception:
                await asyncio.sleep(0.6)

        # Fallback to standard power/on if already awake
        if not woke:
            try:
                on_url = f"http://{self.host}:{DEFAULT_PORT}/api/power/on"
                async with self.session.post(on_url, timeout=aiohttp.ClientTimeout(total=1.5)):
                    pass
            except Exception:
                pass

        self._last_data["awake"] = True
        self._last_data["online"] = True
        self.async_set_updated_data(self._last_data)
        self.hass.loop.call_later(2.0, lambda: self.hass.async_create_task(self.async_request_refresh()))

    async def async_turn_off(self) -> None:
        """Put projector display into standby."""
        url = f"http://{self.host}:{DEFAULT_PORT}/api/power/off"
        try:
            async with self.session.post(url, timeout=aiohttp.ClientTimeout(total=2.0)):
                pass
        except Exception:
            pass
        self._last_data["awake"] = False
        self.async_set_updated_data(self._last_data)
        self.hass.loop.call_later(2.0, lambda: self.hass.async_create_task(self.async_request_refresh()))

    async def async_post_json(self, path: str, body: dict[str, Any] | None = None) -> bool:
        """Send a mutating POST request to the bridge."""
        url = f"http://{self.host}:{DEFAULT_PORT}{path}"
        try:
            async with self.session.post(url, json=body, timeout=aiohttp.ClientTimeout(total=3.0)) as resp:
                if resp.status == 200:
                    await self.async_request_refresh()
                    return True
        except Exception as err:
            _LOGGER.warning("POST %s failed: %s", path, err)
        return False

    async def async_inject_key(self, key_code: int) -> bool:
        """Inject an Android keycode."""
        return await self.async_post_json(f"/api/adb/keyevent/{key_code}")
