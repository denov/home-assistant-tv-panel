package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen weather for the weather chip: now, the next 12 hours and 5 days, from the Home Assistant weather
 * entity the chip shows. Refreshes every 10 minutes while open. Back returns to Home.
 */
public class WeatherActivity extends Activity {
    static final String EXTRA_KEY = "key";
    private static final long REFRESH_MS = 10 * 60_000;
    private static final int HOURS = 12;
    private static final int DAYS = 5;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::load;
    private LinearLayout root;
    private float dp;
    private String entity;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.PAGES.add(this);
        // Opened by voice while Ambient Mode runs, the page would sit behind the screensaver. Asking to turn the screen
        // on makes Android wake from the screensaver (it does nothing when the TV is already awake).
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        dp = getResources().getDisplayMetrics().density;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(64), px(28), px(64), px(20));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF0E1A2E, 0xFF070B14});
        root.setBackground(bg);
        setContentView(root);
        entity = getIntent().getStringExtra(EXTRA_KEY);
        message("Loading weather…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        OpenActivity.PAGES.remove(this);
        io.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        main.removeCallbacks(tick);
        main.postDelayed(tick, REFRESH_MS);
        Config c = Config.load(this);
        if (!c.complete()) {
            message("No Home Assistant address and token yet. Run install.sh with a config file.");
            return;
        }
        io.execute(() -> {
            HaClient ha = new HaClient(c.url, c.token);
            try {
                String id = entity;
                if (id == null || !id.startsWith("weather.")) id = firstWeather(ha, c);
                if (id == null) {
                    post(() -> message("Home Assistant has no weather entity."));
                    return;
                }
                HaClient.Reply s = ha.get("/api/states/" + id);
                if (!s.ok()) {
                    String why = s.code == 0 ? "Home Assistant did not answer." : "Home Assistant answered " + s.code + ".";
                    post(() -> message(why));
                    return;
                }
                Weather.Now now = Weather.now(s.body);
                ZoneId zone = ZoneId.systemDefault();
                HaClient.Reply cfg = ha.get("/api/config");
                if (cfg.ok()) {
                    String place = new JSONObject(cfg.body).optString("location_name", "");
                    if (!place.isEmpty()) now.place = place;
                }
                List<Weather.Point> hourly = forecast(ha, id, "hourly", zone);
                List<Weather.Point> daily = forecast(ha, id, "daily", zone);
                post(() -> show(now, hourly, daily));
            } catch (Exception e) {
                post(() -> message("Could not read the weather: " + e.getMessage()));
            }
        });
    }

    private static List<Weather.Point> forecast(HaClient ha, String id, String type, ZoneId zone) {
        HaClient.Reply r = ha.post("/api/services/weather/get_forecasts?return_response", Weather.forecastBody(id, type));
        if (!r.ok()) return new ArrayList<>();
        try {
            return Weather.forecast(r.body, id, zone);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** The page was opened without a chip (tv_panel.show, voice): see {@link Ha#pickWeather}. */
    private static String firstWeather(HaClient ha, Config c) throws Exception {
        List<String> chips = new ArrayList<>();
        for (Ha.Widget w : WidgetProvider.current()) chips.add(w.key);
        // Straight after the app starts, before Home has asked for the chips, the saved settings know them.
        Settings s = SettingsSource.get(c);
        if (s.entities != null) chips.addAll(s.entities);
        HaClient.Reply r = ha.get("/api/states");
        if (!r.ok()) return Ha.pickWeather(chips, new JSONArray());
        return Ha.pickWeather(chips, new JSONArray(r.body));
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private void message(String text) {
        root.removeAllViews();
        root.setGravity(Gravity.CENTER);
        root.addView(text(text, 22, 0xCCFFFFFF, false));
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private void show(Weather.Now n, List<Weather.Point> hourly, List<Weather.Point> daily) {
        root.removeAllViews();
        root.setGravity(Gravity.NO_GRAVITY);
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

        // Header: place and when.
        LinearLayout head = row();
        TextView place = text(n.place.isEmpty() ? "Weather" : n.place, 22, 0xFFFFFFFF, true);
        place.setGravity(Gravity.START);
        head.addView(place, weight());
        head.addView(text("Updated " + now.format(clock), 16, 0x99FFFFFF, false));
        root.addView(head);

        // Now: icon, temperature, condition, today's high and low; details on the right.
        LinearLayout current = row();
        current.setPadding(0, px(8), 0, px(4));
        current.setGravity(Gravity.CENTER_VERTICAL);
        current.addView(icon(Ha.conditionIcon(n.condition), Ha.conditionColour(n.condition), 84));
        TextView temp = text(Weather.deg(n.temp), 84, 0xFFFFFFFF, false);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setPadding(px(20), 0, px(24), 0);
        current.addView(temp);
        LinearLayout cond = column();
        TextView condText = text(Ha.conditionText(n.condition), 32, 0xFFFFFFFF, false);
        condText.setGravity(Gravity.START);
        cond.addView(condText);
        Weather.Point today = daily.isEmpty() ? null : daily.get(0);
        if (today != null) {
            TextView hl = text("H " + Weather.deg(today.temp) + "   L " + Weather.deg(today.low), 20, 0xB3FFFFFF, false);
            hl.setGravity(Gravity.START);
            cond.addView(hl);
        }
        if (!Double.isNaN(n.feels)) {
            TextView feels = text("Feels like " + Weather.deg(n.feels), 18, 0x99FFFFFF, false);
            feels.setGravity(Gravity.START);
            cond.addView(feels);
        }
        current.addView(cond);
        current.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        LinearLayout details = column();
        LinearLayout d1 = row(), d2 = row();
        d1.addView(detail("Humidity", pct(n.humidity)));
        d1.addView(detail("Wind", wind(n.wind, n.windUnit, n.bearing)));
        d1.addView(detail("UV index", Double.isNaN(n.uv) ? "—" : Ha.number(n.uv)));
        d2.addView(detail("Dew point", Weather.deg(n.dewPoint)));
        d2.addView(detail("Pressure", Double.isNaN(n.pressure) ? "—" : Ha.number(n.pressure) + " " + n.pressureUnit));
        d2.addView(detail("Clouds", pct(n.cloud)));
        details.addView(d1);
        details.addView(d2);
        current.addView(details);
        root.addView(current);

        // Next hours.
        root.addView(heading("Next " + HOURS + " hours"));
        LinearLayout hours = row();
        int shown = 0;
        for (Weather.Point p : hourly) {
            if (p.time.isBefore(now.minusMinutes(59))) continue;
            if (shown++ == HOURS) break;
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(text(shown == 1 ? "Now" : p.time.format(DateTimeFormatter.ofPattern("h a", Locale.US)), 15, 0xB3FFFFFF, false));
            cell.addView(icon(Ha.conditionIcon(p.condition), Ha.conditionColour(p.condition), 30));
            cell.addView(text(Weather.deg(p.temp), 19, 0xFFFFFFFF, false));
            String rain = precip(p, n.precipUnit);
            if (!rain.isEmpty()) cell.addView(text(rain, 13, 0xFF7FC4F0, false));
            hours.addView(cell, weight());
        }
        if (shown == 0) hours.addView(text("No hourly forecast from this weather entity.", 16, 0x99FFFFFF, false));
        root.addView(card(hours));

        // Days.
        root.addView(heading(DAYS + "-day forecast"));
        LinearLayout days = row();
        int n5 = 0;
        LocalDate first = now.toLocalDate();
        for (Weather.Point p : daily) {
            if (p.time.toLocalDate().isBefore(first)) continue;
            if (n5++ == DAYS) break;
            // A day is a day: met.no's daily summary can say clear-night for a clear day.
            if ("clear-night".equals(p.condition)) p.condition = "sunny";
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            String day = p.time.toLocalDate().equals(first) ? "Today"
                    : p.time.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US);
            cell.addView(text(day, 19, 0xFFFFFFFF, true));
            cell.addView(icon(Ha.conditionIcon(p.condition), Ha.conditionColour(p.condition), 42));
            cell.addView(text(Ha.conditionText(p.condition), 14, 0xB3FFFFFF, false));
            LinearLayout hl = row();
            hl.setGravity(Gravity.CENTER);
            hl.addView(text(Weather.deg(p.temp), 22, 0xFFFFFFFF, false));
            TextView lo = text("  " + Weather.deg(p.low), 22, 0x80FFFFFF, false);
            hl.addView(lo);
            cell.addView(hl);
            String extra = precip(p, n.precipUnit);
            if (!Double.isNaN(p.wind)) extra = (extra.isEmpty() ? "" : extra + " · ") + Math.round(p.wind) + " " + n.windUnit;
            cell.addView(text(extra, 13, 0xFF7FC4F0, false));
            if (n5 > 1) {
                View line = new View(this);
                line.setBackgroundColor(0x1FFFFFFF);
                days.addView(line, new LinearLayout.LayoutParams(px(1), ViewGroup.LayoutParams.MATCH_PARENT));
            }
            days.addView(cell, weight());
        }
        if (n5 == 0) days.addView(text("No daily forecast from this weather entity.", 16, 0x99FFFFFF, false));
        root.addView(card(days));

        if (!n.attribution.isEmpty()) {
            TextView credit = text(n.attribution, 12, 0x66FFFFFF, false);
            credit.setPadding(0, px(10), 0, 0);
            root.addView(credit);
        }
    }

    private static String precip(Weather.Point p, String unit) {
        if (!Double.isNaN(p.precipChance) && p.precipChance > 0) return Math.round(p.precipChance) + "%";
        if (!Double.isNaN(p.precip) && p.precip > 0) return Ha.number(p.precip) + " " + unit;
        return "";
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v) + "%";
    }

    private static String wind(double speed, String unit, double bearing) {
        if (Double.isNaN(speed)) return "—";
        String dir = Ha.compass(bearing);
        return Math.round(speed) + " " + unit + (dir.isEmpty() ? "" : " " + dir);
    }

    private View detail(String label, String value) {
        LinearLayout c = column();
        c.setPadding(px(22), px(2), px(0), px(2));
        c.setMinimumWidth(px(150));
        c.addView(text(label, 14, 0x99FFFFFF, false));
        c.addView(text(value, 22, 0xFFFFFFFF, false));
        return c;
    }

    private TextView heading(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 14, 0x99FFFFFF, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(px(4), px(12), 0, px(6));
        return t;
    }

    private View card(View content) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14FFFFFF);
        g.setCornerRadius(px(18));
        g.setStroke(px(1), 0x1FFFFFFF);
        content.setBackground(g);
        content.setPadding(px(12), px(10), px(12), px(10));
        return content;
    }

    private ImageView icon(String name, int colour, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(Icons.tinted(this, name, colour));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = px(4);
        lp.bottomMargin = px(4);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
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

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
