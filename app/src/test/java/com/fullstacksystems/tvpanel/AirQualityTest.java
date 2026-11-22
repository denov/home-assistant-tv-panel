package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The air quality screen: any sensor, with a colour band only where Home Assistant says what it measures (its device
 * class) in a unit the table covers. One test per device class, at each edge of each band.
 */
public class AirQualityTest {
    private static String band(String dc, String unit, double v) {
        AirQuality.Band b = AirQuality.band(dc, unit, v);
        return b == null ? null : b.name;
    }

    private static void edges(String dc, String unit, double[] values, String[] names) {
        for (int i = 0; i < values.length; i++) assertEquals(dc + " " + values[i], names[i], band(dc, unit, values[i]));
    }

    private static final String[] EPA = {"Good", "Moderate", "Unhealthy for some", "Unhealthy", "Very unhealthy",
            "Hazardous"};

    private static String[] twice(String[] names) {
        String[] out = new String[names.length * 2];
        for (int i = 0; i < names.length; i++) {
            out[2 * i] = names[i];
            out[2 * i + 1] = names[i];
        }
        return out;
    }

    @Test
    public void theAirQualityIndex() {
        // Each band's first and last value.
        edges("aqi", null, new double[] {0, 50, 51, 100, 101, 150, 151, 200, 201, 300, 301, 500}, twice(EPA));
        // An index has no unit; one that says "AQI" is one too.
        assertEquals("Good", band("aqi", "AQI", 12));
        assertNull(band("aqi", "ppm", 12));
    }

    @Test
    public void pm25ByThe2024Breakpoints() {
        edges("pm25", "µg/m³", new double[] {0, 9.0, 9.1, 35.4, 35.5, 55.4, 55.5, 125.4, 125.5, 225.4, 225.5, 600},
                twice(EPA));
        // Cut to the breakpoints' one decimal first, as the EPA does: 9.04 is 9.0.
        assertEquals("Good", band("pm25", "µg/m³", 9.04));
        // Home Assistant writes the unit with a Greek mu; some integrations with the micro sign.
        assertEquals("Moderate", band("pm25", "μg/m³", 12));
        assertNull(band("pm25", "ppm", 12));
    }

    @Test
    public void pm10() {
        edges("pm10", "µg/m³", new double[] {0, 54, 55, 154, 155, 254, 255, 354, 355, 424, 425, 700}, twice(EPA));
        assertEquals("Good", band("pm10", "μg/m³", 54.9));
    }

    @Test
    public void carbonMonoxide() {
        edges("carbon_monoxide", "ppm", new double[] {0, 4.4, 4.5, 9.4, 9.5, 12.4, 12.5, 15.4, 15.5, 30.4, 30.5, 60},
                twice(EPA));
        assertNull(band("carbon_monoxide", "mg/m³", 3));
    }

    @Test
    public void carbonDioxideIndoors() {
        edges("carbon_dioxide", "ppm", new double[] {400, 800, 801, 1200, 1201, 3000},
                new String[] {"Good", "Good", "Fair", "Fair", "Poor", "Poor"});
        assertNull(band("carbon_dioxide", "%", 0.1));
    }

    @Test
    public void anythingElseShowsWithoutABand() {
        // VOC sensors differ too much: an index, ppb, µg/m³.
        assertNull(band("volatile_organic_compounds", "µg/m³", 300));
        assertNull(band("volatile_organic_compounds_parts", "ppb", 300));
        assertNull(band(null, null, 112));
        assertNull(band("temperature", "°F", 72));
        assertNull(band("pm25", "µg/m³", Double.NaN));
    }

    @Test
    public void tilesAsTheIntegrationSendsThem() throws Exception {
        AirQuality.Data d = AirQuality.parse(new JSONObject().put("title", "Air").put("sensors", new JSONArray()
                .put(new JSONObject().put("id", "sensor.den_co2").put("area", "Den").put("name", "CO2")
                        .put("state", "612").put("unit", "ppm").put("dc", "carbon_dioxide").put("sc", "measurement"))
                .put(new JSONObject().put("id", "sensor.voc_index").put("area", JSONObject.NULL).put("name", JSONObject.NULL)
                        .put("state", "unavailable").put("unit", JSONObject.NULL).put("dc", JSONObject.NULL)))
                .toString());
        assertEquals("Air", d.title);
        AirQuality.Tile co2 = d.tiles.get(0);
        // The room first.
        assertEquals("Den · CO2", co2.title());
        assertEquals("612", co2.value());
        assertEquals("ppm", co2.unit);
        assertEquals("Good", co2.band().name);
        AirQuality.Tile voc = d.tiles.get(1);
        // No name: from its id. No reading: a dash, and no band.
        assertEquals("Voc index", voc.title());
        assertEquals("—", voc.value());
        assertNull(voc.band());
    }

    @Test
    public void humidityIndoors() {
        // EPA: below 60%, ideally 30 to 50%. Rounded to a whole percent first.
        edges("humidity", "%", new double[] {0, 29, 29.4, 29.6, 30, 50, 50.4, 50.6, 60, 60.4, 60.6, 100},
                new String[] {"Dry", "Dry", "Dry", "Good", "Good", "Good", "Good", "Humid", "Humid", "Humid",
                        "Too humid", "Too humid"});
        assertNull(band("humidity", "g/m³", 9));
    }

    @Test
    public void whereAReadingSitsOnItsScale() {
        // Each band an equal part of the bar, the reading in its part as far as it is through that band.
        assertEquals(0, AirQuality.position("aqi", null, 0), 1e-9);
        assertEquals(1 / 6.0, AirQuality.position("aqi", null, 50), 1e-9);
        assertEquals(1.5 / 6, AirQuality.position("aqi", null, 75), 1e-9);
        // Hazardous runs to the index's 500 (40 CFR 58 appendix G, table 2, note 4); past it stays at the end.
        assertEquals(1, AirQuality.position("aqi", null, 500), 1e-9);
        assertEquals(1, AirQuality.position("aqi", null, 700), 1e-9);
        assertEquals(1, AirQuality.position("pm25", "µg/m³", 325.4), 1e-9);
        assertEquals(1, AirQuality.position("pm10", "µg/m³", 604), 1e-9);
        assertEquals(1, AirQuality.position("carbon_monoxide", "ppm", 50.4), 1e-9);
        // CO2 starts at outdoor air.
        assertEquals(0, AirQuality.position("carbon_dioxide", "ppm", 400), 1e-9);
        assertEquals(0, AirQuality.position("carbon_dioxide", "ppm", 380), 1e-9);
        assertEquals(1 / 3.0, AirQuality.position("carbon_dioxide", "ppm", 800), 1e-9);
        assertEquals(0.5, AirQuality.position("carbon_dioxide", "ppm", 1000), 1e-9);
        assertEquals(1, AirQuality.position("carbon_dioxide", "ppm", 2500), 1e-9);
        assertEquals(1.5 / 4, AirQuality.position("humidity", "%", 40), 1e-9);
        // Its part is its band's: PM2.5 9.04 is cut to 9.0, Good, so it sits at Good's end.
        assertEquals(1 / 6.0, AirQuality.position("pm25", "µg/m³", 9.04), 1e-9);
        // No band, no scale.
        assertTrue(Double.isNaN(AirQuality.position("volatile_organic_compounds_parts", "ppb", 300)));
        assertNull(AirQuality.scale("temperature", "°F"));
        AirQuality.Scale co2 = AirQuality.scale("carbon_dioxide", "ppm");
        assertArrayEquals(new double[] {400, 800, 1200, 2000}, co2.edges, 0);
        assertEquals("Poor", co2.bands[2].name);
    }

    @Test
    public void whichWayItsGoing() {
        // The change over the last hour, against how far it moved in 24 hours.
        assertEquals("▲ 120 in 1 h", AirQuality.trend(120, 400, 1600));
        assertEquals("▼ 3.1 in 1 h", AirQuality.trend(-3.1, 60, 75));
        assertEquals("▲ 2 in 1 h", AirQuality.trend(2.04, 60, 75));
        // Under a twentieth of the day's range, or nothing to show at a tenth: steady.
        assertEquals("Steady", AirQuality.trend(50, 400, 1600));
        assertEquals("Steady", AirQuality.trend(0.02, 72, 72));
        assertEquals("Steady", AirQuality.trend(0, 72, 72));
        // No reading an hour ago: nothing.
        assertEquals("", AirQuality.trend(Double.NaN, 60, 75));
    }

    @Test
    public void aTilesRoomAndNameApart() throws Exception {
        AirQuality.Data d = AirQuality.parse(new JSONObject().put("sensors", new JSONArray()
                .put(new JSONObject().put("id", "sensor.den_co2").put("area", "Den").put("name", "CO2"))
                .put(new JSONObject().put("id", "sensor.voc_index").put("area", JSONObject.NULL)
                        .put("name", JSONObject.NULL))).toString());
        assertEquals("Den", d.tiles.get(0).room());
        assertEquals("CO2", d.tiles.get(0).label());
        assertEquals("", d.tiles.get(1).room());
        assertEquals("Voc index", d.tiles.get(1).label());
    }

    @Test
    public void aTilesTrendAndItsLowAndHigh() {
        long h = 3_600_000L, now = 1_700_000_000_000L, from = now - 24 * h;
        java.util.List<Solar.Stat> rows = java.util.Arrays.asList(new Solar.Stat(from - h, 900),
                new Solar.Stat(from + h, 400), new Solar.Stat(now - 2 * h, 1500), new Solar.Stat(now - 90 * 60_000L, 1180));
        // The live value counts too: the history's last row can be minutes old.
        assertArrayEquals(new String[] {"▲ 120 in 1 h", "400", "1500"}, AirQuality.summary(rows, 1300, from, now));
        assertArrayEquals(new String[] {"▲ 440 in 1 h", "400", "1620"}, AirQuality.summary(rows, 1620, from, now));
        assertArrayEquals(new String[] {"▼ 780 in 1 h", "400", "1500"}, AirQuality.summary(rows, 400, from, now));
        // Low and high to the same places: 70.2 and 73.0, not 73.
        assertArrayEquals(new String[] {"", "70.2", "73.0"}, AirQuality.summary(java.util.Arrays.asList(
                new Solar.Stat(now - 60_000L, 70.2)), 73, from, now));
        // Nothing an hour ago: no trend. Nothing at all: nothing.
        assertArrayEquals(new String[] {"", "70", "70"},
                AirQuality.summary(java.util.Collections.singletonList(new Solar.Stat(now - 60_000L, 70)), 70, from, now));
        assertArrayEquals(new String[] {"", "", ""},
                AirQuality.summary(new java.util.ArrayList<>(), Double.NaN, from, now));
    }

    @Test
    public void sensorsJoinedOnOneTile() throws Exception {
        AirQuality.Data d = AirQuality.parse("{\"title\": \"Air\", \"sensors\": [{\"id\": \"sensor.a\"}, {\"id\": "
                + "\"sensor.b\"}, {\"id\": \"sensor.c\"}], \"tiles\": [[\"sensor.a\", \"sensor.b\"], [\"sensor.c\"]]}");
        assertEquals("[[0, 1], [2]]", d.groups.toString());
        assertEquals("[[0], [1]]", AirQuality.parse("{\"sensors\": [{\"id\": \"sensor.a\"}, {\"id\": \"sensor.b\"}]}")
                .groups.toString());
    }

    @Test
    public void aChartsLineIsTheColourOfItsLabel() {
        // At a band's edge the line and its dot say what the label says: the scale classifies as band() does, rounded or
        // cut as the EPA says.
        String[][] cases = {{"humidity", "%", "30"}, {"humidity", "%", "29.5"}, {"humidity", "%", "50.4"},
                {"humidity", "%", "60.4"}, {"aqi", "", "50.4"}, {"carbon_dioxide", "ppm", "800.4"},
                {"pm25", "µg/m³", "9.05"}, {"pm10", "µg/m³", "54.9"}, {"carbon_monoxide", "ppm", "4.45"}};
        for (String[] c : cases) {
            double v = Double.parseDouble(c[2]);
            assertSame(String.join(" ", c), AirQuality.band(c[0], c[1], v), AirQuality.scale(c[0], c[1]).of(v));
        }
        assertEquals("Good", AirQuality.scale("humidity", "%").of(30).name);
    }
}
