package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The thermostat screen: any climate entity, from Home Assistant's standard attributes (GET
 * /api/tv_panel/data/thermostat?screen=<id>), and its last 24 hours (history/history_during_period).
 */
public class ThermostatTest {
    private static JSONObject climate(String id, String name, String state, JSONObject attributes) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("state", state).put("attributes", attributes);
    }

    private static Thermostat.Data data(String unit, JSONArray sensors, JSONObject... climates) throws Exception {
        return Thermostat.parse(new JSONObject().put("title", "Upstairs").put("unit", unit)
                .put("climates", new JSONArray(Arrays.asList(climates))).put("sensors", sensors).toString());
    }

    private static Thermostat.Data data(JSONObject... climates) throws Exception {
        return data("°F", new JSONArray(), climates);
    }

    private static List<String> details(Thermostat.Climate c) {
        List<String> out = new java.util.ArrayList<>();
        for (String[] d : Thermostat.details(c)) out.add(d[0] + " " + d[1]);
        return out;
    }

    @Test
    public void heatAndCoolWithARange() throws Exception {
        Thermostat.Data d = data(climate("climate.up", "Upstairs", "heat_cool", new JSONObject()
                .put("current_temperature", 71.5).put("target_temp_low", 68).put("target_temp_high", 76)
                .put("hvac_action", "cooling").put("current_humidity", 41).put("fan_mode", "auto")
                .put("preset_mode", "home")));
        assertEquals("Upstairs", d.title);
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("Upstairs", c.name);
        assertEquals("71.5°", Thermostat.current(d, c)[0]);
        assertEquals("68–76°", Thermostat.target(c));
        assertEquals("Heat/Cool", Thermostat.mode(c));
        assertEquals("Cooling", Thermostat.doing(c));
        assertEquals(Thermostat.COOLING, Thermostat.colour(c));
        assertEquals(Arrays.asList("Humidity 41%", "Fan Auto", "Preset Home"), details(c));
    }

    @Test
    public void heatOnlyShowsOnlyWhatItHas() throws Exception {
        Thermostat.Data d = data(climate("climate.hall", "Hallway Thermostat", "heat", new JSONObject()
                .put("current_temperature", 68).put("temperature", 70).put("hvac_action", "heating")
                .put("hvac_modes", new JSONArray().put("off").put("heat"))));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("68°", Thermostat.current(d, c)[0]);
        assertEquals("70°", Thermostat.target(c));
        assertEquals("Heat", Thermostat.mode(c));
        assertEquals("Heating", Thermostat.doing(c));
        assertEquals(Thermostat.HEATING, Thermostat.colour(c));
        // No humidity, fan or preset: no rows for them.
        assertEquals(Collections.emptyList(), details(c));
    }

    @Test
    public void coolOnlyAndIdle() throws Exception {
        Thermostat.Data d = data(climate("climate.den", "Den", "cool", new JSONObject()
                .put("current_temperature", 73).put("temperature", 74).put("hvac_action", "idle")
                .put("current_humidity", 52).put("target_humidity", 45)));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("74°", Thermostat.target(c));
        assertEquals("Cool", Thermostat.mode(c));
        assertEquals("Idle", Thermostat.doing(c));
        assertEquals(Thermostat.IDLE, Thermostat.colour(c));
        assertEquals(Arrays.asList("Humidity 52% → 45%"), details(c));
    }

    @Test
    public void offHasNoTarget() throws Exception {
        // Some integrations keep the last setpoint while off; it isn't a target then.
        Thermostat.Data d = data(climate("climate.up", "Upstairs", "off", new JSONObject()
                .put("current_temperature", 66).put("temperature", 70).put("hvac_action", "off")));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("66°", Thermostat.current(d, c)[0]);
        assertEquals("", Thermostat.target(c));
        assertEquals("Off", Thermostat.mode(c));
        assertEquals("Off", Thermostat.doing(c));
        assertEquals(Thermostat.IDLE, Thermostat.colour(c));
    }

    @Test
    public void celsiusAsHomeAssistantHasIt() throws Exception {
        Thermostat.Data d = data("°C", new JSONArray(), climate("climate.up", "Up", "heat", new JSONObject()
                .put("current_temperature", 21.5).put("temperature", 22).put("target_temp_step", 0.5)));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("°C", d.unit);
        assertEquals("21.5°", Thermostat.current(d, c)[0]);
        assertEquals("22°", Thermostat.target(c));
    }

    @Test
    public void withoutACurrentTemperatureTheFirstTemperatureSensorStandsIn() throws Exception {
        JSONArray sensors = new JSONArray()
                .put(new JSONObject().put("id", "sensor.hum").put("name", "Hall humidity").put("state", "40")
                        .put("unit", "%").put("dc", "humidity"))
                .put(new JSONObject().put("id", "sensor.hall").put("name", "Hall Temperature").put("state", "68.4")
                        .put("unit", "°F").put("dc", "temperature"));
        Thermostat.Data d = data("°F", sensors, climate("climate.z", "Zone", "heat", new JSONObject()
                .put("temperature", 70)));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals(Arrays.asList("68.4°", "Hall Temperature"), Arrays.asList(Thermostat.current(d, c)));
        // In another unit than Home Assistant's, it says which.
        sensors.getJSONObject(1).put("state", "20.2").put("unit", "°C");
        d = data("°F", sensors, climate("climate.z", "Zone", "heat", new JSONObject()));
        assertEquals("20.2 °C", Thermostat.current(d, d.climates.get(0))[0]);
        // Without one either: nothing to show.
        d = data(climate("climate.z", "Zone", "heat", new JSONObject()));
        assertEquals(Arrays.asList("", ""), Arrays.asList(Thermostat.current(d, d.climates.get(0))));
    }

    @Test
    public void withoutHvacActionOnlyTheMode() throws Exception {
        Thermostat.Data d = data(climate("climate.garage", "Garage Heater", "heat", new JSONObject()
                .put("current_temperature", 55).put("temperature", 50)));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("", Thermostat.doing(c));
        assertEquals("Heat", Thermostat.mode(c));
        assertEquals(Thermostat.IDLE, Thermostat.colour(c));
    }

    @Test
    public void oneHomeAssistantDoesntHave() throws Exception {
        Thermostat.Data d = data(climate("climate.gone", null, "unknown", new JSONObject()),
                climate("climate.away", "Away", "unavailable", new JSONObject()));
        Thermostat.Climate gone = d.climates.get(0);
        // No name: from its id.
        assertEquals("Gone", gone.name);
        assertTrue(gone.missing());
        assertEquals("Not in Home Assistant", Thermostat.mode(gone));
        assertEquals("Unavailable", Thermostat.mode(d.climates.get(1)));
        assertEquals("", Thermostat.target(d.climates.get(1)));
    }

    @Test
    public void theLast24Hours() throws Exception {
        long t0 = 1_700_000_000L;
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("s", "heat_cool").put("lu", t0 + 0.5).put("a", new JSONObject()
                        .put("current_temperature", 70).put("target_temp_low", 68).put("target_temp_high", 76)
                        .put("hvac_action", "idle")))
                .put(new JSONObject().put("s", "heat_cool").put("lu", t0 + 600).put("a", new JSONObject()
                        .put("current_temperature", 77).put("target_temp_low", 68).put("target_temp_high", 76)
                        .put("hvac_action", "cooling")))
                // A row without attributes keeps the last ones.
                .put(new JSONObject().put("s", "off").put("lu", t0 + 1200))
                .put(new JSONObject().put("s", "heat").put("lu", t0 + 1800).put("a", new JSONObject()
                        .put("current_temperature", 66).put("temperature", 70).put("hvac_action", "heating")))
                .put(new JSONObject().put("s", "unavailable").put("lu", t0 + 2400).put("a", new JSONObject()));
        List<Thermostat.Point> p = Thermostat.history(rows);
        assertEquals(5, p.size());
        assertEquals(t0 * 1000 + 500, p.get(0).t);
        assertEquals(70, p.get(0).cur, 0);
        assertEquals(68, p.get(0).lo, 0);
        assertEquals(76, p.get(0).hi, 0);
        // Off: no target.
        assertTrue(Double.isNaN(p.get(2).lo));
        assertEquals(77, p.get(2).cur, 0);
        // One setpoint: a band of one.
        assertEquals(70, p.get(3).lo, 0);
        assertEquals(70, p.get(3).hi, 0);
        assertTrue(Double.isNaN(p.get(4).cur));

        // Heating and cooling, as spans along the bottom; the last runs to the end.
        List<Thermostat.Span> s = Thermostat.spans(p, t0 * 1000 + 3000_000);
        assertEquals(2, s.size());
        assertEquals(Thermostat.COOLING, s.get(0).colour);
        assertEquals((t0 + 600) * 1000, s.get(0).from);
        assertEquals((t0 + 1200) * 1000, s.get(0).to);
        assertEquals(Thermostat.HEATING, s.get(1).colour);
        assertEquals((t0 + 2400) * 1000, s.get(1).to);
    }
}
