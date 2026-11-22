package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An air quality screen: a tile per sensor (up to 12), each with its room and name, its value, a colour band where
 * Home Assistant says what it measures in a unit {@link AirQuality} covers, and its last 24 hours. Live values every
 * 30 s and the lines every 5 minutes, only while open. Back returns Home.
 */
public class AirQualityActivity extends Activity {
    private static final long LIVE_MS = 30_000;
    private static final long LINES_MS = 5 * 60_000;
    private static final long DAY_MS = 86_400_000L;
    /** What fits 1080p; the settings page stops at this many too. */
    static final int MAX_TILES = 12;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = this::loadLive;
    private final Runnable linesTick = this::loadLines;
    private float dp;

    private String screenId;
    private FrameLayout frame;
    private View screenView;
    private TextView message, updated;
    /** The screen's layout is built for one set of sensors; another set rebuilds it. */
    private String shape = "";
    private final List<TileViews> tiles = new ArrayList<>();

    private volatile ZoneId zone;
    private AirQuality.Data data;

    private static final class TileViews {
        TextView title, value, band;
        ThermostatViews.SparkView spark;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.SCREENS.add(this);
        screenId = getIntent().getStringExtra(OpenActivity.EXTRA_SCREEN);
        if (screenId == null) {
            finish();
            return;
        }
        dp = getResources().getDisplayMetrics().density;
        frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF10222A, 0xFF081216}));
        message = text("Loading…", 22, 0xCCFFFFFF, false);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        message.setPadding(px(80), 0, px(80), 0);
        frame.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenId == null) return;
        loadLive();
        // onPause stopped the lines' tick, and showLive only starts it for a first or rebuilt screen.
        if (data != null) loadLines();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveTick);
        main.removeCallbacks(linesTick);
    }

    @Override
    protected void onDestroy() {
        OpenActivity.SCREENS.remove(this);
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    private void loadLive() {
        main.removeCallbacks(liveTick);
        main.postDelayed(liveTick, LIVE_MS);
        Config c = Config.load(this);
        if (!c.complete()) {
            showMessage(Ha.NOT_PAIRED);
            return;
        }
        io.execute(() -> {
            HaClient ha = new HaClient(c.url, c.token);
            if (zone == null) zone = Series.zone(ha);
            HaClient.Reply r = ha.get("/api/tv_panel/data/" + Ha.AIR_QUALITY + "?screen=" + Uri.encode(screenId));
            if (!r.ok()) {
                String why = r.code == 404 ? "This TV has no air quality screen called " + screenId
                        + ". Add one on the TV Panel page in Home Assistant." : Ha.why(r.code);
                post(() -> {
                    if (data == null || r.code == 404) showMessage(why);
                });
                return;
            }
            try {
                AirQuality.Data d = AirQuality.parse(r.body);
                post(() -> showLive(d));
            } catch (Exception e) {
                post(() -> {
                    if (data == null) showMessage("Could not read Home Assistant's answer: " + e.getMessage());
                });
            }
        });
    }

    /** Each sensor's last 24 hours: 5-minute statistics, or its history when it has none (no state_class). */
    private void loadLines() {
        main.removeCallbacks(linesTick);
        main.postDelayed(linesTick, LINES_MS);
        AirQuality.Data d = data;
        Config c = Config.load(this);
        if (d == null || !c.complete()) return;
        io.execute(() -> {
            long now = System.currentTimeMillis(), from = now - DAY_MS;
            List<String> ids = new ArrayList<>();
            for (AirQuality.Tile t : shown(d)) ids.add(t.id);
            try (HaSocket ws = HaSocket.open(c.url, c.token)) {
                Map<String, List<Solar.Stat>> s = Series.fetch(ws, ids, from);
                post(() -> showLines(s, from, now));
            } catch (Exception e) {
                // Keep what's on screen; the values still update.
            }
        });
    }

    private static List<AirQuality.Tile> shown(AirQuality.Data d) {
        return d.tiles.size() > MAX_TILES ? d.tiles.subList(0, MAX_TILES) : d.tiles;
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    // ---- showing -----------------------------------------------------------------------------------------------

    private void showMessage(String s) {
        message.setText(s);
        message.setVisibility(View.VISIBLE);
        if (screenView != null) screenView.setVisibility(View.INVISIBLE);
    }

    private void showLive(AirQuality.Data d) {
        boolean first = data == null;
        data = d;
        List<AirQuality.Tile> shown = shown(d);
        if (shown.isEmpty()) {
            showMessage("This screen has no sensors. Add some on the TV Panel page in Home Assistant.");
            return;
        }
        StringBuilder sh = new StringBuilder(d.title).append('|');
        for (AirQuality.Tile t : shown) sh.append(t.id).append(',');
        boolean rebuilt = !sh.toString().equals(shape);
        if (rebuilt) {
            shape = sh.toString();
            if (screenView != null) frame.removeView(screenView);
            screenView = build(d, shown.size());
            frame.addView(screenView, 0);
        }
        message.setVisibility(View.GONE);
        screenView.setVisibility(View.VISIBLE);
        updated.setText("Updated " + ThermostatViews.clock(zone == null ? ZoneId.systemDefault() : zone));
        for (int i = 0; i < shown.size(); i++) fill(shown.get(i), tiles.get(i));
        if (first || rebuilt) loadLines();
    }

    private void fill(AirQuality.Tile t, TileViews v) {
        v.title.setText(t.title());
        SpannableStringBuilder s = new SpannableStringBuilder(t.value());
        if (t.ok() && !t.unit.isEmpty()) {
            int at = s.length();
            s.append(" ").append(t.unit);
            s.setSpan(new RelativeSizeSpan(0.4f), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(SolarViews.SUB), at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.value.setText(s);
        AirQuality.Band b = t.band();
        SpannableStringBuilder band = new SpannableStringBuilder();
        if (b != null) {
            band.append("● ").append(b.name);
            band.setSpan(new ForegroundColorSpan(b.colour), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.band.setText(band);
    }

    private void showLines(Map<String, List<Solar.Stat>> lines, long from, long to) {
        AirQuality.Data d = data;
        if (d == null) return;
        List<AirQuality.Tile> shown = shown(d);
        for (int i = 0; i < shown.size() && i < tiles.size(); i++) {
            AirQuality.Tile t = shown.get(i);
            AirQuality.Band b = t.band();
            tiles.get(i).spark.set(lines.get(t.id), from, to, b == null ? 0xB3FFFFFF : b.colour);
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    /** Columns for n tiles: one row up to 3, then 2×2, 3×2, 4×2, 3×3, 4×3. */
    static int columns(int n) {
        return n <= 3 ? Math.max(n, 1) : n == 4 ? 2 : n <= 6 ? 3 : n <= 8 ? 4 : n == 9 ? 3 : 4;
    }

    private View build(AirQuality.Data d, int n) {
        tiles.clear();
        LinearLayout root = column();
        root.setPadding(px(36), px(16), px(36), px(14));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(d.title.isEmpty() ? "Air quality" : d.title, 22, 0xFFFFFFFF, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        updated = text("", 13, 0x99FFFFFF, false);
        head.addView(updated);
        root.addView(head);

        int cols = columns(n), rows = (n + cols - 1) / cols;
        int big = rows == 1 ? 64 : rows == 2 ? 48 : 38;
        LinearLayout grid = column();
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        gLp.topMargin = px(10);
        root.addView(grid, gLp);
        for (int r = 0; r < rows; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) lineLp.topMargin = px(10);
            grid.addView(line, lineLp);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (c > 0) cLp.leftMargin = px(10);
                // An empty place keeps the last row's tiles the size of the others.
                line.addView(i < n ? tile(big) : new View(this), cLp);
            }
        }
        return root;
    }

    private View tile(int bigSp) {
        TileViews v = new TileViews();
        tiles.add(v);
        LinearLayout t = column();
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(px(16));
        g.setStroke(px(1), 0x1FFFFFFF);
        t.setBackground(g);
        t.setPadding(px(16), px(10), px(16), px(10));
        v.title = text("", 14, 0xE6FFFFFF, true);
        t.addView(v.title);
        v.value = text("", bigSp, 0xFFFFFFFF, false);
        v.value.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        v.value.setIncludeFontPadding(false);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-2, -2);
        vLp.topMargin = px(4);
        t.addView(v.value, vLp);
        v.band = text("", 13, 0xE6FFFFFF, false);
        t.addView(v.band);
        v.spark = new ThermostatViews.SparkView(this);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sLp.topMargin = px(6);
        t.addView(v.spark, sLp);
        return t;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
