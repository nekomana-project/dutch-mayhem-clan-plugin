package com.dmcbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("discordcc")
public interface DiscordCCConfig extends Config
{
	@ConfigItem(
			keyName = "serverUrl",
			name = "Clan Chat URL",
			description = "Endpoint for clan chat messages — get this from #bot-info in the DMC Discord",
			position = 1
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
			keyName = "wildyLootUrl",
			name = "Wildy Loot URL",
			description = "Endpoint for wilderness loot tracking — get this from #bot-info in the DMC Discord",
			position = 2
	)
	default String wildyLootUrl()
	{
		return "";
	}

	@ConfigItem(
			keyName = "secretToken",
			name = "Personal Token",
			description = "Your personal token from /mytoken in Discord",
			position = 3,
			secret = true
	)
	default String secretToken()
	{
		return "";
	}

	@ConfigItem(
			keyName = "minLootValue",
			name = "Minimum Loot Value (GP)",
			description = "Items worth less than this (per stack) are not sent to the bot. Set to 0 to send everything.",
			position = 4
	)
	default int minLootValue()
	{
		return 10_000;
	}

	@ConfigItem(
			keyName = "sendNpcLoot",
			name = "Track NPC Loot",
			description = "Send loot received from NPC kills to the bot",
			position = 5
	)
	default boolean sendNpcLoot()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendPvpLoot",
			name = "Track PvP Loot",
			description = "Send loot received from player kills to the bot",
			position = 6
	)
	default boolean sendPvpLoot()
	{
		return true;
	}
}
