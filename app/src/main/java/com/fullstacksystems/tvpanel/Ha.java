package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Home Assistant tells the TV, and how it reads on Home. Pure Java (no Android types) so the JVM tests cover it.
 *
 * Which entities are widgets is set in Home Assistant: every entity with the label {@value #LABEL}. The optional
 * helper {@value #SWITCH} turns the whole row off (state "off") without removing any labels.
 */
final class Ha {
    static final String LABEL = "tv_widget";
    static final String SWITCH = "input_boolean.tv_widgets";
    /** At most this many chips; the row is one line under the clock. */
    static final int MAX_WIDGETS = 8;

    /** One request returns the switch and every labelled entity with what the chip needs. */
    static final String TEMPLATE = "{% set ns = namespace(out=[]) %}"
            // The integration's list (in its order) when it sends one, else the tv_widget label.
            + "{% for e in (entities if entities is defined else label_entities('" + LABEL + "')) %}"
            + "{% set ns.out = ns.out + [{"
            + "'id': e, 'name': state_attr(e, 'friendly_name'), "
            + "'device': ((device_attr(device_id(e), 'name_by_user') or device_attr(device_id(e), 'name'))"
            + " if device_id(e) else none), "
            + "'state': states(e), 'unit': state_attr(e, 'unit_of_measurement'), 'icon': state_attr(e, 'icon'), "
            + "'dc': state_attr(e, 'device_class'), 'temp': state_attr(e, 'temperature'), "
            + "'tunit': state_attr(e, 'temperature_unit'), 'apex': state_attr(e, 'apex_probe_type'), "
            + "'motion': " + Cameras.MOTION + " > 0}] %}"
            + "{% endfor %}"
            // The Apex controller's alarm (binary_sensor.*apex_alarm, unlabelled): it colours the tank chip.
            + "{% set al = states.binary_sensor | selectattr('entity_id', 'search', 'apex_alarm$') | list %}"
            + "{{ {'on': (('on' if row else 'off') if row is defined else states('" + SWITCH + "')),"
            + " 'ordered': entities is defined, 'items': ns.out,"
            + " 'alarm': al[0].state if al else '',"
            + " 'alerts': ((al[0].attributes.get('active_alerts') or []) | count) if al else 0} | tojson }}";

    static final int BLUE = 0xFF2AA7E0;
    static final int AMBER = 0xFFFFC94A;
    static final int SKY = 0xFFCFE3F2;
    static final int ORANGE = 0xFFFF9F43;
    static final int GREY = 0xFF8A8F98;
    static final int RED = 0xFFFF5252;

    private Ha() {
    }

    /** Pages a chip can open: OpenActivity routes on these. */
    static final String WEATHER = "weather", SOLAR_PAGE = "solar", APEX = "apex", CAMERAS = "cameras";
    /** Not a page: OK on the chip toggles the cover (the garage door) through Home Assistant. */
    static final String TOGGLE = "toggle";
    /** Splits a chip's label and value into columns (the tank chip); the launcher draws each pair as one column. */
    static final String COLUMN = "\t";

    /**
     * A chip on Home. {@code icon} is a bundled icon name (see Icons). {@code page} is the page OK opens
     * ({@link #WEATHER}, {@link #SOLAR_PAGE}, {@link #APEX}) or "" for none; {@code open} says there is one.
     */
    static final class Widget {
        final String key;
        final String label;
        final String value;
        final String icon;
        final int colour;
        final boolean open;
        final String page;
        final String sortGroup;

        Widget(String key, String label, String value, String icon, int colour, String page, String sortGroup) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.icon = icon;
            this.colour = colour;
            this.page = page;
            this.open = !page.isEmpty();
            this.sortGroup = sortGroup;
        }
    }

    static String templateBody() {
        return templateBody(Settings.NONE);
    }

    /** With the integration's settings: its chips and row switch, passed as template variables. */
    static String templateBody(Settings settings) {
        return Settings.body(TEMPLATE, settings, false);
    }

    /**
     * The template's answer as chips: none when the switch is off. In the integration's order when it gave the list;
     * from labels, weather first, then by device, then by name.
     */
    static List<Widget> parseWidgets(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if ("off".equals(o.optString("on"))) return Collections.emptyList();
        JSONArray items = o.optJSONArray("items");
        List<Widget> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.optJSONObject(i);
            if (e == null) continue;
            Widget w = widget(e);
            if (w != null) out.add(w);
        }
        mergeCameras(out);
        mergeApex(out, "on".equals(o.optString("alarm")), o.optInt("alerts", 0));
        // The integration's list is in the order the person chose; the labels have none, so sort those.
        if (!o.optBoolean("ordered")) out.sort((a, b) -> {
            int g = a.sortGroup.compareToIgnoreCase(b.sortGroup);
            return g != 0 ? g : a.label.compareToIgnoreCase(b.label);
        });
        return out.size() > MAX_WIDGETS ? new ArrayList<>(out.subList(0, MAX_WIDGETS)) : out;
    }

    /**
     * Every labelled camera becomes one chip, {@value #CAMERAS}, that opens the camera grid. It has no value, so the
     * launcher draws its icon alone: a motion sensor in orange while any camera sees motion, a camera otherwise.
     */
    static void mergeCameras(List<Widget> chips) {
        boolean any = false, motion = false;
        int at = -1;
        for (int i = chips.size() - 1; i >= 0; i--) {
            Widget w = chips.get(i);
            if (!CAMERAS.equals(w.page)) continue;
            any = true;
            motion |= "motion_sensor".equals(w.icon);
            chips.remove(i);
            at = i;
        }
        if (any) {
            // Where the first camera was, which matters when the integration sets the order.
            chips.add(at, new Widget(CAMERAS, "Cameras", "", motion ? "motion_sensor" : "cctv", motion ? ORANGE : BLUE,
                    CAMERAS, "1"));
        }
    }

    /**
     * Every Apex probe chip (they all open the one tank page) becomes one chip, {@value #APEX}, with a fish icon. Its
     * label and value are tab-separated lists in step ("Alkalinity\tpH\t..." over "9.53 dKH\t8.23\t..."), which the
     * launcher draws as columns, each label left-aligned over its reading. While the Apex alarm is on, the fish turns
     * red and an "Alarm" column comes first with the number of active alerts.
     */
    static void mergeApex(List<Widget> chips, boolean alarm, int alerts) {
        List<Widget> probes = new ArrayList<>();
        int at = -1;
        for (int i = chips.size() - 1; i >= 0; i--) {
            Widget w = chips.get(i);
            if (!APEX.equals(w.page)) continue;
            probes.add(0, w);
            chips.remove(i);
            at = i;
        }
        if (probes.isEmpty()) return;
        probes.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        StringBuilder l = new StringBuilder(), v = new StringBuilder();
        if (alarm) {
            l.append("Alarm");
            v.append(alerts > 0 ? alerts + (alerts == 1 ? " alert" : " alerts") : "On");
        }
        for (Widget w : probes) {
            if (l.length() > 0) { l.append(COLUMN); v.append(COLUMN); }
            l.append(w.label);
            v.append(w.value);
        }
        chips.add(at, new Widget(APEX, l.toString(), v.toString(), "fish", alarm ? RED : BLUE, APEX,
                probes.get(0).sortGroup));
    }

    static Widget widget(JSONObject e) {
        String id = str(e, "id");
        if (id.isEmpty() || !id.contains(".")) return null;
        String domain = id.substring(0, id.indexOf('.'));
        String state = str(e, "state");
        String name = shortName(str(e, "name"), str(e, "device"), id);
        String dc = str(e, "dc");
        boolean missing = state.isEmpty() || "unavailable".equals(state) || "unknown".equals(state);

        if ("weather".equals(domain)) {
            String temp = e.isNull("temp") ? "" : number(e.opt("temp"));
            String unit = str(e, "tunit");
            String value = missing || temp.isEmpty() ? "—" : temp + (unit.isEmpty() ? "°" : " " + unit);
            return new Widget(id, missing ? name : conditionText(state), value, conditionIcon(state),
                    conditionColour(state), WEATHER, "0");
        }
        if ("cover".equals(domain)) {
            boolean garage = "garage".equals(dc) || id.contains("garage") || name.toLowerCase(Locale.ROOT).contains("garage");
            String label = garage && ("Door".equalsIgnoreCase(name) || name.isEmpty()) ? "Garage" : name;
            boolean shut = "closed".equals(state);
            // A garage door is an icon alone (shut in blue, open or moving in orange, grey when HA can't see it),
            // and OK on it toggles the door: OpenActivity calls cover.toggle instead of opening a page.
            if (garage) {
                return new Widget(id, label, "", shut ? "garage" : "garage_open",
                        missing ? GREY : shut ? BLUE : ORANGE, TOGGLE, "1" + str(e, "device"));
            }
            return new Widget(id, label, missing ? "—" : capitalise(state), "door_closed",
                    shut || missing ? BLUE : ORANGE, "", "1" + str(e, "device"));
        }
        if ("camera".equals(domain)) {
            boolean motion = e.optBoolean("motion");
            return new Widget(id, name, "", motion ? "motion_sensor" : "cctv", motion ? ORANGE : BLUE, CAMERAS, "1");
        }
        if ("lock".equals(domain)) {
            boolean locked = "locked".equals(state);
            return new Widget(id, name, missing ? "—" : capitalise(state), locked ? "lock" : "lock_open_variant",
                    locked || missing ? BLUE : ORANGE, "", "1" + str(e, "device"));
        }
        String unit = str(e, "unit");
        String value = missing ? "—" : (isNumber(state) ? reading(state) : capitalise(state));
        if (!missing && !unit.isEmpty()) value = value + " " + unit;
        boolean solar = ("power".equals(dc) || "energy".equals(dc))
                && (id.contains("production") || id.contains("solar") || id.contains("pv"));
        String icon = solar ? "solar_power" : iconFor(str(e, "icon"), dc);
        // "Current power production" says less than "Solar" in a chip; a short name of the owner's own stays.
        if (solar && name.length() > 14) name = "Solar";
        // Solar opens the solar page (SolarActivity), which reads the Envoy the sensor belongs to; an Apex water or
        // Trident probe opens the Apex page (ApexActivity).
        String page = solar ? SOLAR_PAGE : Apex.PAGE_TYPES.contains(str(e, "apex")) ? APEX : "";
        return new Widget(id, name, value, icon, solar ? AMBER : BLUE, page, "2" + str(e, "device"));
    }

    /** "Apex Temp" on the device "Apex" reads "Temp": the device name is on every chip of the group otherwise. */
    static String shortName(String name, String device, String id) {
        String n = name.trim();
        String d = device.trim();
        if (!d.isEmpty() && n.length() > d.length() && n.regionMatches(true, 0, d, 0, d.length())
                && n.charAt(d.length()) == ' ') {
            n = n.substring(d.length() + 1).trim();
        }
        if (n.isEmpty()) n = capitalise(id.substring(id.indexOf('.') + 1).replace('_', ' '));
        return n;
    }

    /** A bundled icon for an "mdi:..." name, or one for the device class, or a gauge. */
    static String iconFor(String mdi, String deviceClass) {
        String m = mdi.startsWith("mdi:") ? mdi.substring(4).replace('-', '_') : "";
        if (Icons.NAMES.contains(m)) return m;
        switch (deviceClass) {
            case "temperature": return "thermometer";
            case "humidity": case "moisture": return "water_percent";
            case "power": case "energy": case "current": case "voltage": return "flash";
            case "ph": return "ph";
            default: return "gauge";
        }
    }

    static String conditionText(String c) {
        switch (c) {
            case "clear-night": return "Clear";
            case "cloudy": return "Cloudy";
            case "exceptional": return "Alert";
            case "fog": return "Fog";
            case "hail": return "Hail";
            case "lightning": return "Thunder";
            case "lightning-rainy": return "Storms";
            case "partlycloudy": return "Partly cloudy";
            case "pouring": return "Heavy rain";
            case "rainy": return "Rain";
            case "snowy": return "Snow";
            case "snowy-rainy": return "Sleet";
            case "sunny": return "Sunny";
            case "windy": case "windy-variant": return "Windy";
            default: return capitalise(c.replace('-', ' '));
        }
    }

    static String conditionIcon(String c) {
        switch (c) {
            case "clear-night": return "weather_night";
            case "cloudy": return "weather_cloudy";
            case "exceptional": return "weather_sunny_alert";
            case "fog": return "weather_fog";
            case "hail": return "weather_hail";
            case "lightning": return "weather_lightning";
            case "lightning-rainy": return "weather_lightning_rainy";
            case "partlycloudy": return "weather_partly_cloudy";
            case "pouring": return "weather_pouring";
            case "rainy": return "weather_rainy";
            case "snowy": return "weather_snowy";
            case "snowy-rainy": return "weather_snowy_rainy";
            case "windy": return "weather_windy";
            case "windy-variant": return "weather_windy_variant";
            default: return "weather_sunny";
        }
    }

    static int conditionColour(String c) {
        return "sunny".equals(c) || "exceptional".equals(c) ? AMBER : SKY;
    }

    static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A sensor's state as Home Assistant wrote it ("8.30" stays "8.30"), rounded only when it is longer than {@link #number}. */
    static String reading(String state) {
        String s = state.trim();
        int dot = s.indexOf('.');
        if (dot < 0 || s.contains("e") || s.contains("E")) return number(s);
        String rounded = number(s);
        int places = rounded.indexOf('.') < 0 ? 0 : rounded.length() - rounded.indexOf('.') - 1;
        return s.length() - dot - 1 <= Math.max(places, 2) && Math.abs(Double.parseDouble(s)) < 100 ? s : rounded;
    }

    /** Readable precision: 2.179 → 2.18, 78.8 → 78.8, 1344.0 → 1344, 67 → 67. */
    static String number(Object raw) {
        double v;
        try {
            v = raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return String.valueOf(raw);
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        double a = Math.abs(v);
        int places = a >= 100 ? 0 : a >= 10 ? 1 : 2;
        String s = String.format(Locale.US, "%." + places + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return "-0".equals(s) ? "0" : s;
    }

    static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "").trim();
    }

    /** Compass point for a bearing in degrees. */
    static String compass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return p[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }
}
