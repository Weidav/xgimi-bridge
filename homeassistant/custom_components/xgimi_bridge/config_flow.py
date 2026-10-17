"""Config flow for XGIMI Bridge integration."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_NAME
from homeassistant.core import callback
from homeassistant.data_entry_flow import FlowResult

from .const import CONF_HOST, DEFAULT_HOST, DEFAULT_NAME, DOMAIN

_LOGGER = logging.getLogger(__name__)


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

            await self.async_set_unique_id(host)
            self._abort_if_unique_id_configured()

            return self.async_create_entry(
                title=name,
                data={
                    CONF_HOST: host,
                    CONF_NAME: name,
                },
            )

        schema = vol.Schema(
            {
                vol.Required(CONF_HOST, default=DEFAULT_HOST): str,
                vol.Optional(CONF_NAME, default=DEFAULT_NAME): str,
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
        return XgimiOptionsFlowHandler(config_entry)


class XgimiOptionsFlowHandler(config_entries.OptionsFlow):
    """Handle options flow for XGIMI Bridge."""

    def __init__(self, config_entry: config_entries.ConfigEntry) -> None:
        """Initialize options flow."""
        self.config_entry = config_entry

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> FlowResult:
        """Manage the options."""
        if user_input is not None:
            new_host = user_input[CONF_HOST].strip()
            self.hass.config_entries.async_update_entry(
                self.config_entry,
                data={**self.config_entry.data, CONF_HOST: new_host},
            )
            return self.async_create_entry(title="", data={})

        current_host = self.config_entry.data.get(CONF_HOST, DEFAULT_HOST)
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST, default=current_host): str,
                }
            ),
        )
