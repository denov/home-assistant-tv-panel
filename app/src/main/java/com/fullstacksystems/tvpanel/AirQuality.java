package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The air quality screen's data: any sensor, as a tile. A tile gets a colour band only when Home Assistant says what it
 * measures (its device class) in a unit the table below covers; anything else shows its value in neutral grey. The
 * whole band table is here, with where each threshold comes from. Pure Java, so the JVM tests cover it.
 */
final class AirQuality {
    /** The EPA's category colours, a little lighter where the originals are too dark on the TV's background. */
    static final int GOOD = 0xFF00E400, MODERATE = 0xFFFFFF00, SENSITIVE = 0xFFFF7E00, UNHEALTHY = 0xFFFF3B3B,
            VERY_UNHEALTHY = 0xFFB565C4, HAZARDOUS = 0xFFC0405A;
    static final int NEUTRAL = Ha.GREY;

    private AirQuality() {
    }

    /** A band: its name and colour. */
    static final class Band {
        final String name;
        final int colour;

        Band(String name, int colour) {
            this.name = name;
            this.colour = colour;
        }
    }

    private static final Band[] EPA = {
            new Band("Good", GOOD), new Band("Moderate", MODERATE), new Band("Unhealthy for some", SENSITIVE),
            new Band("Unhealthy", UNHEALTHY), new Band("Very unhealthy", VERY_UNHEALTHY),
            new Band("Hazardous", HAZARDOUS),
    };
    private static final Band[] CO2 = {new Band("Good", GOOD), new Band("Fair", MODERATE), new Band("Poor", SENSITIVE)};

    // Each table is the top of every band but the last, after cutting the value as the EPA does ("truncate": PM2.5 and
    // CO to one decimal, PM10 to an integer; the index is rounded).
    //
    // US EPA AQI breakpoints: 40 CFR Part 58, Appendix G, table 2 to section 3.4, as amended by 89 FR 16403
    // (6 March 2024), which lowered PM2.5's Good from 12.0 to 9.0 with the revised annual PM2.5 standard:
    //   AQI           0-50, 51-100, 101-150, 151-200, 201-300, 301 and up
    //   PM2.5 µg/m³   0.0-9.0, 9.1-35.4, 35.5-55.4, 55.5-125.4, 125.5-225.4, 225.5 and up (24-hour)
    //   PM10 µg/m³    0-54, 55-154, 155-254, 255-354, 355-424, 425 and up (24-hour)
    //   CO ppm        0.0-4.4, 4.5-9.4, 9.5-12.4, 12.5-15.4, 15.5-30.4, 30.5 and up (8-hour)
    // A sensor's reading now isn't the 24-hour or 8-hour average these are for: the colour is a guide, as consumer
    // monitors use it.
    private static final double[] AQI_TOPS = {50, 100, 150, 200, 300};
    private static final double[] PM25_TOPS = {9.0, 35.4, 55.4, 125.4, 225.4};
    private static final double[] PM10_TOPS = {54, 154, 254, 354, 424};
    private static final double[] CO_TOPS = {4.4, 9.4, 12.4, 15.4, 30.4};
    // CO2 indoors has no EPA index and no health limit. 800 and 1200 ppm are a ventilation rule of thumb: ASHRAE 62.1's
    // informative note ties about 700 ppm above outdoor air (outdoor air is now over 400 ppm) to the ventilation that
    // keeps body odour acceptable to most visitors. ASHRAE says that figure is neither a limit nor a requirement.
    private static final double[] CO2_TOPS = {800, 1200};

    /** The band for a reading, or null when the table doesn't cover that device class in that unit. */
    static Band band(String dc, String unit, double v) {
        if (dc == null || Double.isNaN(v)) return null;
        String u = unit == null ? "" : unit.trim();
        switch (dc) {
            case "aqi":
                return u.isEmpty() || u.equalsIgnoreCase("AQI") ? pick(EPA, AQI_TOPS, Math.round(v)) : null;
            case "pm25":
                return micrograms(u) ? pick(EPA, PM25_TOPS, cut(v, 1)) : null;
            case "pm10":
                return micrograms(u) ? pick(EPA, PM10_TOPS, cut(v, 0)) : null;
            case "carbon_monoxide":
                return "ppm".equals(u) ? pick(EPA, CO_TOPS, cut(v, 1)) : null;
            case "carbon_dioxide":
                return "ppm".equals(u) ? pick(CO2, CO2_TOPS, Math.round(v)) : null;
            default:
                return null;
        }
    }

    /** µg/m³, with the micro sign (U+00B5) or the Greek mu (U+03BC) that Home Assistant's constant uses. */
    private static boolean micrograms(String u) {
        return "µg/m³".equals(u) || "μg/m³".equals(u);
    }

    private static Band pick(Band[] bands, double[] tops, double v) {
        for (int i = 0; i < tops.length; i++) if (v <= tops[i]) return bands[i];
        return bands[tops.length];
    }

    /** Cut to {@code places} decimals, toward zero. */
    private static double cut(double v, int places) {
        double f = Math.pow(10, places);
        // A hair over, so 9.1 (9.0999...) stays 9.1.
        return Math.floor(v * f + 1e-9) / f;
    }

    // ---- tiles -------------------------------------------------------------------------------------------------

    /** One sensor's tile, as /api/tv_panel/data/air_quality sends it. */
    static final class Tile {
        final String id, area, name, state, unit, dc;

        Tile(JSONObject o) {
            id = Ha.str(o, "id");
            area = Ha.str(o, "area");
            name = Ha.str(o, "name");
            state = Ha.str(o, "state");
            unit = Ha.str(o, "unit");
            dc = Ha.str(o, "dc");
        }

        /** "Den · CO2": the room first; the name alone without one; from its id without a name. */
        String title() {
            String n = name.isEmpty() ? Thermostat.fromId(id) : name;
            return area.isEmpty() ? n : area + " · " + n;
        }

        boolean ok() {
            return Ha.isNumber(state);
        }

        String value() {
            return ok() ? Ha.reading(state) : "—";
        }

        /** Its band, or null: none in the table, or no reading. */
        Band band() {
            return ok() ? AirQuality.band(dc.isEmpty() ? null : dc, unit, Double.parseDouble(state)) : null;
        }
    }

    static final class Data {
        final String title;
        final List<Tile> tiles;

        Data(String title, List<Tile> tiles) {
            this.title = title;
            this.tiles = tiles;
        }
    }

    static Data parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<Tile> tiles = new ArrayList<>();
        JSONArray a = o.optJSONArray("sensors");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null) tiles.add(new Tile(s));
        }
        return new Data(Ha.str(o, "title"), tiles);
    }
}
