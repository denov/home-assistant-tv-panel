package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.util.List;

public class HaTest {
    // The shape the template returns, with this house's entities.
    static final String REPLY = "{\"on\": \"unknown\", \"items\": ["
            + "{\"id\": \"sensor.apex_temp\", \"name\": \"Apex Temp\", \"device\": \"Apex\", \"state\": \"78.8\", \"unit\": \"°F\", \"icon\": \"mdi:thermometer\", \"dc\": \"temperature\", \"temp\": null, \"tunit\": null},"
            + "{\"id\": \"sensor.apex_ph\", \"name\": \"Apex pH\", \"device\": \"Apex\", \"state\": \"8.18\", \"unit\": null, \"icon\": \"mdi:ph\", \"dc\": null, \"temp\": null, \"tunit\": null},"
            + "{\"id\": \"cover.ratgdo32_door\", \"name\": \"ratgdo Door\", \"device\": \"ratgdo\", \"state\": \"closed\", \"unit\": null, \"icon\": null, \"dc\": \"garage\", \"temp\": null, \"tunit\": null},"
            + "{\"id\": \"sensor.envoy_1_current_power_production\", \"name\": \"Envoy 1 Current power production\", \"device\": \"Envoy 1\", \"state\": \"2.179\", \"unit\": \"kW\", \"icon\": null, \"dc\": \"power\", \"temp\": null, \"tunit\": null},"
            + "{\"id\": \"weather.forecast_home\", \"name\": \"Home\", \"device\": \"Home\", \"state\": \"partlycloudy\", \"unit\": null, \"icon\": null, \"dc\": null, \"temp\": 67, \"tunit\": \"°F\"},"
            + "{\"id\": \"sensor.gone\", \"name\": \"Gone\", \"device\": null, \"state\": \"unavailable\", \"unit\": \"W\", \"icon\": null, \"dc\": \"power\", \"temp\": null, \"tunit\": null}"
            + "]}";

    @Test
    public void chipsReadLikeTheDashboard() throws Exception {
        List<Ha.Widget> w = Ha.parseWidgets(REPLY);
        assertEquals(6, w.size());
        assertEquals("weather.forecast_home", w.get(0).key);
        assertEquals("Partly cloudy", w.get(0).label);
        assertEquals("67 °F", w.get(0).value);
        assertTrue(w.get(0).open);
        assertEquals("Garage", w.get(1).label);
        // A garage door is an icon alone, and OK toggles it.
        assertEquals("", w.get(1).value);
        assertEquals("garage", w.get(1).icon);
        assertEquals(Ha.TOGGLE, w.get(1).page);
        assertTrue(w.get(1).open);
        // No device sorts ahead of the named devices; unavailable reads as a dash.
        assertEquals("Gone", w.get(2).label);
        assertEquals("—", w.get(2).value);
        // Apex chips drop the device name, keep its case ("pH") and their units.
        assertEquals("pH", w.get(3).label);
        assertEquals("8.18", w.get(3).value);
        assertEquals("Temp", w.get(4).label);
        assertEquals("78.8 °F", w.get(4).value);
        assertEquals("Solar", w.get(5).label);
        assertEquals("2.18 kW", w.get(5).value);
        assertEquals("solar_power", w.get(5).icon);
    }

    @Test
    public void switchOffHidesTheRow() throws Exception {
        assertTrue(Ha.parseWidgets(REPLY.replace("\"on\": \"unknown\"", "\"on\": \"off\"")).isEmpty());
        assertEquals(6, Ha.parseWidgets(REPLY.replace("\"on\": \"unknown\"", "\"on\": \"on\"")).size());
    }

    @Test
    public void numbersReadCleanly() {
        assertEquals("2.18", Ha.number("2.179"));
        assertEquals("78.8", Ha.number("78.8"));
        assertEquals("1344", Ha.number("1344.0"));
        assertEquals("67", Ha.number(67));
        assertEquals("0", Ha.number("-0.001"));
        assertEquals("8.30", Ha.reading("8.30"));
        assertEquals("2.18", Ha.reading("2.179"));
        assertEquals("1344", Ha.reading("1344.0"));
        assertEquals("78.8", Ha.reading("78.8"));
        assertEquals("0.38", Ha.reading("0.378"));
        assertEquals("NW", Ha.compass(315));
        assertEquals("N", Ha.compass(359));
    }

    @Test
    public void forecastParses() throws Exception {
        String json = "{\"changed_states\":[],\"service_response\":{\"weather.forecast_home\":{\"forecast\":["
                + "{\"condition\":\"sunny\",\"datetime\":\"2026-11-07T20:00:00+00:00\",\"temperature\":68,\"templow\":56,\"precipitation\":0.0,\"wind_speed\":11.6},"
                + "{\"condition\":\"rainy\",\"datetime\":\"2026-11-08T20:00:00+00:00\",\"temperature\":66,\"templow\":53,\"precipitation\":0.2}]}}}";
        List<Weather.Point> p = Weather.forecast(json, "weather.forecast_home", ZoneId.of("America/Los_Angeles"));
        assertEquals(2, p.size());
        assertEquals(12, p.get(0).time.getHour());
        assertEquals("68°", Weather.deg(p.get(0).temp));
        assertEquals(0.2, p.get(1).precip, 1e-9);
    }

    @Test
    public void apexProbesMergeIntoOneTankChip() throws Exception {
        String reply = "{\"on\": \"on\", \"items\": ["
                + "{\"id\": \"sensor.apex_temp\", \"name\": \"Apex Temp\", \"device\": \"Apex\", \"state\": \"78.8\", \"unit\": \"°F\", \"dc\": \"temperature\", \"apex\": \"Temp\"},"
                + "{\"id\": \"sensor.apex_ph\", \"name\": \"Apex pH\", \"device\": \"Apex\", \"state\": \"8.23\", \"unit\": null, \"apex\": \"pH\"},"
                + "{\"id\": \"sensor.apex_alkalinity\", \"name\": \"Apex Alkalinity\", \"device\": \"Apex\", \"state\": \"9.53\", \"unit\": \"dKH\", \"apex\": \"alk\"},"
                + "{\"id\": \"sensor.apex_salinity\", \"name\": \"Apex Salinity\", \"device\": \"Apex\", \"state\": \"34.9\", \"unit\": \"ppt\", \"apex\": \"Cond\"},"
                + "{\"id\": \"sensor.other\", \"name\": \"Other\", \"device\": \"Zed\", \"state\": \"1\", \"unit\": null}"
                + "]}";
        List<Ha.Widget> w = Ha.parseWidgets(reply);
        assertEquals(2, w.size());
        assertEquals(Ha.APEX, w.get(0).key);
        assertEquals("Alkalinity\tpH\tSalinity\tTemp", w.get(0).label);
        assertEquals("9.53 dKH\t8.23\t34.9 ppt\t78.8 °F", w.get(0).value);
        assertEquals("fish", w.get(0).icon);
        assertEquals(Ha.APEX, w.get(0).page);
        assertEquals("sensor.other", w.get(1).key);
        assertEquals(Ha.BLUE, w.get(0).colour);

        // The Apex alarm turns the fish red and puts the alert count first.
        w = Ha.parseWidgets(reply.replace("]}", "], \"alarm\": \"on\", \"alerts\": 2}"));
        assertEquals(Ha.RED, w.get(0).colour);
        assertEquals("Alarm\tAlkalinity\tpH\tSalinity\tTemp", w.get(0).label);
        assertEquals("2 alerts\t9.53 dKH\t8.23\t34.9 ppt\t78.8 °F", w.get(0).value);
    }
}
