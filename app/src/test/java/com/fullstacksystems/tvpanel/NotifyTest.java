package com.fullstacksystems.tvpanel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** A message from Home Assistant's notify entity (tvpanel://notify?message=...&title=...), shown as a toast. */
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
}
