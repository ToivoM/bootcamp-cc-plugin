package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

public class ApiClientTest
{
	private static ApiClient client(String apiUrl)
	{
		BootcampCCConfig config = new BootcampCCConfig()
		{
			@Override
			public String apiUrl()
			{
				return apiUrl;
			}
		};
		return new ApiClient(new OkHttpClient(), new Gson(), config);
	}

	private static final ApiClient.ApiCallback<Object> NEVER = new ApiClient.ApiCallback<Object>()
	{
		@Override
		public void onSuccess(Object body, java.time.Instant serverNow)
		{
			throw new AssertionError("no callback expected");
		}

		@Override
		public void onError(int status)
		{
			throw new AssertionError("no callback expected");
		}
	};

	@SuppressWarnings("unchecked")
	private static <T> ApiClient.ApiCallback<T> never()
	{
		return (ApiClient.ApiCallback<T>) (ApiClient.ApiCallback<?>) NEVER;
	}

	@Test
	public void emptyOrInvalidBaseUrlIsNotConfigured()
	{
		assertNull(ApiClient.resolve("", "health"));
		assertNull(ApiClient.resolve("   ", "health"));
		assertNull(ApiClient.resolve(null, "health"));
		assertNull(ApiClient.resolve("not a url", "health"));
		assertNull(ApiClient.resolve("ftp://api.example.com", "health"));
	}

	@Test
	public void plainHttpOnlyForLocalhost()
	{
		assertNull(ApiClient.resolve("http://api.example.com", "health"));
		assertNull(ApiClient.resolve("http://192.168.1.10:8001", "health"));
		assertNull(ApiClient.resolve("http://localhost.example.com", "health"));
		assertEquals("http://localhost:8001/health", ApiClient.resolve("http://localhost:8001", "health").toString());
		assertEquals("http://127.0.0.1:8001/health", ApiClient.resolve("http://127.0.0.1:8001", "health").toString());
		assertEquals("http://[::1]:8001/health", ApiClient.resolve("http://[::1]:8001", "health").toString());
		assertEquals("https://api.example.com/health", ApiClient.resolve("https://api.example.com", "health").toString());
	}

	@Test
	public void joinsBaseUrlAndPath()
	{
		assertEquals("https://api.example.com/health", ApiClient.resolve("https://api.example.com/", "/health").toString());
		assertEquals("https://api.example.com/bootcamp/api/link",
			ApiClient.resolve(" https://api.example.com/bootcamp/ ", "api/link").toString());
	}

	@Test
	public void nothingIsSentWithoutApiUrl()
	{
		ApiClient api = client("");
		assertFalse(api.isConfigured());
		assertFalse(api.link("ABCDEFGH", "Zezima", never()));
		assertFalse(api.events("token", never()));
		assertFalse(api.eventsVersion("token", never()));
		assertNull(api.updatesRequest("token", 0, 0L));
	}

	@Test
	public void insecureApiUrlIsNotUsed()
	{
		ApiClient api = client("http://api.example.com");
		assertFalse(api.isConfigured());
		assertFalse(api.link("ABCDEFGH", "Zezima", never()));
	}

	@Test
	public void updatesRequest()
	{
		Request request = client("https://api.example.com").updatesRequest("tok", 12, 3L);
		HttpUrl url = request.url();
		assertEquals("/api/updates/wait", url.encodedPath());
		assertEquals("12", url.queryParameter("since"));
		assertEquals("3", url.queryParameter("events_version"));
		assertEquals("25", url.queryParameter("timeout"));
		assertEquals("Bearer tok", request.header("Authorization"));

		Request withoutEvents = client("https://api.example.com").updatesRequest("tok", 0, null);
		assertNull(withoutEvents.url().queryParameter("events_version"));
		assertTrue(client("https://api.example.com").isConfigured());
	}
}
