package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
        assertNull(band("humidity", "%", 45));
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
}
