package com.bootcampcc;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BootcampCCConfig.GROUP)
public interface BootcampCCConfig extends Config
{
	/**
	 * Config group name. Never rename it (or any key below) without a migration:
	 * a rename silently resets every user's saved settings.
	 */
	String GROUP = "bootcampcc";

	@ConfigSection(
		name = "General",
		description = "General settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Advanced",
		description = "Settings you normally do not need to change",
		position = 100,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "Base URL of the Bootcamp CC API. Leave empty unless a clan admin tells you otherwise.",
		section = advancedSection,
		position = 0
	)
	default String apiUrl()
	{
		return "";
	}
}
