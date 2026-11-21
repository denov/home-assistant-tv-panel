package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The solar page's data: an Enphase Envoy in Home Assistant (the enphase_envoy integration), plus battery charge and
 * discharge sensors found by name. Pure Java (no Android types) so the JVM tests cover it.
 *
 * Live values come from the integration (/api/tv_panel/data/solar), or this template without it. Totals come from the recorder's statistics over the websocket, because
 * the Envoy's own "today" sensors are unreliable (on some firmware they report the lifetime total).
 */
final class Solar {
    /**
     * Every Envoy sensor, grid status and storage mode, and battery charge/discharge power and energy sensors. Without
     * the integration only (see {@link Ha#data}; its data.py builds the same answer).
     */
    static final String TEMPLATE = "{% set ns = namespace(out=[]) %}"
            + "{% set ids = integration_entities('enphase_envoy') + (states.sensor"
            + " | selectattr('entity_id', 'search', '(batter|encharge).*_(charge|discharge)_(energy|power)$')"
            + " | map(attribute='entity_id') | list) %}"
            + "{% for e in ids | unique %}"
            + "{% if e.startswith('sensor.') or e.endswith('_grid_status') or e.endswith('_storage_mode') %}"
            + "{% set ns.out = ns.out + [[e, states(e), state_attr(e, 'unit_of_measurement')]] %}"
            + "{% endif %}{% endfor %}"
            + "{{ {'items': ns.out} | tojson }}";

    static final String INVERTER = "sensor.inverter_";
    /** A 5-minute or day change larger than this is a counter reset or glitch, not energy. */
    private static final double MAX_DAY_KWH = 2000;
    private static final double MAX_5MIN_KWH = 100;

    /** Totals rows, in order: the statistic each reads is in {@link Live#energyIds}. */
    static final String[] METRICS = {"Produced", "Consumed", "Imported", "Exported", "Charged", "Discharged"};
    static final int PRODUCED = 0, CONSUMED = 1, IMPORTED = 2, EXPORTED = 3, CHARGED = 4, DISCHARGED = 5;

    private Solar() {
    }

    static String templateBody() {
        try {
            return new JSONObject().put("template", TEMPLATE).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the system is doing now. Power in W, energy in kWh; NaN when Home Assistant has no such sensor. */
    static final class Live {
        double productionW = Double.NaN, consumptionW = Double.NaN, netW = Double.NaN;
        double chargeW = Double.NaN, dischargeW = Double.NaN;
        double soc = Double.NaN, availableKWh = Double.NaN, capacityKWh = Double.NaN;
        double lifetimeKWh = Double.NaN;
        /** Null when there is no grid-status sensor (no Enpower / IQ System Controller). */
        Boolean onGrid;
        String mode = "";
        /** Inverter serial to its output now in W (NaN when unavailable), sorted by serial. */
        final Map<String, Double> inverters = new LinkedHashMap<>();
        /** Statistic ids for METRICS, in order; null where there is none. */
        final String[] energyIds = new String[METRICS.length];
        String socId;

        boolean hasEnvoy() {
            return !Double.isNaN(productionW) || energyIds[PRODUCED] != null || !inverters.isEmpty();
        }

        boolean hasBattery() {
            return !Double.isNaN(soc) || energyIds[CHARGED] != null;
        }

        /** Grid power now: positive when importing. */
        double gridW() {
            if (!Double.isNaN(netW)) return netW;
            if (!Double.isNaN(consumptionW) && !Double.isNaN(productionW)) return consumptionW - productionW - batteryW();
            return Double.NaN;
        }

        /** Battery power now: positive when discharging into the house, 0 when unknown. */
        double batteryW() {
            double d = Double.isNaN(dischargeW) ? 0 : dischargeW;
            double c = Double.isNaN(chargeW) ? 0 : chargeW;
            return d - c;
        }

        List<String> inverterIds() {
            List<String> out = new ArrayList<>();
            for (String serial : inverters.keySet()) out.add(INVERTER + serial);
            return out;
        }
    }

    static Live live(String json) throws JSONException {
        JSONArray items = new JSONObject(json).optJSONArray("items");
        Live l = new Live();
        if (items == null) return l;
        TreeSet<String> serials = new TreeSet<>();
        Map<String, Double> watts = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONArray it = items.optJSONArray(i);
            if (it == null) continue;
            String id = it.optString(0, "");
            String state = it.isNull(1) ? "" : it.optString(1, "");
            String unit = it.isNull(2) ? "" : it.optString(2, "");
            double v = parse(state);
            if (id.startsWith(INVERTER)) {
                String serial = id.substring(INVERTER.length());
                serials.add(serial);
                watts.put(serial, watts(v, unit));
            } else if (id.endsWith("_current_power_production")) {
                l.productionW = watts(v, unit);
            } else if (id.endsWith("_current_power_consumption")) {
                l.consumptionW = watts(v, unit);
            } else if (id.endsWith("_current_net_power_consumption")) {
                l.netW = watts(v, unit);
            } else if (id.endsWith("_lifetime_energy_production")) {
                l.energyIds[PRODUCED] = id;
                l.lifetimeKWh = kwh(v, unit);
            } else if (id.endsWith("_lifetime_energy_consumption")) {
                l.energyIds[CONSUMED] = id;
            } else if (id.endsWith("_lifetime_net_energy_consumption")) {
                l.energyIds[IMPORTED] = id;
            } else if (id.endsWith("_lifetime_net_energy_production")) {
                l.energyIds[EXPORTED] = id;
            } else if (id.endsWith("_discharge_energy")) {
                l.energyIds[DISCHARGED] = id;
            } else if (id.endsWith("_charge_energy")) {
                l.energyIds[CHARGED] = id;
            } else if (id.endsWith("_discharge_power")) {
                l.dischargeW = watts(v, unit);
            } else if (id.endsWith("_charge_power")) {
                l.chargeW = watts(v, unit);
            } else if (id.matches("sensor\\.envoy_[^.]*_battery")) {
                l.soc = v;
                l.socId = id;
            } else if (id.endsWith("_available_battery_energy")) {
                l.availableKWh = kwh(v, unit);
            } else if (id.endsWith("_battery_capacity")) {
                l.capacityKWh = kwh(v, unit);
            } else if (id.endsWith("_grid_status")) {
                if ("on".equals(state)) l.onGrid = true;
                else if ("off".equals(state)) l.onGrid = false;
            } else if (id.endsWith("_storage_mode")) {
                l.mode = state;
            }
        }
        for (String s : serials) l.inverters.put(s, watts.get(s));
        return l;
    }

    // ---- statistics --------------------------------------------------------------------------------------------

    /** One statistic row: its start (epoch ms) and its value (a change or a mean). */
    static final class Stat {
        final long start;
        final double value;

        Stat(long start, double value) {
            this.start = start;
            this.value = value;
        }
    }

    /** recorder/statistics_during_period's result for one id: rows with {@code field} ("change" or "mean"). */
    static List<Stat> series(JSONObject result, String id, String field) {
        List<Stat> out = new ArrayList<>();
        JSONArray rows = result == null || id == null ? null : result.optJSONArray(id);
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || r.isNull(field)) continue;
            double v = r.optDouble(field, Double.NaN);
            if (Double.isNaN(v)) continue;
            Object start = r.opt("start");
            long ms = start instanceof Number ? ((Number) start).longValue() : 0;
            // Older Home Assistant sent seconds as a float.
            if (ms > 0 && ms < 100_000_000_000L) ms = (long) (((Number) start).doubleValue() * 1000);
            out.add(new Stat(ms, v));
        }
        return out;
    }

    /** The sum of energy changes, ignoring negative steps and counter resets. */
    static double total(List<Stat> changes, long fromMs, double maxStep) {
        double sum = 0;
        for (Stat s : changes) {
            if (s.start < fromMs || s.value < 0 || s.value > maxStep) continue;
            sum += s.value;
        }
        return sum;
    }

    /** 5-minute changes since midnight, summed into 15-minute bars (96 a day), as kWh. */
    static double[] quarterHours(List<Stat> fiveMin, long midnightMs) {
        double[] out = new double[96];
        for (Stat s : fiveMin) {
            int i = (int) ((s.start - midnightMs) / 900_000L);
            if (i < 0 || i >= 96 || s.value < 0 || s.value > MAX_5MIN_KWH) continue;
            out[i] += s.value;
        }
        return out;
    }

    /**
     * The house's use per 15 minutes from the energy balance: produced + imported + discharged - exported - charged.
     * The Envoy's own consumption meter stops counting while the batteries carry the house, so the chart uses this
     * when the grid and battery figures are there. Null when they are not.
     */
    static double[] balance(JSONObject fiveMin, String[] ids, long midnightMs) {
        if (ids[PRODUCED] == null || ids[IMPORTED] == null || ids[EXPORTED] == null) return null;
        double[] out = quarterHours(series(fiveMin, ids[PRODUCED], "change"), midnightMs);
        add(out, quarterHours(series(fiveMin, ids[IMPORTED], "change"), midnightMs), 1);
        add(out, quarterHours(series(fiveMin, ids[EXPORTED], "change"), midnightMs), -1);
        if (ids[DISCHARGED] != null) add(out, quarterHours(series(fiveMin, ids[DISCHARGED], "change"), midnightMs), 1);
        if (ids[CHARGED] != null) add(out, quarterHours(series(fiveMin, ids[CHARGED], "change"), midnightMs), -1);
        for (int i = 0; i < out.length; i++) out[i] = Math.max(0, out[i]);
        return out;
    }

    private static void add(double[] into, double[] v, int sign) {
        for (int i = 0; i < into.length; i++) into[i] += sign * v[i];
    }

    /** 5-minute means since midnight (battery %), averaged per 15 minutes; NaN where there is none. */
    static double[] quarterMeans(List<Stat> fiveMin, long midnightMs) {
        double[] sum = new double[96];
        int[] n = new int[96];
        for (Stat s : fiveMin) {
            int i = (int) ((s.start - midnightMs) / 900_000L);
            if (i < 0 || i >= 96) continue;
            sum[i] += s.value;
            n[i]++;
        }
        double[] out = new double[96];
        for (int i = 0; i < 96; i++) out[i] = n[i] == 0 ? Double.NaN : sum[i] / n[i];
        return out;
    }

    /** Energy from power means in W: hourly rows count an hour each, 5-minute rows 5 minutes. Result in kWh. */
    static double energyFromMeans(List<Stat> hourly, List<Stat> fiveMin) {
        double wh = 0;
        for (Stat s : hourly) if (s.value > 0) wh += s.value;
        for (Stat s : fiveMin) if (s.value > 0) wh += s.value / 12.0;
        return wh / 1000.0;
    }

    /** Today, this week and this month for every metric, in kWh (NaN when there is no sensor for it). */
    static final class Totals {
        final double[] today = new double[METRICS.length];
        final double[] week = new double[METRICS.length];
        final double[] month = new double[METRICS.length];
    }

    /**
     * {@code fiveMin}: today's 5-minute changes (since midnight). {@code days}: day changes from the earlier of the
     * week and month start up to midnight. Today comes from the 5-minute rows, which are current to five minutes.
     */
    static Totals totals(String[] ids, JSONObject fiveMin, JSONObject days, long weekStartMs, long monthStartMs) {
        Totals t = new Totals();
        for (int m = 0; m < ids.length; m++) {
            if (ids[m] == null) {
                t.today[m] = t.week[m] = t.month[m] = Double.NaN;
                continue;
            }
            double today = total(series(fiveMin, ids[m], "change"), 0, MAX_5MIN_KWH);
            List<Stat> d = series(days, ids[m], "change");
            t.today[m] = today;
            t.week[m] = today + total(d, weekStartMs, MAX_DAY_KWH);
            t.month[m] = today + total(d, monthStartMs, MAX_DAY_KWH);
        }
        return t;
    }

    // ---- panel layout ------------------------------------------------------------------------------------------

    /** One panel in grid units: a portrait panel is 1 wide and 2 tall, a landscape one 2 by 1. */
    static final class Panel {
        final String serial;
        final double x, y, w, h;

        Panel(String serial, double x, double y, double w, double h) {
            this.serial = serial;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    static final class Group {
        final String name;
        final List<Panel> panels = new ArrayList<>();
        /** Its top-left on the map, in units; set by {@link #place}. NaN in the config means "next in the row". */
        double x = Double.NaN, y = Double.NaN;

        Group(String name) {
            this.name = name;
        }

        double width() {
            double r = 0;
            for (Panel p : panels) r = Math.max(r, p.x + p.w);
            return r;
        }

        double height() {
            double r = 0;
            for (Panel p : panels) r = Math.max(r, p.y + p.h);
            return r;
        }
    }

    /**
     * The owner's layout ({"arrays": [{"name", "x"?, "y"?, "panels": [{"id": serial, "x", "y", "w"?, "h"?}]}]}), or,
     * without one, every inverter in rows of portrait panels. Inverters the layout does not place go in "Other".
     */
    static List<Group> layout(String json, Collection<String> serials) {
        List<Group> out = new ArrayList<>();
        TreeSet<String> left = new TreeSet<>(serials);
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONArray arrays = new JSONObject(json).optJSONArray("arrays");
                for (int i = 0; arrays != null && i < arrays.length(); i++) {
                    JSONObject a = arrays.optJSONObject(i);
                    if (a == null) continue;
                    Group g = new Group(a.optString("name", "Array " + (i + 1)));
                    if (a.has("x") && a.has("y")) {
                        g.x = a.optDouble("x", Double.NaN);
                        g.y = a.optDouble("y", Double.NaN);
                    }
                    JSONArray ps = a.optJSONArray("panels");
                    for (int j = 0; ps != null && j < ps.length(); j++) {
                        JSONObject p = ps.optJSONObject(j);
                        if (p == null) continue;
                        String serial = p.optString("id", "");
                        if (serial.startsWith(INVERTER)) serial = serial.substring(INVERTER.length());
                        g.panels.add(new Panel(serial, p.optDouble("x", 0), p.optDouble("y", 0),
                                p.optDouble("w", 1), p.optDouble("h", 2)));
                        left.remove(serial);
                    }
                    if (!g.panels.isEmpty()) out.add(g);
                }
            } catch (JSONException e) {
                out.clear();
                left = new TreeSet<>(serials);
            }
        }
        if (!left.isEmpty()) {
            Group g = new Group(out.isEmpty() ? "Panels" : "Other");
            int perRow = left.size() <= 12 ? left.size() : (int) Math.ceil(left.size() / Math.ceil(left.size() / 16.0));
            int i = 0;
            for (Iterator<String> it = left.iterator(); it.hasNext(); i++) {
                g.panels.add(new Panel(it.next(), i % perRow, (i / perRow) * 2, 1, 2));
            }
            out.add(g);
        }
        return out;
    }

    /** Places groups without a position left to right, {@code gap} apart, tops aligned. Returns {w, h}. */
    static double[] place(List<Group> groups, double gap) {
        double x = 0;
        for (Group g : groups) {
            if (!Double.isNaN(g.x)) continue;
            g.x = x;
            g.y = 0;
            x += g.width() + gap;
        }
        double w = 0, h = 0;
        for (Group g : groups) {
            w = Math.max(w, g.x + g.width());
            h = Math.max(h, g.y + g.height());
        }
        return new double[] {w, h};
    }

    // ---- words and numbers -------------------------------------------------------------------------------------

    /** "640 W", "1.85 kW". */
    static String power(double w) {
        if (Double.isNaN(w)) return "—";
        double a = Math.abs(w);
        if (a < 1000) return Math.round(a) + " W";
        return String.format(Locale.US, a < 10_000 ? "%.2f kW" : "%.1f kW", a / 1000);
    }

    /** "26.7", "404", "0" for a total in kWh. */
    static String energy(double kwh) {
        if (Double.isNaN(kwh)) return "—";
        if (kwh >= 100) return String.format(Locale.US, "%,d", Math.round(kwh));
        if (kwh < 0.05) return "0";
        return String.format(Locale.US, "%.1f", kwh);
    }

    /** "97.1 MWh" or "8,400 kWh". */
    static String lifetime(double kwh) {
        if (Double.isNaN(kwh)) return "";
        return kwh >= 10_000 ? String.format(Locale.US, "%.1f MWh", kwh / 1000) : energy(kwh) + " kWh";
    }

    /** Rounds a chart maximum up to 1, 2, 2.5 or 5 times a power of ten. */
    static double nice(double v) {
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        for (double s : new double[] {1, 2, 2.5, 5, 10}) if (v <= s * p + 1e-12) return s * p;
        return 10 * p;
    }

    static String mode(String m) {
        switch (m) {
            case "self_consumption": return "Self-consumption";
            case "backup": return "Full backup";
            case "savings": return "Savings";
            case "": case "unknown": case "unavailable": return "";
            default: return Ha.capitalise(m.replace('_', ' '));
        }
    }

    static double watts(double v, String unit) {
        if (Double.isNaN(v)) return v;
        switch (unit) {
            case "kW": return v * 1000;
            case "MW": return v * 1_000_000;
            default: return v;
        }
    }

    static double kwh(double v, String unit) {
        if (Double.isNaN(v)) return v;
        switch (unit) {
            case "Wh": return v / 1000;
            case "MWh": return v * 1000;
            default: return v;
        }
    }

    static double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return Double.NaN;
        }
    }
}
