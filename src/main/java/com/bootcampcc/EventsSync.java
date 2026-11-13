package com.bootcampcc;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the events panel's model up to date.
 *
 * <ul>
 *   <li>The long poll reports the server's events_version; when it differs from the shown list's,
 *       {@link #onServerVersion} fetches GET /api/events at once, whether the panel is open or not.</li>
 *   <li>As a safety net: a fetch when the panel is opened, and every 5 minutes.</li>
 *   <li>When the long poll is not running (for example it is backing off after errors) but the panel is on and
 *       the account linked, GET /api/events/version every 60 seconds.</li>
 * </ul>
 * One fetch at a time; a request during a fetch makes one more fetch afterwards, so no change is missed.
 */
@Slf4j
final class EventsSync
{
	static final long REFRESH_MILLIS = TimeUnit.MINUTES.toMillis(5);
	static final long VERSION_CHECK_MILLIS = TimeUnit.SECONDS.toMillis(60);

	interface Api
	{
		boolean events(String token, ApiClient.ApiCallback<ApiClient.EventsResponse> callback);

		boolean eventsVersion(String token, ApiClient.ApiCallback<ApiClient.VersionResponse> callback);
	}

	/** What the sync needs to know about the plugin. Called from any thread. */
	interface Env
	{
		boolean panelEnabled();

		boolean loggedIn();

		/** The token for the current API URL, or null when not linked. */
		String token();

		/** The long poll is running and not failing (so it reports events_version changes itself). */
		boolean pollerRunning();

		/** A list for this version was loaded (to store it with the account). */
		void loadedVersion(long version);

		/** The server rejected the token. */
		void onUnauthorized();
	}

	private final Api api;
	private final Env env;
	private final EventsModel model;
	private final ScheduledExecutorService executor;
	private final long refreshMillis;
	private final long versionCheckMillis;

	// Guarded by this.
	private boolean inFlight;
	private boolean again;
	private ScheduledFuture<?> refreshTask;
	private ScheduledFuture<?> versionTask;

	EventsSync(Api api, Env env, EventsModel model, ScheduledExecutorService executor)
	{
		this(api, env, model, executor, REFRESH_MILLIS, VERSION_CHECK_MILLIS);
	}

	EventsSync(Api api, Env env, EventsModel model, ScheduledExecutorService executor, long refreshMillis, long versionCheckMillis)
	{
		this.api = api;
		this.env = env;
		this.model = model;
		this.executor = executor;
		this.refreshMillis = refreshMillis;
		this.versionCheckMillis = versionCheckMillis;
	}

	synchronized void start()
	{
		stop();
		refreshTask = executor.scheduleWithFixedDelay(this::safeRefresh, refreshMillis, refreshMillis, TimeUnit.MILLISECONDS);
		versionTask = executor.scheduleWithFixedDelay(this::safeVersionCheck, versionCheckMillis, versionCheckMillis, TimeUnit.MILLISECONDS);
	}

	synchronized void stop()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}
		if (versionTask != null)
		{
			versionTask.cancel(false);
			versionTask = null;
		}
	}

	/** The long poll (or the version check) saw this events_version on the server. */
	void onServerVersion(long serverVersion)
	{
		if (env.panelEnabled() && serverVersion != model.version())
		{
			log.debug("Bootcamp CC events changed on the server ({} -> {})", model.version(), serverVersion);
			refresh();
		}
	}

	/** Load the list now (or show why it cannot be loaded). */
	void refresh()
	{
		if (!env.panelEnabled())
		{
			return;
		}
		if (!env.loggedIn())
		{
			model.setStatus(EventsModel.Status.NOT_LOGGED_IN);
			return;
		}
		final String token = env.token();
		if (token == null)
		{
			model.setStatus(EventsModel.Status.NOT_LINKED);
			return;
		}
		synchronized (this)
		{
			if (inFlight)
			{
				again = true;
				return;
			}
			inFlight = true;
			again = false;
		}
		if (model.status() != EventsModel.Status.OK)
		{
			model.setStatus(EventsModel.Status.LOADING);
		}
		boolean sent = api.events(token, new ApiClient.ApiCallback<ApiClient.EventsResponse>()
		{
			@Override
			public void onSuccess(ApiClient.EventsResponse body, Instant serverNow)
			{
				List<ClanEvent> events = new ArrayList<>();
				add(events, body.ongoing);
				add(events, body.upcoming);
				add(events, body.past);
				model.setEvents(events, body.version);
				env.loadedVersion(body.version);
				done();
			}

			@Override
			public void onError(int status)
			{
				if (status == 401)
				{
					env.onUnauthorized();
				}
				else
				{
					model.setStatus(status == 404 ? EventsModel.Status.UNAVAILABLE : EventsModel.Status.ERROR);
				}
				done();
			}
		});
		if (!sent)
		{
			model.setStatus(EventsModel.Status.NO_API);
			done();
		}
	}

	/** Ask the server for its events_version, if the long poll is not doing that already. */
	void versionCheck()
	{
		if (!env.panelEnabled() || !env.loggedIn() || env.pollerRunning())
		{
			return;
		}
		String token = env.token();
		if (token == null)
		{
			return;
		}
		api.eventsVersion(token, new ApiClient.ApiCallback<ApiClient.VersionResponse>()
		{
			@Override
			public void onSuccess(ApiClient.VersionResponse body, Instant serverNow)
			{
				onServerVersion(body.version);
			}

			@Override
			public void onError(int status)
			{
				if (status == 401)
				{
					env.onUnauthorized();
				}
			}
		});
	}

	private void done()
	{
		boolean fetchAgain;
		synchronized (this)
		{
			inFlight = false;
			fetchAgain = again;
			again = false;
		}
		if (fetchAgain)
		{
			executor.execute(this::safeRefresh);
		}
	}

	private void safeRefresh()
	{
		try
		{
			refresh();
		}
		catch (RuntimeException e)
		{
			log.warn("Bootcamp CC events refresh failed", e);
		}
	}

	private void safeVersionCheck()
	{
		try
		{
			versionCheck();
		}
		catch (RuntimeException e)
		{
			log.warn("Bootcamp CC events version check failed", e);
		}
	}

	private static void add(List<ClanEvent> into, List<ApiClient.Event> dtos)
	{
		if (dtos == null)
		{
			return;
		}
		for (ApiClient.Event d : dtos)
		{
			if (d == null || d.id == null || d.name == null || d.startTime == null || d.endTime == null)
			{
				continue;
			}
			try
			{
				into.add(new ClanEvent(d.id, d.name, d.description, d.location,
					Instant.parse(d.startTime), Instant.parse(d.endTime), d.status));
			}
			catch (DateTimeParseException e)
			{
				log.debug("Bootcamp CC skipping event {} with a bad time", d.id);
			}
		}
	}
}
