package com.bootcampcc;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * Shows broadcasts above the chatbox, one at a time: title, wrapped text, a queue counter (1/3), a thin bar
 * for the time left and a close button (X).
 *
 * <p>No right-click menu. Only a left click on the X is consumed; every other click goes to the game.
 */
class BroadcastOverlay extends Overlay
{
	private static final int WIDTH = 360;
	private static final int PADDING = 6;
	private static final int CLOSE_SIZE = 9;
	private static final int MAX_LINES = 8;
	private static final Color BACKGROUND = ComponentConstants.STANDARD_BACKGROUND_COLOR;
	private static final Color BORDER = new Color(0, 0, 0, 160);
	private static final Color TEXT = Color.WHITE;
	private static final Color MUTED = new Color(0xB0, 0xB0, 0xB0);
	private static final Color CLOSE_HOVER = new Color(0xFF, 0x60, 0x60);

	private final BootcampCCConfig config;
	private final BroadcastQueue queue = new BroadcastQueue();

	/** Close button in overlay-local coordinates; null while nothing is shown. Written on the client thread. */
	private volatile Rectangle closeBounds;
	private volatile boolean hoveringClose;
	private boolean pressedOnClose;

	final MouseAdapter mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (SwingUtilities.isLeftMouseButton(e) && isOnClose(e))
			{
				pressedOnClose = true;
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (SwingUtilities.isLeftMouseButton(e) && pressedOnClose)
			{
				pressedOnClose = false;
				if (isOnClose(e))
				{
					queue.dismiss(System.currentTimeMillis());
				}
				// The press was consumed, so the release belongs to us too.
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			if (SwingUtilities.isLeftMouseButton(e) && isOnClose(e))
			{
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent e)
		{
			hoveringClose = isOnClose(e);
			return e;
		}
	};

	@Inject
	BroadcastOverlay(BootcampCCConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	/** Safe from any thread. */
	void add(long id, String text)
	{
		queue.add(id, text);
	}

	/** Drops retracted broadcasts from the queue, and closes the one showing if it is retracted. Any thread. */
	void retract(Collection<Long> ids)
	{
		queue.retract(ids, System.currentTimeMillis());
	}

	void clear()
	{
		queue.clear();
		closeBounds = null;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long durationMillis = Math.max(5, Math.min(300, config.broadcastOverlaySeconds())) * 1000L;
		BroadcastQueue.Shown shown = queue.current(System.currentTimeMillis(), durationMillis);
		if (shown == null)
		{
			closeBounds = null;
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Font titleFont = FontManager.getRunescapeBoldFont();
		Font textFont = FontManager.getRunescapeFont();
		FontMetrics titleFm = g.getFontMetrics(titleFont);
		FontMetrics textFm = g.getFontMetrics(textFont);

		int innerWidth = WIDTH - 2 * PADDING;
		List<String> lines = TextWrap.wrap(shown.getText(), textFm::stringWidth, innerWidth, MAX_LINES);
		int headerHeight = Math.max(titleFm.getHeight(), CLOSE_SIZE);
		int height = PADDING + headerHeight + 2 + lines.size() * textFm.getHeight() + PADDING + 2;

		// Background
		g.setColor(BACKGROUND);
		g.fillRect(0, 0, WIDTH, height);
		g.setColor(BORDER);
		g.drawRect(0, 0, WIDTH - 1, height - 1);

		// Header: [Bootcamp CC]            1/3  X
		int baseline = PADDING + titleFm.getAscent();
		g.setFont(titleFont);
		g.setColor(BootcampCCPlugin.BRAND_COLOR);
		g.drawString(BootcampCCPlugin.PREFIX, PADDING, baseline);

		Rectangle close = new Rectangle(WIDTH - PADDING - CLOSE_SIZE, PADDING + (headerHeight - CLOSE_SIZE) / 2, CLOSE_SIZE, CLOSE_SIZE);
		g.setColor(hoveringClose ? CLOSE_HOVER : MUTED);
		g.setStroke(new BasicStroke(2f));
		g.drawLine(close.x, close.y, close.x + close.width, close.y + close.height);
		g.drawLine(close.x, close.y + close.height, close.x + close.width, close.y);
		// Slightly larger hit area than the drawn X.
		closeBounds = new Rectangle(close.x - 4, close.y - 4, close.width + 8, close.height + 8);

		if (shown.getTotal() > 1)
		{
			String counter = shown.getIndex() + "/" + shown.getTotal();
			g.setFont(textFont);
			g.setColor(MUTED);
			g.drawString(counter, close.x - 8 - textFm.stringWidth(counter), baseline);
		}

		// Message
		g.setFont(textFont);
		g.setColor(TEXT);
		int y = PADDING + headerHeight + 2 + textFm.getAscent();
		for (String line : lines)
		{
			g.drawString(line, PADDING, y);
			y += textFm.getHeight();
		}

		// Time left
		int barWidth = (int) ((WIDTH - 2) * shown.getRemainingMillis() / durationMillis);
		g.setColor(BootcampCCPlugin.BRAND_COLOR);
		g.fillRect(1, height - 3, barWidth, 2);

		return new Dimension(WIDTH, height);
	}

	private boolean isOnClose(MouseEvent e)
	{
		Rectangle local = closeBounds;
		Rectangle bounds = getBounds();
		if (local == null || bounds == null)
		{
			return false;
		}
		return local.contains(e.getX() - bounds.x, e.getY() - bounds.y);
	}
}
