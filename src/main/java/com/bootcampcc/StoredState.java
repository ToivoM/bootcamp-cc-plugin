package com.bootcampcc;

/**
 * Hidden per-account state: the API token, the newest broadcast id seen and the last events_version.
 *
 * <p>Stored per RuneScape profile (RuneLite's RS profile configuration), and tagged with the API URL it
 * belongs to: a value saved for another API URL is never used, because tokens and ids from one server mean
 * nothing on another. Everything is cleared on a successful link and when the API URL changes.
 */
final class StoredState
{
	/** Key-value storage for the current RuneScape profile (ConfigManager in the plugin, a map in tests). */
	interface Store
	{
		/** Null if missing, or if there is no profile (not logged in). */
		String get(String key);

		void set(String key, String value);

		void unset(String key);
	}

	static final String KEY_TOKEN = "token";
	static final String KEY_LAST_BROADCAST_ID = "lastBroadcastId";
	static final String KEY_EVENTS_VERSION = "eventsVersion";
	/** The normalized API URL the values above belong to. */
	static final String KEY_API_URL = "stateApiUrl";

	private final Store store;

	StoredState(Store store)
	{
		this.store = store;
	}

	/** Trimmed, without trailing slashes, so "https://x/" and "https://x" are the same server. */
	static String normalizeApi(String apiUrl)
	{
		String s = apiUrl == null ? "" : apiUrl.trim();
		while (s.endsWith("/"))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	private boolean belongsTo(String apiUrl)
	{
		String stored = store.get(KEY_API_URL);
		return stored != null && !stored.isEmpty() && stored.equals(normalizeApi(apiUrl));
	}

	/** The token for this API URL, or null. */
	synchronized String token(String apiUrl)
	{
		if (!belongsTo(apiUrl))
		{
			return null;
		}
		String token = store.get(KEY_TOKEN);
		return token == null || token.isEmpty() ? null : token;
	}

	synchronized boolean isLinked(String apiUrl)
	{
		return token(apiUrl) != null;
	}

	/** The newest broadcast id already handled, or 0. */
	synchronized long lastBroadcastId(String apiUrl)
	{
		return belongsTo(apiUrl) ? parse(store.get(KEY_LAST_BROADCAST_ID), 0) : 0;
	}

	/** The last events_version the panel was loaded for, or -1 if unknown. */
	synchronized long eventsVersion(String apiUrl)
	{
		return belongsTo(apiUrl) ? parse(store.get(KEY_EVENTS_VERSION), -1) : -1;
	}

	/** A successful link: the new token, and everything else starts over. */
	synchronized void linked(String apiUrl, String token)
	{
		clear();
		store.set(KEY_API_URL, normalizeApi(apiUrl));
		store.set(KEY_TOKEN, token);
	}

	synchronized void setLastBroadcastId(String apiUrl, long id)
	{
		if (belongsTo(apiUrl))
		{
			store.set(KEY_LAST_BROADCAST_ID, Long.toString(id));
		}
	}

	synchronized void setEventsVersion(String apiUrl, long version)
	{
		if (belongsTo(apiUrl))
		{
			store.set(KEY_EVENTS_VERSION, Long.toString(version));
		}
	}

	/** Forget everything (the API URL changed, or the server rejected the token). */
	synchronized void clear()
	{
		store.unset(KEY_TOKEN);
		store.unset(KEY_LAST_BROADCAST_ID);
		store.unset(KEY_EVENTS_VERSION);
		store.unset(KEY_API_URL);
	}

	private static long parse(String value, long fallback)
	{
		if (value == null)
		{
			return fallback;
		}
		try
		{
			long parsed = Long.parseLong(value.trim());
			return parsed < 0 ? fallback : parsed;
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}
}
