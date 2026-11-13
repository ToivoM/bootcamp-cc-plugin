package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class StoredStateTest
{
	static final class MapStore implements StoredState.Store
	{
		final Map<String, String> values = new HashMap<>();

		@Override
		public String get(String key)
		{
			return values.get(key);
		}

		@Override
		public void set(String key, String value)
		{
			values.put(key, value);
		}

		@Override
		public void unset(String key)
		{
			values.remove(key);
		}
	}

	private static final String API = "https://api.example.com";

	@Test
	public void nothingStored()
	{
		StoredState state = new StoredState(new MapStore());
		assertNull(state.token(API));
		assertFalse(state.isLinked(API));
		assertEquals(0, state.lastBroadcastId(API));
		assertEquals(-1, state.eventsVersion(API));
	}

	@Test
	public void valuesBelongToTheirApiUrl()
	{
		StoredState state = new StoredState(new MapStore());
		state.linked(API + "/", "tok");
		state.setLastBroadcastId(API, 7);
		state.setEventsVersion(API, 3);
		assertEquals("tok", state.token(API));
		assertEquals("tok", state.token(" " + API + "// "));
		assertEquals(7, state.lastBroadcastId(API));
		assertEquals(3, state.eventsVersion(API));

		String other = "http://localhost:8001";
		assertNull(state.token(other));
		assertEquals(0, state.lastBroadcastId(other));
		assertEquals(-1, state.eventsVersion(other));
		state.setLastBroadcastId(other, 99); // ignored: not linked there
		assertEquals(7, state.lastBroadcastId(API));
	}

	@Test
	public void linkingStartsOver()
	{
		StoredState state = new StoredState(new MapStore());
		state.linked(API, "old");
		state.setLastBroadcastId(API, 7);
		state.setEventsVersion(API, 3);
		state.linked(API, "new");
		assertEquals("new", state.token(API));
		assertEquals(0, state.lastBroadcastId(API));
		assertEquals(-1, state.eventsVersion(API));
	}

	@Test
	public void clearForgetsEverything()
	{
		MapStore store = new MapStore();
		StoredState state = new StoredState(store);
		state.linked(API, "tok");
		state.setLastBroadcastId(API, 7);
		state.clear();
		assertFalse(state.isLinked(API));
		assertTrue(store.values.isEmpty());
	}

	@Test
	public void valuesWithoutApiUrlAreNotUsed()
	{
		MapStore store = new MapStore();
		store.set(StoredState.KEY_TOKEN, "tok");
		store.set(StoredState.KEY_LAST_BROADCAST_ID, "5");
		StoredState state = new StoredState(store);
		assertNull(state.token(API));
		assertEquals(0, state.lastBroadcastId(API));
	}

	@Test
	public void badNumbersAreIgnored()
	{
		MapStore store = new MapStore();
		StoredState state = new StoredState(store);
		state.linked(API, "tok");
		store.set(StoredState.KEY_LAST_BROADCAST_ID, "abc");
		store.set(StoredState.KEY_EVENTS_VERSION, "-4");
		assertEquals(0, state.lastBroadcastId(API));
		assertEquals(-1, state.eventsVersion(API));
	}
}
