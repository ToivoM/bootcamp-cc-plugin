package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs the long-poll loop against a local HTTP server. Backoff is scaled down to milliseconds. */
public class UpdatePollerTest
{
	private interface Reply
	{
		void handle(HttpExchange ex) throws Exception;
	}

	private static final String EMPTY = "{\"broadcasts\": [], \"retracted\": [], \"events_version\": 0}";

	private HttpServer server;
	private ScheduledExecutorService executor;
	private OkHttpClient client;
	private final Gson gson = new Gson();
	private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
	/** Arrival time (ms) and since of every request. */
	private final List<long[]> requests = new CopyOnWriteArrayList<>();
	private final List<String> received = new CopyOnWriteArrayList<>();
	private final List<Long> retracted = new CopyOnWriteArrayList<>();
	private final AtomicLong eventsVersion = new AtomicLong(-1);
	private final AtomicInteger unauthorized = new AtomicInteger();
	private final AtomicLong since = new AtomicLong();
	private final AtomicReference<Instant> serverNow = new AtomicReference<>();
	private final AtomicReference<String> token = new AtomicReference<>("tok");
	private UpdatePoller poller;

	@Before
	public void setUp() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/api/updates/wait", ex ->
		{
			String query = ex.getRequestURI().getQuery();
			requests.add(new long[]{System.currentTimeMillis(), Long.parseLong(query.replaceAll(".*since=(\\d+).*", "$1"))});
			try
			{
				Reply reply = replies.poll(5, TimeUnit.SECONDS);
				(reply == null ? hold(10_000) : reply).handle(ex);
			}
			catch (Exception e)
			{
				// the client went away
			}
			finally
			{
				ex.close();
			}
		});
		server.start();

		executor = Executors.newScheduledThreadPool(2);
		client = new OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build();
		HttpUrl base = HttpUrl.parse("http://127.0.0.1:" + server.getAddress().getPort() + "/api/updates/wait");
		poller = new UpdatePoller(client, executor, new Backoff(50, 400), () ->
		{
			String t = token.get();
			if (t == null)
			{
				return null;
			}
			long s = since.get();
			Request request = new Request.Builder()
				.url(base.newBuilder().addQueryParameter("since", Long.toString(s)).build())
				.header("Authorization", "Bearer " + t)
				.build();
			return new UpdatePoller.PollRequest(request, s);
		}, json -> gson.fromJson(json, ApiClient.Updates.class), new UpdatePoller.Listener()
		{
			@Override
			public void onUpdates(ApiClient.Updates updates, long sinceId, Instant now)
			{
				serverNow.set(now);
				for (ApiClient.Broadcast b : updates.broadcasts)
				{
					received.add(b.text);
					since.set(Math.max(since.get(), b.id));
				}
				retracted.addAll(updates.retracted);
				eventsVersion.set(updates.eventsVersion);
			}

			@Override
			public void onUnauthorized()
			{
				unauthorized.incrementAndGet();
			}
		});
	}

	@After
	public void tearDown()
	{
		poller.stop();
		server.stop(0);
		executor.shutdownNow();
		client.dispatcher().executorService().shutdownNow();
	}

	private static Reply json(int status, String body)
	{
		return ex ->
		{
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = ex.getResponseBody())
			{
				out.write(bytes);
			}
		};
	}

	private static Reply hold(long millis)
	{
		return ex ->
		{
			Thread.sleep(millis);
			json(200, EMPTY).handle(ex);
		};
	}

	private void waitForRequests(int n) throws InterruptedException
	{
		waitFor(() -> requests.size() >= n, "expected " + n + " requests");
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
	public void deliversUpdatesAndPollsAgainRightAway() throws Exception
	{
		replies.add(json(200, "{\"broadcasts\": [{\"id\": 1, \"text\": \"hello\", \"created_at\": \"2027-01-15T08:00:00Z\"},"
			+ " {\"id\": 2, \"text\": \"world\"}], \"retracted\": [7], \"events_version\": 4}"));
		poller.start();
		waitForRequests(2);
		assertEquals(List.of("hello", "world"), received);
		assertEquals(List.of(7L), retracted);
		assertEquals(4, eventsVersion.get());
		assertEquals(0, requests.get(0)[1]);
		assertEquals("the next request continues from the newest id", 2, requests.get(1)[1]);
		assertTrue("the next request starts at once", requests.get(1)[0] - requests.get(0)[0] < 1_000);
		assertTrue(poller.isHealthy());
		assertTrue("server time comes from the Date header", Math.abs(serverNow.get().toEpochMilli() - System.currentTimeMillis()) < 5_000);
	}

	@Test
	public void backsOffExponentiallyOnErrorsAndResetsAfterSuccess() throws Exception
	{
		replies.add(json(500, "{}"));
		replies.add(json(503, "{}"));
		replies.add(json(200, "not json"));
		replies.add(json(200, EMPTY));
		replies.add(json(500, "{}"));
		poller.start();
		waitForRequests(6);
		long gap1 = requests.get(1)[0] - requests.get(0)[0];
		long gap2 = requests.get(2)[0] - requests.get(1)[0];
		long gap3 = requests.get(3)[0] - requests.get(2)[0];
		long gapAfterSuccess = requests.get(4)[0] - requests.get(3)[0];
		long gapReset = requests.get(5)[0] - requests.get(4)[0];
		assertTrue("50 ms: " + gap1, gap1 >= 45);
		assertTrue("100 ms: " + gap2, gap2 >= 95);
		assertTrue("200 ms: " + gap3, gap3 >= 195);
		assertTrue("at once after success: " + gapAfterSuccess, gapAfterSuccess < 150);
		assertTrue("backoff reset to 50 ms: " + gapReset, gapReset >= 45 && gapReset < 195);
	}

	@Test
	public void isNotHealthyWhileBackingOff() throws Exception
	{
		replies.add(json(500, "{}"));
		replies.add(hold(3_000));
		poller.start();
		waitForRequests(1);
		waitFor(() -> !poller.isHealthy(), "unhealthy after an error");
		assertTrue(poller.isRunning());
	}

	@Test
	public void networkErrorIsRetried() throws Exception
	{
		replies.add(ex -> ex.getResponseBody().close()); // closes the connection without a response
		replies.add(json(200, "{\"broadcasts\": [{\"id\": 5, \"text\": \"after error\"}], \"retracted\": [], \"events_version\": 0}"));
		poller.start();
		waitFor(() -> received.contains("after error"), "delivered after a network error");
	}

	@Test
	public void unauthorizedStopsTheLoop() throws Exception
	{
		replies.add(json(401, "{}"));
		poller.start();
		waitFor(() -> unauthorized.get() == 1, "told about 401");
		Thread.sleep(300);
		assertEquals(1, requests.size());
		assertFalse(poller.isRunning());
	}

	@Test
	public void stopCancelsTheOpenRequest() throws Exception
	{
		replies.add(hold(3_000));
		poller.start();
		waitForRequests(1);
		Thread.sleep(100);
		poller.stop();
		waitFor(() -> client.dispatcher().runningCallsCount() == 0, "the open call is cancelled");
		Thread.sleep(500);
		assertEquals("a cancelled call is not retried", 1, requests.size());
		assertFalse(poller.isRunning());
	}

	@Test
	public void noRequestPossibleStopsTheLoop() throws Exception
	{
		token.set(null);
		poller.start();
		Thread.sleep(200);
		assertFalse(poller.isRunning());
		assertTrue(requests.isEmpty());
	}

	@Test
	public void restartUsesTheNewState() throws Exception
	{
		replies.add(hold(3_000));
		poller.start();
		waitForRequests(1);
		since.set(41);
		replies.add(json(200, "{\"broadcasts\": [{\"id\": 42, \"text\": \"again\"}], \"retracted\": [], \"events_version\": 0}"));
		poller.restart();
		waitFor(() -> received.contains("again"), "delivered after restart");
		assertEquals(41, requests.get(1)[1]);
	}
}
