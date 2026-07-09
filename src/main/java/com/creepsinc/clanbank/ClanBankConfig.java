package com.creepsinc.clanbank;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("clanbank")
public interface ClanBankConfig extends Config
{
	@ConfigItem(
		keyName = "apiUrl",
		name = "Bot API URL",
		description = "Base URL of the clan bank bot's local API, e.g. http://localhost:3939"
	)
	default String apiUrl()
	{
		return "http://localhost:3939";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "Must match PLUGIN_API_KEY in the bot's .env file"
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "refreshSeconds",
		name = "Refresh interval (seconds)",
		description = "How often to poll the bot for your current loan status"
	)
	default int refreshSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Toggle the in-game status panel on or off"
	)
	default boolean showOverlay()
	{
		return true;
	}
}
