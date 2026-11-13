package com.bootcampcc;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(BootcampCCConfig.GROUP)
public interface BootcampCCConfig extends Config
{
	/**
	 * Config group name. Never rename it (or any key below) without a migration:
	 * a rename silently resets every user's saved settings.
	 */
	String GROUP = "bootcampcc";

	String THIRD_PARTY_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	/** Appended to the description of settings that make the plugin contact the Bootcamp CC server. */
	String IP_NOTE = " Your IP address is visible to the Bootcamp CC server (a third-party server, not run by RuneLite).";

	String KEY_LINK_CODE = "linkCode";
	String KEY_RECEIVE_BROADCASTS = "receiveBroadcasts";
	String KEY_SHOW_OVERLAY = "showBroadcastOverlay";
	String KEY_OVERLAY_SECONDS = "broadcastOverlaySeconds";
	String KEY_NOTIFY = "notifyOnBroadcast";
	String KEY_SHOW_EVENTS_PANEL = "showEventsPanel";
	String KEY_API_URL = "apiUrl";

	@ConfigSection(
		name = "Account",
		description = "Link this plugin to your Bootcamp CC account",
		position = 0
	)
	String accountSection = "account";

	@ConfigSection(
		name = "Broadcasts",
		description = "Messages from the clan admins",
		position = 10
	)
	String broadcastsSection = "broadcasts";

	@ConfigSection(
		name = "Events",
		description = "The clan's Discord events",
		position = 20
	)
	String eventsSection = "events";

	@ConfigSection(
		name = "Advanced",
		description = "Settings you normally do not need to change",
		position = 100,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = KEY_LINK_CODE,
		name = "Link code",
		description = "Paste the code from /link in the Bootcamp CC Discord while logged in to the game."
			+ " Linking connects this plugin to the Bootcamp CC server and sends it your RuneScape name."
			+ " Nothing is sent anywhere before you link." + IP_NOTE,
		warning = THIRD_PARTY_WARNING,
		section = accountSection,
		position = 0
	)
	default String linkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = KEY_RECEIVE_BROADCASTS,
		name = "Receive broadcasts",
		description = "Show clan broadcasts in your chatbox. Has no effect until your account is linked.",
		section = broadcastsSection,
		position = 0
	)
	default boolean receiveBroadcasts()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_SHOW_OVERLAY,
		name = "Overlay",
		description = "Also show new broadcasts in a box above the chatbox",
		section = broadcastsSection,
		position = 1
	)
	default boolean showBroadcastOverlay()
	{
		return true;
	}

	@Range(min = 5, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = KEY_OVERLAY_SECONDS,
		name = "Overlay duration",
		description = "How long a broadcast stays above the chatbox",
		section = broadcastsSection,
		position = 2
	)
	default int broadcastOverlaySeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = KEY_NOTIFY,
		name = "Notify",
		description = "Send a RuneLite notification when a new broadcast arrives",
		section = broadcastsSection,
		position = 3
	)
	default boolean notifyOnBroadcast()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_SHOW_EVENTS_PANEL,
		name = "Side panel",
		description = "Show the clan's Discord events in a side panel. Events are loaded only when your account is linked.",
		section = eventsSection,
		position = 0
	)
	default boolean showEventsPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_API_URL,
		name = "API URL",
		description = "Address of the Bootcamp CC server. Change only if a clan admin asks you to."
			+ " Must start with https:// (http:// is allowed only for localhost).",
		section = advancedSection,
		position = 0
	)
	default String apiUrl()
	{
		return "";
	}
}
