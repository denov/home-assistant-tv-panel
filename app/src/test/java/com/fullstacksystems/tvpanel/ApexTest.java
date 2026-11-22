package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ApexTest {
    private static String item(String id, String state, String unit, String type, String extra) {
        return "{\"id\": \"" + id + "\", \"state\": \"" + state + "\", \"unit\": " + (unit == null ? "null" : "\"" + unit + "\"")
                + ", \"name\": \"Apex x\", \"type\": " + (type == null ? "null" : "\"" + type + "\"")
                + ", \"changed\": \"2026-11-08T20:40:00+00:00\", \"alerts\": 0" + extra + "}";
    }

    private static final String LIVE = "{\"items\": ["
            + item("sensor.apex_temp_8", "20.7", "°F", "Temp", "") + ","
            + item("sensor.apex_temp_2", "79.1", "°F", "Temp", "") + ","
            + item("sensor.apex_temp", "78.0", "°F", "Temp", "") + ","
            + item("sensor.apex_ph", "unavailable", null, "pH", "") + ","
            + item("sensor.apex_ph_2", "8.1", null, "pH", "") + ","
            + item("sensor.apex_salinity", "34.6", "ppt", "Cond", "") + ","
            + item("sensor.apex_current_2", "1.0", "A", "Amps", "") + ","
            + item("sensor.apex_alkalinity", "9.26", "dKH", "alk", "") + ","
            + item("sensor.apex_calcium", "485.0", "ppm", "ca", "") + ","
            + item("sensor.tank_tri_reagent_b", "8.338", "fl. oz.", null, ", \"tests\": 121") + ","
            + item("sensor.tank_tri_reagent_a", "6.63", "fl. oz.", null, ", \"tests\": 12") + ","
            + item("sensor.tank_tri_waste", "13.6", "fl. oz.", null, ", \"full\": 25.0") + ","
            + item("sensor.tank_dos_del_volume_left", "0.0", "fl. oz.", null, ", \"left\": 0.0") + ","
            + item("select.tank_trident_mode", "on", null, null, "") + ","
            + item("select.tank_dos_mode", "auto", null, null, "") + ","
            + item("binary_sensor.tank_apex_alarm", "off", null, null, "") + "]}";

    @Test
    public void parsesProbesSuppliesAndModes() throws Exception {
        Apex.Data d = Apex.parse(LIVE);
        // The first two temperature probes by id; the expansion module's third is left out.
        assertEquals(2, d.temp.size());
        assertEquals("sensor.apex_temp", d.temp.get(0).id);
        assertEquals("sensor.apex_temp_2", d.temp.get(1).id);
        // An unavailable probe is skipped.
        assertEquals(1, d.ph.size());
        assertEquals("8.1", Apex.reading(d.ph.get(0)));
        assertEquals("78.0 °F", Apex.reading(d.temp.get(0)));
        assertEquals("485 ppm", Apex.reading(d.ca));
        assertEquals(null, d.mg);
        assertEquals(2, d.reagents.size());
        assertEquals("Reagent A", Apex.label(d.reagents.get(0)));
        assertEquals(12, d.reagents.get(0).tests);
        assertEquals("6.6 fl oz", Apex.volume(d.reagents.get(0)));
        assertEquals(25.0, d.waste.full, 1e-9);
        assertEquals("DOS DEL", Apex.label(d.dosing.get(0)));
        assertEquals(0.0, d.dosing.get(0).left, 1e-9);
        assertEquals("On", Apex.mode(d.tridentMode));
        assertEquals("Auto", Apex.mode(d.dosMode));
        assertEquals(Boolean.FALSE, d.alarm);
        assertTrue(d.lastTest() > 0);
        List<Apex.Item> all = d.trendItems();
        assertEquals(6, all.size());
    }

    @Test
    public void apexChipsOpenTheTankScreen() throws Exception {
        String json = "{\"on\": \"on\", \"items\": ["
                + "{\"id\": \"sensor.apex_ph\", \"name\": \"Apex pH\", \"device\": \"Apex\", \"state\": \"8.08\", \"apex\": \"pH\"},"
                + "{\"id\": \"sensor.apex_current_2\", \"name\": \"Apex Current 2\", \"device\": \"Apex\", \"state\": \"1\", \"apex\": \"Amps\"},"
                + "{\"id\": \"sensor.envoy_1_current_power_production\", \"name\": \"Envoy Current power production\","
                + " \"device\": \"Envoy\", \"state\": \"0.4\", \"unit\": \"kW\", \"dc\": \"power\"}]}";
        List<Ha.Widget> w = Ha.parseWidgets(json);
        for (Ha.Widget x : w) {
            if (x.key.equals("sensor.apex_ph")) assertEquals(Ha.APEX, x.screen);
            if (x.key.equals("sensor.apex_current_2")) assertFalse(x.open);
            if (x.key.startsWith("sensor.envoy")) assertEquals(Ha.SOLAR_SCREEN, x.screen);
        }
        assertTrue(Apex.CHIP_TYPES.contains("Cond"));
    }
}
