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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A thermostat screen: up to four thermostats side by side (any climate entity), each with where it is, where it's set,
 * its mode and what it's doing, and its last 24 hours; the screen's other readings along the bottom. Show only: the
 * remote changes nothing. Live values every 15 s and the graphs every 5 minutes, only while open. Back returns Home.
 */
public class ThermostatActivity extends Activity {
    private static final long LIVE_MS = 15_000;
    private static final long GRAPH_MS = 5 * 60_000;
    private static final long DAY_MS = 86_400_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = this::loadLive;
    private final Runnable graphTick = this::loadGraphs;
    private float dp;

    private String screenId;
    private FrameLayout frame;
    private View screenView;
    private TextView message, updated;
    private LinearLayout cards, readings;
    /** The screen's layout is built for one set of thermostats and readings; another set rebuilds it. */
    private String shape = "";
    private final List<ClimateViews> climateViews = new ArrayList<>();
    private final Map<String, ThermostatViews.SparkView> sparks = new HashMap<>();
    private final Map<String, TextView> readingValues = new HashMap<>();

    private volatile ZoneId zone;
    private Thermostat.Data data;

    /** One thermostat's card. */
    private static final class ClimateViews {
        TextView name, now, from, doing, target;
        LinearLayout details;
        ThermostatViews.GraphView graph;
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
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF14202E, 0xFF0A1018}));
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
        // onPause stopped the graphs' tick, and showLive only starts it for a first or rebuilt screen.
        if (data != null) loadGraphs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveTick);
        main.removeCallbacks(graphTick);
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
            HaClient.Reply r = ha.get("/api/tv_panel/data/" + Ha.THERMOSTAT + "?screen=" + Uri.encode(screenId));
            if (!r.ok()) {
                String why = r.code == 404 ? "This TV has no thermostat screen called " + screenId
                        + ". Add one on the TV Panel page in Home Assistant." : Ha.why(r.code);
                post(() -> {
                    if (data == null || r.code == 404) showMessage(why);
                });
                return;
            }
            try {
                Thermostat.Data d = Thermostat.parse(r.body);
                post(() -> showLive(d));
            } catch (Exception e) {
                post(() -> {
                    if (data == null) showMessage("Could not read Home Assistant's answer: " + e.getMessage());
                });
            }
        });
    }

    /** Each thermostat's history (with its attributes), and the other readings' 5-minute statistics. */
    private void loadGraphs() {
        main.removeCallbacks(graphTick);
        main.postDelayed(graphTick, GRAPH_MS);
        Thermostat.Data d = data;
        Config c = Config.load(this);
        if (d == null || !c.complete()) return;
        io.execute(() -> {
            long now = System.currentTimeMillis(), from = now - DAY_MS;
            List<String> climates = new ArrayList<>(), sensors = new ArrayList<>();
            for (Thermostat.Climate t : d.climates) climates.add(t.id);
            for (Thermostat.Reading r : d.sensors) sensors.add(r.id);
            try (HaSocket ws = HaSocket.open(c.url, c.token)) {
                JSONObject h = Series.history(ws, climates, from, true);
                Map<String, List<Solar.Stat>> s = Series.fetch(ws, sensors, from);
                post(() -> showGraphs(h, s, from, now));
            } catch (Exception e) {
                // Keep what's on screen; the readings still update.
            }
        });
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

    private void showLive(Thermostat.Data d) {
        boolean first = data == null;
        data = d;
        // The title is drawn once, in build(): a renamed screen rebuilds too.
        StringBuilder sh = new StringBuilder(d.title).append('|');
        for (Thermostat.Climate c : d.climates) sh.append(c.id).append(',');
        sh.append('|');
        for (Thermostat.Reading r : d.sensors) sh.append(r.id).append(',');
        boolean rebuilt = !sh.toString().equals(shape);
        if (rebuilt) {
            shape = sh.toString();
            if (screenView != null) frame.removeView(screenView);
            screenView = build(d);
            frame.addView(screenView, 0);
        }
        message.setVisibility(View.GONE);
        screenView.setVisibility(View.VISIBLE);
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        updated.setText("Updated " + ThermostatViews.clock(z));
        for (int i = 0; i < d.climates.size(); i++) fillClimate(d, d.climates.get(i), climateViews.get(i));
        for (Thermostat.Reading r : d.sensors) {
            TextView v = readingValues.get(r.id);
            if (v != null) v.setText(readingText(r));
        }
        if (first || rebuilt) loadGraphs();
    }

    private void fillClimate(Thermostat.Data d, Thermostat.Climate c, ClimateViews v) {
        v.name.setText(c.name);
        String[] now = Thermostat.current(d, c);
        v.now.setText(now[0].isEmpty() ? "—" : now[0]);
        v.from.setText(now[1].isEmpty() ? "" : "from " + now[1]);
        v.from.setVisibility(now[1].isEmpty() ? View.GONE : View.VISIBLE);

        SpannableStringBuilder s = new SpannableStringBuilder();
        String doing = Thermostat.doing(c), mode = Thermostat.mode(c);
        int at = s.length();
        s.append("● ");
        s.setSpan(new ForegroundColorSpan(Thermostat.colour(c)), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // "Cooling · Heat/Cool"; the mode alone when it doesn't say, or when that's all there is ("Off").
        if (!doing.isEmpty() && !doing.equals(mode)) {
            at = s.length();
            s.append(doing);
            s.setSpan(new ForegroundColorSpan(Thermostat.colour(c) == Thermostat.IDLE ? 0xE6FFFFFF : Thermostat.colour(c)),
                    at, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append("  ·  ");
        }
        s.append(mode);
        v.doing.setText(s);

        String target = Thermostat.target(c);
        v.target.setText(target.isEmpty() ? "" : "Set to " + target);
        v.target.setVisibility(target.isEmpty() ? View.GONE : View.VISIBLE);

        v.details.removeAllViews();
        for (String[] row : Thermostat.details(c)) {
            LinearLayout r = row();
            r.addView(text(row[0], 13, SolarViews.SUB, false), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(text(row[1], 13, 0xE6FFFFFF, true));
            v.details.addView(r);
        }
    }

    private void showGraphs(JSONObject history, Map<String, List<Solar.Stat>> stats, long from, long to) {
        Thermostat.Data d = data;
        if (d == null) return;
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        for (int i = 0; i < d.climates.size() && i < climateViews.size(); i++) {
            List<Thermostat.Point> p = Thermostat.history(history.optJSONArray(d.climates.get(i).id));
            climateViews.get(i).graph.set(p, from, to, z);
        }
        for (Thermostat.Reading r : d.sensors) {
            ThermostatViews.SparkView v = sparks.get(r.id);
            if (v != null) v.set(stats.get(r.id), from, to, 0xB3FFFFFF);
        }
    }

    private String readingText(Thermostat.Reading r) {
        if (!r.ok()) return "—";
        boolean temp = "temperature".equals(r.dc) && data != null && r.unit.equals(data.unit);
        return Ha.reading(r.state) + (temp ? "°" : r.unit.isEmpty() ? "" : " " + r.unit);
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private View build(Thermostat.Data d) {
        climateViews.clear();
        sparks.clear();
        readingValues.clear();
        LinearLayout root = column();
        root.setPadding(px(36), px(16), px(36), px(14));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(d.title.isEmpty() ? "Thermostats" : d.title, 22, 0xFFFFFFFF, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        updated = text("", 13, 0x99FFFFFF, false);
        head.addView(updated);
        root.addView(head);

        cards = row();
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardsLp.topMargin = px(10);
        root.addView(cards, cardsLp);
        int n = d.climates.size();
        for (int i = 0; i < n; i++) {
            ClimateViews v = new ClimateViews();
            climateViews.add(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) lp.leftMargin = px(10);
            cards.addView(n == 1 ? wideCard(v) : card(v, n), lp);
        }

        if (!d.sensors.isEmpty()) {
            readings = row();
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = px(10);
            root.addView(readings, rLp);
            for (int i = 0; i < d.sensors.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (i > 0) lp.leftMargin = px(10);
                readings.addView(readingTile(d.sensors.get(i)), lp);
            }
        }
        return root;
    }

    /** The top of a card: name, where it is (and where that comes from), what it's doing, where it's set. */
    private LinearLayout readout(ClimateViews v, int bigSp) {
        LinearLayout col = column();
        v.name = text("", 16, 0xFFFFFFFF, true);
        col.addView(v.name);
        v.now = text("", bigSp, 0xFFFFFFFF, false);
        v.now.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        v.now.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(-2, -2);
        nowLp.topMargin = px(4);
        col.addView(v.now, nowLp);
        v.from = text("", 11, SolarViews.SUB, false);
        col.addView(v.from);
        v.doing = text("", 15, 0xE6FFFFFF, false);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.topMargin = px(6);
        col.addView(v.doing, dLp);
        v.target = text("", 15, 0xE6FFFFFF, false);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = px(2);
        col.addView(v.target, tLp);
        v.details = column();
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(-1, -2);
        detLp.topMargin = px(8);
        col.addView(v.details, detLp);
        return col;
    }

    /** Two to four thermostats: a column each, the graph at the bottom. */
    private View card(ClimateViews v, int n) {
        LinearLayout c = column();
        c.setBackground(cardBackground());
        c.setPadding(px(16), px(12), px(16), px(10));
        c.addView(readout(v, n <= 2 ? 64 : n == 3 ? 54 : 46), new LinearLayout.LayoutParams(-1, -2));
        v.graph = new ThermostatViews.GraphView(this);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        gLp.topMargin = px(10);
        c.addView(v.graph, gLp);
        return c;
    }

    /** One thermostat: the readings on the left, the graph across the rest. */
    private View wideCard(ClimateViews v) {
        LinearLayout c = row();
        c.setBackground(cardBackground());
        c.setPadding(px(20), px(16), px(20), px(12));
        c.addView(readout(v, 84), new LinearLayout.LayoutParams(px(280), -1));
        v.graph = new ThermostatViews.GraphView(this);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(0, -1, 1f);
        gLp.leftMargin = px(20);
        c.addView(v.graph, gLp);
        return c;
    }

    private View readingTile(Thermostat.Reading r) {
        LinearLayout t = column();
        t.setBackground(cardBackground());
        t.setPadding(px(12), px(7), px(12), px(7));
        TextView name = text(r.name, 11, SolarViews.SUB, false);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = text("", 18, 0xFFFFFFFF, true);
        readingValues.put(r.id, value);
        line.addView(value);
        ThermostatViews.SparkView spark = new ThermostatViews.SparkView(this);
        sparks.put(r.id, spark);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, px(20), 1f);
        sLp.leftMargin = px(10);
        line.addView(spark, sLp);
        t.addView(line);
        return t;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(px(16));
        g.setStroke(px(1), 0x1FFFFFFF);
        return g;
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
