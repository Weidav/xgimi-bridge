"""Config flow for XGIMI Bridge integration."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.const import CONF_NAME
from homeassistant.core import callback
from homeassistant.data_entry_flow import FlowResult

from .const import CONF_HOST, CONF_MAC, DEFAULT_HOST, DEFAULT_NAME, DOMAIN
from .coordinator import format_mac

_LOGGER = logging.getLogger(__name__)


def _validate_host(host: str) -> bool:
    """Validate configured host as hostname or IP literal without scheme, path or userinfo."""
    if not host or not host.strip():
        return False
    h = host.strip()
    return not ("://" in h or "/" in h or "@" in h or "?" in h or "#" in h or " " in h)


class XgimiBridgeConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for XGIMI Bridge."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Handle the initial step."""
        errors: dict[str, str] = {}

        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            name = user_input.get(CONF_NAME, DEFAULT_NAME).strip()
            mac_raw = user_input.get(CONF_MAC, "").strip()

            if not _validate_host(host):
                errors[CONF_HOST] = "invalid_host"
            elif mac_raw and not format_mac(mac_raw):
                errors[CONF_MAC] = "invalid_mac"
            else:
                await self.async_set_unique_id(host)
                self._abort_if_unique_id_configured()

                data: dict[str, Any] = {
                    CONF_HOST: host,
                    CONF_NAME: name,
                }
                if mac_raw:
                    data[CONF_MAC] = format_mac(mac_raw)

                return self.async_create_entry(
                    title=name,
                    data=data,
                )

        schema = vol.Schema(
            {
                vol.Required(CONF_HOST, default=DEFAULT_HOST): str,
                vol.Optional(CONF_NAME, default=DEFAULT_NAME): str,
                vol.Optional(CONF_MAC): str,
            }
        )

        return self.async_show_form(
            step_id="user",
            data_schema=schema,
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(
        config_entry: config_entries.ConfigEntry,
    ) -> config_entries.OptionsFlow:
        """Get the options flow for this handler."""
        return XgimiOptionsFlowHandler()


class XgimiOptionsFlowHandler(config_entries.OptionsFlow):
    """Handle options flow for XGIMI Bridge."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Manage the options."""
        errors: dict[str, str] = {}
        if user_input is not None:
            new_host = user_input[CONF_HOST].strip()
            new_mac_raw = user_input.get(CONF_MAC, "").strip()

            if not _validate_host(new_host):
                errors[CONF_HOST] = "invalid_host"
            elif new_mac_raw and not format_mac(new_mac_raw):
                errors[CONF_MAC] = "invalid_mac"
            else:
                updated_data = {**self.config_entry.data, CONF_HOST: new_host}
                if new_mac_raw:
                    updated_data[CONF_MAC] = format_mac(new_mac_raw)
                elif CONF_MAC in updated_data:
                    del updated_data[CONF_MAC]

                self.hass.config_entries.async_update_entry(
                    self.config_entry,
                    data=updated_data,
                )
                return self.async_create_entry(title="", data={})

        current_host = self.config_entry.data.get(CONF_HOST, DEFAULT_HOST)
        current_mac = self.config_entry.data.get(CONF_MAC, "")
        schema_dict: dict[Any, Any] = {
            vol.Required(CONF_HOST, default=current_host): str,
        }
        if current_mac:
            schema_dict[vol.Optional(CONF_MAC, default=current_mac)] = str
        else:
            schema_dict[vol.Optional(CONF_MAC)] = str

        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(schema_dict),
            errors=errors,
        )
