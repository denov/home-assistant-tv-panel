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
        /** Its room (the entity's area, or its device's), or "". */
        final String area;
        /** The HVAC mode: off, heat, cool, heat_cool, auto, dry, fan_only; or unknown/unavailable. */
        final String state;
        final double cur, temp, lo, hi, humidity, targetHumidity, min, max;
        final String action, fan, preset;
        /** The modes it offers (hvac_modes), empty when it doesn't say. */
        final List<String> modes = new ArrayList<>();

        Climate(String id, String name, String area, String state, JSONObject a) {
            this.id = id;
            this.name = name;
            this.area = area;
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
            min = num(a, "min_temp");
            max = num(a, "max_temp");
            JSONArray m = a.optJSONArray("hvac_modes");
            for (int i = 0; m != null && i < m.length(); i++) modes.add(m.optString(i));
        }

        /** What the card is called: the room it's in, else its name. */
        String title() {
            return area.isEmpty() ? name : area;
        }

        /** Its name under the room, or "" when the name is the title. */
        String subtitle() {
            return area.isEmpty() ? "" : name;
        }

        /** Home Assistant doesn't have it, or can't reach it. */
        boolean missing() {
            return "unknown".equals(state) || "unavailable".equals(state);
        }
    }

    /** One of the screen's other readings: another room, or outside. Its name is without its room, as on a tile. */
    static final class Reading {
        final String id, area, name, state, unit, dc;

        Reading(JSONObject o) {
            id = Ha.str(o, "id");
            area = Ha.str(o, "area");
            // The label (the name without its room and device), else the whole name.
            String n = Ha.str(o, "label");
            if (n.isEmpty()) n = Ha.str(o, "name");
            name = n.isEmpty() ? fromId(id) : n;
            state = Ha.str(o, "state");
            unit = Ha.str(o, "unit");
            dc = Ha.str(o, "dc");
        }

        /** "Hall · Temperature": the room first; the name alone without one. */
        String title() {
            return area.isEmpty() ? name : area + " · " + name;
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
        /** The readings' tiles, as indexes into {@code sensors}: several on a tile when the settings join them. */
        final List<List<Integer>> tiles;

        Data(String title, String unit, List<Climate> climates, List<Reading> sensors, List<List<Integer>> tiles) {
            this.title = title;
            this.unit = unit;
            this.climates = climates;
            this.sensors = sensors;
            this.tiles = tiles;
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
            climates.add(new Climate(id, name.isEmpty() ? fromId(id) : name, Ha.str(c, "area"), Ha.str(c, "state"),
                    a == null ? new JSONObject() : a));
        }
        List<Reading> sensors = new ArrayList<>();
        JSONArray ss = o.optJSONArray("sensors");
        for (int i = 0; ss != null && i < ss.length(); i++) {
            JSONObject s = ss.optJSONObject(i);
            if (s != null) sensors.add(new Reading(s));
        }
        List<String> ids = new ArrayList<>();
        for (Reading r : sensors) ids.add(r.id);
        return new Data(Ha.str(o, "title"), Ha.str(o, "unit"), climates, sensors, Tiles.group(ids, o.optJSONArray("tiles")));
    }

    /**
     * Where it is now, and where that reading comes from: {"71.5°", ""} from the thermostat itself. Without one, the
     * screen's first temperature sensor stands in, by name: {"68.4°", "Hall · Temperature"}. {"", ""} with neither.
     */
    static String[] current(Data d, Climate c) {
        if (!Double.isNaN(c.cur)) return new String[] {deg(c.cur), ""};
        for (Reading r : d.sensors) {
            if (!"temperature".equals(r.dc) || !r.ok()) continue;
            String v = Ha.reading(r.state) + (r.unit.equals(d.unit) ? "°" : " " + r.unit);
            return new String[] {v, r.title()};
        }
        return new String[] {"", ""};
    }

    /**
     * The room's temperature as a number in Home Assistant's unit, for the dial: the thermostat's own, else the reading
     * {@link #current} stands in with, when it's in that unit. NaN otherwise.
     */
    static double room(Data d, Climate c) {
        if (!Double.isNaN(c.cur)) return c.cur;
        for (Reading r : d.sensors) {
            if (!"temperature".equals(r.dc) || !r.ok()) continue;
            return r.unit.equals(d.unit) ? Double.parseDouble(r.state) : Double.NaN;
        }
        return Double.NaN;
    }

    /** Where it's set: "68–76°" for a range, "70°" for one setpoint, "" when it's off or there's none. */
    static String target(Climate c) {
        if ("off".equals(c.state) || c.missing()) return "";
        if (!Double.isNaN(c.lo) && !Double.isNaN(c.hi)) return Ha.number(c.lo) + "–" + deg(c.hi);
        return Double.isNaN(c.temp) ? "" : deg(c.temp);
    }

    /** A run of text in one colour. */
    static final class Part {
        final String text;
        final int colour;

        Part(String text, int colour) {
            this.text = text;
            this.colour = colour;
        }
    }

    /**
     * The setting, for under the room's temperature on the dial, each setpoint in its colour: "68° – 76°", "Heat to
     * 68°", "Cool to 76°", "Set to 70°"; "Off"; nothing when it isn't known.
     */
    static List<Part> setting(Climate c) {
        List<Part> out = new ArrayList<>();
        if (c.missing() || c.state.isEmpty()) return out;
        double[] t = targets(c);
        if (!Double.isNaN(t[0]) && !Double.isNaN(t[1])) {
            out.add(new Part(deg(t[0]), HEATING));
            out.add(new Part(" – ", SolarViews.SUB));
            out.add(new Part(deg(t[1]), COOLING));
        } else if (!Double.isNaN(t[0])) {
            out.add(new Part("Heat to ", SolarViews.SUB));
            out.add(new Part(deg(t[0]), HEATING));
        } else if (!Double.isNaN(t[1])) {
            out.add(new Part("Cool to ", SolarViews.SUB));
            out.add(new Part(deg(t[1]), COOLING));
        } else if (!Double.isNaN(t[2])) {
            out.add(new Part("Set to ", SolarViews.SUB));
            out.add(new Part(deg(t[2]), 0xFFFFFFFF));
        } else if ("off".equals(c.state)) {
            out.add(new Part("Off", SolarViews.SUB));
        }
        return out;
    }

    /**
     * Where it heats to, cools to, or (auto, dry, fan only with one setpoint) is just held at: {heat, cool, other}, NaN
     * where there's none. A range heats to its low end and cools to its high end. Nothing when it's off or not known.
     */
    static double[] targets(String mode, double temp, double lo, double hi) {
        double[] t = {Double.NaN, Double.NaN, Double.NaN};
        if ("off".equals(mode) || "unknown".equals(mode) || "unavailable".equals(mode) || mode.isEmpty()) return t;
        // Heat or cool with a setpoint uses it, even beside a range some integrations keep.
        if ("heat".equals(mode) && !Double.isNaN(temp)) {
            t[0] = temp;
        } else if ("cool".equals(mode) && !Double.isNaN(temp)) {
            t[1] = temp;
        } else if (!Double.isNaN(lo) && !Double.isNaN(hi)) {
            t[0] = lo;
            t[1] = hi;
        } else {
            t[2] = temp;
        }
        return t;
    }

    static double[] targets(Climate c) {
        return targets(c.state, c.temp, c.lo, c.hi);
    }

    /**
     * The dial's scale, {low, high}: the thermostat's own min_temp to max_temp, or a usual thermostat's range in Home
     * Assistant's unit without one; widened, to whole degrees, for a room or a setpoint outside it.
     */
    static double[] dial(Climate c, String unit) {
        return dial(c, unit, c.cur);
    }

    /** {@link #dial(Climate, String)} widened for {@code room}, the temperature the dial points at. */
    static double[] dial(Climate c, String unit, double room) {
        boolean ok = !Double.isNaN(c.min) && !Double.isNaN(c.max) && c.min < c.max;
        boolean celsius = "°C".equals(unit);
        double lo = ok ? c.min : celsius ? 10 : 50, hi = ok ? c.max : celsius ? 32 : 90;
        double[] t = targets(c);
        for (double v : new double[] {room, t[0], t[1], t[2]}) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, Math.floor(v));
            hi = Math.max(hi, Math.ceil(v));
        }
        return new double[] {lo, hi};
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

    /**
     * One moment in the thermostat's history: where it was, the target band (NaN when off), where it heated and cooled
     * to (see {@link #targets}), and what it was doing.
     */
    static final class Point {
        final long t;
        final double cur, lo, hi, heat, cool, other;
        final String action;

        Point(long t, double cur, double lo, double hi, double[] targets, String action) {
            this.t = t;
            this.cur = cur;
            this.lo = lo;
            this.hi = hi;
            heat = targets[0];
            cool = targets[1];
            other = targets[2];
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
            double lo = num(last, "target_temp_low"), hi = num(last, "target_temp_high"), temp = num(last, "temperature");
            double[] targets = targets(off ? "off" : state, temp, lo, hi);
            if (Double.isNaN(lo) || Double.isNaN(hi)) lo = hi = temp;
            if (off) lo = hi = Double.NaN;
            String action = gone ? "" : "off".equals(state) ? "off" : Ha.str(last, "hvac_action");
            out.add(new Point(t, cur, lo, hi, targets, action));
        }
        return out;
    }

    /**
     * A week fetched a while ago and the last hours fetched now, as one: the week's points before the first of
     * {@code recent}, then {@code recent}. Either may be null or empty.
     */
    static List<Point> join(List<Point> week, List<Point> recent) {
        if (recent == null || recent.isEmpty()) return week == null ? new ArrayList<>() : week;
        List<Point> out = new ArrayList<>();
        long from = recent.get(0).t;
        if (week != null) for (Point p : week) if (p.t < from) out.add(p);
        out.addAll(recent);
        return out;
    }

    /** The points from the one a window starting at {@code from} begins with: the last at or before it. */
    static List<Point> since(List<Point> points, long from) {
        int i = 0;
        while (i + 1 < points.size() && points.get(i + 1).t <= from) i++;
        return points.subList(i, points.size());
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

    /**
     * The spans as the graph draws them: heating (or cooling) that stops and starts again within {@code gap} is one
     * block, so short cycles don't draw as a row of thin lines. Only for drawing: runtimes use the spans themselves.
     */
    static List<Span> blocks(List<Span> spans, long gap) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            Span prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && prev.colour == s.colour && s.from - prev.to < gap) {
                out.set(out.size() - 1, new Span(prev.from, Math.max(prev.to, s.to), s.colour));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** One day of the week's summary: how warm the room got, and how long it heated and cooled. */
    static final class Day {
        /** Its local midnight. */
        final long start;
        /** Whether the history reaches it: a day before it starts has nothing to say. */
        final boolean known;
        final double low, high;
        final long heat, cool;

        Day(long start, boolean known, double low, double high, long heat, long cool) {
            this.start = start;
            this.known = known;
            this.low = low;
            this.high = high;
            this.heat = heat;
            this.cool = cool;
        }
    }

    /**
     * The last {@code n} days in {@code zone}, today (until {@code now}) last. A day's low and high are the room's
     * readings in it, with the one it carried in from the day before.
     */
    static List<Day> days(List<Point> points, List<Span> spans, long now, java.time.ZoneId zone, int n) {
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long first = points.isEmpty() ? Long.MAX_VALUE : points.get(0).t;
        List<Day> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            java.time.LocalDate d = today.minusDays(i);
            long start = d.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now);
            boolean known = end > first;
            double lo = Double.NaN, hi = Double.NaN, carried = Double.NaN;
            for (Point p : points) {
                if (p.t >= end) break;
                if (p.t <= start) {
                    carried = p.cur;
                    continue;
                }
                if (Double.isNaN(p.cur)) continue;
                lo = Double.isNaN(lo) ? p.cur : Math.min(lo, p.cur);
                hi = Double.isNaN(hi) ? p.cur : Math.max(hi, p.cur);
            }
            if (!Double.isNaN(carried)) {
                lo = Double.isNaN(lo) ? carried : Math.min(lo, carried);
                hi = Double.isNaN(hi) ? carried : Math.max(hi, carried);
            }
            out.add(new Day(start, known, known ? lo : Double.NaN, known ? hi : Double.NaN,
                    known ? runtime(spans, HEATING, start, end) : 0, known ? runtime(spans, COOLING, start, end) : 0));
        }
        return out;
    }

    /** How long the spans in {@code colour} ran between {@code from} and {@code to}. */
    static long runtime(List<Span> spans, int colour, long from, long to) {
        long ms = 0;
        for (Span s : spans) if (s.colour == colour) ms += Math.max(0, Math.min(s.to, to) - Math.max(s.from, from));
        return ms;
    }

    /** A day's runtime under its column: "0", "40 m", "1.7 h"; whole hours from 10. */
    static String brief(long ms) {
        long min = Math.round(ms / 60_000.0);
        if (min == 0) return "0";
        if (min < 60) return min + " m";
        double h = min / 60.0;
        if (h >= 10) return Math.round(h) + " h";
        String s = String.format(java.util.Locale.US, "%.1f", h);
        return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + " h";
    }

    /** "0 m", "45 m", "2 h", "2 h 40 m": to the nearest minute. */
    static String duration(long ms) {
        long min = Math.round(ms / 60_000.0), h = min / 60, m = min % 60;
        if (h == 0) return m + " m";
        return m == 0 ? h + " h" : h + " h " + m + " m";
    }

    /**
     * How long it heated and cooled between {@code from} and {@code to} (the graph's 24 hours), for what it can do: its
     * hvac_modes, or its mode without them. "—" while {@code spans} is null (the history isn't in yet).
     */
    static List<String[]> runtimes(Climate c, List<Span> spans, long from, long to) {
        List<String> m = c.modes.isEmpty() ? Collections.singletonList(c.state) : c.modes;
        boolean heats = m.contains("heat") || m.contains("heat_cool") || m.contains("auto");
        boolean cools = m.contains("cool") || m.contains("heat_cool") || m.contains("auto");
        List<String[]> out = new ArrayList<>();
        if (heats) out.add(new String[] {"Heated", spans == null ? "—" : duration(runtime(spans, HEATING, from, to))});
        if (cools) out.add(new String[] {"Cooled", spans == null ? "—" : duration(runtime(spans, COOLING, from, to))});
        return out;
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
