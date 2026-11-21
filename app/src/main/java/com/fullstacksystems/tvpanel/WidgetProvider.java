package com.fullstacksystems.tvpanel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The chips for the launcher's widget row: content://com.fullstacksystems.tvpanel.widgets/widgets.
 *
 * There is no background service. The launcher queries while Home is on screen; a query answers at once from the
 * last fetch, and when that is older than {@link #FRESH_MS} it fetches from Home Assistant in the background and
 * calls notifyChange, so the launcher reads again. Off screen, nothing is fetched.
 *
 * Only the launchers with the widget row (and the ADB shell, for checks) get rows: the garage door's state is nobody
 * else's business. TV Panel Launcher is the fork with the row (github.com/denov/tv-panel-launcher); CleanYourTV's own
 * launcher has the same contract.
 */
public class WidgetProvider extends ContentProvider {
    static final String[] COLUMNS = {"key", "label", "value", "icon", "open"};
    private static final long FRESH_MS = 12_000;
    /** Rows older than this are dropped when Home Assistant stops answering, rather than shown as current. */
    private static final long STALE_MS = 10 * 60_000;
    private static final int ICON_PX = 72;
    private static final java.util.Set<String> READERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.fullstacksystems.tvhome", "com.gathin.tvhome", "com.android.shell"));

    private static volatile List<Ha.Widget> rows = Collections.emptyList();
    private static volatile long fetchedAt = -1;
    private static volatile String lastError = "";
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Map<String, byte[]> iconCache = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    static Uri uri(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".widgets/widgets");
    }

    /** The address or token changed: fetch on the next query. */
    static void invalidate() {
        fetchedAt = -1;
    }

    private static final ExecutorService actions = Executors.newSingleThreadExecutor();

    /**
     * OK on the garage door chip: cover.toggle in Home Assistant, then make the launcher read again so the chip
     * turns orange (opening) right away. It reaches open or closed on the launcher's next tick.
     */
    static void toggle(Context ctx, String entity) {
        actions.execute(() -> {
            Config c = Config.load(ctx);
            if (!c.complete()) return;
            String body;
            try {
                body = new org.json.JSONObject().put("entity_id", entity).toString();
            } catch (org.json.JSONException e) {
                return;
            }
            HaClient.Reply r = new HaClient(c.url, c.token).post("/api/services/cover/toggle", body);
            if (!r.ok()) {
                lastError = "Could not toggle " + entity + " (" + (r.code == 0 ? "no answer" : r.code) + ").";
                return;
            }
            invalidate();
            ctx.getContentResolver().notifyChange(uri(ctx), null);
        });
    }

    /**
     * OK on a chip the integration acts on (Ha.PRESS). The integration's message ("Garage Door: opening") shows as a
     * toast, and the chips are read again so a toggled chip changes colour at once.
     */
    static void press(Context ctx, String key) {
        post(ctx, Ha.pressBody(key), true);
    }

    /** OK on a chip that opened a page ("page") or does nothing ("none"): only the integration's event. */
    static void event(Context ctx, String key, String action) {
        post(ctx, Ha.pressBody(key, action), false);
    }

    private static void post(Context ctx, String body, boolean answer) {
        actions.execute(() -> {
            Config c = Config.load(ctx);
            if (!c.complete()) return;
            HaClient.Reply r = new HaClient(c.url, c.token).post("/api/tv_panel/press", body);
            if (!answer) return;
            String say = r.ok() ? Ha.pressMessage(r.body)
                    : r.code == 0 ? "Couldn't reach Home Assistant." : "Home Assistant answered " + r.code + ".";
            if (!say.isEmpty()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(ctx, say, android.widget.Toast.LENGTH_SHORT).show());
            }
            invalidate();
            ctx.getContentResolver().notifyChange(uri(ctx), null);
        });
    }

    static boolean mayRead(String caller) {
        return caller != null && READERS.contains(caller);
    }

    static List<Ha.Widget> current() {
        return rows;
    }

    static String lastError() {
        return lastError;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        if (!mayRead(getCallingPackage())) return out;
        // In case it was stopped and nothing has started it since. Android 12 may refuse while the launcher asks
        // from the background; the next query tries again.
        MessageService.start(getContext());
        long now = SystemClock.elapsedRealtime();
        if (fetchedAt < 0 || now - fetchedAt > FRESH_MS) refresh(uri);
        List<Ha.Widget> list = rows;
        if (fetchedAt >= 0 && now - fetchedAt > STALE_MS) list = Collections.emptyList();
        for (Ha.Widget w : list) {
            out.addRow(new Object[] {w.key, w.label, w.value, icon(w), w.open ? 1 : 0});
        }
        return out;
    }

    private byte[] icon(Ha.Widget w) {
        String k = w.icon + "#" + Integer.toHexString(w.colour);
        synchronized (iconCache) {
            byte[] b = iconCache.get(k);
            if (b == null) {
                b = Icons.png(getContext(), w.icon, w.colour, ICON_PX);
                iconCache.put(k, b);
            }
            return b;
        }
    }

    private void refresh(Uri uri) {
        if (!busy.compareAndSet(false, true)) return;
        Context ctx = getContext();
        io.execute(() -> {
            try {
                List<Ha.Widget> got = fetch(ctx);
                if (got != null) {
                    boolean changed = !same(rows, got);
                    rows = got;
                    fetchedAt = SystemClock.elapsedRealtime();
                    if (changed) ctx.getContentResolver().notifyChange(uri, null);
                }
            } finally {
                busy.set(false);
            }
        });
    }

    /** Null when Home Assistant could not be read: the last rows stay until they are {@link #STALE_MS} old. */
    static List<Ha.Widget> fetch(Context ctx) {
        Config c = Config.load(ctx);
        if (!c.complete()) {
            lastError = "Not paired with Home Assistant yet.";
            return Collections.singletonList(Ha.setupChip(false));
        }
        Settings settings = SettingsSource.get(c);
        HaClient.Reply r = Ha.data(new HaClient(c.url, c.token), Ha.DATA_CHIPS, Ha.templateBody(settings));
        if (!r.ok()) {
            lastError = r.code == 0 ? "Home Assistant did not answer at " + c.url + "." : Ha.why(r.code);
            // Signed out: the token is no good any more (the integration was removed). The way back is pairing.
            return r.code == 401 ? Collections.singletonList(Ha.setupChip(true)) : null;
        }
        try {
            List<Ha.Widget> list = Ha.parseWidgets(r.body, settings);
            lastError = "";
            return list;
        } catch (Exception e) {
            lastError = "Could not read Home Assistant's answer: " + e.getMessage();
            return null;
        }
    }

    private static boolean same(List<Ha.Widget> a, List<Ha.Widget> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Ha.Widget x = a.get(i), y = b.get(i);
            if (!x.key.equals(y.key) || !x.label.equals(y.label) || !x.value.equals(y.value)
                    || !x.icon.equals(y.icon) || x.colour != y.colour) return false;
        }
        return true;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
