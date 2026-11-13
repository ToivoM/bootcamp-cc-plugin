package com.bootcampcc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Word wrapping for a given pixel width, using any width measure (e.g. FontMetrics::stringWidth). */
final class TextWrap
{
	static final String ELLIPSIS = "…";

	private TextWrap()
	{
	}

	/**
	 * Wraps text to lines no wider than maxWidth. Words longer than a line are split.
	 * If maxLines > 0 and the text needs more lines, the last kept line ends with an ellipsis.
	 */
	static List<String> wrap(String text, ToIntFunction<String> width, int maxWidth, int maxLines)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.trim().split("\\s+"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (width.applyAsInt(candidate) <= maxWidth)
			{
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if (line.length() > 0)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			// Split words that don't fit on a line of their own.
			String rest = word;
			while (width.applyAsInt(rest) > maxWidth && rest.length() > 1)
			{
				int cut = rest.length() - 1;
				while (cut > 1 && width.applyAsInt(rest.substring(0, cut)) > maxWidth)
				{
					cut--;
				}
				lines.add(rest.substring(0, cut));
				rest = rest.substring(cut);
			}
			line.append(rest);
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}

		if (maxLines > 0 && lines.size() > maxLines)
		{
			List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
			String last = kept.get(maxLines - 1);
			while (!last.isEmpty() && width.applyAsInt(last + ELLIPSIS) > maxWidth)
			{
				last = last.substring(0, last.length() - 1);
			}
			kept.set(maxLines - 1, last.trim() + ELLIPSIS);
			return kept;
		}
		return lines;
	}
}
