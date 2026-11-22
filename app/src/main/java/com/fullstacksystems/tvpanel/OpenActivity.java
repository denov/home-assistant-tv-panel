package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's OK on a chip lands here (com.gathin.tvhome.action.OPEN_WIDGET with the chip's key) and goes on to
 * that chip's screen. It also takes tvpanel://weather, tvpanel://solar, tvpanel://apex, tvpanel://cameras[/camera.x]
 * and tvpanel://screen/&lt;id&gt; (a screen the owner added), which Home Assistant sends to open a screen (tv_panel.show,
 * or by voice). One entry point, so the launcher never sees a
 * chooser. No window of its own. Messages don't come this way: opening an activity can pause what's playing, so they
 * arrive over {@link MessageService}'s connection.
 */
public class OpenActivity extends Activity {
    /** The id of a screen the owner added, for the screen that shows it. */
    static final String EXTRA_SCREEN = "screen";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // The app is in front for a moment: a chance to start the messages connection if it isn't running.
        MessageService.start(this);
        Class<?> screen = null;
        String key = null;
        String added = null;
        // The chip whose press the integration hears about, as "screen", once its screen opens.
        String opened = null;
        Uri link = getIntent().getData();
        if (link != null && "tvpanel".equals(link.getScheme())) {
            // A deep link (from Home Assistant, by voice) names the screen, not an entity. Each screen finds its own.
            String host = link.getHost();
            screen = screenFor(host);
            // tvpanel://screen/thermostat opens the screen with that id.
            if (Ha.ADDED_SCREEN.equals(host + "/")) added = link.getLastPathSegment();
            // tvpanel://cameras/camera.driveway opens that camera full screen.
            String cam = link.getLastPathSegment();
            if (Ha.CAMERAS.equals(host) && cam != null && cam.startsWith("camera.")) {
                screen = CameraViewActivity.class;
                key = cam;
            }
        } else {
            key = getIntent().getStringExtra(WeatherActivity.EXTRA_KEY);
            String chipScreen = key == null ? "" : screenOfChip(key);
            Settings.Ok ok = key == null ? null : SettingsSource.cached().oks.get(key);
            // A press the integration carries out (a toggle, a script, an action): Home stays on screen.
            if (Ha.PRESS.equals(chipScreen)) {
                String k = key;
                Runnable go = () -> WidgetProvider.press(getApplicationContext(), k);
                // Not knowing whether it should ask (no settings yet), it asks: it may be a garage door.
                if (ok == null || ok.confirm) confirm(key, go);
                else go.run();
                finish();
                return;
            }
            // Without the integration's settings: the garage door chip has no screen, and OK twice within CONFIRM_MS
            // toggles the door.
            if (key != null && key.startsWith("cover.") && Ha.TOGGLE.equals(chipScreen)) {
                String k = key;
                confirm(key, () -> WidgetProvider.toggle(getApplicationContext(), k));
                finish();
                return;
            }
            // A chip set to do nothing: only the integration's event, for automations.
            if (Ha.EVENT_ONLY.equals(chipScreen)) {
                WidgetProvider.event(getApplicationContext(), key, Ha.DID_NOTHING);
                finish();
                return;
            }
            // A screen: the TV opens it, and tells the integration, so automations see the press too (below, once
            // there's a screen to open). Only the event: what the chip is set to in the integration now is not done.
            if (ok != null) opened = key;
            if (chipScreen.startsWith(Ha.CAMERA_SCREEN)) {
                screen = CameraViewActivity.class;
                key = chipScreen.substring(Ha.CAMERA_SCREEN.length());
            } else if (chipScreen.startsWith(Ha.ADDED_SCREEN)) {
                added = chipScreen.substring(Ha.ADDED_SCREEN.length());
            } else {
                screen = key == null ? null : screenFor(chipScreen);
            }
        }
        if (added != null) screen = addedScreen(added);
        // Every press is an event: "none" when there's nothing this app can open (a type it doesn't know yet).
        if (opened != null) {
            WidgetProvider.event(getApplicationContext(), opened, screen == null ? Ha.DID_NOTHING : Ha.OPENED_SCREEN);
        }
        if (screen == null) { finish(); return; }
        Intent go = new Intent(this, screen).putExtra(WeatherActivity.EXTRA_KEY, key);
        if (added != null) go.putExtra(EXTRA_SCREEN, added);
        // Voice commands arrive one after another ("show weather", then "show solar"): close the screens already
        // open first, so they don't pile up and one Back still returns Home. The status screen is left alone.
        if (link != null) closeOtherScreens();
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

    private static Class<?> screenFor(String screen) {
        if (Ha.SETUP.equals(screen)) return StatusActivity.class;
        if (Ha.WEATHER.equals(screen)) return WeatherActivity.class;
        if (Ha.SOLAR_SCREEN.equals(screen)) return SolarActivity.class;
        if (Ha.APEX.equals(screen)) return ApexActivity.class;
        if (Ha.CAMERAS.equals(screen)) return CameraActivity.class;
        return null;
    }

    /**
     * The activity for a screen the owner added, by its type: from the TV's list, or from its id when the app was
     * restarted and hasn't read the list yet. The screen reads its own settings by id.
     */
    private static Class<?> addedScreen(String id) {
        if (id.isEmpty()) return null;
        String type = Ha.screenType(SettingsSource.cached(), id);
        if (Ha.THERMOSTAT.equals(type)) return ThermostatActivity.class;
        if (Ha.AIR_QUALITY.equals(type)) return AirQualityActivity.class;
        return null;
    }

    /** The screen the chip says it opens; when the app was restarted and has no chips yet, a guess from the id. */
    static String screenOfChip(String key) {
        for (Ha.Widget w : WidgetProvider.current()) if (w.key.equals(key)) return w.screen;
        return Ha.guessScreen(key);
    }

    /** The screens that are open now (weather, solar, tank, cameras, thermostats, air quality). They add themselves in onCreate and leave in onDestroy. */
    static final List<Activity> SCREENS = new ArrayList<>();

    private static void closeOtherScreens() {
        for (Activity a : new ArrayList<>(SCREENS)) a.finish();
    }
}
