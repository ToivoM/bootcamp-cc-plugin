package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BackoffTest
{
	@Test
	public void doublesUpToTheCap()
	{
		Backoff b = new Backoff(5_000, 60_000);
		assertEquals(5_000, b.next());
		assertEquals(10_000, b.next());
		assertEquals(20_000, b.next());
		assertEquals(40_000, b.next());
		assertEquals(60_000, b.next());
		assertEquals(60_000, b.next());
	}

	@Test
	public void resetStartsOver()
	{
		Backoff b = new Backoff(5_000, 60_000);
		b.next();
		b.next();
		b.reset();
		assertEquals(5_000, b.next());
	}
}
