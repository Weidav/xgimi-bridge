"""DataUpdateCoordinator for XGIMI Bridge."""

from __future__ import annotations

import asyncio
import logging
import socket
import time
from datetime import timedelta
from typing import Any

import aiohttp
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator

from .const import (
    CAST_WAKE_PORT,
    CONF_MAC,
    DEFAULT_PORT,
    MDNS_WAKE_PORT,
    REMOTE_WAKE_PORT,
    WAKE_PORT,
    WOL_DISCARD_PORT,
    WOL_WAKE_PORT,
)

_LOGGER = logging.getLogger(__name__)
SCAN_INTERVAL = timedelta(seconds=10)


def format_host(host: str) -> str:
    """Format host for URLs, bracket IPv6 literals if needed."""
    if ":" in host and not host.startswith("["):
        return f"[{host}]"
    return host


def format_mac(mac: str | None) -> str | None:
    """Format and validate a MAC address as xx:xx:xx:xx:xx:xx or return None."""
    if not mac:
        return None
    cleaned = mac.replace(":", "").replace("-", "").replace(".", "").strip().lower()
    if len(cleaned) == 12 and all(c in "0123456789abcdef" for c in cleaned):
        return ":".join(cleaned[i : i + 2] for i in range(0, 12, 2))
    return None


def create_magic_packet(mac: str | None) -> bytes | None:
    """Create a standard 102-byte Wake-on-LAN magic packet."""
    formatted = format_mac(mac)
    if not formatted:
        return None
    mac_bytes = bytes.fromhex(formatted.replace(":", ""))
    return b"\xff" * 6 + mac_bytes * 16


def resolve_mac_from_arp(host: str) -> str | None:
    """Attempt to resolve MAC address for host from local ARP table."""
    clean_host = host.strip("[]")
    if ":" in clean_host:
        return None
    ip = clean_host
    try:
        parts = clean_host.split(".")
        if not (len(parts) == 4 and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts)):
            ip = socket.gethostbyname(clean_host)
    except Exception:
        pass

    try:
        with open("/proc/net/arp", "r", encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 4 and parts[0] == ip:
                    candidate = parts[3]
                    mac = format_mac(candidate)
                    if mac and mac != "00:00:00:00:00:00":
                        return mac
    except Exception:
        pass
    return None


def _send_udp_knock(
    host: str, port: int, payload: bytes = b"\x00", repeat: int = 2
) -> None:
    """Send best-effort UDP wake knock datagrams (unicast + broadcast)."""
    clean_host = host.strip("[]")
    is_ipv6 = ":" in clean_host
    sock_family = socket.AF_INET6 if is_ipv6 else socket.AF_INET
    try:
        sock = socket.socket(sock_family, socket.SOCK_DGRAM)
        sock.setblocking(False)
        if not is_ipv6:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for _ in range(repeat):
            sock.sendto(payload, (clean_host, port))
        sock.close()
    except Exception as err:
        _LOGGER.debug(
            "UDP wake knock to %s:%s failed: %s (%s)",
            host,
            port,
            type(err).__name__,
            err,
        )

    # Also send broadcast UDP for IPv4 to bypass stale ARP tables on sleeping targets
    if not is_ipv6 and clean_host != "255.255.255.255":
        try:
            b_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            b_sock.setblocking(False)
            b_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            for _ in range(repeat):
                b_sock.sendto(payload, ("255.255.255.255", port))
            b_sock.close()
        except Exception:
            pass


# Minimal standard mDNS PTR query for _googlecast._tcp.local
MDNS_CAST_QUERY = (
    b"\x00\x00"  # Transaction ID
    b"\x00\x00"  # Flags: Standard query
    b"\x00\x01"  # Questions: 1
    b"\x00\x00"  # Answer RRs
    b"\x00\x00"  # Authority RRs
    b"\x00\x00"  # Additional RRs
    b"\x0b_googlecast\x04_tcp\x05local\x00"
    b"\x00\x0c"  # Type: PTR (12)
    b"\x80\x01"  # Class: IN, unicast response requested
)


def _send_mdns_knock(host: str) -> None:
    """Send mDNS multicast and broadcast queries to wake Android TV Cast WoWLAN."""
    clean_host = host.strip("[]")
    # Multicast to 224.0.0.251:5353
    try:
        m_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        m_sock.setblocking(False)
        m_sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        m_sock.sendto(MDNS_CAST_QUERY, ("224.0.0.251", MDNS_WAKE_PORT))
        m_sock.close()
    except Exception:
        pass

    # Also broadcast & unicast to mDNS port 5353
    _send_udp_knock(clean_host, MDNS_WAKE_PORT, payload=MDNS_CAST_QUERY, repeat=1)


async def _send_tcp_knock(host: str, port: int, timeout: float = 0.8) -> None:
    """Send a quick TCP connection attempt to trigger WoWLAN."""
    try:
        conn = asyncio.open_connection(host, port)
        _, writer = await asyncio.wait_for(conn, timeout=timeout)
        writer.close()
        await writer.wait_closed()
    except (OSError, TimeoutError) as err:
        _LOGGER.debug(
            "TCP WoWLAN knock on port %s: %s (%s)", port, type(err).__name__, err
        )


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
        self.mac: str | None = None
        if config_entry is not None:
            self.mac = format_mac(config_entry.data.get(CONF_MAC))
        if not self.mac:
            self.mac = resolve_mac_from_arp(self.host)
        self.config_entry_id: str = (
            str(getattr(config_entry, "entry_id", "")) if config_entry is not None else ""
        )
        self._unloaded = False
        self._refresh_tasks: set[asyncio.TimerHandle | asyncio.Task[Any]] = set()
        self._power_transition_until: float = 0.0
        self._last_data: dict[str, Any] = {
            "awake": False,
            "online": False,
            "picture": {},
            "presets": [],
            "active_preset": None,
        }

    @property
    def model(self) -> str:
        """Return projector hardware model from telemetry or default."""
        pic = self._last_data.get("picture")
        if isinstance(pic, dict) and pic.get("model"):
            return str(pic["model"])
        return "Horizon 20 (XR14A)"

    @property
    def sw_version(self) -> str:
        """Return projector software / OS version from telemetry or default."""
        pic = self._last_data.get("picture")
        if isinstance(pic, dict) and pic.get("osVersion"):
            return str(pic["osVersion"])
        return "Android 14"

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

        now = time.monotonic()
        in_transition = now < self._power_transition_until

        try:
            async with self.session.get(
                pic_url, timeout=aiohttp.ClientTimeout(total=3.0)
            ) as resp:
                if resp.status == 200:
                    pic = await resp.json()
                    self._last_data["picture"] = pic
                    self._last_data["online"] = True
                    is_awake = bool(pic.get("awake", True))
                    if not self.mac:
                        discovered_mac = resolve_mac_from_arp(self.host)
                        if discovered_mac:
                            self.mac = discovered_mac
                            if self.config_entry and CONF_MAC not in self.config_entry.data:
                                self.hass.config_entries.async_update_entry(
                                    self.config_entry,
                                    data={**self.config_entry.data, CONF_MAC: discovered_mac},
                                )
                    if in_transition:
                        if self._last_data["awake"] and is_awake:
                            self._power_transition_until = 0.0
                        elif not self._last_data["awake"] and not is_awake:
                            self._power_transition_until = 0.0
                    else:
                        self._last_data["awake"] = is_awake
                else:
                    if not in_transition:
                        self._last_data["awake"] = False
                        self._last_data["online"] = False
        except (aiohttp.ClientError, TimeoutError) as err:
            _LOGGER.debug("Picture state request failed (standby or offline): %s", err)
            if not in_transition:
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

    async def _send_cast_http_knock(self) -> None:
        """Send an HTTP GET knock to Cast port 8010 (as proven in xgimi_wake_knock)."""
        cast_url = f"http://{self.formatted_host}:{CAST_WAKE_PORT}/"
        try:
            async with self.session.get(
                cast_url, timeout=aiohttp.ClientTimeout(total=1.2)
            ):
                pass
        except Exception as err:
            _LOGGER.debug(
                "Cast HTTP knock on port %s: %s (%s)",
                CAST_WAKE_PORT,
                type(err).__name__,
                err,
            )

    async def _send_wowlan_knocks(self) -> None:
        """Send a comprehensive burst of WoWLAN wake stimuli across UDP, mDNS, and TCP."""
        if not self.mac:
            self.mac = resolve_mac_from_arp(self.host)

        magic_payload = create_magic_packet(self.mac) if self.mac else None

        ports_to_knock = [
            (CAST_WAKE_PORT, b"\x00"),
            (REMOTE_WAKE_PORT, b"\x00"),
            (WOL_WAKE_PORT, magic_payload or b"\x00"),
            (WOL_DISCARD_PORT, magic_payload or b"\x00"),
        ]

        for port, payload in ports_to_knock:
            _send_udp_knock(self.host, port, payload=payload, repeat=2)

        _send_mdns_knock(self.host)

        await asyncio.gather(
            self._send_cast_http_knock(),
            _send_tcp_knock(self.host, CAST_WAKE_PORT, timeout=0.8),
            _send_tcp_knock(self.host, REMOTE_WAKE_PORT, timeout=0.8),
            return_exceptions=True,
        )

    async def async_turn_on(self) -> bool:
        """Wake projector from WoWLAN standby and turn display on."""
        if self._unloaded:
            return False

        # Initial WoWLAN knock burst
        await self._send_wowlan_knocks()
        await asyncio.sleep(0.4)

        # Hit shell-UID wake listener on port 8081 (KeyDispatcher) and port 8080 concurrently
        woke = False
        fallback_ok = False
        wake_url = f"http://{self.formatted_host}:{WAKE_PORT}/wake"
        on_url = f"http://{self.formatted_host}:{DEFAULT_PORT}/api/power/on"

        deadline = time.monotonic() + 24.0  # 24-second total wake window
        attempts = 0
        max_attempts = 16

        async def _probe_wake() -> bool:
            try:
                async with self.session.post(
                    wake_url, timeout=aiohttp.ClientTimeout(total=1.0)
                ) as resp:
                    if resp.status == 200:
                        data = await resp.json()
                        if data and data.get("ok") is not False:
                            return True
            except (aiohttp.ClientError, TimeoutError) as err:
                _LOGGER.debug(
                    "Wake 8081 attempt %s failed: %s (%s)",
                    attempts,
                    type(err).__name__,
                    err,
                )
            return False

        async def _probe_power_on() -> bool:
            try:
                async with self.session.post(
                    on_url, timeout=aiohttp.ClientTimeout(total=1.0)
                ) as resp:
                    if resp.status in (200, 202):
                        data = await resp.json()
                        if data and data.get("ok") is not False:
                            return True
            except (aiohttp.ClientError, TimeoutError) as err:
                _LOGGER.debug(
                    "Power on 8080 attempt %s failed: %s (%s)",
                    attempts,
                    type(err).__name__,
                    err,
                )
            return False

        while time.monotonic() < deadline and attempts < max_attempts:
            attempts += 1

            # Periodically re-send wake knocks in case initial burst was missed during Wi-Fi sleep cycle
            if attempts % 2 == 0:
                await self._send_wowlan_knocks()

            res_woke, res_fallback = await asyncio.gather(
                _probe_wake(),
                _probe_power_on(),
                return_exceptions=True,
            )
            if res_woke is True:
                woke = True
            if res_fallback is True:
                fallback_ok = True

            if woke or fallback_ok:
                # If 8081 woke the system but 8080 hasn't acknowledged yet, send a quick best-effort strike
                if woke and not fallback_ok:
                    try:
                        async with self.session.post(
                            on_url, timeout=aiohttp.ClientTimeout(total=0.8)
                        ) as resp:
                            if resp.status in (200, 202):
                                fallback_ok = True
                    except (aiohttp.ClientError, TimeoutError):
                        pass
                break

            await asyncio.sleep(0.4)

        if woke or fallback_ok:
            self._last_data["awake"] = True
            self._last_data["online"] = True
            self._power_transition_until = time.monotonic() + 10.0
            self.async_set_updated_data(self._last_data)
            self._schedule_delayed_refresh(3.0)
            self._schedule_delayed_refresh(6.0)
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
            self._power_transition_until = time.monotonic() + 10.0
            self.async_set_updated_data(self._last_data)
        self._schedule_delayed_refresh(3.0)
        self._schedule_delayed_refresh(6.0)
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
                    # Optimistically update cached state
                    if isinstance(body, dict) and "value" in body and path.startswith("/api/picture/"):
                        setting_key = path[len("/api/picture/"):].replace("-", "")
                        if path == "/api/picture/wb-red":
                            setting_key = "wbRed"
                        elif path == "/api/picture/wb-green":
                            setting_key = "wbGreen"
                        elif path == "/api/picture/wb-blue":
                            setting_key = "wbBlue"
                        self._last_data.setdefault("picture", {})[setting_key] = body["value"]
                        self.async_set_updated_data(self._last_data)
                    elif isinstance(body, dict) and "to" in body and path == "/api/input":
                        self._last_data.setdefault("picture", {})["currentInputSource"] = body["to"]
                        self.async_set_updated_data(self._last_data)

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
