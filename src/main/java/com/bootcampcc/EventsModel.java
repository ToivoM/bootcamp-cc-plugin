package com.bootcampcc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the events panel shows: the latest event list (and the events_version it was loaded for), or a
 * status message. Thread-safe. Listeners are told about every change, on the thread that made it; the panel
 * moves to the Swing thread itself. The panel does not need to be open or reopened to get changes.
 */
final class EventsModel
{
	enum Status
	{
		LOADING("Loading events…"),
		NOT_LOGGED_IN("Log in to the game to see clan events."),
		NOT_LINKED("Link your account in Bootcamp CC settings to see clan events."),
		NO_API("Set the API URL in Bootcamp CC settings (Advanced) to see clan events."),
		UNAVAILABLE("Clan events are not available on this server."),
		ERROR("Could not load events. Trying again soon."),
		OK(null);

		final String message;

		Status(String message)
		{
			this.message = message;
		}
	}

	interface Listener
	{
		void onEventsChanged();
	}

	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	// Guarded by this.
	private List<ClanEvent> events = Collections.emptyList();
	private Status status = Status.LOADING;
	private long version = -1;

	void addListener(Listener listener)
	{
		listeners.add(listener);
	}

	void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	synchronized List<ClanEvent> events()
	{
		return events;
	}

	synchronized Status status()
	{
		return status;
	}

	/** The events_version of the shown list, or -1 if no list is loaded. */
	synchronized long version()
	{
		return version;
	}

	void setEvents(List<ClanEvent> newEvents, long newVersion)
	{
		synchronized (this)
		{
			events = Collections.unmodifiableList(new ArrayList<>(newEvents));
			version = newVersion;
			status = Status.OK;
		}
		fire();
	}

	/** Shows a status message. An ERROR keeps showing the events already loaded. */
	void setStatus(Status newStatus)
	{
		synchronized (this)
		{
			if (newStatus == Status.ERROR && status == Status.OK)
			{
				return;
			}
			if (newStatus == status)
			{
				return;
			}
			if (newStatus != Status.OK)
			{
				events = Collections.emptyList();
				version = -1;
			}
			status = newStatus;
		}
		fire();
	}

	private void fire()
	{
		for (Listener listener : listeners)
		{
			listener.onEventsChanged();
		}
	}
}
