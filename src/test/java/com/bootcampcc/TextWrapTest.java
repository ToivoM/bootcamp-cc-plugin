package com.bootcampcc;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TextWrapTest
{
	// 1 px per character keeps the expectations readable.
	private static List<String> wrap(String text, int width, int maxLines)
	{
		return TextWrap.wrap(text, String::length, width, maxLines);
	}

	@Test
	public void shortTextIsOneLine()
	{
		assertEquals(Collections.singletonList("hello world"), wrap("hello world", 20, 0));
	}

	@Test
	public void wrapsAtWords()
	{
		assertEquals(Arrays.asList("clan event", "tonight at", "8 pm"), wrap("clan event tonight at 8 pm", 10, 0));
	}

	@Test
	public void collapsesWhitespace()
	{
		assertEquals(Collections.singletonList("a b"), wrap("  a \n  b  ", 10, 0));
	}

	@Test
	public void splitsLongWords()
	{
		assertEquals(Arrays.asList("abcde", "fghij", "k"), wrap("abcdefghijk", 5, 0));
	}

	@Test
	public void truncatesWithEllipsis()
	{
		List<String> lines = wrap("one two three four five six", 9, 2);
		assertEquals(2, lines.size());
		assertEquals("one two", lines.get(0));
		assertEquals("three…", lines.get(1));
	}

	@Test
	public void emptyText()
	{
		assertEquals(Collections.emptyList(), wrap("   ", 10, 2));
	}
}
