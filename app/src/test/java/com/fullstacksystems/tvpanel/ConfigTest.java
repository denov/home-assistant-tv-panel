package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A config.json pushed by install.sh: read once, then deleted. */
public class ConfigTest {
    @Test
    public void aFileThatDoesntReadYetMayStillBeBeingWritten() {
        // The status page can look while install.sh's `content write` is still writing: keep it for the import that
        // runs when the write is done.
        long now = 1_000_000;
        assertTrue(Config.mayBeWriting(now - 2_000, now));
        assertFalse(Config.mayBeWriting(now - Config.WRITE_GRACE_MS - 1, now));
    }

    @Test
    public void addressesAreTidied() {
        assertEquals("http://ha:8123", Config.normalizeUrl("ha:8123/"));
        assertEquals("https://ha.example", Config.normalizeUrl(" https://ha.example// "));
        assertEquals("", Config.normalizeUrl(null));
    }
}
