package com.bootcampcc;

import com.google.inject.Provides;
import java.awt.Color;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;
import okhttp3.Request;

/**
 * Bootcamp CC clan plugin: account linking, clan broadcasts and the clan's Discord events.
 *
 * <p>Talks only to the Bootcamp CC API, and only after the player links their account. Everything is
 * shown locally (chatbox, overlay, side panel); nothing is ever sent to the game server, typed into the
 * chatbox or added to menus.
 */
@Slf4j
@PluginDescriptor(
	name = "Bootcamp CC",
	description = "Clan broadcasts and Discord events for the Bootcamp CC clan",
	tags = {"clan", "bootcamp", "cc", "discord", "broadcast", "events"}
)
public class BootcampCCPlugin extends Plugin
{
	static final String PREFIX = "[Bootcamp CC]";
	static final Color BRAND_COLOR = new Color(0xE0, 0x8A, 0x1E);

	private static final long BACKOFF_INITIAL_MILLIS = 5_000;
	private static final long BACKOFF_MAX_MILLIS = 60_000;
	/** After login, give RuneLite a moment to switch to the account's RS profile, where the token is. */
	private static final long LOGIN_DELAY_SECONDS = 5;

	@Inject
	private Client client;

	@Inject
	private BootcampCCConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ApiClient api;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Notifier notifier;

	@Inject
	private BroadcastOverlay overlay;

	@Inject
	private ClientToolbar clientToolbar;

	private StoredState state;
	private Linker linker;
	private UpdatePoller poller;
	private EventsModel eventsModel;
	private EventsSync eventsSync;
	private EventsPanel eventsPanel;
	private NavigationButton eventsButton;

	/** The local player's name, updated on the client thread every tick; null when logged out. */
	private volatile String rsn;
	/** Set when linking should be (re)checked; handled on the next game tick, where the name is known. */
	private volatile boolean linkCheckPending;
	/** Client thread only: whether the next LOGGED_IN is a fresh login (or world hop). */
	private boolean loggingIn;

	@Override
	protected void startUp()
	{
		state = new StoredState(new ProfileStore());
		linker = new Linker(api, new Linker.Listener()
		{
			@Override
			public void onLinked(String token, String name)
			{
				handleLinked(token, name);
			}

			@Override
			public void onMessage(String text)
			{
				chat(text);
			}
		});
		poller = new UpdatePoller(api.longPollClient(), executor, new Backoff(BACKOFF_INITIAL_MILLIS, BACKOFF_MAX_MILLIS),
			this::nextPollRequest, api::parseUpdates, new UpdatePoller.Listener()
		{
			@Override
			public void onUpdates(ApiClient.Updates updates, long since, Instant serverNow)
			{
				handleUpdates(updates, since, serverNow);
			}

			@Override
			public void onUnauthorized()
			{
				handleUnauthorized();
			}
		});

		eventsModel = new EventsModel();
		eventsSync = new EventsSync(api, new EventsEnv(), eventsModel, executor);
		eventsPanel = new EventsPanel(eventsModel);
		eventsPanel.setOnOpen(() -> executor.execute(eventsSync::refresh));
		eventsButton = NavigationButton.builder()
			.tooltip("Bootcamp CC events")
			.icon(ImageUtil.loadImageResource(BootcampCCPlugin.class, "nav_icon.png"))
			.priority(8)
			.panel(eventsPanel)
			.build();
		if (config.showEventsPanel())
		{
			clientToolbar.addNavigation(eventsButton);
		}
		eventsSync.start();

		overlayManager.add(overlay);
		mouseManager.registerMouseListener(overlay.mouseListener);

		linkCheckPending = true;
		// In case the plugin is turned on while already logged in.
		executor.schedule(this::afterLogin, 2, TimeUnit.SECONDS);
		log.debug("Bootcamp CC started");
	}

	@Override
	protected void shutDown()
	{
		poller.stop();
		eventsSync.stop();
		clientToolbar.removeNavigation(eventsButton);
		eventsPanel.shutdown();
		mouseManager.unregisterMouseListener(overlay.mouseListener);
		overlayManager.remove(overlay);
		overlay.clear();
		rsn = null;
		log.debug("Bootcamp CC stopped");
	}

	@Provides
	BootcampCCConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BootcampCCConfig.class);
	}

	// ------------------------------------------------------------------------------------------------
	// Game and config events
	// ------------------------------------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gameState = event.getGameState();
		if (gameState == GameState.LOGGING_IN || gameState == GameState.HOPPING)
		{
			loggingIn = true;
		}
		else if (gameState == GameState.LOGGED_IN)
		{
			linkCheckPending = true;
			if (loggingIn)
			{
				loggingIn = false;
				linker.allowRetry();
				executor.schedule(this::afterLogin, LOGIN_DELAY_SECONDS, TimeUnit.SECONDS);
			}
		}
		else if (gameState == GameState.LOGIN_SCREEN)
		{
			rsn = null;
			poller.stop();
			overlay.clear();
			eventsModel.setStatus(EventsModel.Status.NOT_LOGGED_IN);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		rsn = local.getName();
		if (linkCheckPending)
		{
			linkCheckPending = false;
			linker.tryLink(config.linkCode(), rsn); // only enqueues a request; nothing blocks here
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BootcampCCConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		switch (event.getKey())
		{
			case BootcampCCConfig.KEY_LINK_CODE:
				linkCheckPending = true; // handled on the next game tick, where the name is known
				break;
			case BootcampCCConfig.KEY_API_URL:
				// A token and ids from the old server mean nothing on the new one. (Other RS profiles keep theirs,
				// but they are tagged with their API URL and are never used with another.)
				state.clear();
				overlay.clear();
				linker.allowRetry();
				linkCheckPending = true;
				poller.stop();
				executor.execute(this::afterLogin);
				break;
			case BootcampCCConfig.KEY_RECEIVE_BROADCASTS:
				if (!config.receiveBroadcasts())
				{
					overlay.clear();
				}
				executor.execute(this::updatePolling);
				break;
			case BootcampCCConfig.KEY_SHOW_OVERLAY:
				if (!config.showBroadcastOverlay())
				{
					overlay.clear();
				}
				break;
			case BootcampCCConfig.KEY_SHOW_EVENTS_PANEL:
				if (config.showEventsPanel())
				{
					clientToolbar.addNavigation(eventsButton);
					executor.execute(eventsSync::refresh);
				}
				else
				{
					clientToolbar.removeNavigation(eventsButton);
				}
				// The long poll sends events_version only while the panel is on: start a new request.
				poller.stop();
				executor.execute(this::updatePolling);
				break;
			default:
				break;
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Linking
	// ------------------------------------------------------------------------------------------------

	/** Called on an OkHttp thread. */
	private void handleLinked(String token, String name)
	{
		state.linked(api.apiUrl(), token);
		configManager.unsetConfiguration(BootcampCCConfig.GROUP, BootcampCCConfig.KEY_LINK_CODE);
		overlay.clear();
		poller.stop();
		log.debug("Bootcamp CC linked as {}", name);
		afterLogin();
	}

	/** The API rejected the token (relinked elsewhere, or revoked): forget it so a new code can be used. */
	private void handleUnauthorized()
	{
		if (state.token(api.apiUrl()) == null)
		{
			return; // already handled by another request
		}
		log.debug("Bootcamp CC token rejected by the API; forgetting it");
		poller.stop();
		state.clear();
		overlay.clear();
		eventsModel.setStatus(EventsModel.Status.NOT_LINKED);
		chat("Your account link is no longer valid. Get a new code with /link in the Bootcamp CC Discord.");
	}

	// ------------------------------------------------------------------------------------------------
	// Long poll: broadcasts and event changes
	// ------------------------------------------------------------------------------------------------

	/** Start whatever should run now that the player (or the link, or a setting) changed. Not on the client thread. */
	private void afterLogin()
	{
		updatePolling();
		eventsSync.refresh();
	}

	/**
	 * Runs the long poll only while it is wanted: logged in, linked, and broadcasts or the events panel on.
	 * Safe to call from any thread except the client thread.
	 */
	private void updatePolling()
	{
		try
		{
			boolean wanted = isLoggedIn()
				&& state.isLinked(api.apiUrl())
				&& api.isConfigured()
				&& (config.receiveBroadcasts() || config.showEventsPanel());
			if (wanted)
			{
				poller.start();
			}
			else
			{
				poller.stop();
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Bootcamp CC could not update long polling", e);
		}
	}

	/** The next long poll request, or null if polling is not possible right now. */
	private UpdatePoller.PollRequest nextPollRequest()
	{
		String apiUrl = api.apiUrl();
		String token = state.token(apiUrl);
		if (token == null)
		{
			return null;
		}
		long since = state.lastBroadcastId(apiUrl);
		Long eventsVersion = config.showEventsPanel() ? knownEventsVersion() : null;
		Request request = api.updatesRequest(token, since, eventsVersion);
		return request == null ? null : new UpdatePoller.PollRequest(request, since);
	}

	private long knownEventsVersion()
	{
		long shown = eventsModel.version();
		return shown >= 0 ? shown : Math.max(0, state.eventsVersion(api.apiUrl()));
	}

	/**
	 * Called on an OkHttp thread with what the server considers new for this since id. Broadcasts are not
	 * filtered by id: after a server or database change the server may return ids lower than since.
	 */
	private void handleUpdates(ApiClient.Updates updates, long since, Instant serverNow)
	{
		String apiUrl = api.apiUrl();
		List<ApiClient.Broadcast> broadcasts = updates.broadcasts == null
			? Collections.emptyList() : updates.broadcasts;
		long next = nextSince(since, broadcasts);
		if (config.receiveBroadcasts())
		{
			deliver(broadcasts, serverNow);
		}
		// The cursor moves on even when broadcasts are off, so the server does not send them again and again.
		if (next != since)
		{
			state.setLastBroadcastId(apiUrl, next);
		}
		if (updates.retracted != null && !updates.retracted.isEmpty())
		{
			overlay.retract(updates.retracted);
		}
		eventsSync.onServerVersion(updates.eventsVersion);
	}

	/** The highest returned id, or since if nothing was returned. */
	static long nextSince(long since, List<ApiClient.Broadcast> broadcasts)
	{
		long max = -1;
		for (ApiClient.Broadcast b : broadcasts)
		{
			if (b != null)
			{
				max = Math.max(max, b.id);
			}
		}
		return max < 0 ? since : max;
	}

	private void deliver(List<ApiClient.Broadcast> broadcasts, Instant serverNow)
	{
		List<BroadcastDelivery.Item> items = new ArrayList<>();
		for (ApiClient.Broadcast b : broadcasts)
		{
			if (b != null && b.text != null && !b.text.isEmpty())
			{
				items.add(new BroadcastDelivery.Item(b.id, b.text, parseInstant(b.createdAt)));
			}
		}
		if (items.isEmpty())
		{
			return;
		}
		BroadcastDelivery.Plan plan = BroadcastDelivery.plan(items, serverNow);
		plan.getChatLines().forEach(this::chat);
		for (BroadcastDelivery.Item item : plan.getOverlayItems())
		{
			if (config.showBroadcastOverlay())
			{
				overlay.add(item.getId(), item.getText());
			}
			if (config.notifyOnBroadcast())
			{
				notifier.notify(PREFIX + " " + item.getText());
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	private boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN && configManager.getRSProfileKey() != null;
	}

	/** Shows a message in the local chatbox only. Nothing is sent to the game server. */
	private void chat(String text)
	{
		String safe = (PREFIX + " " + text).replace("<", "<lt>").replace(">", "<gt>");
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(ColorUtil.wrapWithColorTag(safe, BRAND_COLOR))
			.build());
	}

	private static Instant parseInstant(String value)
	{
		if (value == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	/** StoredState backed by the RS profile configuration of the logged-in account. */
	private final class ProfileStore implements StoredState.Store
	{
		@Override
		public String get(String key)
		{
			return configManager.getRSProfileConfiguration(BootcampCCConfig.GROUP, key);
		}

		@Override
		public void set(String key, String value)
		{
			configManager.setRSProfileConfiguration(BootcampCCConfig.GROUP, key, value);
		}

		@Override
		public void unset(String key)
		{
			configManager.unsetRSProfileConfiguration(BootcampCCConfig.GROUP, key);
		}
	}

	private final class EventsEnv implements EventsSync.Env
	{
		@Override
		public boolean panelEnabled()
		{
			return config.showEventsPanel();
		}

		@Override
		public boolean loggedIn()
		{
			return isLoggedIn();
		}

		@Override
		public String token()
		{
			return state.token(api.apiUrl());
		}

		@Override
		public boolean pollerRunning()
		{
			return poller.isHealthy();
		}

		@Override
		public void loadedVersion(long version)
		{
			state.setEventsVersion(api.apiUrl(), version);
		}

		@Override
		public void onUnauthorized()
		{
			handleUnauthorized();
		}
	}
}
