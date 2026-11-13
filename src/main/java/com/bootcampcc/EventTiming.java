package com.bootcampcc;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.Value;

/**
 * Event state and time texts, computed locally so the panel can update without calling the API.
 * Uses the same rules as the server.
 */
final class EventTiming
{
	enum State
	{
		LIVE,
		UPCOMING,
		PAST,
		/** Canceled: never shown. */
		HIDDEN
	}

	static final Duration SOON = Duration.ofHours(1);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d.M.", Locale.ENGLISH);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

	/** Events split for the panel, each list in display order. */
	@Value
	static class Sections
	{
		List<ClanEvent> live;
		List<ClanEvent> upcoming;
		List<ClanEvent> past;
	}

	private EventTiming()
	{
	}

	/** Live and upcoming by start time, past newest first; canceled events are left out. */
	static Sections sections(List<ClanEvent> events, Instant now)
	{
		List<ClanEvent> live = new ArrayList<>();
		List<ClanEvent> upcoming = new ArrayList<>();
		List<ClanEvent> past = new ArrayList<>();
		for (ClanEvent e : events)
		{
			switch (state(e, now))
			{
				case LIVE:
					live.add(e);
					break;
				case UPCOMING:
					upcoming.add(e);
					break;
				case PAST:
					past.add(e);
					break;
				default:
					break;
			}
		}
		live.sort(Comparator.comparing(ClanEvent::getStart));
		upcoming.sort(Comparator.comparing(ClanEvent::getStart));
		past.sort(Comparator.comparing(ClanEvent::getEnd).reversed());
		return new Sections(Collections.unmodifiableList(live), Collections.unmodifiableList(upcoming),
			Collections.unmodifiableList(past));
	}

	static State state(ClanEvent e, Instant now)
	{
		String status = e.getStatus() == null ? "scheduled" : e.getStatus();
		switch (status)
		{
			case "canceled":
				return State.HIDDEN;
			case "active":
				return State.LIVE;
			case "completed":
				return State.PAST;
			default:
				if (!now.isBefore(e.getStart()) && now.isBefore(e.getEnd()))
				{
					return State.LIVE;
				}
				return now.isBefore(e.getStart()) ? State.UPCOMING : State.PAST;
		}
	}

	/** Upcoming and starting within an hour. */
	static boolean isSoon(ClanEvent e, Instant now)
	{
		return state(e, now) == State.UPCOMING && Duration.between(now, e.getStart()).compareTo(SOON) < 0;
	}

	/** "starts in 2 h 15 min", "ends in 45 min", "ended 2 days ago". */
	static String relative(ClanEvent e, Instant now)
	{
		switch (state(e, now))
		{
			case UPCOMING:
				return "starts in " + duration(Duration.between(now, e.getStart()));
			case LIVE:
				return now.isBefore(e.getEnd()) ? "ends in " + duration(Duration.between(now, e.getEnd())) : "live now";
			case PAST:
				return now.isAfter(e.getEnd()) ? "ended " + duration(Duration.between(e.getEnd(), now)) + " ago" : "ended";
			default:
				return "";
		}
	}

	/** "Fri 14.11. 19:00–21:00", or with both days if the event ends on another day. */
	static String timeRange(ClanEvent e, ZoneId zone)
	{
		ZonedDateTime start = e.getStart().atZone(zone);
		ZonedDateTime end = e.getEnd().atZone(zone);
		if (start.toLocalDate().equals(end.toLocalDate()))
		{
			return DAY.format(start) + " " + TIME.format(start) + "–" + TIME.format(end);
		}
		return DAY.format(start) + " " + TIME.format(start) + " – " + DAY.format(end) + " " + TIME.format(end);
	}

	/** Rounded down: "less than a minute", "45 min", "2 h", "2 h 15 min", "1 day", "3 days". */
	static String duration(Duration d)
	{
		long minutes = Math.max(0, d.getSeconds()) / 60;
		if (minutes < 1)
		{
			return "less than a minute";
		}
		if (minutes < 60)
		{
			return minutes + " min";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			long rest = minutes % 60;
			return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
		}
		long days = hours / 24;
		return days == 1 ? "1 day" : days + " days";
	}
}
