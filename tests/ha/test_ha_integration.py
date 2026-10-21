import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp

# Ensure homeassistant custom_components can be imported
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "homeassistant"))

from custom_components.xgimi_bridge.button import (
    XgimiKeyButton,
    XgimiPowerActionButton,
    XgimiRestartButton,
)
from custom_components.xgimi_bridge.config_flow import (
    XgimiOptionsFlowHandler,
    _validate_host,
)
from custom_components.xgimi_bridge.coordinator import XgimiDataUpdateCoordinator
from custom_components.xgimi_bridge.number import XgimiSlider
from custom_components.xgimi_bridge.select import (
    XgimiColorTempSelect,
    XgimiGameModeSelect,
    XgimiInputSourceSelect,
    XgimiPictureModeSelect,
    XgimiPresetSelect,
)
from custom_components.xgimi_bridge.switch import XgimiPowerSwitch
from homeassistant.exceptions import HomeAssistantError


class TestHaIntegration(unittest.IsolatedAsyncioTestCase):
    def test_validate_host(self):
        # Valid
        self.assertTrue(_validate_host("192.168.1.100"))
        self.assertTrue(_validate_host("xgimi.local"))
        self.assertTrue(_validate_host("my-projector"))
        self.assertTrue(_validate_host("[::1]"))
        self.assertTrue(_validate_host("[2001:db8::1]"))

        # Invalid: scheme
        self.assertFalse(_validate_host("http://192.168.1.100"))
        self.assertFalse(_validate_host("https://xgimi.local"))

        # Invalid: path or query
        self.assertFalse(_validate_host("192.168.1.100/api"))
        self.assertFalse(_validate_host("xgimi.local?foo=bar"))

        # Invalid: userinfo
        self.assertFalse(_validate_host("admin@xgimi.local"))

        # Invalid: empty
        self.assertFalse(_validate_host(""))
        self.assertFalse(_validate_host("   "))

    def test_options_flow_constructor(self):
        # Options flow must be instantiable without arguments
        handler = XgimiOptionsFlowHandler()
        self.assertIsNotNone(handler)

        # In HA 2024.11+, options flow config_entry is managed via hass.config_entries
        fake_hass = MagicMock()
        fake_entry = MagicMock()
        fake_entry.entry_id = "test_entry"
        fake_hass.config_entries.async_get_known_entry.return_value = fake_entry

        handler.hass = fake_hass
        handler.handler = "test_entry"
        self.assertEqual(handler.config_entry, fake_entry)

    async def test_coordinator_post_json_response_checking(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)
        coordinator.async_request_refresh = AsyncMock()

        # 1. Successful 200 with ok: true
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"ok": True})
        session.post.return_value.__aenter__.return_value = mock_resp

        ok = await coordinator.async_post_json("/api/picture/brightness", {"value": 50})
        self.assertTrue(ok)
        coordinator.async_request_refresh.assert_awaited()

        # 2. HTTP 200 but ok: false (e.g. partial fail or rejected write) -> must return False
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"ok": False, "error": "device_write_failed"})
        coordinator.async_request_refresh.reset_mock()

        ok2 = await coordinator.async_post_json("/api/picture/brightness", {"value": 50})
        self.assertFalse(ok2)

        # 3. HTTP 202 Accepted -> must return True
        mock_resp.status = 202
        mock_resp.json = AsyncMock(return_value={"accepted": True, "pending": True})

        ok3 = await coordinator.async_post_json("/api/power/on")
        self.assertTrue(ok3)

        # 4. HTTP 500 / 502 / 503 -> must return False
        mock_resp.status = 502
        mock_resp.json = AsyncMock(return_value={"ok": False, "error": "Hardware setter failed"})

        ok4 = await coordinator.async_post_json("/api/picture/brightness", {"value": 50})
        self.assertFalse(ok4)

        # 5. Network / timeout error -> returns False
        session.post.return_value.__aenter__.side_effect = aiohttp.ClientError("Connection refused")

        ok5 = await coordinator.async_post_json("/api/picture/brightness", {"value": 50})
        self.assertFalse(ok5)

    async def test_coordinator_turn_on_wake_and_fallback(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)

        # Mock WoWLAN knock sockets
        with patch("asyncio.open_connection", side_effect=OSError("Offline")):
            # Case 1: Wake succeeds on port 8081
            wake_resp = AsyncMock()
            wake_resp.status = 200
            wake_resp.json = AsyncMock(return_value={"ok": True, "action": "wake"})
            session.post.return_value.__aenter__.return_value = wake_resp

            res = await coordinator.async_turn_on()
            self.assertTrue(res)
            self.assertTrue(coordinator.data["awake"])
            self.assertTrue(coordinator.data["online"])

            # Case 2: Wake fails and fallback succeeds
            coordinator.data["awake"] = False
            coordinator.data["online"] = False

            wake_fail = AsyncMock()
            wake_fail.status = 500
            wake_fail.json = AsyncMock(return_value={"ok": False})

            fallback_ok = AsyncMock()
            fallback_ok.status = 202
            fallback_ok.json = AsyncMock(return_value={"accepted": True, "pending": True})

            # Return wake_fail 4 times (for the loop), then fallback_ok
            session.post.return_value.__aenter__.side_effect = [
                wake_fail, wake_fail, wake_fail, wake_fail, fallback_ok
            ]

            res2 = await coordinator.async_turn_on()
            self.assertTrue(res2)
            self.assertTrue(coordinator.data["awake"])
            self.assertTrue(coordinator.data["online"])

            # Case 3: Both wake and fallback fail -> returns False, does NOT set awake/online
            coordinator.data["awake"] = False
            coordinator.data["online"] = False

            fallback_fail = AsyncMock()
            fallback_fail.status = 500
            fallback_fail.json = AsyncMock(return_value={"ok": False})

            session.post.return_value.__aenter__.side_effect = [
                wake_fail, wake_fail, wake_fail, wake_fail, fallback_fail
            ]

            res3 = await coordinator.async_turn_on()
            self.assertFalse(res3)
            self.assertFalse(coordinator.data["awake"])
            self.assertFalse(coordinator.data["online"])

    async def test_coordinator_turn_off(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)
        coordinator.async_set_updated_data({"awake": True, "online": True})

        # Successful turn off
        off_resp = AsyncMock()
        off_resp.status = 200
        off_resp.json = AsyncMock(return_value={"ok": True})
        session.post.return_value.__aenter__.return_value = off_resp

        res = await coordinator.async_turn_off()
        self.assertTrue(res)
        self.assertFalse(coordinator.data["awake"])

        # Dropped connection during turn off -> uncertain, returns False, does not set awake
        coordinator.async_set_updated_data({"awake": True, "online": True})
        session.post.return_value.__aenter__.side_effect = aiohttp.ClientError("Connection reset by peer")

        res2 = await coordinator.async_turn_off()
        self.assertFalse(res2)

    async def test_coordinator_unload_cancels_tasks(self):
        hass = MagicMock()
        hass.loop = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)

        # Schedule a delayed refresh
        mock_timer = MagicMock()
        hass.loop.call_later.return_value = mock_timer

        coordinator._schedule_delayed_refresh(2.0)
        self.assertEqual(len(coordinator._refresh_tasks), 1)

        # Unload
        await coordinator.async_unload()
        self.assertTrue(coordinator._unloaded)
        mock_timer.cancel.assert_called_once()
        self.assertEqual(len(coordinator._refresh_tasks), 0)

        # Post-unload actions return False without network calls
        self.assertFalse(await coordinator.async_turn_on())
        self.assertFalse(await coordinator.async_turn_off())
        self.assertFalse(await coordinator.async_post_json("/api/picture"))

    async def test_entity_actions_raise_homeassistant_error_on_failure(self):
        coordinator = MagicMock()
        coordinator.async_turn_on = AsyncMock(return_value=False)
        coordinator.async_turn_off = AsyncMock(return_value=False)
        coordinator.async_post_json = AsyncMock(return_value=False)
        coordinator.async_inject_key = AsyncMock(return_value=False)
        coordinator.data = {"awake": False, "picture": {}, "presets": []}

        # 1. Switch
        switch = XgimiPowerSwitch(coordinator)
        with self.assertRaises(HomeAssistantError):
            await switch.async_turn_on()

        with self.assertRaises(HomeAssistantError):
            await switch.async_turn_off()

        # 2. Number slider
        slider = XgimiSlider(coordinator, "brightness", "Brightness", "brightness", "mdi:brightness-6")
        with self.assertRaises(HomeAssistantError):
            await slider.async_set_native_value(75)

        # 3. Button
        key_btn = XgimiKeyButton(coordinator, "home", "Home", 3, "mdi:home")
        with self.assertRaises(HomeAssistantError):
            await key_btn.async_press()

        pwr_btn = XgimiPowerActionButton(coordinator, "lamp_off", "Lamp Off", "led-off", "mdi:lightbulb-off")
        with self.assertRaises(HomeAssistantError):
            await pwr_btn.async_press()

        restart_btn = XgimiRestartButton(coordinator)
        with self.assertRaises(HomeAssistantError):
            await restart_btn.async_press()

        # 4. Selects
        in_sel = XgimiInputSourceSelect(coordinator)
        with self.assertRaises(HomeAssistantError):
            await in_sel.async_select_option("HDMI 1")
        with self.assertRaises(HomeAssistantError):
            await in_sel.async_select_option("Nonexistent")

        pic_sel = XgimiPictureModeSelect(coordinator)
        with self.assertRaises(HomeAssistantError):
            await pic_sel.async_select_option("Movie")

        temp_sel = XgimiColorTempSelect(coordinator)
        with self.assertRaises(HomeAssistantError):
            await temp_sel.async_select_option("Warm")

        game_sel = XgimiGameModeSelect(coordinator)
        with self.assertRaises(HomeAssistantError):
            await game_sel.async_select_option("Boost")

        preset_sel = XgimiPresetSelect(coordinator)
        coordinator.data["presets"] = [{"id": "p1", "name": "TestPreset"}]
        with self.assertRaises(HomeAssistantError):
            await preset_sel.async_select_option("TestPreset")
        with self.assertRaises(HomeAssistantError):
            await preset_sel.async_select_option("MissingPreset")


if __name__ == "__main__":
    unittest.main()
