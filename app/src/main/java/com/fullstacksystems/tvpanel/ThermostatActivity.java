package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;


import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A thermostat screen: up to four thermostats side by side (any climate entity), each as a dial (where the room is,
 * where it's set, what it's doing), a row of facts (humidity, how long it heated and cooled), and its last 24 hours;
 * the screen's other readings as tiles along the bottom. Show only: the remote changes nothing. Live values every
 * 15 s and the graphs every 5 minutes, only while open. Back returns Home.
 */
public class ThermostatActivity extends Activity {
    private static final long LIVE_MS = 15_000;
    private static final long GRAPH_MS = 5 * 60_000;
    private static final long DAY_MS = 86_400_000L;
    /** The 7 days' history again every hour; between, only the last 24 hours, joined on. */
    private static final long WEEK_MS = 60 * 60_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Each thermostat's last fetched week, and when: used on {@link #io} only. */
    private final Map<String, List<Thermostat.Point>> weeks = new HashMap<>();
    private final Map<String, Long> weekAt = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = this::loadLive;
    private final Runnable graphTick = this::loadGraphs;
    private float dp;

    private String screenId;
    private FrameLayout frame;
    private View screenView;
    private TextView message, updated;
    private LinearLayout cards;
    /** The screen's layout is built for one set of thermostats and readings; another set rebuilds it. */
    private String shape = "";
    private final List<ClimateViews> climateViews = new ArrayList<>();
    private final Map<String, SensorTileView> tiles = new HashMap<>();
    /** Each reading's place on its tile, for a tile with several. */
    private final Map<String, Integer> partOf = new HashMap<>();
    private final Map<String, List<Solar.Stat>> lines = new HashMap<>();
    private long linesFrom, linesTo;

    private volatile ZoneId zone;
    private Thermostat.Data data;

    /** One thermostat's card. */
    private static final class ClimateViews {
        TextView name, sub;
        ThermostatViews.DialView dial;
        LinearLayout facts;
        /** Facts in columns, a label over each value; or, in a narrow card, a row each. */
        int factColumns;
        boolean compact;
        ThermostatViews.GraphView graph;
        ThermostatViews.WeekView week;
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

    /**
     * Each thermostat's last 7 days of history (with its attributes), for the graph's 24 hours and the week; and the
     * other readings' 5-minute statistics for their 24 hours. A week of a thermostat that reports every minute is a
     * large answer, so each is its own call, made once an hour; between, only its last 24 hours. Whatever fails keeps
     * what's on screen, without stopping the rest.
     */
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
                Map<String, List<Thermostat.Point>> points = new HashMap<>();
                for (String id : climates) {
                    try {
                        Long at = weekAt.get(id);
                        if (at == null || now - at >= WEEK_MS) {
                            List<Thermostat.Point> week = history(ws, id, now - 7 * DAY_MS);
                            weeks.put(id, week);
                            weekAt.put(id, now);
                            points.put(id, week);
                        } else {
                            points.put(id, Thermostat.join(weeks.get(id), history(ws, id, from)));
                        }
                    } catch (Exception e) {
                        // This one's graphs stay as they are.
                    }
                }
                Map<String, List<Solar.Stat>> s;
                try {
                    s = Series.fetch(ws, sensors, from);
                } catch (Exception e) {
                    s = null;
                }
                Map<String, List<Solar.Stat>> stats = s;
                post(() -> showGraphs(points, stats, from, now));
            } catch (Exception e) {
                // Keep what's on screen; the readings still update.
            }
        });
    }

    private static List<Thermostat.Point> history(HaSocket ws, String id, long from) throws Exception {
        return Thermostat.history(Series.history(ws, java.util.Collections.singletonList(id), from, true)
                .optJSONArray(id));
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
        sh.append(d.tiles);
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
        for (Thermostat.Reading r : d.sensors) fillReading(r);
        if (first || rebuilt) loadGraphs();
    }

    private void fillClimate(Thermostat.Data d, Thermostat.Climate c, ClimateViews v) {
        v.name.setText(c.title());
        v.sub.setText(c.subtitle());
        String[] now = Thermostat.current(d, c);
        double cur = Thermostat.room(d, c);
        String doing = Thermostat.doing(c), mode = Thermostat.mode(c);
        double[] t = Thermostat.targets(c);
        // The mode under the setting, unless the setting says it ("Heat to 68°") or there's nothing more to say.
        boolean says = Double.isNaN(t[0]) != Double.isNaN(t[1]);
        String modeLine = says || "off".equals(c.state) || c.missing() || doing.equals(mode) ? "" : mode;
        v.dial.set(Thermostat.dial(c, d.unit, cur), cur, t, Thermostat.colour(c), doing,
                now[0].isEmpty() ? "—" : now[0], Thermostat.setting(c), modeLine,
                now[1].isEmpty() ? "" : "from " + now[1]);
        fillFacts(c, v);
    }

    /** Humidity, the fan and the preset, as the thermostat has them: a label over each. */
    private void fillFacts(Thermostat.Climate c, ClimateViews v) {
        List<String[]> facts = Thermostat.details(c);
        v.facts.removeAllViews();
        if (v.compact) {
            for (String[] f : facts) {
                LinearLayout r = row();
                r.addView(text(f[0], 14, SolarViews.SUB, false), new LinearLayout.LayoutParams(0, -2, 1f));
                TextView value = text(f[1], 15, 0xFFFFFFFF, true);
                level(value, c, f);
                r.addView(value);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = px(3);
                v.facts.addView(r, lp);
            }
            return;
        }
        LinearLayout line = null;
        for (int i = 0; i < facts.size(); i++) {
            if (i % v.factColumns == 0) {
                line = row();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = px(10);
                v.facts.addView(line, lp);
            }
            LinearLayout cell = column();
            cell.setGravity(v.factColumns == 1 ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            TextView label = text(facts.get(i)[0].toUpperCase(java.util.Locale.US), 11, SolarViews.SUB, true);
            label.setLetterSpacing(0.08f);
            cell.addView(label);
            TextView value = text(facts.get(i)[1], 18, 0xFFFFFFFF, false);
            level(value, c, facts.get(i));
            LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-2, -2);
            vLp.topMargin = px(2);
            cell.addView(value, vLp);
            line.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        // A short last row keeps its cells the width of the others.
        while (line != null && line.getChildCount() < v.factColumns) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /**
     * The humidity's level after it, in its colour ("68%  Too humid"), as the air quality screen and the tiles say it:
     * the same reading reads the same everywhere.
     */
    private static void level(TextView value, Thermostat.Climate c, String[] fact) {
        if (!"Humidity".equals(fact[0]) || Double.isNaN(c.humidity)) return;
        AirQuality.Band b = AirQuality.band("humidity", "%", c.humidity);
        if (b == null) return;
        android.text.SpannableString s = new android.text.SpannableString(fact[1] + "  " + b.name);
        int at = fact[1].length() + 2;
        s.setSpan(new android.text.style.ForegroundColorSpan(b.colour), at, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.8f), at, s.length(), 0);
        value.setText(s);
    }

    private void fillReading(Thermostat.Reading r) {
        SensorTileView t = tiles.get(r.id);
        if (t == null) return;
        Integer k = partOf.get(r.id);
        int i = k == null ? 0 : k;
        boolean temp = "temperature".equals(r.dc) && data != null && r.unit.equals(data.unit);
        double v = r.ok() ? Double.parseDouble(r.state) : Double.NaN;
        String dc = r.dc.isEmpty() ? null : r.dc;
        t.setLive(i, r.area, r.name, r.ok() ? Ha.reading(r.state) + (temp ? "°" : "") : "—", temp ? "" : r.unit,
                r.ok() ? AirQuality.band(dc, r.unit, v) : null, dc == null ? null : AirQuality.scale(dc, r.unit),
                dc == null ? Double.NaN : AirQuality.position(dc, r.unit, v));
        // Only once its rows are here: a reading added since the last fetch says Loading, not "No history yet".
        if (linesTo > 0 && lines.containsKey(r.id)) {
            List<Solar.Stat> rows = lines.get(r.id);
            String[] sum = AirQuality.summary(rows, v, linesFrom, linesTo);
            t.setLines(i, rows, linesFrom, linesTo, zone == null ? ZoneId.systemDefault() : zone, sum[0], sum[1],
                    sum[2]);
        }
    }

    /** The graphs from what {@link #loadGraphs} got: a thermostat it has no points for, or no stats, stay as they are. */
    private void showGraphs(Map<String, List<Thermostat.Point>> points, Map<String, List<Solar.Stat>> stats, long from,
            long to) {
        Thermostat.Data d = data;
        if (d == null) return;
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        for (int i = 0; i < d.climates.size() && i < climateViews.size(); i++) {
            Thermostat.Climate c = d.climates.get(i);
            List<Thermostat.Point> week = points.get(c.id);
            if (week == null) continue;
            List<Thermostat.Span> spans = Thermostat.spans(week, to);
            ClimateViews v = climateViews.get(i);
            v.graph.set(Thermostat.since(week, from), from, to, z, ran(c, spans, from, to));
            List<Thermostat.Day> days = Thermostat.days(week, spans, to, z, 7);
            v.week.set(days, z, !Thermostat.runtimes(c, spans, from, to).isEmpty(),
                    ran(c, spans, days.get(0).start, to));
        }
        if (stats == null) return;
        lines.clear();
        lines.putAll(stats);
        linesFrom = from;
        linesTo = to;
        for (Thermostat.Reading r : d.sensors) fillReading(r);
    }

    /** "Heated 1 h 40 m   Cooled 20 m", each in its colour; what it didn't do left out. */
    private static List<Thermostat.Part> ran(Thermostat.Climate c, List<Thermostat.Span> spans, long from, long to) {
        List<Thermostat.Part> out = new ArrayList<>();
        for (String[] r : Thermostat.runtimes(c, spans, from, to)) {
            if (r[1].equals("0 m")) continue;
            if (!out.isEmpty()) out.add(new Thermostat.Part("     ", 0));
            out.add(new Thermostat.Part(r[0] + " " + r[1], r[0].equals("Heated") ? Thermostat.HEATING
                    : Thermostat.COOLING));
        }
        return out;
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private View build(Thermostat.Data d) {
        climateViews.clear();
        tiles.clear();
        partOf.clear();
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

        // The readings: a place each, a tile of several taking theirs side by side; up to four places a row, a
        // second row past four.
        int[] sizes = new int[d.tiles.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = d.tiles.get(i).size();
        int places = Tiles.places(sizes), per = places <= 4 ? Math.max(places, 1) : (places + 1) / 2;
        List<int[]> rows = Tiles.pack(sizes, per, 2);
        int wide = per;
        for (int[] r : rows) {
            int used = 0;
            for (int g : r) used += sizes[g];
            wide = Math.max(wide, used);
        }
        for (int[] r : rows) {
            LinearLayout line = row();
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, px(rows.size() == 1 ? 96 : 76));
            rLp.topMargin = px(10);
            root.addView(line, rLp);
            int used = 0;
            for (int k = 0; k < r.length; k++) {
                List<Integer> group = d.tiles.get(r[k]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, sizes[r[k]]);
                if (k > 0) lp.leftMargin = px(10);
                SensorTileView t = new SensorTileView(this);
                t.setCount(group.size());
                for (int j = 0; j < group.size(); j++) {
                    String id = d.sensors.get(group.get(j)).id;
                    tiles.put(id, t);
                    partOf.put(id, j);
                }
                line.addView(t, lp);
                used += sizes[r[k]];
            }
            // An empty place keeps this row's tiles the size of the others.
            if (used < wide) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, wide - used);
                lp.leftMargin = px(10);
                line.addView(new View(this), lp);
            }
        }
        return root;
    }

    /** The card's title: the room (or the thermostat's name), and its name after it, smaller, when it's the room. */
    private View titleRow(ClimateViews v, int sp) {
        LinearLayout t = row();
        t.setBaselineAligned(true);
        v.name = text("", sp, 0xFFFFFFFF, true);
        t.addView(v.name, new LinearLayout.LayoutParams(-2, -2));
        v.sub = text("", 13, SolarViews.SUB, false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, -2, 1f);
        sLp.leftMargin = px(10);
        t.addView(v.sub, sLp);
        return t;
    }

    /**
     * Two to four thermostats: a column each, the dial at the top, then the 24 hours and the 7 days. Two have the
     * width for the facts beside the dial; three or four have them under it, a row each.
     */
    private View card(ClimateViews v, int n) {
        LinearLayout c = column();
        c.setBackground(cardBackground());
        c.setPadding(px(14), px(12), px(14), px(10));
        c.addView(titleRow(v, 17));
        v.dial = new ThermostatViews.DialView(this);
        v.facts = column();
        if (n == 2) {
            LinearLayout top = row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(v.dial, new LinearLayout.LayoutParams(0, -1, 1.15f));
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, -2, 1f);
            fLp.leftMargin = px(12);
            top.addView(v.facts, fLp);
            v.factColumns = 1;
            c.addView(top, new LinearLayout.LayoutParams(-1, 0, 1.45f));
        } else {
            c.addView(v.dial, new LinearLayout.LayoutParams(-1, 0, 1.15f));
            v.factColumns = 3;
            v.compact = true;
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
            fLp.topMargin = px(4);
            c.addView(v.facts, fLp);
        }
        v.graph = new ThermostatViews.GraphView(this);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        gLp.topMargin = px(12);
        c.addView(v.graph, gLp);
        v.week = new ThermostatViews.WeekView(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-1, 0, 0.8f);
        wLp.topMargin = px(10);
        c.addView(v.week, wLp);
        return c;
    }

    /** One thermostat: the room, dial and facts on the left; the 24 hours over the 7 days on the right. */
    private View wideCard(ClimateViews v) {
        LinearLayout c = row();
        c.setBackground(cardBackground());
        c.setPadding(px(20), px(16), px(24), px(12));
        LinearLayout left = column();
        left.addView(titleRow(v, 20));
        v.dial = new ThermostatViews.DialView(this);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dLp.topMargin = px(4);
        left.addView(v.dial, dLp);
        v.facts = column();
        v.factColumns = 3;
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
        fLp.topMargin = px(6);
        left.addView(v.facts, fLp);
        c.addView(left, new LinearLayout.LayoutParams(px(360), -1));
        LinearLayout right = column();
        v.graph = new ThermostatViews.GraphView(this);
        right.addView(v.graph, new LinearLayout.LayoutParams(-1, 0, 1.35f));
        v.week = new ThermostatViews.WeekView(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        wLp.topMargin = px(16);
        right.addView(v.week, wLp);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rLp.leftMargin = px(28);
        c.addView(right, rLp);
        return c;
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
