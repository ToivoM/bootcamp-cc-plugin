package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

/** The overlay's layout with the logo, and the icon files the plugin ships. */
public class OverlayLayoutTest
{
	private static final int WIDTH = 360;
	private static final int LOGO_W = 48;
	private static final int LOGO_H = 40;
	private static final int HEADER = 14;
	private static final int LINE = 14;

	@Test
	public void shortMessageIsAsTallAsTheLogoAndCentred()
	{
		OverlayLayout l = OverlayLayout.of(WIDTH, LOGO_W, LOGO_H, HEADER, 1, LINE);
		assertEquals("padding + logo + padding + bar", 6 + 40 + 6 + 2, l.getHeight());
		assertEquals(6, l.getLogoX());
		assertEquals(6, l.getLogoY());
		int textBlock = HEADER + 2 + LINE;
		assertEquals("the text is centred next to the logo", 6 + (40 - textBlock) / 2, l.getTextTop());
	}

	@Test
	public void longMessageGrowsAndTheLogoStaysCentred()
	{
		OverlayLayout l = OverlayLayout.of(WIDTH, LOGO_W, LOGO_H, HEADER, 6, LINE);
		int textBlock = HEADER + 2 + 6 * LINE;
		assertEquals(6 + textBlock + 6 + 2, l.getHeight());
		assertEquals(6, l.getTextTop());
		assertEquals(6 + (textBlock - LOGO_H) / 2, l.getLogoY());
	}

	@Test
	public void textIsRightOfTheLogo()
	{
		OverlayLayout l = OverlayLayout.of(WIDTH, LOGO_W, LOGO_H, HEADER, 2, LINE);
		assertEquals(6 + 48 + 8, l.getTextX());
		assertEquals(WIDTH - l.getTextX() - 6, l.getTextWidth());
		assertEquals("wrapping uses the same width", l.getTextWidth(), OverlayLayout.textWidth(WIDTH, LOGO_W));
		assertTrue(l.getTextX() > l.getLogoX() + LOGO_W);
	}

	@Test
	public void withoutLogo()
	{
		OverlayLayout l = OverlayLayout.of(WIDTH, 0, 0, HEADER, 1, LINE);
		assertEquals(6, l.getTextX());
		assertEquals(6 + HEADER + 2 + LINE + 6 + 2, l.getHeight());
	}

	// --- shipped images ------------------------------------------------------------------------------

	private static BufferedImage resource(String name) throws IOException
	{
		try (InputStream in = BootcampCCPlugin.class.getResourceAsStream(name))
		{
			assertNotNull(name + " is on the classpath", in);
			return ImageIO.read(in);
		}
	}

	@Test
	public void overlayLogoIsTheBootsAt2x() throws IOException
	{
		BufferedImage logo = resource("overlay_logo.png");
		assertEquals(48, logo.getWidth());
		assertEquals(40, logo.getHeight());
		assertTrue("transparent background", logo.getColorModel().hasAlpha());
		assertEquals("corner is transparent", 0, logo.getRGB(0, 0) >>> 24);
	}

	@Test
	public void navigationIconIs16x16() throws IOException
	{
		BufferedImage icon = resource("nav_icon.png");
		assertEquals(16, icon.getWidth());
		assertEquals(16, icon.getHeight());
	}

	@Test
	public void pluginHubIconFitsTheLimit() throws IOException
	{
		BufferedImage icon = ImageIO.read(new File("icon.png"));
		assertNotNull(icon);
		assertTrue(icon.getWidth() <= 48 && icon.getHeight() <= 72);
	}
}
