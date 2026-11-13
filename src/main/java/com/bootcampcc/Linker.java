package com.bootcampcc;

import java.time.Instant;

/**
 * Links the plugin with a code from /link. Nothing is sent before the player enters a code.
 *
 * <p>States: IDLE (nothing to do) → IN_FLIGHT (POST /api/link sent) → back to IDLE on success (the code is
 * cleared by the plugin), or FAILED for that code. A failed code is never sent again by itself:
 * <ul>
 *   <li>rejected by the server (400 invalid/used/expired, 422 bad name): never retried; the player needs a
 *       new code</li>
 *   <li>server unreachable or busy (network error, 429, 5xx): retried on the next login or when the API URL
 *       changes, never in a loop</li>
 * </ul>
 * A new code always gets a fresh try. Entering a code while already linked links again (a relink replaces
 * the old token on the server).
 */
final class Linker
{
	interface Api
	{
		/** Returns false (and never calls back) if the API URL is not set or not valid. */
		boolean link(String code, String rsn, ApiClient.ApiCallback<ApiClient.LinkResponse> callback);
	}

	interface Listener
	{
		/** Linking succeeded. Called on an OkHttp thread. */
		void onLinked(String token, String rsn);

		/** A message for the player's chatbox (without the "[Bootcamp CC]" prefix). */
		void onMessage(String text);
	}

	enum State
	{
		IDLE,
		IN_FLIGHT,
		FAILED
	}

	static final String MSG_LINKED = "Account linked.";
	static final String MSG_BAD_CODE = "Linking failed: the code is invalid, used or expired. Get a new one with /link in the Bootcamp CC Discord.";
	static final String MSG_BAD_NAME = "Linking failed: the server did not accept your RuneScape name.";
	static final String MSG_NO_API = "Linking failed: the API URL is not set or not valid (Bootcamp CC settings → Advanced).";
	static final String MSG_UNREACHABLE = "Linking failed: could not reach the Bootcamp CC server. It will try again when you next log in.";
	static final String MSG_BUSY = "Linking failed: the server is busy. It will try again when you next log in.";
	static final String MSG_UNEXPECTED = "Linking failed: unexpected answer from the server.";

	private final Api api;
	private final Listener listener;

	// Guarded by this.
	private State state = State.IDLE;
	private String failedCode;
	private boolean retryable;

	Linker(Api api, Listener listener)
	{
		this.api = api;
		this.listener = listener;
	}

	synchronized State state()
	{
		return state;
	}

	/**
	 * Link if there is a code to try. Call when something may have changed: on login (with the player's
	 * name), and when the link code or API URL changes.
	 *
	 * @param rawCode the Link code setting
	 * @param rsn     the logged-in player's name, or null when not logged in
	 */
	void tryLink(String rawCode, String rsn)
	{
		final String code = rawCode == null ? "" : rawCode.trim();
		synchronized (this)
		{
			if (code.isEmpty() || rsn == null || rsn.isEmpty() || state == State.IN_FLIGHT)
			{
				return;
			}
			if (state == State.FAILED && code.equals(failedCode))
			{
				return;
			}
			state = State.IN_FLIGHT;
		}

		boolean sent = api.link(code, rsn, new ApiClient.ApiCallback<ApiClient.LinkResponse>()
		{
			@Override
			public void onSuccess(ApiClient.LinkResponse body, Instant serverNow)
			{
				if (body.token == null || body.token.isEmpty())
				{
					fail(code, false, MSG_UNEXPECTED);
					return;
				}
				synchronized (Linker.this)
				{
					state = State.IDLE;
					failedCode = null;
				}
				listener.onLinked(body.token, rsn);
				listener.onMessage(MSG_LINKED);
			}

			@Override
			public void onError(int status)
			{
				if (status == 400 || status == 404 || status == 410)
				{
					fail(code, false, MSG_BAD_CODE);
				}
				else if (status == 422)
				{
					fail(code, false, MSG_BAD_NAME);
				}
				else if (status == ApiClient.NETWORK_ERROR)
				{
					fail(code, true, MSG_UNREACHABLE);
				}
				else if (status == 429 || status >= 500)
				{
					fail(code, true, MSG_BUSY);
				}
				else
				{
					fail(code, false, MSG_UNEXPECTED + " (HTTP " + status + ")");
				}
			}
		});
		if (!sent)
		{
			fail(code, true, MSG_NO_API);
		}
	}

	/** Login or API URL change: a code that failed because the server was unreachable may be tried again. */
	synchronized void allowRetry()
	{
		if (state == State.FAILED && retryable)
		{
			state = State.IDLE;
			failedCode = null;
		}
	}

	private void fail(String code, boolean canRetry, String message)
	{
		synchronized (this)
		{
			state = State.FAILED;
			failedCode = code;
			retryable = canRetry;
		}
		listener.onMessage(message);
	}
}
