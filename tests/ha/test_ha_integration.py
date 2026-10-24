import sys
import time
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
    XgimiBridgeConfigFlow,
    XgimiOptionsFlowHandler,
    _validate_host,
)
from custom_components.xgimi_bridge.const import CONF_HOST, CONF_MAC
from custom_components.xgimi_bridge.coordinator import (
    XgimiDataUpdateCoordinator,
    create_magic_packet,
    format_mac,
    resolve_mac_from_arp,
)
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

    def test_mac_helpers_and_wol_packet(self):
        # Valid MAC normalization
        self.assertEqual(format_mac("AA:BB:CC:DD:EE:FF"), "aa:bb:cc:dd:ee:ff")
        self.assertEqual(format_mac("aa-bb-cc-dd-ee-ff"), "aa:bb:cc:dd:ee:ff")
        self.assertEqual(format_mac("112233445566"), "11:22:33:44:55:66")

        # Invalid MAC formats
        self.assertIsNone(format_mac(None))
        self.assertIsNone(format_mac(""))
        self.assertIsNone(format_mac("invalid_mac"))
        self.assertIsNone(format_mac("11:22:33:44:55"))
        self.assertIsNone(format_mac("gg:hh:ii:jj:kk:ll"))

        # Magic packet generation
        pkt = create_magic_packet("aa:bb:cc:dd:ee:ff")
        self.assertIsNotNone(pkt)
        self.assertEqual(len(pkt), 102)
        self.assertEqual(pkt[:6], b"\xff" * 6)
        self.assertEqual(pkt[6:12], bytes.fromhex("aabbccddeeff"))
        self.assertEqual(pkt[96:102], bytes.fromhex("aabbccddeeff"))

        # ARP resolution
        fake_arp = "IP address       HW type     Flags       HW address            Mask     Device\n192.168.1.50     0x1         0x2         aa:bb:cc:dd:ee:ff     *        eth0\n"
        with patch("builtins.open", unittest.mock.mock_open(read_data=fake_arp)):
            with patch("socket.gethostbyname", side_effect=lambda h: "192.168.1.50" if h == "xgimi.local" else h):
                self.assertEqual(resolve_mac_from_arp("192.168.1.50"), "aa:bb:cc:dd:ee:ff")
                self.assertEqual(resolve_mac_from_arp("xgimi.local"), "aa:bb:cc:dd:ee:ff")
                self.assertIsNone(resolve_mac_from_arp("192.168.1.99"))

    async def test_config_flow_mac_support(self):
        flow = XgimiBridgeConfigFlow()
        flow.hass = MagicMock()

        # Invalid MAC error
        res_err = await flow.async_step_user({
            CONF_HOST: "192.168.1.100",
            CONF_MAC: "invalid_mac_val",
        })
        self.assertEqual(res_err["type"], "form")
        self.assertEqual(res_err["errors"], {CONF_MAC: "invalid_mac"})

        # Valid MAC creates entry with normalized MAC
        with patch.object(flow, "async_set_unique_id", AsyncMock()), \
             patch.object(flow, "_abort_if_unique_id_configured", MagicMock()):
            res_ok = await flow.async_step_user({
                CONF_HOST: "192.168.1.100",
                CONF_MAC: "AA:BB:CC:DD:EE:FF",
            })
            self.assertEqual(res_ok["type"], "create_entry")
            self.assertEqual(res_ok["data"][CONF_MAC], "aa:bb:cc:dd:ee:ff")

    async def test_options_flow_mac_support(self):
        handler = XgimiOptionsFlowHandler()
        fake_hass = MagicMock()
        fake_entry = MagicMock()
        fake_entry.entry_id = "test_entry"
        fake_entry.data = {CONF_HOST: "192.168.1.50", CONF_MAC: "aa:bb:cc:dd:ee:ff"}
        fake_hass.config_entries.async_get_known_entry.return_value = fake_entry
        handler.hass = fake_hass
        handler.handler = "test_entry"

        # Invalid MAC in options
        res_err = await handler.async_step_init({
            CONF_HOST: "192.168.1.51",
            CONF_MAC: "bad_mac",
        })
        self.assertEqual(res_err["errors"], {CONF_MAC: "invalid_mac"})

        # Valid update
        res_ok = await handler.async_step_init({
            CONF_HOST: "192.168.1.52",
            CONF_MAC: "11:22:33:44:55:66",
        })
        self.assertEqual(res_ok["type"], "create_entry")
        fake_hass.config_entries.async_update_entry.assert_called_with(
            fake_entry,
            data={CONF_HOST: "192.168.1.52", CONF_MAC: "11:22:33:44:55:66"},
        )

        # Clear MAC
        await handler.async_step_init({
            CONF_HOST: "192.168.1.52",
            CONF_MAC: "",
        })
        fake_hass.config_entries.async_update_entry.assert_called_with(
            fake_entry,
            data={CONF_HOST: "192.168.1.52"},
        )

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

            # Case 2: Wake on 8081 fails, fallback on 8080 succeeds
            coordinator.data["awake"] = False
            coordinator.data["online"] = False

            wake_fail = AsyncMock()
            wake_fail.status = 500
            wake_fail.json = AsyncMock(return_value={"ok": False})

            fallback_ok = AsyncMock()
            fallback_ok.status = 202
            fallback_ok.json = AsyncMock(return_value={"accepted": True, "pending": True})

            # 8081 returns wake_fail, then 8080 returns fallback_ok
            session.post.return_value.__aenter__.side_effect = [
                wake_fail, fallback_ok
            ]

            res2 = await coordinator.async_turn_on()
            self.assertTrue(res2)
            self.assertTrue(coordinator.data["awake"])
            self.assertTrue(coordinator.data["online"])

            # Case 3: Retries through wake failures before succeeding
            coordinator.data["awake"] = False
            coordinator.data["online"] = False

            # Fails on iteration 1 (8081 fail, 8080 fail), succeeds on iteration 2 (8081 ok)
            session.post.return_value.__aenter__.side_effect = [
                wake_fail, wake_fail, wake_resp, fallback_ok
            ]

            res3 = await coordinator.async_turn_on()
            self.assertTrue(res3)
            self.assertTrue(coordinator.data["awake"])
            self.assertTrue(coordinator.data["online"])

            # Case 4: Both wake and fallback fail -> returns False, does NOT set awake/online
            coordinator.data["awake"] = False
            coordinator.data["online"] = False

            session.post.return_value.__aenter__.side_effect = aiohttp.ClientError("Host unreachable")

            with patch("asyncio.sleep", AsyncMock()):
                res4 = await coordinator.async_turn_on()
            self.assertFalse(res4)
            self.assertFalse(coordinator.data["awake"])
            self.assertFalse(coordinator.data["online"])

    async def test_coordinator_mac_auto_discovery_and_wol_burst(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)
        self.assertIsNone(coordinator.mac)

        # 1. Successful poll with ARP entry discovers MAC and updates config entry
        fake_arp = "IP address       HW type     Flags       HW address            Mask     Device\n192.168.1.50     0x1         0x2         aa:bb:cc:dd:ee:ff     *        eth0\n"
        succ_resp = AsyncMock()
        succ_resp.status = 200
        succ_resp.json = AsyncMock(return_value={"awake": True, "brightness": 50})
        session.get.return_value.__aenter__.return_value = succ_resp

        with patch("builtins.open", unittest.mock.mock_open(read_data=fake_arp)):
            await coordinator._async_update_data()

        self.assertEqual(coordinator.mac, "aa:bb:cc:dd:ee:ff")
        hass.config_entries.async_update_entry.assert_called_with(
            entry,
            data={"host": "192.168.1.50", CONF_MAC: "aa:bb:cc:dd:ee:ff"},
        )

        # 2. WoWLAN knock burst sends 102-byte Magic Packet
        with patch("custom_components.xgimi_bridge.coordinator._send_udp_knock") as mock_udp_knock, \
             patch("custom_components.xgimi_bridge.coordinator._send_mdns_knock") as mock_mdns_knock, \
             patch("custom_components.xgimi_bridge.coordinator._send_tcp_knock", AsyncMock()):
            await coordinator._send_wowlan_knocks()
            mock_mdns_knock.assert_called_once_with("192.168.1.50")
            # Verify Magic packet was passed for WOL port (9) and discard port (7)
            called_ports = {call[0][1]: call[1]["payload"] for call in mock_udp_knock.call_args_list}
            self.assertEqual(len(called_ports[9]), 102)
            self.assertEqual(len(called_ports[7]), 102)
            self.assertEqual(called_ports[9][:6], b"\xff" * 6)

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

    async def test_power_transition_state_protection(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.data = {"host": "192.168.1.50"}

        session = MagicMock()
        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.50", "XGIMI", config_entry=entry, session=session)

        # 1. Turn-on transition protection: transient polling failures while transitioning do NOT reset awake to False
        coordinator._last_data["awake"] = True
        coordinator._last_data["online"] = True
        coordinator._power_transition_until = time.monotonic() + 10.0

        # Simulate timeout on /api/picture during turn-on
        session.get.return_value.__aenter__.side_effect = TimeoutError("Projector display initializing")

        data = await coordinator._async_update_data()
        self.assertTrue(data["awake"], "Power switch must remain ON during turn-on transition even if poll times out")

        # Once /api/picture succeeds with awake: true, transition completes
        succ_resp = AsyncMock()
        succ_resp.status = 200
        succ_resp.json = AsyncMock(return_value={"awake": True, "brightness": 50})
        session.get.return_value.__aenter__.side_effect = None
        session.get.return_value.__aenter__.return_value = succ_resp

        data2 = await coordinator._async_update_data()
        self.assertTrue(data2["awake"])
        self.assertEqual(coordinator._power_transition_until, 0.0, "Transition flag must clear on confirmed state")

        # 2. Turn-off transition protection: stale awake: true does NOT clobber awake: False
        coordinator._last_data["awake"] = False
        coordinator._power_transition_until = time.monotonic() + 10.0

        data3 = await coordinator._async_update_data()
        self.assertFalse(data3["awake"], "Stale awake:true must not clobber awake:False while turning off")

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

    async def test_coordinator_attributes_and_select_states(self):
        hass = MagicMock()
        entry = MagicMock()
        entry.entry_id = "test_entry_456"
        entry.data = {"host": "192.168.1.100"}
        session = MagicMock()

        coordinator = XgimiDataUpdateCoordinator(hass, "192.168.1.100", "XGIMI Test", config_entry=entry, session=session)
        self.assertEqual(coordinator.config_entry_id, "test_entry_456")
        self.assertEqual(coordinator.model, "Horizon 20 (XR14A)")
        self.assertEqual(coordinator.sw_version, "Android 14")

        # Dynamic model & OS from coordinator data
        coordinator._last_data["picture"] = {"model": "Horizon Ultra", "osVersion": "Android 13"}
        coordinator.data = coordinator._last_data
        self.assertEqual(coordinator.model, "Horizon Ultra")
        self.assertEqual(coordinator.sw_version, "Android 13")

        # Unknown input source returns None
        input_sel = XgimiInputSourceSelect(coordinator)
        coordinator.data["picture"]["currentInputSource"] = "UNKNOWN_SOURCE"
        self.assertIsNone(input_sel.current_option)
        coordinator.data["picture"]["currentInputSource"] = "E_INPUT_SOURCE_HDMI1"
        self.assertEqual(input_sel.current_option, "HDMI 1")

        # Picture mode select options and state derivation
        pic_sel = XgimiPictureModeSelect(coordinator)
        expected_options = [
            "Standard", "Movie", "Vivid", "Game", "Sports", "Office",
            "Filmmaker Mode", "ISF Day", "ISF Night"
        ]
        self.assertEqual(pic_sel.options, expected_options)

        mode_mapping = {
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
        for raw_val, label in mode_mapping.items():
            coordinator.data["picture"]["pictureMode"] = raw_val
            self.assertEqual(pic_sel.current_option, label)

        coordinator.data["picture"]["pictureMode"] = 999
        self.assertIsNone(pic_sel.current_option)

        # Null-safe handling when picture is None or empty
        coordinator.data["picture"] = None
        self.assertIsNone(pic_sel.current_option)
        coordinator.data = {}
        self.assertIsNone(pic_sel.current_option)
        coordinator.data = {"picture": {}}

        # Successful selection dispatches correct integer value
        coordinator.async_post_json = AsyncMock(return_value=True)
        for label, val in [("Standard", 8), ("Vivid", 0), ("Filmmaker Mode", 27), ("ISF Day", 30), ("ISF Night", 31)]:
            await pic_sel.async_select_option(label)
            coordinator.async_post_json.assert_called_with("/api/picture/picture-mode", {"value": val})

        with self.assertRaises(HomeAssistantError):
            await pic_sel.async_select_option("InvalidMode")

        # Preset select derives state from coordinator
        preset_sel = XgimiPresetSelect(coordinator)
        coordinator.data["presets"] = [{"id": "p1", "name": "Cinema"}]
        coordinator.data["active_preset"] = "Cinema"
        self.assertEqual(preset_sel.current_option, "Cinema")
        coordinator.data["active_preset"] = "Nonexistent"
        self.assertIsNone(preset_sel.current_option)


if __name__ == "__main__":
    unittest.main()
