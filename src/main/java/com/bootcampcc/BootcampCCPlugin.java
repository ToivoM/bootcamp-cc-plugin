package com.bootcampcc;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Bootcamp CC",
	description = "Companion plugin for the Bootcamp CC clan",
	tags = {"clan", "bootcamp", "cc", "discord"}
)
public class BootcampCCPlugin extends Plugin
{
	@Override
	protected void startUp()
	{
		log.debug("Bootcamp CC started");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Bootcamp CC stopped");
	}

	@Provides
	BootcampCCConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BootcampCCConfig.class);
	}
}
