package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Where broadcasts go (overlay and chat), the backlog limit, ages, the overlay queue and retractions. */
public class BroadcastTest
{
	private static final Instant NOW = Instant.parse("2027-01-15T12:00:00Z");

	private static BroadcastDelivery.Item item(long id, String text, Duration age)
	{
		return new BroadcastDelivery.Item(id, text, age == null ? null : NOW.minus(age));
	}

	@Test
	public void freshGoesToOverlayAndChatOldToChatWithAge()
	{
		BroadcastDelivery.Plan plan = BroadcastDelivery.plan(List.of(
			item(2, "fresh", Duration.ofMinutes(1)),
			item(1, "old", Duration.ofHours(3))), NOW);
		assertEquals(List.of("(3 h ago) old", "fresh"), plan.getChatLines());
		assertEquals(1, plan.getOverlayItems().size());
		assertEquals(2, plan.getOverlayItems().get(0).getId());
	}

	@Test
	public void fiveMinutesIsTheLimit()
	{
		assertTrue(BroadcastDelivery.isFresh(item(1, "a", Duration.ofSeconds(299)), NOW));
		assertEquals(List.of("(5 min ago) a"), BroadcastDelivery.plan(List.of(item(1, "a", Duration.ofMinutes(5))), NOW).getChatLines());
	}

	@Test
	public void broadcastWithoutTimeIsChatOnly()
	{
		BroadcastDelivery.Plan plan = BroadcastDelivery.plan(List.of(item(1, "no time", null)), NOW);
		assertEquals(List.of("no time"), plan.getChatLines());
		assertTrue(plan.getOverlayItems().isEmpty());
	}

	@Test
	public void backlogIsLimitedToFiveNewest()
	{
		List<BroadcastDelivery.Item> items = new ArrayList<>();
		for (int i = 1; i <= 8; i++)
		{
			items.add(item(i, "m" + i, Duration.ofHours(10 - i)));
		}
		List<String> chat = BroadcastDelivery.plan(items, NOW).getChatLines();
		assertEquals("+ 3 older broadcasts", chat.get(0));
		assertEquals(6, chat.size());
		assertEquals("(6 h ago) m4", chat.get(1));
		assertEquals("(2 h ago) m8", chat.get(5));

		List<String> six = BroadcastDelivery.plan(items.subList(2, 8), NOW).getChatLines();
		assertEquals("+ 1 older broadcast", six.get(0));
	}

	@Test
	public void ageText()
	{
		assertEquals("1 min ago", BroadcastDelivery.ageText(Duration.ofSeconds(10)));
		assertEquals("59 min ago", BroadcastDelivery.ageText(Duration.ofMinutes(59)));
		assertEquals("1 h ago", BroadcastDelivery.ageText(Duration.ofMinutes(119)));
		assertEquals("23 h ago", BroadcastDelivery.ageText(Duration.ofHours(23)));
		assertEquals("1 day ago", BroadcastDelivery.ageText(Duration.ofHours(47)));
		assertEquals("3 days ago", BroadcastDelivery.ageText(Duration.ofDays(3)));
		assertEquals("1 min ago", BroadcastDelivery.ageText(Duration.ofSeconds(-30)));
	}

	@Test
	public void nextSinceIsTheHighestReturnedId()
	{
		ApiClient.Broadcast a = new ApiClient.Broadcast();
		a.id = 3;
		ApiClient.Broadcast b = new ApiClient.Broadcast();
		b.id = 9;
		assertEquals(9, BootcampCCPlugin.nextSince(5, List.of(a, b)));
		assertEquals(5, BootcampCCPlugin.nextSince(5, List.of()));
		assertEquals("a replaced database may send lower ids", 3, BootcampCCPlugin.nextSince(50, List.of(a)));
	}

	// --- overlay queue --------------------------------------------------------------------------------

	@Test
	public void queueShowsOneAtATimeWithCounterAndTimer()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add(1, "a");
		q.add(2, "b");
		q.add(3, "c");
		BroadcastQueue.Shown shown = q.current(0, 1_000);
		assertEquals("a", shown.getText());
		assertEquals(1, shown.getIndex());
		assertEquals(3, shown.getTotal());
		assertEquals(400, q.current(600, 1_000).getRemainingMillis());
		assertEquals("b", q.current(1_000, 1_000).getText());
		q.dismiss(1_100);
		assertEquals("c", q.current(1_100, 1_000).getText());
		assertEquals(3, q.current(1_100, 1_000).getIndex());
		assertNull(q.current(2_100, 1_000));
	}

	@Test
	public void retractRemovesWaitingBroadcasts()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add(1, "a");
		q.add(2, "b");
		q.add(3, "c");
		q.current(0, 1_000);
		q.retract(Set.of(2L, 99L), 10);
		BroadcastQueue.Shown shown = q.current(20, 1_000);
		assertEquals("a", shown.getText());
		assertEquals(2, shown.getTotal());
		assertEquals("c", q.current(1_000, 1_000).getText());
	}

	@Test
	public void retractClosesTheShownBroadcast()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add(1, "a");
		q.add(2, "b");
		q.current(0, 1_000);
		q.retract(Set.of(1L), 500);
		BroadcastQueue.Shown shown = q.current(500, 1_000);
		assertEquals("b", shown.getText());
		assertEquals("the retracted one no longer counts", 1, shown.getIndex());
		assertEquals(1, shown.getTotal());
		assertEquals("a fresh timer", 1_000, shown.getRemainingMillis());

		q.retract(Set.of(2L), 600);
		assertNull(q.current(600, 1_000));
		assertEquals(0, q.size());
	}

	@Test
	public void queueKeepsAtMostTenWaiting()
	{
		BroadcastQueue q = new BroadcastQueue();
		for (int i = 1; i <= 15; i++)
		{
			q.add(i, "m" + i);
		}
		assertEquals(BroadcastQueue.MAX_PENDING, q.size());
		assertEquals("m6", q.current(0, 1_000).getText());
	}
}
