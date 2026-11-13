package com.bootcampcc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Value;

/**
 * Decides where broadcasts are shown. Fresh broadcasts (under 5 minutes old) go to the chatbox and the
 * overlay. Older ones, such as the backlog received at login, go to the chatbox only with their age, and at
 * most 5 of them are listed (with a "+ N older broadcasts" line for the rest).
 */
final class BroadcastDelivery
{
	static final Duration OVERLAY_MAX_AGE = Duration.ofMinutes(5);
	static final int MAX_OLD_IN_CHAT = 5;

	@Value
	static class Item
	{
		long id;
		String text;
		/** Null if the server did not send a valid time. */
		Instant createdAt;
	}

	@Value
	static class Plan
	{
		/** Chat lines in display order, without the "[Bootcamp CC] " prefix. */
		List<String> chatLines;
		/** Broadcasts for the overlay (and notifications), oldest first. */
		List<Item> overlayItems;
	}

	private BroadcastDelivery()
	{
	}

	/**
	 * @param now the current time from the server's Date header, so a wrong local clock does not matter
	 */
	static Plan plan(List<Item> items, Instant now)
	{
		List<Item> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingLong(Item::getId));

		List<Item> fresh = new ArrayList<>();
		List<Item> old = new ArrayList<>();
		for (Item item : sorted)
		{
			(isFresh(item, now) ? fresh : old).add(item);
		}

		List<String> chat = new ArrayList<>();
		int skipped = Math.max(0, old.size() - MAX_OLD_IN_CHAT);
		if (skipped > 0)
		{
			chat.add("+ " + skipped + " older broadcast" + (skipped == 1 ? "" : "s"));
		}
		for (Item item : old.subList(skipped, old.size()))
		{
			chat.add(item.getCreatedAt() == null
				? item.getText()
				: "(" + ageText(Duration.between(item.getCreatedAt(), now)) + ") " + item.getText());
		}
		for (Item item : fresh)
		{
			chat.add(item.getText());
		}
		return new Plan(chat, fresh);
	}

	/** Under 5 minutes old. A broadcast without a time is treated as old (chat only). */
	static boolean isFresh(Item item, Instant now)
	{
		return item.getCreatedAt() != null && Duration.between(item.getCreatedAt(), now).compareTo(OVERLAY_MAX_AGE) < 0;
	}

	/** Rounded down: "1 min ago", "3 min ago", "3 h ago", "1 day ago", "2 days ago". */
	static String ageText(Duration age)
	{
		long minutes = Math.max(0, age.getSeconds()) / 60;
		if (minutes < 60)
		{
			return Math.max(1, minutes) + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + " h ago";
		}
		long days = hours / 24;
		return days == 1 ? "1 day ago" : days + " days ago";
	}
}
