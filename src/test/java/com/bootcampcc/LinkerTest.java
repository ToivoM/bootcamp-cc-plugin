package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** The link state machine, with a fake API that answers when the test says so. */
public class LinkerTest
{
	private final List<String[]> requests = new ArrayList<>();
	private final List<ApiClient.ApiCallback<ApiClient.LinkResponse>> pending = new ArrayList<>();
	private final List<String> messages = new ArrayList<>();
	private final List<String> linkedTokens = new ArrayList<>();
	private boolean configured;
	private Linker linker;

	@Before
	public void setUp()
	{
		configured = true;
		linker = new Linker((code, rsn, callback) ->
		{
			if (!configured)
			{
				return false;
			}
			requests.add(new String[]{code, rsn});
			pending.add(callback);
			return true;
		}, new Linker.Listener()
		{
			@Override
			public void onLinked(String token, String rsn)
			{
				linkedTokens.add(token);
			}

			@Override
			public void onMessage(String text)
			{
				messages.add(text);
			}
		});
	}

	private void succeed(String token)
	{
		ApiClient.LinkResponse body = new ApiClient.LinkResponse();
		body.token = token;
		pending.remove(0).onSuccess(body, Instant.now());
	}

	private void fail(int status)
	{
		pending.remove(0).onError(status);
	}

	@Test
	public void nothingIsSentWithoutCodeOrName()
	{
		linker.tryLink("", "Zezima");
		linker.tryLink("   ", "Zezima");
		linker.tryLink(null, "Zezima");
		linker.tryLink("ABCDEFGH", null);
		assertTrue(requests.isEmpty());
		assertEquals(Linker.State.IDLE, linker.state());
	}

	@Test
	public void successfulLink()
	{
		linker.tryLink(" ABCDEFGH ", "Zezima");
		assertEquals(1, requests.size());
		assertEquals("ABCDEFGH", requests.get(0)[0]);
		assertEquals("Zezima", requests.get(0)[1]);
		assertEquals(Linker.State.IN_FLIGHT, linker.state());

		linker.tryLink("ABCDEFGH", "Zezima"); // a second trigger while in flight sends nothing
		assertEquals(1, requests.size());

		succeed("tok");
		assertEquals(Linker.State.IDLE, linker.state());
		assertEquals(List.of("tok"), linkedTokens);
		assertEquals(List.of(Linker.MSG_LINKED), messages);
	}

	@Test
	public void rejectedCodeIsNeverRetried()
	{
		linker.tryLink("ABCDEFGH", "Zezima");
		fail(400);
		assertEquals(Linker.State.FAILED, linker.state());
		assertEquals(List.of(Linker.MSG_BAD_CODE), messages);

		linker.tryLink("ABCDEFGH", "Zezima");
		linker.allowRetry(); // login: still not retried, the server said no
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(1, requests.size());

		linker.tryLink("NEWCODE1", "Zezima"); // a new code gets a fresh try
		assertEquals(2, requests.size());
	}

	@Test
	public void badNameIsNotRetried()
	{
		linker.tryLink("ABCDEFGH", "Zezima");
		fail(422);
		assertEquals(List.of(Linker.MSG_BAD_NAME), messages);
		linker.allowRetry();
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(1, requests.size());
	}

	@Test
	public void unreachableServerIsRetriedOnlyAfterLoginOrApiChange()
	{
		linker.tryLink("ABCDEFGH", "Zezima");
		fail(ApiClient.NETWORK_ERROR);
		assertEquals(List.of(Linker.MSG_UNREACHABLE), messages);
		linker.tryLink("ABCDEFGH", "Zezima"); // no loop
		assertEquals(1, requests.size());

		linker.allowRetry();
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(2, requests.size());
		succeed("tok");
		assertEquals(List.of("tok"), linkedTokens);
	}

	@Test
	public void busyServer()
	{
		linker.tryLink("ABCDEFGH", "Zezima");
		fail(503);
		assertEquals(List.of(Linker.MSG_BUSY), messages);
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(1, requests.size());
	}

	@Test
	public void noApiUrl()
	{
		configured = false;
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(List.of(Linker.MSG_NO_API), messages);
		assertEquals(Linker.State.FAILED, linker.state());
		linker.tryLink("ABCDEFGH", "Zezima"); // the message is not repeated every tick
		assertEquals(1, messages.size());

		configured = true;
		linker.allowRetry(); // the API URL was set
		linker.tryLink("ABCDEFGH", "Zezima");
		assertEquals(1, requests.size());
	}

	@Test
	public void responseWithoutTokenFails()
	{
		linker.tryLink("ABCDEFGH", "Zezima");
		succeed("");
		assertTrue(linkedTokens.isEmpty());
		assertEquals(List.of(Linker.MSG_UNEXPECTED), messages);
	}
}
