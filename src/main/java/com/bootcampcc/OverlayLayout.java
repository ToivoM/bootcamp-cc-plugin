package com.bootcampcc;

import lombok.Value;

/**
 * Where things go in the broadcast overlay. Pure arithmetic, so it can be tested without graphics.
 *
 * <pre>
 * +--------------------------------------------------+
 * | +------+  [Bootcamp CC]                  1/3  X  |
 * | | logo |  wrapped message text ...               |
 * | +------+  ...                                    |
 * |==========                                        |  time left
 * +--------------------------------------------------+
 * </pre>
 *
 * The logo is on the left, centred vertically; the title and message are to its right, also centred when the
 * logo is taller than the text. The close button stays in the top right corner.
 */
@Value
class OverlayLayout
{
	static final int PADDING = 6;
	static final int LOGO_GAP = 8;
	static final int HEADER_GAP = 2;
	static final int BAR_HEIGHT = 2;

	int width;
	int height;
	int logoX;
	int logoY;
	int textX;
	/** Width available for the wrapped message (and the header). */
	int textWidth;
	/** Top of the header row (title, counter, close button row). */
	int textTop;

	/** The message width depends only on the overlay width and the logo, so it is known before wrapping. */
	static int textWidth(int width, int logoWidth)
	{
		return width - textX(logoWidth) - PADDING;
	}

	private static int textX(int logoWidth)
	{
		return logoWidth > 0 ? PADDING + logoWidth + LOGO_GAP : PADDING;
	}

	/**
	 * @param logoWidth    0 for no logo
	 * @param headerHeight height of the header row
	 * @param lines        number of wrapped message lines
	 * @param lineHeight   height of one message line
	 */
	static OverlayLayout of(int width, int logoWidth, int logoHeight, int headerHeight, int lines, int lineHeight)
	{
		int textBlock = headerHeight + HEADER_GAP + lines * lineHeight;
		int inner = Math.max(textBlock, logoWidth > 0 ? logoHeight : 0);
		int height = PADDING + inner + PADDING + BAR_HEIGHT;
		return new OverlayLayout(
			width,
			height,
			PADDING,
			PADDING + (inner - logoHeight) / 2,
			textX(logoWidth),
			textWidth(width, logoWidth),
			PADDING + (inner - textBlock) / 2);
	}
}
