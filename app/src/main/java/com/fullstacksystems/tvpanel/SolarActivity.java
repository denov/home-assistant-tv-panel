package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen solar for the solar chip: the energy flow now and today, every panel's output on the owner's layout,
 * today's production and use per 15 minutes, and today / week / month totals. Live values every 15 s, statistics
 * every 5 minutes, only while open. Left, Right or OK switches the panels between now (W) and today (kWh).
 */
public class SolarActivity extends Activity {
    private static final long LIVE_MS = 15_000;
    private static final long STATS_MS = 5 * 60_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = this::loadLive;
    private final Runnable statsTick = this::loadStats;
    private float dp;

    private FrameLayout frame;
    private View page;
    private TextView message, status, updated, panelsTitle, panelsTotal;
    private LinearLayout totalsTable;
    private SolarViews.FlowView flow;
    private SolarViews.PanelMapView panels;
    private SolarViews.ChartView chart;

    private volatile ZoneId zone;
    private Solar.Live live;
    private Solar.Totals totals;
    private boolean layoutSet;
    private boolean userPickedMode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.PAGES.add(this);
        // Opened by voice while Ambient Mode runs, the page would sit behind the screensaver. Asking to turn the screen
        // on makes Android wake from the screensaver (it does nothing when the TV is already awake).
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        dp = getResources().getDisplayMetrics().density;
        frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF0E1A2E, 0xFF070B14}));
        page = build();
        page.setVisibility(View.INVISIBLE);
        frame.addView(page);
        message = text("Loading solar…", 22, 0xCCFFFFFF, false);
        message.setGravity(Gravity.CENTER);
        frame.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveTick);
        main.removeCallbacks(statsTick);
    }

    @Override
    protected void onDestroy() {
        OpenActivity.PAGES.remove(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                userPickedMode = true;
                panels.setShowToday(!panels.showToday());
                panelsHeading();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** The panel layout: the integration's, else the pushed one. Read once, on the io thread. */
    private volatile String layout;

    // ---- loading -----------------------------------------------------------------------------------------------

    private void loadLive() {
        main.removeCallbacks(liveTick);
        main.postDelayed(liveTick, LIVE_MS);
        Config c = Config.load(this);
        if (!c.complete()) {
            showMessage("Not paired with Home Assistant yet. Open TV Panel from the app grid to pair.");
            return;
        }
        io.execute(() -> {
            HaClient ha = new HaClient(c.url, c.token);
            if (zone == null) zone = haZone(ha);
            if (layout == null) layout = SettingsSource.get(c).solarLayout(Config.solarLayout(this));
            HaClient.Reply r = ha.post("/api/template", Solar.templateBody());
            if (!r.ok()) {
                String why = r.code == 401 ? "Home Assistant refused the token (401)."
                        : r.code == 0 ? "Home Assistant did not answer." : "Home Assistant answered " + r.code + ".";
                post(() -> {
                    if (live == null) showMessage(why);
                });
                return;
            }
            try {
                Solar.Live l = Solar.live(r.body);
                post(() -> showLive(l));
            } catch (Exception e) {
                post(() -> {
                    if (live == null) showMessage("Could not read Home Assistant's answer: " + e.getMessage());
                });
            }
        });
    }

    private static ZoneId haZone(HaClient ha) {
        try {
            HaClient.Reply cfg = ha.get("/api/config");
            if (cfg.ok()) return ZoneId.of(new JSONObject(cfg.body).optString("time_zone"));
        } catch (Exception ignored) {
            // the TV's own zone
        }
        return ZoneId.systemDefault();
    }

    private void loadStats() {
        main.removeCallbacks(statsTick);
        main.postDelayed(statsTick, STATS_MS);
        Solar.Live l = live;
        Config c = Config.load(this);
        if (l == null || !c.complete()) return;
        io.execute(() -> {
            ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
            ZonedDateTime now = ZonedDateTime.now(z);
            ZonedDateTime midnight = now.toLocalDate().atStartOfDay(z);
            ZonedDateTime hour = now.truncatedTo(ChronoUnit.HOURS);
            ZonedDateTime week = midnight.with(WeekFields.of(Locale.US).dayOfWeek(), 1);
            ZonedDateTime month = midnight.withDayOfMonth(1);
            ZonedDateTime from = week.isBefore(month) ? week : month;
            JSONArray energy = new JSONArray();
            for (String id : l.energyIds) if (id != null) energy.put(id);
            try (HaSocket ws = HaSocket.open(c.url, c.token)) {
                JSONObject five = null, days = null, invHours = null, invFive = null;
                if (energy.length() > 0) {
                    JSONArray ids = new JSONArray(energy.toString());
                    if (l.socId != null) ids.put(l.socId);
                    five = stats(ws, ids, midnight, null, "5minute", "change", "mean");
                    if (from.isBefore(midnight)) days = stats(ws, energy, from, midnight, "day", "change");
                }
                JSONArray inv = new JSONArray(l.inverterIds());
                if (inv.length() > 0) {
                    if (hour.isAfter(midnight)) invHours = stats(ws, inv, midnight, hour, "hour", "mean");
                    invFive = stats(ws, inv, hour, null, "5minute", "mean");
                }
                long mid = midnight.toInstant().toEpochMilli();
                Solar.Totals t = Solar.totals(l.energyIds, five, days, week.toInstant().toEpochMilli(),
                        month.toInstant().toEpochMilli());
                double[] produced = Solar.quarterHours(Solar.series(five, l.energyIds[Solar.PRODUCED], "change"), mid);
                double[] consumed = Solar.balance(five, l.energyIds, mid);
                if (consumed == null && l.energyIds[Solar.CONSUMED] != null) {
                    consumed = Solar.quarterHours(Solar.series(five, l.energyIds[Solar.CONSUMED], "change"), mid);
                }
                double[] use = consumed;
                double[] soc = l.socId == null ? null : Solar.quarterMeans(Solar.series(five, l.socId, "mean"), mid);
                Map<String, Double> panelToday = new HashMap<>();
                for (String serial : l.inverters.keySet()) {
                    String id = Solar.INVERTER + serial;
                    panelToday.put(serial, Solar.energyFromMeans(Solar.series(invHours, id, "mean"),
                            Solar.series(invFive, id, "mean")));
                }
                int nowIndex = (int) ((now.toInstant().toEpochMilli() - mid) / 900_000L);
                post(() -> showStats(t, produced, use, soc, nowIndex, panelToday));
            } catch (Exception e) {
                // Keep what is on screen; the live values still update.
            }
        });
    }

    private static JSONObject stats(HaSocket ws, JSONArray ids, ZonedDateTime start, ZonedDateTime end, String period,
                                    String... types) throws Exception {
        JSONObject msg = new JSONObject()
                .put("type", "recorder/statistics_during_period")
                .put("start_time", start.toInstant().toString())
                .put("statistic_ids", ids)
                .put("period", period)
                .put("types", new JSONArray(java.util.Arrays.asList(types)))
                .put("units", new JSONObject().put("energy", "kWh").put("power", "W"));
        if (end != null) msg.put("end_time", end.toInstant().toString());
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
        page.setVisibility(View.INVISIBLE);
    }

    private void showLive(Solar.Live l) {
        if (!l.hasEnvoy()) {
            showMessage("No Enphase Envoy in Home Assistant. This page reads the Enphase Envoy integration.");
            return;
        }
        boolean first = live == null;
        live = l;
        message.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
        if (!layoutSet) {
            panels.setLayout(Solar.layout(layout == null ? Config.solarLayout(this) : layout, l.inverters.keySet()));
            layoutSet = true;
        }
        if (!userPickedMode) panels.setShowToday(Double.isNaN(l.productionW) || l.productionW < 20);
        panels.setValues(l.inverters, null);
        flow.set(l, totals);
        statusLine(l);
        updated.setText("Updated " + ZonedDateTime.now(zone == null ? ZoneId.systemDefault() : zone)
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)));
        panelsHeading();
        if (first) loadStats();
    }

    private void showStats(Solar.Totals t, double[] produced, double[] consumed, double[] soc, int nowIndex,
                           Map<String, Double> panelToday) {
        totals = t;
        flow.set(live, t);
        chart.set(produced, consumed, soc, nowIndex);
        panels.setValues(null, panelToday);
        panelsHeading();
        fillTotals(t);
    }

    private void statusLine(Solar.Live l) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (l.onGrid != null) {
            dot(s, l.onGrid ? SolarViews.BATTERY : SolarViews.HOME);
            s.append(l.onGrid ? "On grid" : "Off grid · backup power");
        }
        if (!Double.isNaN(l.soc)) {
            gap(s);
            dot(s, SolarViews.BATTERY);
            s.append("Battery ").append(String.valueOf(Math.round(l.soc))).append("%");
            if (!Double.isNaN(l.availableKWh) && !Double.isNaN(l.capacityKWh)) {
                s.append(" · ").append(Solar.energy(l.availableKWh)).append(" of ")
                        .append(Solar.energy(l.capacityKWh)).append(" kWh");
            }
        }
        String mode = Solar.mode(l.mode);
        if (!mode.isEmpty()) {
            gap(s);
            s.append(mode);
        }
        if (!Double.isNaN(l.lifetimeKWh)) {
            gap(s);
            s.append(Solar.lifetime(l.lifetimeKWh)).append(" lifetime");
        }
        status.setText(s);
    }

    private static void dot(SpannableStringBuilder s, int colour) {
        int at = s.length();
        s.append("● ");
        s.setSpan(new ForegroundColorSpan(colour), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void gap(SpannableStringBuilder s) {
        if (s.length() > 0) s.append("      ");
    }

    private void panelsHeading() {
        boolean today = panels.showToday();
        panelsTitle.setText(today ? "PANELS · TODAY (kWh)" : "PANELS · NOW (W)");
        String total = "";
        if (today && totals != null) total = Solar.energy(totals.today[Solar.PRODUCED]) + " kWh";
        else if (!today && live != null) total = Solar.power(live.productionW);
        panelsTotal.setText(total + "    ◀ ▶  " + (today ? "now" : "today"));
    }

    private void fillTotals(Solar.Totals t) {
        totalsTable.removeAllViews();
        LinearLayout head = row();
        head.addView(cell("kWh", 11, 0x66FFFFFF, false, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1.35f));
        for (String h : new String[] {"Today", "Week", "Month"}) {
            head.addView(cell(h, 11, 0x99FFFFFF, true, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        totalsTable.addView(head);
        int[] colours = {SolarViews.SOLAR, SolarViews.HOME, SolarViews.GRID, SolarViews.GRID, SolarViews.BATTERY, SolarViews.BATTERY};
        for (int m = 0; m < Solar.METRICS.length; m++) {
            if (Double.isNaN(t.today[m])) continue;
            LinearLayout r = row();
            r.setPadding(0, px(1), 0, px(1));
            SpannableStringBuilder name = new SpannableStringBuilder();
            dot(name, colours[m]);
            name.append(Solar.METRICS[m]);
            TextView n = cell("", 13, 0xE6FFFFFF, false, Gravity.START);
            n.setText(name);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1.35f));
            r.addView(cell(Solar.energy(t.today[m]), 14, 0xFFFFFFFF, true, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(cell(Solar.energy(t.week[m]), 14, 0xE6FFFFFF, false, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(cell(Solar.energy(t.month[m]), 14, 0xE6FFFFFF, false, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1f));
            totalsTable.addView(r);
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private View build() {
        LinearLayout root = column();
        root.setPadding(px(36), px(16), px(36), px(14));

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Solar", 22, 0xFFFFFFFF, true);
        title.setPadding(0, 0, px(22), 0);
        head.addView(title);
        status = text("", 13, 0xCCFFFFFF, false);
        status.setGravity(Gravity.START);
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
        flow = new SolarViews.FlowView(this);
        left.addView(card(heading("NOW", "TODAY IN kWh"), flow), new LinearLayout.LayoutParams(-1, 0, 1f));
        totalsTable = column();
        totalsTable.addView(cell("Loading…", 13, 0x99FFFFFF, false, Gravity.START));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = px(10);
        left.addView(card(heading("TOTALS", null), totalsTable), tLp);

        LinearLayout right = column();
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rLp.leftMargin = px(12);
        body.addView(right, rLp);
        panels = new SolarViews.PanelMapView(this);
        LinearLayout ph = row();
        panelsTitle = headingText("PANELS");
        ph.addView(panelsTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        panelsTotal = text("", 12, 0x99FFFFFF, false);
        ph.addView(panelsTotal);
        right.addView(card(ph, panels), new LinearLayout.LayoutParams(-1, 0, 1.25f));
        chart = new SolarViews.ChartView(this);
        LinearLayout ch = row();
        ch.addView(headingText("TODAY"), new LinearLayout.LayoutParams(0, -2, 1f));
        SpannableStringBuilder legend = new SpannableStringBuilder();
        dot(legend, SolarViews.SOLAR);
        legend.append("Produced    ");
        dot(legend, SolarViews.HOME);
        legend.append("Consumed    ");
        dot(legend, SolarViews.BATTERY);
        legend.append("Battery %");
        TextView lg = text("", 12, 0x99FFFFFF, false);
        lg.setText(legend);
        ch.addView(lg);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cLp.topMargin = px(10);
        right.addView(card(ch, chart), cLp);
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

    private View card(View heading, View content) {
        LinearLayout c = column();
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(px(16));
        g.setStroke(px(1), 0x1FFFFFFF);
        c.setBackground(g);
        c.setPadding(px(14), px(9), px(14), px(9));
        c.addView(heading);
        boolean fills = !(content instanceof LinearLayout);
        LinearLayout.LayoutParams lp = fills ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(6);
        c.addView(content, lp);
        return c;
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
