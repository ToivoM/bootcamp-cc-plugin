package com.bootcampcc;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;
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
 * Small JSON client for the Bootcamp CC API.
 *
 * <p>Rules for every caller:
 * <ul>
 *   <li>Requests are always asynchronous ({@link Call#enqueue}). Nothing here ever blocks, so it is safe
 *       to call from the client thread.</li>
 *   <li>Callbacks run on an OkHttp thread, never on the client thread. Use
 *       {@code clientThread.invoke(...)} before touching the game client from a callback.</li>
 *   <li>A feature that sends anything to the API must be opt-in (off by default) and carry the
 *       third-party server warning. See DEVELOPMENT.md.</li>
 * </ul>
 *
 * <p>Not used by any feature yet.
 */
@Slf4j
@Singleton
public class ApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final BootcampCCConfig config;

	@Inject
	ApiClient(OkHttpClient httpClient, Gson gson, BootcampCCConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	/** True if the API URL is set and valid. */
	public boolean isConfigured()
	{
		return resolve(config.apiUrl(), "") != null;
	}

	/**
	 * GET {@code path} and parse the JSON response as {@code type}.
	 *
	 * @return false (and no callback is called) if the API URL is not configured or not valid
	 */
	public <T> boolean get(String path, Class<T> type, Consumer<T> onSuccess, Consumer<Exception> onFailure)
	{
		HttpUrl url = resolve(config.apiUrl(), path);
		if (url == null)
		{
			return false;
		}
		enqueue(new Request.Builder().url(url).get().build(), type, onSuccess, onFailure);
		return true;
	}

	/**
	 * POST {@code body} as JSON to {@code path} and parse the JSON response as {@code type}.
	 *
	 * @return false (and no callback is called) if the API URL is not configured or not valid
	 */
	public <T> boolean post(String path, Object body, Class<T> type, Consumer<T> onSuccess, Consumer<Exception> onFailure)
	{
		HttpUrl url = resolve(config.apiUrl(), path);
		if (url == null)
		{
			return false;
		}
		RequestBody requestBody = RequestBody.create(JSON, gson.toJson(body));
		enqueue(new Request.Builder().url(url).post(requestBody).build(), type, onSuccess, onFailure);
		return true;
	}

	private <T> void enqueue(Request request, Class<T> type, Consumer<T> onSuccess, Consumer<Exception> onFailure)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bootcamp CC API request failed: {} {}", request.method(), request.url(), e);
				onFailure.accept(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					if (!response.isSuccessful())
					{
						throw new IOException("HTTP " + response.code() + " from " + request.url().encodedPath());
					}
					if (responseBody == null)
					{
						throw new IOException("Empty response from " + request.url().encodedPath());
					}
					T result;
					try (Reader reader = responseBody.charStream())
					{
						result = gson.fromJson(reader, type);
					}
					onSuccess.accept(result);
				}
				catch (IOException | JsonParseException e)
				{
					log.debug("Bootcamp CC API request failed: {} {}", request.method(), request.url(), e);
					onFailure.accept(e);
				}
			}
		});
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
