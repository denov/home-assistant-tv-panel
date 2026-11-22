package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen reef tank for the Apex chips: water probes, the Trident's last results, reagents and dosing
 * containers, and trends (temperature, pH and salinity over 24 hours; alkalinity, calcium and magnesium over 7
 * days). Live values every 30 s and trends every 5 minutes, only while open. Back returns to Home.
 */
public class ApexActivity extends Activity {
    private static final long LIVE_MS = 30_000;
    private static final long TRENDS_MS = 5 * 60_000;
    private static final long DAY_MS = 86_400_000L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = this::loadLive;
    private final Runnable trendsTick = this::loadTrends;
    private float dp;

    private View screenView;
    private TextView message, status, updated, tested;
    private LinearLayout water, trident, supplies;
    private final ApexViews.TrendView[] trends = new ApexViews.TrendView[6];

    private volatile ZoneId zone;
    private Apex.Data data;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.SCREENS.add(this);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF0B1E2C, 0xFF061016}));
        screenView = build();
        screenView.setVisibility(View.INVISIBLE);
        frame.addView(screenView);
        message = text("Loading the tank…", 22, 0xCCFFFFFF, false);
        message.setGravity(Gravity.CENTER);
        frame.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLive();
        // onPause stopped the trends' tick, and showLive only starts it the first time.
        if (data != null) loadTrends();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveTick);
        main.removeCallbacks(trendsTick);
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
            HaClient.Reply r = Ha.data(ha, Ha.DATA_TANK, Apex.templateBody());
            if (!r.ok()) {
                String why = r.code == 401 ? "Home Assistant refused the token (401)."
                        : r.code == 0 ? "Home Assistant did not answer." : "Home Assistant answered " + r.code + ".";
                post(() -> {
                    if (data == null) showMessage(why);
                });
                return;
            }
            try {
                Apex.Data d = Apex.parse(r.body);
                post(() -> showLive(d));
            } catch (Exception e) {
                post(() -> {
                    if (data == null) showMessage("Could not read Home Assistant's answer: " + e.getMessage());
                });
            }
        });
    }


    /** Water probes from 5-minute statistics over 24 hours; the Trident's from hourly ones over 7 days. */
    private void loadTrends() {
        main.removeCallbacks(trendsTick);
        main.postDelayed(trendsTick, TRENDS_MS);
        Apex.Data d = data;
        Config c = Config.load(this);
        if (d == null || !c.complete()) return;
        io.execute(() -> {
            long now = System.currentTimeMillis();
            JSONArray waterIds = new JSONArray(), tridentIds = new JSONArray();
            for (List<Apex.Item> g : Arrays.asList(d.temp, d.ph, d.salinity)) for (Apex.Item i : g) waterIds.put(i.id);
            for (Apex.Item i : new Apex.Item[] {d.alk, d.ca, d.mg}) if (i != null) tridentIds.put(i.id);
            try (HaSocket ws = HaSocket.open(c.url, c.token)) {
                JSONObject day = waterIds.length() == 0 ? new JSONObject() : stats(ws, waterIds, now - DAY_MS, "5minute");
                JSONObject week = tridentIds.length() == 0 ? new JSONObject() : stats(ws, tridentIds, now - 7 * DAY_MS, "hour");
                post(() -> showTrends(d, day, week, now));
            } catch (Exception e) {
                // Keep what is on screen; the readings still update.
            }
        });
    }

    private static JSONObject stats(HaSocket ws, JSONArray ids, long startMs, String period) throws Exception {
        JSONObject msg = new JSONObject()
                .put("type", "recorder/statistics_during_period")
                .put("start_time", Instant.ofEpochMilli(startMs).toString())
                .put("statistic_ids", ids)
                .put("period", period)
                .put("types", new JSONArray().put("mean"));
        Object r = ws.call(msg);
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
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
        screenView.setVisibility(View.INVISIBLE);
    }

    private void showLive(Apex.Data d) {
        if (!d.any()) {
            showMessage("No Apex probes in Home Assistant. This screen reads sensors with an apex_probe_type attribute.");
            return;
        }
        boolean first = data == null;
        data = d;
        message.setVisibility(View.GONE);
        screenView.setVisibility(View.VISIBLE);
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
        statusLine(d);
        updated.setText("Updated " + ZonedDateTime.now(z).format(clock));
        fillWater(d);
        fillTrident(d, z, clock);
        fillSupplies(d);
        nowReadings(d);
        if (first) loadTrends();
    }

    private void statusLine(Apex.Data d) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (d.alarm != null) {
            dot(s, d.alarm ? ApexViews.BAD : SolarViews.BATTERY);
            s.append(d.alarm ? (d.alerts > 0 ? d.alerts + (d.alerts == 1 ? " alert" : " alerts") : "Alarm") : "No alarms");
        }
        String tri = Apex.mode(d.tridentMode), dos = Apex.mode(d.dosMode);
        if (!tri.isEmpty()) {
            gap(s);
            s.append("Trident ").append(tri);
        }
        if (!dos.isEmpty()) {
            gap(s);
            s.append("DOS ").append(dos);
        }
        status.setText(s);
    }

    private void fillWater(Apex.Data d) {
        water.removeAllViews();
        LinearLayout head = row();
        head.addView(cell("", 11, 0, false, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1.1f));
        head.addView(cell("Probe 1", 11, 0x99FFFFFF, true, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(cell("Probe 2", 11, 0x99FFFFFF, true, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
        water.addView(head);
        waterRow("Temperature", d.temp);
        waterRow("pH", d.ph);
        waterRow("Salinity", d.salinity);
    }

    private void waterRow(String name, List<Apex.Item> probes) {
        if (probes.isEmpty()) return;
        LinearLayout r = row();
        r.setPadding(0, px(2), 0, px(2));
        r.addView(cell(name, 14, 0xE6FFFFFF, false, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1.1f));
        for (int i = 0; i < 2; i++) {
            String v = i < probes.size() ? Apex.reading(probes.get(i)) : "";
            r.addView(cell(v, i == 0 ? 17 : 15, i == 0 ? 0xFFFFFFFF : 0xB3FFFFFF, i == 0, Gravity.END),
                    new LinearLayout.LayoutParams(0, -2, 1f));
        }
        water.addView(r);
    }

    private void fillTrident(Apex.Data d, ZoneId z, DateTimeFormatter clock) {
        trident.removeAllViews();
        String[] names = {"Alkalinity", "Calcium", "Magnesium"};
        Apex.Item[] items = {d.alk, d.ca, d.mg};
        for (int i = 0; i < 3; i++) {
            if (items[i] == null) continue;
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(cell(names[i], 12, 0x99FFFFFF, false, Gravity.CENTER));
            Apex.Item it = items[i];
            String v = it.ok() ? Ha.reading(it.state) : "—";
            TextView big = cell(v, 26, 0xFFFFFFFF, false, Gravity.CENTER);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            col.addView(big);
            col.addView(cell(it.unit, 12, 0x99FFFFFF, false, Gravity.CENTER));
            trident.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        long t = d.lastTest();
        tested.setText(t == 0 ? "" : "Tested " + when(t, z, clock));
    }

    /** "1:35 PM" today, "Sat 1:35 PM" this week, "Nov 1" before that. */
    private static String when(long ms, ZoneId z, DateTimeFormatter clock) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(z);
        ZonedDateTime now = ZonedDateTime.now(z);
        if (t.toLocalDate().equals(now.toLocalDate())) return t.format(clock);
        if (t.isAfter(now.minusDays(6))) return t.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US));
        return t.format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
    }

    private void fillSupplies(Apex.Data d) {
        supplies.removeAllViews();
        for (Apex.Item r : d.reagents) {
            int colour = r.tests >= 0 && r.tests < 20 ? ApexViews.WARN : 0xFFFFFFFF;
            supplyRow(Apex.label(r), Apex.volume(r), r.tests < 0 ? "" : r.tests + " tests", colour, Double.NaN, 0);
        }
        if (d.waste != null) {
            double full = d.waste.full;
            int colour = !Double.isNaN(full) && full >= 80 ? ApexViews.BAD : !Double.isNaN(full) && full >= 60 ? ApexViews.WARN : ApexViews.LINE_1;
            supplyRow("Trident waste", Apex.volume(d.waste), Double.isNaN(full) ? "" : Math.round(full) + "% full",
                    colour == ApexViews.LINE_1 ? 0xFFFFFFFF : colour, Double.isNaN(full) ? Double.NaN : full / 100, colour);
        }
        for (Apex.Item c : d.dosing) {
            double left = c.left;
            int colour = !Double.isNaN(left) && left <= 10 ? ApexViews.BAD : !Double.isNaN(left) && left <= 25 ? ApexViews.WARN : ApexViews.LINE_1;
            String note = Double.isNaN(left) ? "" : left <= 0.5 ? "Empty" : Math.round(left) + "% left";
            supplyRow(Apex.label(c), Apex.volume(c), note, colour == ApexViews.LINE_1 ? 0xFFFFFFFF : colour,
                    Double.isNaN(left) ? Double.NaN : left / 100, colour);
        }
    }

    /** Name, amount and note on one line; a level bar under it when {@code share} is a number. */
    private void supplyRow(String name, String amount, String note, int noteColour, double share, int barColour) {
        LinearLayout r = row();
        r.setPadding(0, px(2), 0, Double.isNaN(share) ? px(2) : 0);
        r.addView(cell(name, 14, 0xE6FFFFFF, false, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1.25f));
        r.addView(cell(amount, 14, 0xB3FFFFFF, false, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(cell(note, 14, noteColour, true, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
        supplies.addView(r);
        if (!Double.isNaN(share)) {
            ApexViews.BarView bar = new ApexViews.BarView(this);
            bar.set(share, barColour);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(4));
            lp.topMargin = px(2);
            lp.bottomMargin = px(4);
            supplies.addView(bar, lp);
        }
    }

    // ---- trends ------------------------------------------------------------------------------------------------

    private static final String[] TREND_TITLES = {"Temperature · 24 h", "pH · 24 h", "Salinity · 24 h",
            "Alkalinity · 7 days", "Calcium · 7 days", "Magnesium · 7 days"};

    private List<List<Apex.Item>> trendGroups(Apex.Data d) {
        List<List<Apex.Item>> g = new ArrayList<>();
        g.add(d.temp);
        g.add(d.ph);
        g.add(d.salinity);
        g.add(single(d.alk));
        g.add(single(d.ca));
        g.add(single(d.mg));
        return g;
    }

    private static List<Apex.Item> single(Apex.Item i) {
        List<Apex.Item> out = new ArrayList<>();
        if (i != null) out.add(i);
        return out;
    }

    /** The readings now at the top right of each trend. */
    private void nowReadings(Apex.Data d) {
        List<List<Apex.Item>> groups = trendGroups(d);
        for (int t = 0; t < trends.length; t++) {
            List<String> values = new ArrayList<>();
            List<Integer> colours = new ArrayList<>();
            List<Apex.Item> g = groups.get(t);
            for (int i = 0; i < g.size(); i++) {
                values.add(Apex.reading(g.get(i)));
                colours.add(i == 0 ? ApexViews.LINE_1 : ApexViews.LINE_2);
            }
            trends[t].setNow(values, colours);
        }
    }

    private void showTrends(Apex.Data d, JSONObject day, JSONObject week, long now) {
        List<List<Apex.Item>> groups = trendGroups(d);
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        int[] decimals = {1, 2, 1, 2, 0, 0};
        for (int t = 0; t < trends.length; t++) {
            boolean days = t >= 3;
            List<ApexViews.Line> lines = new ArrayList<>();
            List<Apex.Item> g = groups.get(t);
            for (int i = 0; i < g.size(); i++) {
                Apex.Item it = g.get(i);
                lines.add(new ApexViews.Line(Apex.label(it), i == 0 ? ApexViews.LINE_1 : ApexViews.LINE_2,
                        Solar.series(days ? week : day, it.id, "mean")));
            }
            trends[t].setLines(lines, now - (days ? 7 : 1) * DAY_MS, now, days, z, decimals[t]);
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private View build() {
        LinearLayout root = column();
        root.setPadding(px(36), px(16), px(36), px(14));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Reef tank", 22, 0xFFFFFFFF, true);
        title.setPadding(0, 0, px(22), 0);
        head.addView(title);
        status = text("", 13, 0xCCFFFFFF, false);
        head.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        updated = text("", 13, 0x99FFFFFF, false);
        head.addView(updated);
        root.addView(head);

        LinearLayout body = row();
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.topMargin = px(10);
        root.addView(body, bodyLp);

        LinearLayout left = column();
        body.addView(left, new LinearLayout.LayoutParams(px(300), -1));
        water = column();
        left.addView(card(heading("WATER", null), water), new LinearLayout.LayoutParams(-1, -2));
        trident = row();
        LinearLayout th = row();
        th.addView(headingText("TRIDENT"), new LinearLayout.LayoutParams(0, -2, 1f));
        tested = text("", 11, 0x66FFFFFF, false);
        th.addView(tested);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = px(10);
        left.addView(card(th, trident), tLp);
        supplies = column();
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sLp.topMargin = px(10);
        left.addView(card(heading("REAGENTS & DOSING", null), supplies), sLp);

        LinearLayout right = column();
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rLp.leftMargin = px(12);
        body.addView(right, rLp);
        for (int r = 0; r < 3; r++) {
            LinearLayout line = row();
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) lineLp.topMargin = px(10);
            right.addView(line, lineLp);
            for (int c = 0; c < 2; c++) {
                // Water on the left (temperature, pH, salinity), the Trident on the right.
                int t = c == 0 ? r : 3 + r;
                ApexViews.TrendView v = new ApexViews.TrendView(this);
                v.setTitle(TREND_TITLES[t]);
                trends[t] = v;
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (c > 0) cLp.leftMargin = px(10);
                line.addView(box(v), cLp);
            }
        }
        return root;
    }

    private View heading(String title, String note) {
        LinearLayout h = row();
        h.addView(headingText(title), new LinearLayout.LayoutParams(0, -2, 1f));
        if (note != null) h.addView(text(note, 11, 0x66FFFFFF, false));
        return h;
    }

    private TextView headingText(String s) {
        TextView t = text(s, 12, 0x99FFFFFF, true);
        t.setGravity(Gravity.START);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(px(16));
        g.setStroke(px(1), 0x1FFFFFFF);
        return g;
    }

    private View card(View heading, View content) {
        LinearLayout c = column();
        c.setBackground(cardBackground());
        c.setPadding(px(14), px(9), px(14), px(10));
        c.addView(heading);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(6);
        c.addView(content, lp);
        return c;
    }

    /** A card around a view that draws its own title. */
    private View box(View content) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(cardBackground());
        f.setPadding(px(12), px(9), px(12), px(6));
        f.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private static void dot(SpannableStringBuilder s, int colour) {
        int at = s.length();
        s.append("● ");
        s.setSpan(new ForegroundColorSpan(colour), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void gap(SpannableStringBuilder s) {
        if (s.length() > 0) s.append("      ");
    }

    private TextView cell(String s, int sp, int colour, boolean bold, int gravity) {
        TextView t = text(s, sp, colour, bold);
        t.setGravity(gravity);
        return t;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setSingleLine(true);
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
