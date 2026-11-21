package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's OK on a chip lands here (com.gathin.tvhome.action.OPEN_WIDGET with the chip's key) and goes on to
 * that chip's page. It also takes tvpanel://weather, tvpanel://solar, tvpanel://apex and tvpanel://cameras[/camera.x],
 * which Home Assistant sends to open a page (tv_panel.show, or by voice), and tvpanel://notify?message=...&title=...,
 * a message from the TV's notify entity. One entry point, so the launcher never sees a chooser. No window of its own.
 */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Class<?> page = null;
        String key = null;
        Uri link = getIntent().getData();
        if (link != null && "tvpanel".equals(link.getScheme()) && Ha.NOTIFY.equals(link.getHost())) {
            // A message from Home Assistant: a toast over whatever is on screen, which keeps playing. No page opens
            // or closes.
            String text = Ha.notifyText(link.getQueryParameter("title"), link.getQueryParameter("message"));
            if (!text.isEmpty()) {
                android.widget.Toast.makeText(getApplicationContext(), text, android.widget.Toast.LENGTH_LONG).show();
            }
            finish();
            return;
        }
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
            String chipPage = key == null ? "" : pageOfChip(key);
            Settings.Ok ok = key == null ? null : SettingsSource.cached().oks.get(key);
            // A press the integration carries out (a toggle, a script, an action): Home stays on screen.
            if (Ha.PRESS.equals(chipPage)) {
                String k = key;
                Runnable go = () -> WidgetProvider.press(getApplicationContext(), k);
                // Not knowing whether it should ask (no settings yet), it asks: it may be a garage door.
                if (ok == null || ok.confirm) confirm(key, go);
                else go.run();
                finish();
                return;
            }
            // Without the integration's settings: the garage door chip has no page, and OK twice within CONFIRM_MS
            // toggles the door.
            if (key != null && key.startsWith("cover.") && Ha.TOGGLE.equals(chipPage)) {
                String k = key;
                confirm(key, () -> WidgetProvider.toggle(getApplicationContext(), k));
                finish();
                return;
            }
            // A chip set to do nothing: only the integration's event, for automations.
            if (Ha.EVENT_ONLY.equals(chipPage)) {
                WidgetProvider.event(getApplicationContext(), key, "none");
                finish();
                return;
            }
            // A page: the TV opens it, and tells the integration, so automations see the press too. Only the
            // event: what the chip is set to in the integration now is not carried out.
            if (ok != null) WidgetProvider.event(getApplicationContext(), key, "page");
            if (chipPage.startsWith(Ha.CAMERA_PAGE)) {
                page = CameraViewActivity.class;
                key = chipPage.substring(Ha.CAMERA_PAGE.length());
            } else {
                page = key == null ? null : pageFor(chipPage);
            }
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

    /** The first OK only asks ("Press OK again to open the garage"); the second, in time, does it. */
    private void confirm(String key, Runnable action) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(armedKey) && now - armedAt < CONFIRM_MS) {
            armedKey = null;
            action.run();
            return;
        }
        armedKey = key;
        armedAt = now;
        String ask = "Press OK again";
        for (Ha.Widget w : WidgetProvider.current()) {
            if (!w.key.equals(key)) continue;
            String label = w.label.toLowerCase(java.util.Locale.ROOT);
            boolean garage = w.icon.startsWith("garage");
            ask = garage ? "Press OK again to " + ("garage".equals(w.icon) ? "open" : "close") + " the " + label
                    : "Press OK again: " + w.label;
        }
        android.widget.Toast.makeText(getApplicationContext(), ask, android.widget.Toast.LENGTH_SHORT).show();
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
        return Ha.guessPage(key);
    }

    /** The weather, solar, Apex and camera pages that are open now. They add themselves in onCreate and leave in onDestroy. */
    static final List<Activity> PAGES = new ArrayList<>();

    private static void closeOtherPages() {
        for (Activity a : new ArrayList<>(PAGES)) a.finish();
    }
}
