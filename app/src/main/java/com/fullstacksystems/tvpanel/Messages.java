package com.fullstacksystems.tvpanel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Messages from Home Assistant's notify entity for this TV. The app keeps a websocket open, signed in as the TV's own
 * user and subscribed to tv_panel/messages (the integration's messages.py), and shows each message as a toast. Nothing
 * opens on the TV, so whatever is playing keeps playing; only when the TV's "Pause for messages" switch is on does a
 * message say pause. Pure Java (no Android types), so the JVM tests cover it; MessageService runs it.
 */
final class Messages {
    /** The longest wait between tries while Home Assistant is away. */
    static final long MAX_RETRY_MS = 60_000;
    /** An integration without messages (before 0.6.0), or a token it refuses: in the end, ask only this often. */
    static final long RARELY_MS = 10 * 60_000;
    /** Home Assistant pings every 55 s. Nothing at all for this long means the connection is gone. */
    static final int SILENT_MS = 130_000;
    static final String SUBSCRIBE = "tv_panel/messages";

    private Messages() {
    }

    /** What to show: "Title: message", and whether to pause playback first. */
    static final class Message {
        final String text;
        final boolean pause;

        Message(String text, boolean pause) {
            this.text = text;
            this.pause = pause;
        }
    }

    interface Sink {
        void show(Message m);
    }

    /** Why a connection ended. */
    enum Why { DROPPED, UNSUPPORTED, REFUSED }

    static final class Result {
        final Why why;
        /** It got as far as listening: the next try can be soon again. */
        final boolean subscribed;

        Result(Why why, boolean subscribed) {
            this.why = why;
            this.subscribed = subscribed;
        }
    }

    /** One of the subscription's events as a message, or null for anything else (a result, an empty message). */
    static Message parse(String json) throws JSONException {
        return parse(new JSONObject(json));
    }

    static Message parse(JSONObject o) {
        JSONObject e = "event".equals(o.optString("type")) ? o.optJSONObject("event") : null;
        if (e == null) return null;
        String text = Ha.notifyText(Ha.str(e, "title"), Ha.str(e, "message"));
        return text.isEmpty() ? null : new Message(text, e.optBoolean("pause"));
    }

    /**
     * How long to wait after {@code r}, with {@code failures} tries in a row before it that didn't get to listen: 2 s,
     * doubling. At most {@link #MAX_RETRY_MS} while Home Assistant is away. An unknown command or a refused token can be
     * Home Assistant still starting (the integration loads after the websocket), so those start soon too, but end up
     * at {@link #RARELY_MS}.
     */
    static long waitMs(Result r, int failures) {
        long cap = r.why == Why.DROPPED ? MAX_RETRY_MS : RARELY_MS;
        return Math.min(cap, 2_000L << Math.min(failures, 20));
    }

    /**
     * One connection: sign in, subscribe, and show each message until the connection ends. Blocking. {@code current}
     * (optional) holds the open socket while it runs, so it can be closed from outside to reconnect at once.
     */
    static Result listen(String url, String token, Sink sink, AtomicReference<Closeable> current) {
        HaSocket ws = null;
        boolean subscribed = false;
        try {
            ws = HaSocket.open(url, token, SILENT_MS);
            if (current != null) current.set(ws);
            int id = ws.subscribe(SUBSCRIBE);
            subscribed = true;
            while (true) {
                JSONObject m = ws.next();
                if (m.optInt("id", -1) != id) continue;
                Message msg = parse(m);
                if (msg != null) sink.show(msg);
            }
        } catch (HaSocket.Refused e) {
            return new Result(Why.REFUSED, subscribed);
        } catch (HaSocket.CommandError e) {
            boolean unsupported = "unknown_command".equals(e.code) || "not_a_tv".equals(e.code);
            return new Result(unsupported ? Why.UNSUPPORTED : Why.DROPPED, subscribed);
        } catch (IOException | RuntimeException e) {
            return new Result(Why.DROPPED, subscribed);
        } finally {
            if (current != null) current.set(null);
            if (ws != null) ws.close();
        }
    }
}
