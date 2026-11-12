package com.bootcampcc;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BootcampCCPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BootcampCCPlugin.class);
		RuneLite.main(args);
	}
}
