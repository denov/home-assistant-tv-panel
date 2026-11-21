package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's OK on a chip lands here (com.gathin.tvhome.action.OPEN_WIDGET with the chip's key) and goes on to
 * that chip's page. It also takes tvpanel://weather, tvpanel://solar, tvpanel://apex and tvpanel://cameras[/camera.x], which Home Assistant sends to open a
 * page by voice. One entry point, so the launcher never sees a chooser. No window of its own.
 */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Class<?> page = null;
        String key = null;
        Uri link = getIntent().getData();
        if (link != null && "tvpanel".equals(link.getScheme())) {
            // A deep link (from Home Assistant, by voice) names the page, not an entity. Each page finds its own.
            String host = link.getHost();
            page = pageFor(host);
            // tvpanel://cameras/camera.driveway opens that camera full screen.
            String cam = link.getLastPathSegment();
            if (Ha.CAMERAS.equals(host) && cam != null && cam.startsWith("camera.")) {
                page = CameraViewActivity.class;
                key = cam;
            }
        } else {
            key = getIntent().getStringExtra(WeatherActivity.EXTRA_KEY);
            // The garage door chip has no page: OK twice within CONFIRM_MS toggles the door, and Home stays on screen.
            if (key != null && key.startsWith("cover.") && Ha.TOGGLE.equals(pageOfChip(key))) {
                confirmToggle(key);
                finish();
                return;
            }
            page = key == null ? null : pageFor(pageOfChip(key));
        }
        if (page == null) { finish(); return; }
        Intent go = new Intent(this, page).putExtra(WeatherActivity.EXTRA_KEY, key);
        // Voice commands arrive one after another ("show weather", then "show solar"): close the pages already
        // open first, so they don't pile up and one Back still returns Home. The status page is left alone.
        if (link != null) closeOtherPages();
        startActivity(go);
        finish();
    }

    /** A second OK within this long confirms a toggle. */
    static final long CONFIRM_MS = 3000;
    private static String armedKey;
    private static long armedAt;

    /** The first OK only asks ("Press OK again to open the garage"); the second, in time, toggles the door. */
    private void confirmToggle(String key) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(armedKey) && now - armedAt < CONFIRM_MS) {
            armedKey = null;
            WidgetProvider.toggle(getApplicationContext(), key);
            return;
        }
        armedKey = key;
        armedAt = now;
        String label = "the door";
        boolean shut = false;
        for (Ha.Widget w : WidgetProvider.current()) {
            if (w.key.equals(key)) {
                label = "the " + w.label.toLowerCase(java.util.Locale.ROOT);
                shut = "garage".equals(w.icon);
            }
        }
        android.widget.Toast.makeText(getApplicationContext(),
                "Press OK again to " + (shut ? "open " : "close ") + label, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static Class<?> pageFor(String page) {
        if (Ha.WEATHER.equals(page)) return WeatherActivity.class;
        if (Ha.SOLAR_PAGE.equals(page)) return SolarActivity.class;
        if (Ha.APEX.equals(page)) return ApexActivity.class;
        if (Ha.CAMERAS.equals(page)) return CameraActivity.class;
        return null;
    }

    /** The page the chip says it opens; when the app was restarted and has no chips yet, a guess from the id. */
    static String pageOfChip(String key) {
        for (Ha.Widget w : WidgetProvider.current()) if (w.key.equals(key)) return w.page;
        if (key.startsWith("weather.")) return Ha.WEATHER;
        if (key.equals(Ha.CAMERAS) || key.startsWith("camera.")) return Ha.CAMERAS;
        if (key.equals(Ha.APEX) || key.startsWith("sensor.apex_")) return Ha.APEX;
        if (key.startsWith("sensor.")) return Ha.SOLAR_PAGE;
        return "";
    }

    /** The weather, solar, Apex and camera pages that are open now. They add themselves in onCreate and leave in onDestroy. */
    static final List<Activity> PAGES = new ArrayList<>();

    private static void closeOtherPages() {
        for (Activity a : new ArrayList<>(PAGES)) a.finish();
    }
}
