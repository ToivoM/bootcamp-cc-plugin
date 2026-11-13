package com.bootcampcc;

/** Exponential backoff: initial, 2x, 4x, ... capped at max. Not thread-safe; callers synchronize. */
final class Backoff
{
	private final long initialMillis;
	private final long maxMillis;
	private long nextMillis;

	Backoff(long initialMillis, long maxMillis)
	{
		this.initialMillis = initialMillis;
		this.maxMillis = maxMillis;
		this.nextMillis = initialMillis;
	}

	/** The delay to wait now; each call doubles the following one, up to the cap. */
	long next()
	{
		long delay = nextMillis;
		nextMillis = Math.min(maxMillis, nextMillis * 2);
		return delay;
	}

	void reset()
	{
		nextMillis = initialMillis;
	}
}
