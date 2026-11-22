package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tank screen's data: a Neptune Apex in Home Assistant. Probes are found by their {@code apex_probe_type}
 * attribute (Temp, pH, Cond, and the Trident's alk, ca, mg). Trident reagents and waste, DOS containers, the Trident
 * and DOS outlet modes and the alarm are found by entity id. Pure Java (no Android types) so the JVM tests cover it.
 */
final class Apex {
    /** Probe types that go in the tank chip (model.TANK_PROBES in the integration); their chips open the tank screen. */
    static final Set<String> CHIP_TYPES = new HashSet<>(Arrays.asList("Temp", "pH", "Cond", "alk", "ca", "mg"));
    /** Water probes per type: the first two by id. A third (an expansion module's) is often unplugged and wrong. */
    static final int PROBES_PER_TYPE = 2;

    /** Without the integration only (see {@link Ha#data}; its data.py builds the same answer). */
    static final String TEMPLATE = "{% set ns = namespace(out=[]) %}"
            + "{% set probes = states.sensor | selectattr('attributes.apex_probe_type', 'defined') | list %}"
            + "{% set others = states | selectattr('entity_id', 'search',"
            + " '^sensor[.].*_(tri_reagent_[a-z]|tri_waste|dos_[a-z0-9]+_volume_left)$"
            + "|^select[.].*_(trident|dos)_mode$|^binary_sensor[.].*apex_alarm$') | list %}"
            + "{% for s in probes + others %}{% set a = s.attributes %}"
            + "{% set ns.out = ns.out + [{'id': s.entity_id, 'state': s.state, 'unit': a.get('unit_of_measurement'),"
            + " 'name': a.get('friendly_name'), 'type': a.get('apex_probe_type'), 'changed': s.last_changed.isoformat(),"
            + " 'tests': a.get('tests_remaining'), 'full': a.get('percent_full'), 'left': a.get('percent_left'),"
            + " 'alerts': (a.get('active_alerts') or []) | count}] %}"
            + "{% endfor %}"
            + "{{ {'items': ns.out} | tojson }}";

    private Apex() {
    }

    static String templateBody() {
        try {
            return new JSONObject().put("template", TEMPLATE).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One entity as the screen needs it. Numbers are NaN, and {@code tests} -1, when Home Assistant has none. */
    static final class Item {
        String id = "", state = "", unit = "", name = "", type = "";
        double value = Double.NaN, full = Double.NaN, left = Double.NaN;
        int tests = -1, alerts = 0;
        long changedMs;

        boolean ok() {
            return !state.isEmpty() && !"unavailable".equals(state) && !"unknown".equals(state);
        }
    }

    static final class Data {
        final List<Item> temp = new ArrayList<>(), ph = new ArrayList<>(), salinity = new ArrayList<>();
        Item alk, ca, mg;
        final List<Item> reagents = new ArrayList<>();
        Item waste;
        final List<Item> dosing = new ArrayList<>();
        String tridentMode = "", dosMode = "";
        /** Null when there is no alarm entity. */
        Boolean alarm;
        int alerts;

        boolean any() {
            return !temp.isEmpty() || !ph.isEmpty() || !salinity.isEmpty() || alk != null || ca != null || mg != null;
        }

        /** Every probe the trends draw: water first, then the Trident's. */
        List<Item> trendItems() {
            List<Item> out = new ArrayList<>(temp);
            out.addAll(ph);
            out.addAll(salinity);
            for (Item i : new Item[] {alk, ca, mg}) if (i != null) out.add(i);
            return out;
        }

        /** When the Trident last reported, from its probes' last change (epoch ms), or 0. */
        long lastTest() {
            long t = 0;
            for (Item i : new Item[] {alk, ca, mg}) if (i != null) t = Math.max(t, i.changedMs);
            return t;
        }
    }

    static Data parse(String json) throws JSONException {
        JSONArray items = new JSONObject(json).optJSONArray("items");
        Data d = new Data();
        if (items == null) return d;
        List<Item> all = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.id = Ha.str(o, "id");
            it.state = Ha.str(o, "state");
            it.unit = Ha.str(o, "unit");
            it.name = Ha.str(o, "name");
            it.type = Ha.str(o, "type");
            it.value = Solar.parse(it.state);
            it.tests = o.isNull("tests") ? -1 : o.optInt("tests", -1);
            it.full = Weather.num(o, "full");
            it.left = Weather.num(o, "left");
            it.alerts = o.optInt("alerts", 0);
            try {
                it.changedMs = OffsetDateTime.parse(Ha.str(o, "changed")).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                it.changedMs = 0;
            }
            all.add(it);
        }
        all.sort((a, b) -> a.id.compareTo(b.id));
        for (Item it : all) {
            String id = it.id;
            switch (it.type) {
                case "Temp": addProbe(d.temp, it); continue;
                case "pH": addProbe(d.ph, it); continue;
                case "Cond": addProbe(d.salinity, it); continue;
                case "alk": if (d.alk == null) d.alk = it; continue;
                case "ca": if (d.ca == null) d.ca = it; continue;
                case "mg": if (d.mg == null) d.mg = it; continue;
                default: break;
            }
            if (id.matches("sensor\\..*_tri_reagent_[a-z]")) d.reagents.add(it);
            else if (id.endsWith("_tri_waste")) d.waste = it;
            else if (id.endsWith("_volume_left") && id.contains("_dos_")) d.dosing.add(it);
            else if (id.endsWith("_trident_mode")) d.tridentMode = it.state;
            else if (id.endsWith("_dos_mode")) d.dosMode = it.state;
            else if (id.startsWith("binary_sensor.") && id.endsWith("apex_alarm")) {
                if ("on".equals(it.state)) d.alarm = true;
                else if ("off".equals(it.state)) d.alarm = false;
                d.alerts = it.alerts;
            }
        }
        return d;
    }

    private static void addProbe(List<Item> into, Item it) {
        if (into.size() < PROBES_PER_TYPE && it.ok() && !Double.isNaN(it.value)) into.add(it);
    }

    // ---- words -------------------------------------------------------------------------------------------------

    /** "Temp 2" from "Apex Temp 2"; "Reagent A" from "..._tri_reagent_a"; "DOS DEL" from "..._dos_del_volume_left". */
    static String label(Item it) {
        String id = it.id;
        int r = id.indexOf("_tri_reagent_");
        if (r >= 0) return "Reagent " + id.substring(r + 13).toUpperCase(Locale.ROOT);
        if (id.endsWith("_tri_waste")) return "Trident waste";
        int dos = id.indexOf("_dos_");
        if (dos >= 0 && id.endsWith("_volume_left")) {
            return "DOS " + id.substring(dos + 5, id.length() - "_volume_left".length()).replace('_', ' ').toUpperCase(Locale.ROOT);
        }
        String n = it.name;
        if (n.regionMatches(true, 0, "Apex ", 0, 5)) n = n.substring(5);
        return n.isEmpty() ? id : n;
    }

    /** The reading as Home Assistant wrote it, with a short unit: "78.0 °F", "8.08", "485 ppm", "6.6 fl oz". */
    static String reading(Item it) {
        if (it == null || !it.ok()) return "—";
        String v = Double.isNaN(it.value) ? Ha.capitalise(it.state) : Ha.reading(it.state);
        String u = it.unit.replace("fl. oz.", "fl oz");
        return u.isEmpty() ? v : v + " " + u;
    }

    /** A volume in fl oz with one decimal ("6.6 fl oz"), for reagents and containers. */
    static String volume(Item it) {
        if (it == null || !it.ok() || Double.isNaN(it.value)) return "—";
        String u = it.unit.replace("fl. oz.", "fl oz");
        return String.format(Locale.US, "%.1f", it.value) + (u.isEmpty() ? "" : " " + u);
    }

    /** "On", "Off", "Auto" for an outlet mode. */
    static String mode(String m) {
        if (m == null || m.isEmpty() || "unavailable".equals(m) || "unknown".equals(m)) return "";
        return Ha.capitalise(m.toLowerCase(Locale.ROOT));
    }
}
