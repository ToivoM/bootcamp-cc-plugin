package com.bootcampcc;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Long-poll loop for GET /api/updates/wait: keeps one request open and starts the next one as soon as a
 * response arrives. Errors (network, 5xx, unexpected status, bad JSON) are retried with exponential backoff
 * (5, 10, 20, 40, 60 s, reset by a successful response); 401 stops the loop. stop() cancels the open request.
 * All network IO runs on OkHttp's threads.
 */
@Slf4j
final class UpdatePoller
{
	interface Listener
	{
		/** A successful response for a request made with the given since id. Called on an OkHttp thread. */
		void onUpdates(ApiClient.Updates updates, long since, Instant serverNow);

		/** The API rejected the token. The loop has already stopped. */
		void onUnauthorized();
	}

	/** A request and the since id it was made with. */
	static final class PollRequest
	{
		final Request request;
		final long since;

		PollRequest(Request request, long since)
		{
			this.request = request;
			this.since = since;
		}
	}

	private final OkHttpClient client;
	private final ScheduledExecutorService executor;
	private final Backoff backoff;
	/** Builds the next request, or returns null if polling is not possible (e.g. no token). */
	private final Supplier<PollRequest> requestFactory;
	private final Function<String, ApiClient.Updates> parser;
	private final Listener listener;

	// Guarded by this. The generation changes on every start/stop, so callbacks of an old loop are ignored.
	private boolean running;
	private int generation;
	private Call call;
	private ScheduledFuture<?> retry;
	/** True after a failed request until the next successful one. */
	private boolean failing;

	UpdatePoller(OkHttpClient client, ScheduledExecutorService executor, Backoff backoff,
		Supplier<PollRequest> requestFactory, Function<String, ApiClient.Updates> parser, Listener listener)
	{
		this.client = client;
		this.executor = executor;
		this.backoff = backoff;
		this.requestFactory = requestFactory;
		this.parser = parser;
		this.listener = listener;
	}

	synchronized boolean isRunning()
	{
		return running;
	}

	/** Running and its last request succeeded (not backing off after errors). */
	synchronized boolean isHealthy()
	{
		return running && !failing;
	}

	/** Starts the loop if it is not running. */
	synchronized void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		failing = false;
		generation++;
		backoff.reset();
		final int gen = generation;
		executor.execute(() -> poll(gen));
		log.debug("Bootcamp CC long polling started");
	}

	/** Stops the loop and cancels the open request and any pending retry. */
	synchronized void stop()
	{
		if (!running)
		{
			return;
		}
		running = false;
		generation++;
		if (call != null)
		{
			call.cancel();
			call = null;
		}
		if (retry != null)
		{
			retry.cancel(false);
			retry = null;
		}
		log.debug("Bootcamp CC long polling stopped");
	}

	/** Stop and start again, e.g. after a setting changed; the next request is built from the new state. */
	synchronized void restart()
	{
		stop();
		start();
	}

	private void poll(int gen)
	{
		final PollRequest next;
		final Call newCall;
		synchronized (this)
		{
			if (!isCurrent(gen))
			{
				return;
			}
			PollRequest built;
			try
			{
				built = requestFactory.get();
			}
			catch (RuntimeException e)
			{
				log.warn("Bootcamp CC could not build the long poll request", e);
				built = null;
			}
			if (built == null)
			{
				stop();
				return;
			}
			next = built;
			newCall = client.newCall(next.request);
			call = newCall;
		}

		newCall.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				if (c.isCanceled() || !isCurrent(gen))
				{
					return;
				}
				log.debug("Bootcamp CC long poll failed: {}", e.getMessage());
				retryLater(gen);
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				try (Response r = response)
				{
					if (!isCurrent(gen))
					{
						return;
					}
					if (r.code() == 401)
					{
						stop();
						listener.onUnauthorized();
						return;
					}
					if (!r.isSuccessful())
					{
						log.debug("Bootcamp CC long poll returned HTTP {}", r.code());
						retryLater(gen);
						return;
					}
					ResponseBody body = r.body();
					ApiClient.Updates updates = parser.apply(body == null ? "" : body.string());
					if (updates == null)
					{
						throw new JsonParseException("empty response");
					}
					listener.onUpdates(updates, next.since, ApiClient.serverNow(r));
					synchronized (UpdatePoller.this)
					{
						backoff.reset();
						failing = false;
					}
					schedule(gen, 0);
				}
				catch (IOException | JsonParseException e)
				{
					log.debug("Bootcamp CC long poll response could not be read: {}", e.getMessage());
					retryLater(gen);
				}
				catch (RuntimeException e)
				{
					log.warn("Bootcamp CC update handling failed", e);
					retryLater(gen);
				}
			}
		});
	}

	private synchronized boolean isCurrent(int gen)
	{
		return running && gen == generation;
	}

	private synchronized void retryLater(int gen)
	{
		if (isCurrent(gen))
		{
			failing = true;
			long delay = backoff.next();
			log.debug("Bootcamp CC retrying long poll in {} ms", delay);
			schedule(gen, delay);
		}
	}

	private synchronized void schedule(int gen, long delayMillis)
	{
		if (!isCurrent(gen))
		{
			return;
		}
		if (delayMillis <= 0)
		{
			executor.execute(() -> poll(gen));
		}
		else
		{
			retry = executor.schedule(() -> poll(gen), delayMillis, TimeUnit.MILLISECONDS);
		}
	}
}
