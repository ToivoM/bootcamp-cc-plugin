package com.bootcampcc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel listing clan events: Live now, Upcoming, Past.
 *
 * <p>The panel shows {@link EventsModel} and rebuilds itself whenever the model changes, also while it is
 * open (the model is updated as soon as the server reports a change). Times and states (upcoming → live →
 * past) are recomputed locally every 30 seconds without calling the API. All methods run on the Swing
 * thread unless noted otherwise.
 */
class EventsPanel extends PluginPanel implements EventsModel.Listener
{
	private static final int LOCAL_REFRESH_MILLIS = 30_000;
	private static final int DESCRIPTION_LINES = 2;
	private static final int STRIPE_WIDTH = 4;
	/** Room for text inside a card: panel width minus panel padding, stripe and card padding. */
	private static final int TEXT_WIDTH = PANEL_WIDTH - 2 * 10 - STRIPE_WIDTH - 8 - 6 - 4;

	private static final Color LIVE = new Color(0x3F, 0xC1, 0x5A);
	private static final Color UPCOMING = new Color(0x4A, 0x90, 0xE2);
	private static final Color SOON = new Color(0x7F, 0xC8, 0xFF);
	private static final Color SOON_BADGE = new Color(0xF2, 0xC9, 0x4C);
	private static final Color PAST = new Color(0x7A, 0x7A, 0x7A);
	private static final Color TEXT = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color MUTED = new Color(0x9A, 0x9A, 0x9A);
	private static final Color PAST_TEXT = new Color(0x74, 0x74, 0x74);
	private static final Color PAST_DESCRIPTION = new Color(0x62, 0x62, 0x62);

	private final JPanel content = new JPanel();
	private final Timer localRefresh = new Timer(LOCAL_REFRESH_MILLIS, e -> render());
	private final Set<String> expanded = new HashSet<>();
	private final EventsModel model;
	private Runnable onOpen = () -> { };
	/** How many times the view has been rebuilt (for tests and debugging). */
	private int renders;

	EventsPanel(EventsModel model)
	{
		this.model = model;
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		add(content, BorderLayout.NORTH);
		render();
		model.addListener(this);
		localRefresh.start();
	}

	/** Called when the panel is opened, e.g. to fetch fresh data. */
	void setOnOpen(Runnable onOpen)
	{
		this.onOpen = onOpen;
	}

	/** The model changed. Called on any thread; the view is rebuilt on the Swing thread, open or not. */
	@Override
	public void onEventsChanged()
	{
		SwingUtilities.invokeLater(this::render);
	}

	@Override
	public void onActivate()
	{
		render();
		onOpen.run();
	}

	void shutdown()
	{
		model.removeListener(this);
		localRefresh.stop();
	}

	int renderCount()
	{
		return renders;
	}

	// ------------------------------------------------------------------

	private void render()
	{
		renders++;
		EventsModel.Status status = model.status();
		content.removeAll();
		if (status != EventsModel.Status.OK)
		{
			content.add(message(status.message));
		}
		else
		{
			Instant now = Instant.now();
			ZoneId zone = ZoneId.systemDefault();
			EventTiming.Sections sections = EventTiming.sections(model.events(), now);
			section("Live now", sections.getLive(), "No events live right now", now, zone);
			section("Upcoming", sections.getUpcoming(), "No upcoming events", now, zone);
			section("Past", sections.getPast(), "No past events", now, zone);
		}
		// Rebuild the whole panel, not only the list: the scroll pane around it must see the new size.
		content.revalidate();
		revalidate();
		repaint();
	}

	private void section(String title, List<ClanEvent> list, String emptyText, Instant now, ZoneId zone)
	{
		if (content.getComponentCount() > 0)
		{
			content.add(Box.createVerticalStrut(12));
		}
		JLabel header = new JLabel(title);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setForeground(Color.WHITE);
		header.setBorder(new EmptyBorder(0, 0, 4, 0));
		left(header);
		content.add(header);

		if (list.isEmpty())
		{
			JLabel empty = new JLabel(emptyText);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(MUTED);
			left(empty);
			content.add(empty);
			return;
		}
		for (ClanEvent e : list)
		{
			content.add(card(e, now, zone));
			content.add(Box.createVerticalStrut(6));
		}
	}

	private JPanel card(ClanEvent e, Instant now, ZoneId zone)
	{
		EventTiming.State state = EventTiming.state(e, now);
		boolean soon = EventTiming.isSoon(e, now);
		Color accent = state == EventTiming.State.LIVE ? LIVE
			: state == EventTiming.State.UPCOMING ? (soon ? SOON : UPCOMING)
			: PAST;
		Color textColor = state == EventTiming.State.PAST ? PAST_TEXT : TEXT;

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(state == EventTiming.State.PAST ? ColorScheme.DARKER_GRAY_COLOR.darker() : ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, STRIPE_WIDTH, 0, 0, accent),
			new EmptyBorder(6, 8, 6, 6)));
		left(card);

		// Title row: name + badge
		JPanel titleRow = new JPanel(new BorderLayout(4, 0));
		titleRow.setOpaque(false);
		left(titleRow);
		String badgeText = state == EventTiming.State.LIVE ? "LIVE" : soon ? "SOON" : null;
		JLabel badge = null;
		if (badgeText != null)
		{
			badge = new JLabel(badgeText);
			badge.setFont(FontManager.getRunescapeSmallFont());
			badge.setOpaque(true);
			badge.setBackground(state == EventTiming.State.LIVE ? LIVE : SOON_BADGE);
			badge.setForeground(state == EventTiming.State.LIVE ? Color.WHITE : Color.BLACK);
			badge.setBorder(new EmptyBorder(0, 3, 0, 3));
		}
		JLabel name = new JLabel();
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(accent);
		int nameWidth = TEXT_WIDTH - (badge != null ? badge.getPreferredSize().width + 4 : 0);
		name.setText(html(TextWrap.wrap(e.getName(), metrics(name)::stringWidth, nameWidth, 3)));
		titleRow.add(name, BorderLayout.CENTER);
		if (badge != null)
		{
			JPanel badgeHolder = new JPanel(new BorderLayout());
			badgeHolder.setOpaque(false);
			badgeHolder.add(badge, BorderLayout.NORTH);
			titleRow.add(badgeHolder, BorderLayout.EAST);
		}
		card.add(titleRow);

		card.add(line(EventTiming.timeRange(e, zone), textColor));
		card.add(line(EventTiming.relative(e, now), state == EventTiming.State.PAST ? PAST_TEXT : accent));
		if (e.getLocation() != null && !e.getLocation().isEmpty())
		{
			card.add(line(e.getLocation(), textColor));
		}

		String description = e.getDescription() == null ? "" : e.getDescription().trim();
		if (!description.isEmpty())
		{
			JLabel desc = new JLabel();
			desc.setFont(FontManager.getRunescapeSmallFont());
			desc.setForeground(state == EventTiming.State.PAST ? PAST_DESCRIPTION : MUTED);
			desc.setBorder(new EmptyBorder(4, 0, 0, 0));
			left(desc);
			FontMetrics fm = metrics(desc);
			List<String> all = TextWrap.wrap(description, fm::stringWidth, TEXT_WIDTH, 0);
			boolean expandable = all.size() > DESCRIPTION_LINES;
			boolean isExpanded = expanded.contains(e.getId());
			List<String> shown = expandable && !isExpanded
				? TextWrap.wrap(description, fm::stringWidth, TEXT_WIDTH, DESCRIPTION_LINES)
				: all;
			desc.setText(html(shown));
			card.add(desc);

			if (expandable)
			{
				card.setToolTipText(isExpanded ? "Click to collapse" : "Click to show the full description");
				card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				card.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent ev)
					{
						if (SwingUtilities.isLeftMouseButton(ev))
						{
							if (!expanded.remove(e.getId()))
							{
								expanded.add(e.getId());
							}
							render();
						}
					}
				});
			}
		}
		return card;
	}

	private JLabel line(String text, Color color)
	{
		JLabel label = new JLabel();
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setText(html(TextWrap.wrap(text, metrics(label)::stringWidth, TEXT_WIDTH, 2)));
		left(label);
		return label;
	}

	private JLabel message(String text)
	{
		JLabel label = new JLabel();
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(TEXT);
		label.setText(html(TextWrap.wrap(text, metrics(label)::stringWidth, PANEL_WIDTH - 20, 0)));
		left(label);
		return label;
	}

	private FontMetrics metrics(JLabel label)
	{
		Font font = label.getFont();
		return label.getFontMetrics(font);
	}

	private static void left(Component c)
	{
		if (c instanceof javax.swing.JComponent)
		{
			((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
	}

	/** Lines joined with <br>, with HTML special characters escaped. */
	static String html(List<String> lines)
	{
		StringBuilder sb = new StringBuilder("<html>");
		for (int i = 0; i < lines.size(); i++)
		{
			if (i > 0)
			{
				sb.append("<br>");
			}
			sb.append(lines.get(i).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
		}
		return sb.append("</html>").toString();
	}
}
