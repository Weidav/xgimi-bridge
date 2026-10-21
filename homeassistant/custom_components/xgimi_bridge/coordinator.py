"""DataUpdateCoordinator for XGIMI Bridge."""

from __future__ import annotations

import asyncio
import logging
from datetime import timedelta
from typing import Any

import aiohttp
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator

from .const import CAST_WAKE_PORT, DEFAULT_PORT, REMOTE_WAKE_PORT, WAKE_PORT

_LOGGER = logging.getLogger(__name__)
SCAN_INTERVAL = timedelta(seconds=10)


def format_host(host: str) -> str:
    """Format host for URLs, bracket IPv6 literals if needed."""
    if ":" in host and not host.startswith("["):
        return f"[{host}]"
    return host


class XgimiDataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Coordinator to fetch picture settings and presets from XGIMI Bridge."""

    def __init__(
        self,
        hass: HomeAssistant,
        host: str,
        name: str,
        config_entry: ConfigEntry | None = None,
        session: aiohttp.ClientSession | None = None,
    ) -> None:
        """Initialize the coordinator."""
        super().__init__(
            hass,
            _LOGGER,
            name=f"{name} Coordinator",
            update_interval=SCAN_INTERVAL,
            config_entry=config_entry,
        )
        self.host = host
        self.formatted_host = format_host(host)
        self.device_name = name
        self.session = session if session is not None else async_get_clientsession(hass)
        self._unloaded = False
        self._refresh_tasks: set[asyncio.TimerHandle | asyncio.Task[Any]] = set()
        self._last_data: dict[str, Any] = {
            "awake": False,
            "online": False,
            "picture": {},
            "presets": [],
        }

    async def async_unload(self) -> None:
        """Cancel delayed refresh tasks and mark coordinator unloaded."""
        self._unloaded = True
        for item in list(self._refresh_tasks):
            try:
                item.cancel()
            except (asyncio.CancelledError, OSError) as err:
                _LOGGER.debug("Task cancel error: %s", err)
        self._refresh_tasks.clear()

    def _schedule_delayed_refresh(self, delay: float = 2.0) -> None:
        """Schedule a delayed refresh and track its lifecycle."""
        if self._unloaded:
            return

        def _on_timer() -> None:
            if self._unloaded:
                return
            task = self.hass.async_create_task(self.async_request_refresh())
            self._refresh_tasks.add(task)
            task.add_done_callback(self._refresh_tasks.discard)

        handle = self.hass.loop.call_later(delay, _on_timer)
        self._refresh_tasks.add(handle)

    async def _async_update_data(self) -> dict[str, Any]:
        """Fetch picture data and presets from projector bridge."""
        if self._unloaded:
            return self._last_data

        pic_url = f"http://{self.formatted_host}:{DEFAULT_PORT}/api/picture"
        presets_url = f"http://{self.formatted_host}:{DEFAULT_PORT}/api/presets"

        try:
            async with self.session.get(
                pic_url, timeout=aiohttp.ClientTimeout(total=3.0)
            ) as resp:
                if resp.status == 200:
                    pic = await resp.json()
                    self._last_data["picture"] = pic
                    self._last_data["awake"] = bool(pic.get("awake", True))
                    self._last_data["online"] = True
                else:
                    self._last_data["awake"] = False
                    self._last_data["online"] = False
        except (aiohttp.ClientError, TimeoutError) as err:
            _LOGGER.debug("Picture state request failed (standby or offline): %s", err)
            self._last_data["awake"] = False
            self._last_data["online"] = False

        if self._last_data["online"]:
            try:
                async with self.session.get(
                    presets_url, timeout=aiohttp.ClientTimeout(total=3.0)
                ) as resp:
                    if resp.status == 200:
                        presets = await resp.json()
                        if isinstance(presets, list):
                            self._last_data["presets"] = presets
            except (aiohttp.ClientError, TimeoutError) as err:
                _LOGGER.debug("Presets request failed: %s", err)

        return self._last_data

    async def async_turn_on(self) -> bool:
        """Wake projector from WoWLAN standby and turn display on."""
        if self._unloaded:
            return False

        # Step 1: TCP knock on WoWLAN ports to wake the MediaTek CPU from STR suspend
        for port in (CAST_WAKE_PORT, REMOTE_WAKE_PORT):
            try:
                conn = asyncio.open_connection(self.host, port)
                _, writer = await asyncio.wait_for(conn, timeout=0.8)
                writer.close()
                await writer.wait_closed()
            except (OSError, TimeoutError) as err:
                _LOGGER.debug("WoWLAN knock on port %s: %s", port, err)

        await asyncio.sleep(0.8)

        # Step 2: Hit shell-UID wake listener on port 8081 (KeyDispatcher)
        woke = False
        wake_url = f"http://{self.formatted_host}:{WAKE_PORT}/wake"
        for _ in range(4):
            try:
                async with self.session.post(
                    wake_url, timeout=aiohttp.ClientTimeout(total=1.2)
                ) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        if data and data.get("ok") is not False:
                            woke = True
                            break
            except (aiohttp.ClientError, TimeoutError):
                await asyncio.sleep(0.6)

        fallback_ok = False
        # Fallback to standard power/on if wake didn't confirm
        if not woke:
            try:
                on_url = f"http://{self.formatted_host}:{DEFAULT_PORT}/api/power/on"
                async with self.session.post(
                    on_url, timeout=aiohttp.ClientTimeout(total=1.5)
                ) as resp:
                    if resp.status in (200, 202):
                        data = await resp.json()
                        if data and data.get("ok") is not False:
                            fallback_ok = True
            except (aiohttp.ClientError, TimeoutError) as err:
                _LOGGER.debug("Fallback power on request failed: %s", err)

        if woke or fallback_ok:
            self._last_data["awake"] = True
            self._last_data["online"] = True
            self.async_set_updated_data(self._last_data)
            self._schedule_delayed_refresh(2.0)
            return True

        return False

    async def async_turn_off(self) -> bool:
        """Put projector display into standby."""
        if self._unloaded:
            return False

        url = f"http://{self.formatted_host}:{DEFAULT_PORT}/api/power/off"
        success = False
        try:
            async with self.session.post(url, timeout=aiohttp.ClientTimeout(total=2.0)) as resp:
                if resp.status in (200, 202):
                    data = await resp.json()
                    if data and data.get("ok") is not False:
                        success = True
        except (aiohttp.ClientError, TimeoutError) as err:
            _LOGGER.debug("Power off request exception: %s", err)
            success = False

        if success:
            self._last_data["awake"] = False
            self.async_set_updated_data(self._last_data)
        self._schedule_delayed_refresh(2.0)
        return success

    async def async_post_json(
        self, path: str, body: dict[str, Any] | None = None
    ) -> bool:
        """Send a mutating POST request to the bridge."""
        if self._unloaded:
            return False

        url = f"http://{self.formatted_host}:{DEFAULT_PORT}{path}"
        kwargs: dict[str, Any] = {"timeout": aiohttp.ClientTimeout(total=3.0)}
        if body is not None:
            kwargs["json"] = body
        try:
            async with self.session.post(url, **kwargs) as resp:
                if resp.status in (200, 202):
                    try:
                        data = await resp.json()
                        if isinstance(data, dict) and data.get("ok") is False:
                            _LOGGER.warning("POST %s returned ok:false: %s", path, data.get("error"))
                            return False
                    except (ValueError, aiohttp.ContentTypeError):
                        pass
                    if not self._unloaded:
                        await self.async_request_refresh()
                    return True
                else:
                    _LOGGER.warning("POST %s failed with HTTP status %s", path, resp.status)
                    return False
        except (aiohttp.ClientError, TimeoutError) as err:
            _LOGGER.warning("POST %s failed: %s", path, err)
            return False

    async def async_inject_key(self, key_code: int) -> bool:
        """Inject an Android keycode."""
        return await self.async_post_json(f"/api/adb/keyevent/{key_code}")
