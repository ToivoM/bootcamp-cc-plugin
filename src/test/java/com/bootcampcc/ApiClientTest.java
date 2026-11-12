package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class ApiClientTest
{
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
		assertEquals("http://localhost:8001/health", ApiClient.resolve("http://localhost:8001", "health").toString());
		assertEquals("http://127.0.0.1:8001/health", ApiClient.resolve("http://127.0.0.1:8001", "health").toString());
	}

	@Test
	public void joinsBaseUrlAndPath()
	{
		assertEquals("https://api.example.com/health", ApiClient.resolve("https://api.example.com", "health").toString());
		assertEquals("https://api.example.com/health", ApiClient.resolve("https://api.example.com/", "/health").toString());
		assertEquals("https://api.example.com/bootcamp/v1/health",
			ApiClient.resolve(" https://api.example.com/bootcamp/ ", "v1/health").toString());
		assertEquals("https://api.example.com/bootcamp",
			ApiClient.resolve("https://api.example.com/bootcamp", "").toString());
	}

	@Test
	public void requestsAreNotSentWithoutApiUrl()
	{
		BootcampCCConfig config = new BootcampCCConfig()
		{
		};
		ApiClient client = new ApiClient(new OkHttpClient(), new Gson(), config);
		assertFalse(client.isConfigured());
		assertFalse(client.get("health", Object.class,
			r -> { throw new AssertionError("no callback expected"); },
			e -> { throw new AssertionError("no callback expected"); }));
	}
}
