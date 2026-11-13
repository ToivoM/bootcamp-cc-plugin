package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Keeping the events panel's model current: version changes, the safety nets, and the panel itself. */
public class EventsSyncTest
{
	private final AtomicBoolean panelEnabled = new AtomicBoolean(true);
	private final AtomicBoolean loggedIn = new AtomicBoolean(true);
	private volatile String token = "tok";
	private final AtomicBoolean pollerHealthy = new AtomicBoolean(true);
	private final AtomicInteger unauthorized = new AtomicInteger();
	private final List<Long> loadedVersions = new ArrayList<>();

	/** What the fake server has. */
	private volatile long serverVersion = 1;
	private volatile List<ApiClient.Event> serverUpcoming = new ArrayList<>();
	private volatile int failWith;
	private final AtomicInteger eventFetches = new AtomicInteger();
	private final AtomicInteger versionFetches = new AtomicInteger();

	private ScheduledExecutorService executor;
	private EventsModel model;
	private final AtomicInteger modelChanges = new AtomicInteger();

	private final EventsSync.Api api = new EventsSync.Api()
	{
		@Override
		public boolean events(String t, ApiClient.ApiCallback<ApiClient.EventsResponse> callback)
		{
			eventFetches.incrementAndGet();
			if (failWith != 0)
			{
				callback.onError(failWith);
				return true;
			}
			ApiClient.EventsResponse body = new ApiClient.EventsResponse();
			body.version = serverVersion;
			body.ongoing = new ArrayList<>();
			body.upcoming = new ArrayList<>(serverUpcoming);
			body.past = new ArrayList<>();
			callback.onSuccess(body, Instant.now());
			return true;
		}

		@Override
		public boolean eventsVersion(String t, ApiClient.ApiCallback<ApiClient.VersionResponse> callback)
		{
			versionFetches.incrementAndGet();
			ApiClient.VersionResponse body = new ApiClient.VersionResponse();
			body.version = serverVersion;
			callback.onSuccess(body, Instant.now());
			return true;
		}
	};

	private final EventsSync.Env env = new EventsSync.Env()
	{
		@Override
		public boolean panelEnabled()
		{
			return panelEnabled.get();
		}

		@Override
		public boolean loggedIn()
		{
			return loggedIn.get();
		}

		@Override
		public String token()
		{
			return token;
		}

		@Override
		public boolean pollerRunning()
		{
			return pollerHealthy.get();
		}

		@Override
		public void loadedVersion(long version)
		{
			loadedVersions.add(version);
		}

		@Override
		public void onUnauthorized()
		{
			unauthorized.incrementAndGet();
		}
	};

	@Before
	public void setUp()
	{
		executor = Executors.newScheduledThreadPool(1);
		model = new EventsModel();
		model.addListener(modelChanges::incrementAndGet);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private static ApiClient.Event event(String id, String name, Instant start)
	{
		ApiClient.Event e = new ApiClient.Event();
		e.id = id;
		e.name = name;
		e.description = "";
		e.startTime = start.toString();
		e.endTime = start.plus(Duration.ofHours(2)).toString();
		e.status = "scheduled";
		return e;
	}

	private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + 5_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue(what, condition.getAsBoolean());
	}

	@Test
	public void versionChangeFetchesAndUpdatesTheModel()
	{
		EventsSync sync = new EventsSync(api, env, model, executor);
		serverUpcoming = List.of(event("1", "Mass", Instant.now().plus(Duration.ofHours(1))));
		sync.onServerVersion(1);
		assertEquals(1, eventFetches.get());
		assertEquals(EventsModel.Status.OK, model.status());
		assertEquals(1, model.version());
		assertEquals("Mass", model.events().get(0).getName());
		assertEquals(List.of(1L), loadedVersions);

		sync.onServerVersion(1); // same version: nothing to do
		assertEquals(1, eventFetches.get());

		serverVersion = 2;
		serverUpcoming = List.of(event("1", "Mass (moved)", Instant.now().plus(Duration.ofHours(3))));
		int changesBefore = modelChanges.get();
		sync.onServerVersion(2); // the panel does not need to be opened for this
		assertEquals(2, eventFetches.get());
		assertEquals("Mass (moved)", model.events().get(0).getName());
		assertTrue("listeners are told", modelChanges.get() > changesBefore);
	}

	@Test
	public void nothingIsFetchedWithThePanelOff()
	{
		panelEnabled.set(false);
		EventsSync sync = new EventsSync(api, env, model, executor);
		sync.onServerVersion(5);
		sync.refresh();
		sync.versionCheck();
		assertEquals(0, eventFetches.get() + versionFetches.get());
	}

	@Test
	public void statusWhenNotLoggedInOrNotLinked()
	{
		EventsSync sync = new EventsSync(api, env, model, executor);
		loggedIn.set(false);
		sync.refresh();
		assertEquals(EventsModel.Status.NOT_LOGGED_IN, model.status());
		loggedIn.set(true);
		token = null;
		sync.refresh();
		assertEquals(EventsModel.Status.NOT_LINKED, model.status());
		assertEquals("Link your account in Bootcamp CC settings to see clan events.", model.status().message);
		assertEquals(0, eventFetches.get());
	}

	@Test
	public void errors()
	{
		EventsSync sync = new EventsSync(api, env, model, executor);
		sync.refresh();
		assertEquals(EventsModel.Status.OK, model.status());

		failWith = 500;
		sync.refresh();
		assertEquals("an error keeps the loaded list", EventsModel.Status.OK, model.status());

		failWith = 404;
		sync.refresh();
		assertEquals(EventsModel.Status.UNAVAILABLE, model.status());

		failWith = 401;
		sync.refresh();
		assertEquals(1, unauthorized.get());
	}

	@Test
	public void versionCheckOnlyWhenTheLongPollIsNotWorking() throws Exception
	{
		EventsSync sync = new EventsSync(api, env, model, executor, 60_000, 50);
		sync.start();
		Thread.sleep(300);
		assertEquals("the long poll reports changes itself", 0, versionFetches.get());

		pollerHealthy.set(false);
		waitFor(() -> versionFetches.get() > 0, "checks the version while the long poll is down");
		waitFor(() -> model.version() == 1, "and fetches the list when it differs");

		serverVersion = 2;
		waitFor(() -> model.version() == 2, "a later change is picked up by the check");
		sync.stop();
	}

	@Test
	public void noVersionCheckWhenNotLinked() throws Exception
	{
		pollerHealthy.set(false);
		token = null;
		EventsSync sync = new EventsSync(api, env, model, executor, 60_000, 30);
		sync.start();
		Thread.sleep(200);
		sync.stop();
		assertEquals(0, versionFetches.get());
	}

	@Test
	public void periodicRefresh() throws Exception
	{
		EventsSync sync = new EventsSync(api, env, model, executor, 50, 60_000);
		sync.start();
		waitFor(() -> eventFetches.get() >= 2, "refreshes on a timer");
		sync.stop();
	}

	@Test
	public void panelRebuildsWhenTheModelChangesWithoutBeingOpened() throws Exception
	{
		EventsPanel panel = new EventsPanel(model);
		try
		{
			EventsSync sync = new EventsSync(api, env, model, executor);
			serverUpcoming = List.of(event("1", "Clan mass", Instant.now().plus(Duration.ofHours(5))));
			int before = renders(panel);
			sync.onServerVersion(1);
			SwingUtilities.invokeAndWait(() -> { });
			assertTrue("rebuilt", renders(panel) > before);
			assertTrue(labels(panel).stream().anyMatch(t -> t.contains("Clan mass")));

			serverVersion = 2;
			serverUpcoming = List.of(event("1", "Renamed mass", Instant.now().plus(Duration.ofHours(5))));
			sync.onServerVersion(2);
			SwingUtilities.invokeAndWait(() -> { });
			assertTrue(labels(panel).stream().anyMatch(t -> t.contains("Renamed mass")));
			assertTrue(labels(panel).stream().noneMatch(t -> t.contains("Clan mass")));
		}
		finally
		{
			SwingUtilities.invokeAndWait(panel::shutdown);
		}
	}

	private static int renders(EventsPanel panel) throws Exception
	{
		int[] count = new int[1];
		SwingUtilities.invokeAndWait(() -> count[0] = panel.renderCount());
		return count[0];
	}

	private static List<String> labels(Container root) throws Exception
	{
		List<String> texts = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> collect(root, texts));
		return texts;
	}

	private static void collect(Container c, List<String> into)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel && ((JLabel) child).getText() != null)
			{
				into.add(((JLabel) child).getText());
			}
			if (child instanceof Container)
			{
				collect((Container) child, into);
			}
		}
	}
}
