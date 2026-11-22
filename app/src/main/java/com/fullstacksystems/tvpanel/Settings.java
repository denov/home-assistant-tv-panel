package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TV's settings from the TV Panel integration (GET /api/tv_panel/config): the chips in order, the camera grid's
 * order, the solar layout, the widget row switch, and (0.7.0) the TV's screens. A null field is one the integration doesn't set (Configure was
 * never saved, or there is no integration): then the app keeps using the tv_widget label and the old helpers.
 * Pure Java, so the JVM tests cover it.
 */
final class Settings {
    /** The screens every TV had before 0.7.0, and has when the integration sends no list. Before NONE, which uses it. */
    static final List<Screen> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            new Screen("weather", "weather", "", null, null), new Screen("solar", "solar", "", null, null),
            new Screen("tank", "tank", "", null, null), new Screen("cameras", "cameras", "", null, null)));
    /** No integration (an older setup with a pushed token): everything the old way. */
    static final Settings NONE = new Settings(null, null, null, null, Collections.emptyMap(), false, false,
            Collections.emptyMap(), null);

    final List<String> entities;
    final List<String> cameraOrder;
    /** The solar layout as JSON text, as Config.solarLayout() holds it. */
    final String solar;
    final Boolean row;
    /**
     * The chips' own labels, by chip key: an entity id, {@link Ha#CAMERAS} or {@link Ha#APEX}. A chip without one
     * uses its entity's name.
     */
    final Map<String, String> labels;
    /** 0.3.0: {@link #cameraOrder} is the whole grid, so the camera screens use it rather than the chips' list. */
    final boolean ownCameras;
    /** 0.3.0: the tank's columns come in a set order, so they're not sorted by label. */
    final boolean tankOrdered;
    /** 0.4.0: what OK on each chip does, by chip key, as the integration worked it out. Empty: the app decides. */
    final Map<String, Ok> oks;
    /** 0.7.0: the TV's screens in order: the built-in ones it kept, and thermostat and air quality ones. */
    final List<Screen> screens;

    /**
     * One of the TV's screens. Its id is made once, from its type, when it's added ("thermostat", "thermostat_2"),
     * and stays when it's renamed. A built-in screen's id is its type, and it has no title or entities.
     */
    static final class Screen {
        final String id;
        final String type;
        final String title;
        /** A thermostat screen's thermostats (climate entities). */
        final List<String> climates;
        /** A thermostat screen's other readings, or an air quality screen's sensors. */
        final List<String> sensors;

        Screen(String id, String type, String title, List<String> climates, List<String> sensors) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.climates = climates == null ? Collections.emptyList() : climates;
            this.sensors = sensors == null ? Collections.emptyList() : sensors;
        }
    }

    /**
     * OK on a chip: "none", "screen" (with screen, and camera for one camera), or a press: "toggle", "run", "perform".
     * Integrations before 0.7.0 said "page" for a screen, and 0.7.0 still does, with "screen" alongside, for apps
     * before 0.7.0.
     */
    static final class Ok {
        final String action;
        final String screen;
        final String camera;
        final boolean confirm;

        Ok(String action, String screen, String camera, boolean confirm) {
            this.action = action;
            this.screen = screen;
            this.camera = camera;
            this.confirm = confirm;
        }

        /** A screen to open: what 0.7.0 says, what integrations before it said, or (0.7.0) a screen older apps lack. */
        boolean isScreen() {
            return "screen".equals(action) || "page".equals(action) || ("none".equals(action) && !screen.isEmpty());
        }

        boolean isPress() {
            return "toggle".equals(action) || "run".equals(action) || "perform".equals(action);
        }
    }

    Settings(List<String> entities, List<String> cameraOrder, String solar, Boolean row, Map<String, String> labels,
            boolean ownCameras, boolean tankOrdered, Map<String, Ok> oks, List<Screen> screens) {
        this.entities = entities;
        this.cameraOrder = cameraOrder;
        this.solar = solar;
        this.row = row;
        this.labels = labels;
        this.ownCameras = ownCameras;
        this.tankOrdered = tankOrdered;
        this.oks = oks;
        this.screens = screens == null ? BUILT_IN : screens;
    }

    /** The screen with this id, or null when the TV has none. */
    Screen screen(String id) {
        for (Screen s : screens) if (s.id.equals(id)) return s;
        return null;
    }

    static Settings parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONObject solar = o.optJSONObject("solar");
        // 0.3.0 sends "cameras" (the grid's order and members); 0.2.0 sent "camera_order".
        List<String> cameras = list(o, "cameras");
        return new Settings(list(o, "entities"), cameras != null ? cameras : list(o, "camera_order"),
                solar == null ? null : solar.toString(),
                o.isNull("row") || !o.has("row") ? null : o.optBoolean("row", true), labels(o.optJSONArray("chips")),
                cameras != null, o.optJSONArray("tank") != null, oks(o.optJSONArray("chips")),
                screens(o.optJSONArray("screens")));
    }

    private static List<Screen> screens(JSONArray a) {
        if (a == null) return null;
        List<Screen> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null || s.optString("id").isEmpty()) continue;
            out.add(new Screen(s.optString("id"), s.optString("type"), s.optString("title"), list(s, "climates"),
                    list(s, "sensors")));
        }
        return Collections.unmodifiableList(out);
    }

    private static String key(JSONObject chip) {
        String type = chip.optString("type");
        return "entity".equals(type) ? chip.optString("entity") : "tank".equals(type) ? Ha.APEX
                : "cameras".equals(type) ? Ha.CAMERAS : "";
    }

    private static Map<String, Ok> oks(JSONArray chips) {
        Map<String, Ok> out = new HashMap<>();
        if (chips == null) return Collections.unmodifiableMap(out);
        for (int i = 0; i < chips.length(); i++) {
            JSONObject c = chips.optJSONObject(i);
            JSONObject ok = c == null ? null : c.optJSONObject("ok");
            if (ok == null || key(c).isEmpty()) continue;
            String screen = ok.optString("screen", ok.optString("page"));
            out.put(key(c), new Ok(ok.optString("action"), screen, ok.optString("camera"), ok.optBoolean("confirm")));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> labels(JSONArray chips) {
        Map<String, String> out = new HashMap<>();
        if (chips == null) return Collections.unmodifiableMap(out);
        for (int i = 0; i < chips.length(); i++) {
            JSONObject c = chips.optJSONObject(i);
            if (c == null) continue;
            String label = c.optString("label", "").trim();
            if (label.isEmpty()) continue;
            String key = key(c);
            if (!key.isEmpty()) out.put(key, label);
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<String> list(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    /** The solar layout to draw: the integration's, else the one pushed in config.json (or "" for rows). */
    String solarLayout(String pushed) {
        return solar != null ? solar : pushed;
    }

    /** The template variables for what's set: {"entities": [...], "row": bool, "camera_order": "a,b"}. */
    JSONObject variables(boolean withCameraOrder) throws JSONException {
        JSONObject v = new JSONObject();
        List<String> list = withCameraOrder && ownCameras ? cameraOrder : entities;
        if (list != null) v.put("entities", new JSONArray(list));
        if (row != null) v.put("row", row.booleanValue());
        if (withCameraOrder && cameraOrder != null) v.put("camera_order", String.join(",", cameraOrder));
        return v;
    }

    /** A template request: {"template": ..., "variables": {...}} when anything is set. */
    static String body(String template, Settings s, boolean withCameraOrder) {
        try {
            JSONObject b = new JSONObject().put("template", template);
            JSONObject v = s.variables(withCameraOrder);
            if (v.length() > 0) b.put("variables", v);
            return b.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
