package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TV's settings from the TV Panel integration (GET /api/tv_panel/config): the chips in order, the camera grid's
 * order, the solar layout, and the widget row switch. A null field is one the integration doesn't set (Configure was
 * never saved, or there is no integration): then the app keeps using the tv_widget label and the old helpers.
 * Pure Java, so the JVM tests cover it.
 */
final class Settings {
    /** No integration (an older setup with a pushed token): everything the old way. */
    static final Settings NONE = new Settings(null, null, null, null);

    final List<String> entities;
    final List<String> cameraOrder;
    /** The solar layout as JSON text, as Config.solarLayout() holds it. */
    final String solar;
    final Boolean row;

    Settings(List<String> entities, List<String> cameraOrder, String solar, Boolean row) {
        this.entities = entities;
        this.cameraOrder = cameraOrder;
        this.solar = solar;
        this.row = row;
    }

    static Settings parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONObject solar = o.optJSONObject("solar");
        return new Settings(list(o, "entities"), list(o, "camera_order"), solar == null ? null : solar.toString(),
                o.isNull("row") || !o.has("row") ? null : o.optBoolean("row", true));
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
        if (entities != null) v.put("entities", new JSONArray(entities));
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
