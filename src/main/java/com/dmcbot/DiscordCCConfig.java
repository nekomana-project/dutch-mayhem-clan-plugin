package com.dmcbot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("discordcc")
public interface DiscordCCConfig extends Config
{
	@ConfigItem(
			keyName = "serverUrl",
			name = "Server URL",
			description = "Base server URL — get this from #bot-info in the DMC Discord",
			position = 1
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
			keyName = "secretToken",
			name = "Personal Token",
			description = "Your personal token from /mytoken in Discord",
			position = 2,
			secret = true
	)
	default String secretToken()
	{
		return "";
	}
}
