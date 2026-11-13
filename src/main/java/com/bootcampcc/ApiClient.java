package com.bootcampcc;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The Bootcamp CC API: every request the plugin makes goes through here.
 *
 * <p>Rules for every caller:
 * <ul>
 *   <li>Requests are always asynchronous ({@link Call#enqueue}); nothing here blocks, so it is safe to call
 *       from the client thread.</li>
 *   <li>Callbacks run on an OkHttp thread, never on the client thread. Use {@code clientThread.invoke(...)}
 *       before touching the game client from a callback.</li>
 *   <li>Nothing is sent before the player links their account (see DEVELOPMENT.md).</li>
 * </ul>
 */
@Slf4j
@Singleton
public class ApiClient implements Linker.Api, EventsSync.Api
{
	/** The server holds a long poll for at most this long. */
	static final int LONG_POLL_SECONDS = 25;
	/** Read timeout for long polls: longer than the server's wait. */
	static final int LONG_POLL_READ_TIMEOUT_SECONDS = 35;
	/** Status passed to {@link ApiCallback#onError} when the request did not get an HTTP response. */
	static final int NETWORK_ERROR = -1;

	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	interface ApiCallback<T>
	{
		/** A 2xx response. serverNow is the server's time (Date header), or the local time without one. */
		void onSuccess(T body, Instant serverNow);

		/** An HTTP error status, or {@link #NETWORK_ERROR}. */
		void onError(int status);
	}

	// --- response bodies ------------------------------------------------------------------------------

	static final class LinkResponse
	{
		String token;
	}

	static final class Broadcast
	{
		long id;
		String text;
		/** ISO 8601 UTC. */
		@SerializedName("created_at")
		String createdAt;
	}

	static final class Updates
	{
		List<Broadcast> broadcasts;
		List<Long> retracted;
		@SerializedName("events_version")
		long eventsVersion;
	}

	static final class Event
	{
		String id;
		String name;
		String description;
		String location;
		@SerializedName("start_time")
		String startTime;
		@SerializedName("end_time")
		String endTime;
		String status;
	}

	static final class EventsResponse
	{
		long version;
		List<Event> ongoing;
		List<Event> upcoming;
		List<Event> past;
	}

	static final class VersionResponse
	{
		long version;
	}

	// --------------------------------------------------------------------------------------------------

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final BootcampCCConfig config;
	private OkHttpClient longPollClient;

	@Inject
	ApiClient(OkHttpClient httpClient, Gson gson, BootcampCCConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	/** The API URL setting as entered (for StoredState). */
	String apiUrl()
	{
		return config.apiUrl();
	}

	/** True if the API URL is set and valid. */
	boolean isConfigured()
	{
		return resolve(config.apiUrl(), "") != null;
	}

	/** A copy of RuneLite's client with a read timeout longer than the server's long poll wait. */
	synchronized OkHttpClient longPollClient()
	{
		if (longPollClient == null)
		{
			longPollClient = httpClient.newBuilder().readTimeout(LONG_POLL_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build();
		}
		return longPollClient;
	}

	/** POST /api/link. Returns false (no callback) if the API URL is not set or not valid. */
	@Override
	public boolean link(String code, String rsn, ApiCallback<LinkResponse> callback)
	{
		HttpUrl url = resolve(config.apiUrl(), "api/link");
		if (url == null)
		{
			return false;
		}
		Map<String, String> body = new HashMap<>();
		body.put("code", code);
		body.put("rsn", rsn);
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(body))).build();
		enqueue(httpClient, request, LinkResponse.class, callback);
		return true;
	}

	/** GET /api/events. Returns false (no callback) if the API URL is not set or not valid. */
	@Override
	public boolean events(String token, ApiCallback<EventsResponse> callback)
	{
		return get("api/events", token, EventsResponse.class, callback);
	}

	/** GET /api/events/version. Returns false (no callback) if the API URL is not set or not valid. */
	@Override
	public boolean eventsVersion(String token, ApiCallback<VersionResponse> callback)
	{
		return get("api/events/version", token, VersionResponse.class, callback);
	}

	/**
	 * The long poll request GET /api/updates/wait, or null if the API URL is not set or not valid.
	 *
	 * @param eventsVersion null if the plugin does not track events (event changes then do not end the wait)
	 */
	@Nullable
	Request updatesRequest(String token, long since, @Nullable Long eventsVersion)
	{
		HttpUrl base = resolve(config.apiUrl(), "api/updates/wait");
		if (base == null)
		{
			return null;
		}
		HttpUrl.Builder url = base.newBuilder()
			.addQueryParameter("since", Long.toString(Math.max(0, since)))
			.addQueryParameter("timeout", Integer.toString(LONG_POLL_SECONDS));
		if (eventsVersion != null)
		{
			url.addQueryParameter("events_version", Long.toString(Math.max(0, eventsVersion)));
		}
		return authorized(url.build(), token);
	}

	Updates parseUpdates(String json)
	{
		return gson.fromJson(json, Updates.class);
	}

	private <T> boolean get(String path, String token, Class<T> type, ApiCallback<T> callback)
	{
		HttpUrl url = resolve(config.apiUrl(), path);
		if (url == null)
		{
			return false;
		}
		enqueue(httpClient, authorized(url, token), type, callback);
		return true;
	}

	private static Request authorized(HttpUrl url, String token)
	{
		return new Request.Builder().url(url).header("Authorization", "Bearer " + token).get().build();
	}

	private <T> void enqueue(OkHttpClient client, Request request, Class<T> type, ApiCallback<T> callback)
	{
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bootcamp CC API request failed: {} {}: {}", request.method(), request.url().encodedPath(), e.getMessage());
				callback.onError(NETWORK_ERROR);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				T result;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful())
					{
						log.debug("Bootcamp CC API {} returned HTTP {}", request.url().encodedPath(), response.code());
						callback.onError(response.code());
						return;
					}
					if (body == null)
					{
						throw new IOException("empty response");
					}
					try (Reader reader = body.charStream())
					{
						result = gson.fromJson(reader, type);
					}
					if (result == null)
					{
						throw new IOException("empty response");
					}
				}
				catch (IOException | JsonParseException e)
				{
					log.debug("Bootcamp CC API {} response could not be read: {}", request.url().encodedPath(), e.getMessage());
					callback.onError(NETWORK_ERROR);
					return;
				}
				callback.onSuccess(result, serverNow(response));
			}
		});
	}

	/** The server's time from the Date header, or the local time without one. */
	static Instant serverNow(Response response)
	{
		Date date = response.headers().getDate("Date");
		return date != null ? date.toInstant() : Instant.now();
	}

	/**
	 * Join the configured base URL and an API path.
	 *
	 * <p>Returns null if the base URL is empty or invalid, or uses plain http to anything other than
	 * localhost (plain http is only for local development against a bot on the same machine).
	 */
	@Nullable
	static HttpUrl resolve(String baseUrl, String path)
	{
		if (baseUrl == null || baseUrl.trim().isEmpty())
		{
			return null;
		}
		HttpUrl base = HttpUrl.parse(baseUrl.trim());
		if (base == null || !(base.isHttps() || isLocalhost(base.host())))
		{
			return null;
		}
		String relative = path.startsWith("/") ? path.substring(1) : path;
		if (relative.isEmpty())
		{
			return base;
		}
		return base.newBuilder().addPathSegments(relative).build();
	}

	private static boolean isLocalhost(String host)
	{
		return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
	}
}
