package com.bootcampcc;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import lombok.Value;

/**
 * Broadcasts waiting to be shown in the overlay, one at a time. Thread-safe: broadcasts and retractions
 * arrive on OkHttp threads, the overlay reads on the client thread and the close button is clicked on the
 * AWT thread.
 */
final class BroadcastQueue
{
	/** Keep at most this many waiting broadcasts; they are all in the chatbox anyway. */
	static final int MAX_PENDING = 10;

	@Value
	static class Entry
	{
		long id;
		String text;
	}

	@Value
	static class Shown
	{
		long id;
		String text;
		/** 1-based position of this broadcast in the current batch. */
		int index;
		/** Shown so far in this batch plus the ones still waiting. */
		int total;
		long remainingMillis;
	}

	private final Deque<Entry> pending = new ArrayDeque<>();
	private Entry current;
	private long shownAtMillis;
	private int shownInBatch;

	synchronized void add(long id, String text)
	{
		pending.addLast(new Entry(id, text));
		while (pending.size() > MAX_PENDING)
		{
			pending.removeFirst();
		}
	}

	/**
	 * The broadcast to show now, or null. An expired broadcast is replaced by the next one; a broadcast's
	 * display time starts when it is first returned here.
	 */
	synchronized Shown current(long nowMillis, long durationMillis)
	{
		if (current != null && nowMillis - shownAtMillis >= durationMillis)
		{
			advance(nowMillis);
		}
		if (current == null && !pending.isEmpty())
		{
			advance(nowMillis);
		}
		if (current == null)
		{
			return null;
		}
		long remaining = Math.max(0, durationMillis - (nowMillis - shownAtMillis));
		return new Shown(current.getId(), current.getText(), shownInBatch, shownInBatch + pending.size(), remaining);
	}

	/** Closes the current broadcast; the next one (if any) is shown with a fresh timer. */
	synchronized void dismiss(long nowMillis)
	{
		if (current != null)
		{
			advance(nowMillis);
		}
	}

	/** Removes retracted broadcasts: from the waiting list, and closes the shown one if it is retracted. */
	synchronized void retract(Collection<Long> ids, long nowMillis)
	{
		pending.removeIf(e -> ids.contains(e.getId()));
		if (current != null && ids.contains(current.getId()))
		{
			shownInBatch--;
			advance(nowMillis);
		}
	}

	synchronized int size()
	{
		return pending.size() + (current == null ? 0 : 1);
	}

	synchronized void clear()
	{
		pending.clear();
		current = null;
		shownInBatch = 0;
	}

	private void advance(long nowMillis)
	{
		current = pending.pollFirst();
		if (current == null)
		{
			shownInBatch = 0;
		}
		else
		{
			shownInBatch++;
			shownAtMillis = nowMillis;
		}
	}
}
