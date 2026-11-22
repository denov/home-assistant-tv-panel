package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The thermostat screen's data: any climate entity, read only through Home Assistant's standard climate attributes, so
 * an ecobee, a Nest, a Z-Wave thermostat or a generic_thermostat all work. Temperatures are in Home Assistant's own
 * unit ({@link Data#unit}). Pure Java, so the JVM tests cover it.
 */
final class Thermostat {
    static final int HEATING = Ha.ORANGE;
    static final int COOLING = Ha.BLUE;
    static final int DRYING = 0xFF4DD0E1;
    static final int FAN = 0xFFB0BEC5;
    static final int IDLE = Ha.GREY;

    private Thermostat() {
    }

    /** One thermostat as Home Assistant has it now. A number it doesn't give is NaN; a word it doesn't give is "". */
    static final class Climate {
        final String id;
        final String name;
        /** The HVAC mode: off, heat, cool, heat_cool, auto, dry, fan_only; or unknown/unavailable. */
        final String state;
        final double cur, temp, lo, hi, humidity, targetHumidity;
        final String action, fan, preset;

        Climate(String id, String name, String state, JSONObject a) {
            this.id = id;
            this.name = name;
            this.state = state;
            cur = num(a, "current_temperature");
            temp = num(a, "temperature");
            lo = num(a, "target_temp_low");
            hi = num(a, "target_temp_high");
            humidity = num(a, "current_humidity");
            targetHumidity = num(a, "target_humidity");
            action = Ha.str(a, "hvac_action");
            fan = Ha.str(a, "fan_mode");
            preset = Ha.str(a, "preset_mode");
        }

        /** Home Assistant doesn't have it, or can't reach it. */
        boolean missing() {
            return "unknown".equals(state) || "unavailable".equals(state);
        }
    }

    /** One of the screen's other readings: another room, or outside. */
    static final class Reading {
        final String id, name, state, unit, dc;

        Reading(JSONObject o) {
            id = Ha.str(o, "id");
            String n = Ha.str(o, "name");
            name = n.isEmpty() ? fromId(id) : n;
            state = Ha.str(o, "state");
            unit = Ha.str(o, "unit");
            dc = Ha.str(o, "dc");
        }

        boolean ok() {
            return Ha.isNumber(state);
        }
    }

    static final class Data {
        final String title;
        /** Home Assistant's temperature unit, "°F" or "°C": every climate attribute is in it. */
        final String unit;
        final List<Climate> climates;
        final List<Reading> sensors;

        Data(String title, String unit, List<Climate> climates, List<Reading> sensors) {
            this.title = title;
            this.unit = unit;
            this.climates = climates;
            this.sensors = sensors;
        }
    }

    static Data parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<Climate> climates = new ArrayList<>();
        JSONArray cs = o.optJSONArray("climates");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            String id = Ha.str(c, "id");
            String name = Ha.str(c, "name");
            JSONObject a = c.optJSONObject("attributes");
            climates.add(new Climate(id, name.isEmpty() ? fromId(id) : name, Ha.str(c, "state"),
                    a == null ? new JSONObject() : a));
        }
        List<Reading> sensors = new ArrayList<>();
        JSONArray ss = o.optJSONArray("sensors");
        for (int i = 0; ss != null && i < ss.length(); i++) {
            JSONObject s = ss.optJSONObject(i);
            if (s != null) sensors.add(new Reading(s));
        }
        return new Data(Ha.str(o, "title"), Ha.str(o, "unit"), climates, sensors);
    }

    /**
     * Where it is now, and where that reading comes from: {"71.5°", ""} from the thermostat itself. Without one, the
     * screen's first temperature sensor stands in, by name: {"68.4°", "Hall Temperature"}. {"", ""} with neither.
     */
    static String[] current(Data d, Climate c) {
        if (!Double.isNaN(c.cur)) return new String[] {deg(c.cur), ""};
        for (Reading r : d.sensors) {
            if (!"temperature".equals(r.dc) || !r.ok()) continue;
            String v = Ha.reading(r.state) + (r.unit.equals(d.unit) ? "°" : " " + r.unit);
            return new String[] {v, r.name};
        }
        return new String[] {"", ""};
    }

    /** Where it's set: "68–76°" for a range, "70°" for one setpoint, "" when it's off or there's none. */
    static String target(Climate c) {
        if ("off".equals(c.state) || c.missing()) return "";
        if (!Double.isNaN(c.lo) && !Double.isNaN(c.hi)) return Ha.number(c.lo) + "–" + deg(c.hi);
        return Double.isNaN(c.temp) ? "" : deg(c.temp);
    }

    /** The mode in words: "Heat/Cool", "Fan only", "Off". */
    static String mode(Climate c) {
        switch (c.state) {
            case "heat_cool": return "Heat/Cool";
            case "fan_only": return "Fan only";
            case "unknown": return "Not in Home Assistant";
            case "": return "";
            default: return Ha.capitalise(c.state.replace('_', ' '));
        }
    }

    /** What it's doing now ("Heating", "Idle"), or "" when the integration doesn't say. Off when it's off. */
    static String doing(Climate c) {
        String a = "off".equals(c.state) ? "off" : c.action;
        return Ha.capitalise(a.replace('_', ' '));
    }

    /** Orange heating, blue cooling, cyan drying, light grey for the fan alone, grey idle, off or not known. */
    static int colour(Climate c) {
        return colourOf("off".equals(c.state) ? "off" : c.action);
    }

    static int colourOf(String action) {
        switch (action) {
            case "heating":
            case "preheating":
            case "defrosting":
                return HEATING;
            case "cooling": return COOLING;
            case "drying": return DRYING;
            case "fan": return FAN;
            default: return IDLE;
        }
    }

    /** The rows under the readings, only those the thermostat has: humidity (and its target), fan and preset. */
    static List<String[]> details(Climate c) {
        List<String[]> out = new ArrayList<>();
        if (!Double.isNaN(c.humidity)) {
            String h = Ha.number(c.humidity) + "%";
            if (!Double.isNaN(c.targetHumidity)) h += " → " + Ha.number(c.targetHumidity) + "%";
            out.add(new String[] {"Humidity", h});
        }
        if (!c.fan.isEmpty()) out.add(new String[] {"Fan", words(c.fan)});
        if (!c.preset.isEmpty()) out.add(new String[] {"Preset", words(c.preset)});
        return out;
    }

    // ---- the last 24 hours ---------------------------------------------------------------------------------------

    /** One moment in the thermostat's history: where it was, the target band (NaN when off), and what it was doing. */
    static final class Point {
        final long t;
        final double cur, lo, hi;
        final String action;

        Point(long t, double cur, double lo, double hi, String action) {
            this.t = t;
            this.cur = cur;
            this.lo = lo;
            this.hi = hi;
            this.action = action;
        }
    }

    /** A stretch of heating or cooling, drawn along the bottom of the graph in its colour. */
    static final class Span {
        final long from, to;
        final int colour;

        Span(long from, long to, int colour) {
            this.from = from;
            this.to = to;
            this.colour = colour;
        }
    }

    /**
     * history/history_during_period's rows for one thermostat ({"s": state, "a": attributes, "lu": seconds}), oldest
     * first. A row without attributes keeps the last ones. One setpoint is a band of one.
     */
    static List<Point> history(JSONArray rows) {
        List<Point> out = new ArrayList<>();
        if (rows == null) return out;
        JSONObject last = new JSONObject();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            long t = Math.round(r.optDouble("lu", r.optDouble("lc", Double.NaN)) * 1000);
            if (t <= 0) continue;
            JSONObject a = r.optJSONObject("a");
            if (a != null) last = a;
            String state = Ha.str(r, "s");
            boolean gone = "unknown".equals(state) || "unavailable".equals(state);
            boolean off = "off".equals(state) || gone;
            double cur = gone ? Double.NaN : num(last, "current_temperature");
            double lo = num(last, "target_temp_low"), hi = num(last, "target_temp_high");
            if (Double.isNaN(lo) || Double.isNaN(hi)) lo = hi = num(last, "temperature");
            if (off) lo = hi = Double.NaN;
            String action = gone ? "" : "off".equals(state) ? "off" : Ha.str(last, "hvac_action");
            out.add(new Point(t, cur, lo, hi, action));
        }
        return out;
    }

    /** The stretches of heating and cooling (and drying), each until the next change; the last until {@code to}. */
    static List<Span> spans(List<Point> points, long to) {
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            int colour = colourOf(p.action);
            if (colour == IDLE || colour == FAN) continue;
            long end = i + 1 < points.size() ? points.get(i + 1).t : to;
            Span prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && prev.colour == colour && prev.to == p.t) out.set(out.size() - 1, new Span(prev.from, end, colour));
            else out.add(new Span(p.t, end, colour));
        }
        return Collections.unmodifiableList(out);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** "71.5°", "70°". */
    static String deg(double v) {
        return Ha.number(v) + "°";
    }

    private static double num(JSONObject a, String key) {
        if (a == null || a.isNull(key)) return Double.NaN;
        Object v = a.opt(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return v == null ? Double.NaN : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "auto" → "Auto", "eco_plus" → "Eco plus". */
    private static String words(String s) {
        return Ha.capitalise(s.replace('_', ' '));
    }

    /** "climate.garage_heater" → "Garage heater": for an entity Home Assistant doesn't have. */
    static String fromId(String id) {
        return Ha.capitalise(id.substring(id.indexOf('.') + 1).replace('_', ' ').toLowerCase(Locale.ROOT));
    }
}
