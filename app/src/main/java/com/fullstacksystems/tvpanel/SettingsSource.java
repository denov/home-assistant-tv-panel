package com.fullstacksystems.tvpanel;

import android.os.SystemClock;

/**
 * Reads {@link Settings} from the integration, at most every {@link #FRESH_MS}. Blocking: call it off the main thread.
 * Without the integration (404) or while Home Assistant is away, the last answer stands, or {@link Settings#NONE}.
 */
final class SettingsSource {
    static final long FRESH_MS = 10_000;
    private static volatile Settings last = Settings.NONE;
    private static volatile long at = -1;

    private SettingsSource() {
    }

    static Settings get(Config c) {
        long now = SystemClock.elapsedRealtime();
        if (at >= 0 && now - at < FRESH_MS) return last;
        HaClient.Reply r = new HaClient(c.url, c.token).get("/api/tv_panel/config");
        if (r.ok()) {
            try {
                last = Settings.parse(r.body);
            } catch (Exception e) {
                // Keep the last answer.
            }
        } else if (r.code == 404) {
            last = Settings.NONE;
        }
        at = now;
        return last;
    }

    /** The address or token changed, or the person changed something: read again next time. */
    static void invalidate() {
        at = -1;
    }
}
