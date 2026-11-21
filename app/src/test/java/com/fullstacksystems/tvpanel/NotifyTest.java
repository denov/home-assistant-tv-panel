package com.fullstacksystems.tvpanel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A message from Home Assistant's notify entity (see Messages), shown as a toast. */
public class NotifyTest {

    @Test
    public void theTitleLeadsLikeTheOtherToasts() {
        assertEquals("Garage: Left open", Ha.notifyText("Garage", "Left open"));
        assertEquals("Left open", Ha.notifyText(null, "Left open"));
        assertEquals("Left open", Ha.notifyText("  ", " Left open "));
    }

    @Test
    public void aTitleAloneStillShows() {
        assertEquals("Garage", Ha.notifyText("Garage", ""));
        assertEquals("", Ha.notifyText(null, null));
    }

    @Test
    public void aLongMessageIsCut() {
        String text = Ha.notifyText(null, "x".repeat(500));
        assertEquals(Ha.NOTIFY_MAX, text.length());
        assertEquals('…', text.charAt(text.length() - 1));
    }

    @Test
    public void aLongMessageIsNeverCutInsideAnEmoji() {
        // The cut falls on an emoji's second half (a surrogate pair in Java): the whole emoji goes instead.
        String message = repeat("a", Ha.NOTIFY_MAX - 2) + "\uD83D\uDE97" + repeat("b", 50);
        String text = Ha.notifyText("", message);
        assertEquals(repeat("a", Ha.NOTIFY_MAX - 2) + "…", text);
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
