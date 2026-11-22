package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertArrayEquals;
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
                .put(new JSONObject().put("id", "sensor.hall").put("area", "Hall").put("name", "Temperature")
                        .put("state", "68.4").put("unit", "°F").put("dc", "temperature"));
        Thermostat.Data d = data("°F", sensors, climate("climate.z", "Zone", "heat", new JSONObject()
                .put("temperature", 70)));
        Thermostat.Climate c = d.climates.get(0);
        // Named with its room, as its tile is.
        assertEquals(Arrays.asList("68.4°", "Hall · Temperature"), Arrays.asList(Thermostat.current(d, c)));
        assertEquals("Hall", d.sensors.get(1).area);
        assertEquals("Hall humidity", d.sensors.get(0).title());
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

    private static void targets(double heat, double cool, double other, double[] t) {
        assertEquals("heat", heat, t[0], 0);
        assertEquals("cool", cool, t[1], 0);
        assertEquals("other", other, t[2], 0);
    }

    private static final double NaN = Double.NaN;

    @Test
    public void whereItHeatsAndCoolsTo() {
        // A range: heat to its low end, cool to its high end.
        targets(68, 76, NaN, Thermostat.targets("heat_cool", NaN, 68, 76));
        targets(68, 76, NaN, Thermostat.targets("auto", NaN, 68, 76));
        // One setpoint: heat to it, cool to it, or (auto, dry, fan only) just held at it.
        targets(70, NaN, NaN, Thermostat.targets("heat", 70, NaN, NaN));
        targets(NaN, 74, NaN, Thermostat.targets("cool", 74, NaN, NaN));
        // A setpoint wins over a range some integrations keep beside it.
        targets(70, NaN, NaN, Thermostat.targets("heat", 70, 68, 76));
        targets(NaN, NaN, 72, Thermostat.targets("auto", 72, NaN, NaN));
        targets(NaN, NaN, 72, Thermostat.targets("dry", 72, NaN, NaN));
        // Off, or not known: nothing, whatever it kept.
        targets(NaN, NaN, NaN, Thermostat.targets("off", 70, 68, 76));
        targets(NaN, NaN, NaN, Thermostat.targets("unavailable", 70, NaN, NaN));
    }

    @Test
    public void theHistoryKnowsWhatItHeatedAndCooledTo() throws Exception {
        long t0 = 1_700_000_000L;
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("s", "heat_cool").put("lu", t0).put("a", new JSONObject()
                        .put("current_temperature", 70).put("target_temp_low", 68).put("target_temp_high", 76)))
                .put(new JSONObject().put("s", "heat").put("lu", t0 + 60).put("a", new JSONObject()
                        .put("current_temperature", 66).put("temperature", 70)))
                .put(new JSONObject().put("s", "cool").put("lu", t0 + 120).put("a", new JSONObject()
                        .put("current_temperature", 77).put("temperature", 75)))
                .put(new JSONObject().put("s", "off").put("lu", t0 + 180));
        List<Thermostat.Point> p = Thermostat.history(rows);
        assertEquals(68, p.get(0).heat, 0);
        assertEquals(76, p.get(0).cool, 0);
        assertEquals(70, p.get(1).heat, 0);
        assertTrue(Double.isNaN(p.get(1).cool));
        assertEquals(75, p.get(2).cool, 0);
        assertTrue(Double.isNaN(p.get(2).heat));
        assertTrue(Double.isNaN(p.get(3).heat) && Double.isNaN(p.get(3).cool) && Double.isNaN(p.get(3).other));
    }

    @Test
    public void theDialRunsOverTheThermostatsOwnRange() throws Exception {
        Thermostat.Climate c = data(climate("climate.up", "Up", "heat_cool", new JSONObject()
                .put("current_temperature", 68).put("target_temp_low", 68).put("target_temp_high", 76)
                .put("min_temp", 50).put("max_temp", 90))).climates.get(0);
        assertArrayEquals(new double[] {50, 90}, Thermostat.dial(c, "°F"), 0);
        // Without one, a usual thermostat's range in Home Assistant's unit.
        Thermostat.Climate f = data(climate("climate.up", "Up", "heat", new JSONObject()
                .put("current_temperature", 68).put("temperature", 70))).climates.get(0);
        assertArrayEquals(new double[] {50, 90}, Thermostat.dial(f, "°F"), 0);
        Thermostat.Climate c21 = data(climate("climate.up", "Up", "heat", new JSONObject()
                .put("current_temperature", 21.5).put("temperature", 22))).climates.get(0);
        assertArrayEquals(new double[] {10, 32}, Thermostat.dial(c21, "°C"), 0);
        // A room or a setpoint outside it widens it, to whole degrees.
        Thermostat.Climate cold = data(climate("climate.garage", "Garage", "heat", new JSONObject()
                .put("current_temperature", 44.5).put("temperature", 92).put("min_temp", 50).put("max_temp", 90)))
                .climates.get(0);
        assertArrayEquals(new double[] {44, 92}, Thermostat.dial(cold, "°F"), 0);
        // One that's the wrong way round is ignored.
        Thermostat.Climate odd = data(climate("climate.odd", "Odd", "heat", new JSONObject()
                .put("current_temperature", 20).put("min_temp", 30).put("max_temp", 5))).climates.get(0);
        assertArrayEquals(new double[] {10, 32}, Thermostat.dial(odd, "°C"), 0);
    }

    @Test
    public void howLongItHeatedAndCooled() throws Exception {
        long h = 3_600_000L, m = 60_000L, from = 1_700_000_000_000L, to = from + 24 * h;
        List<Thermostat.Span> spans = Arrays.asList(
                // Started before the window: only its part inside counts.
                new Thermostat.Span(from - h, from + 30 * m, Thermostat.HEATING),
                new Thermostat.Span(from + 2 * h, from + 2 * h + 45 * m, Thermostat.COOLING),
                new Thermostat.Span(from + 5 * h, from + 7 * h + 10 * m, Thermostat.HEATING));
        assertEquals(2 * h + 40 * m, Thermostat.runtime(spans, Thermostat.HEATING, from, to));
        assertEquals(45 * m, Thermostat.runtime(spans, Thermostat.COOLING, from, to));
        assertEquals("0 m", Thermostat.duration(0));
        assertEquals("1 m", Thermostat.duration(50_000));
        assertEquals("45 m", Thermostat.duration(45 * m));
        assertEquals("2 h", Thermostat.duration(2 * h));
        assertEquals("2 h 40 m", Thermostat.duration(2 * h + 40 * m));

        // Heated and cooled (over the graph's 24 hours), for a thermostat that can do both; "—" until the history is in.
        Thermostat.Climate both = data(climate("climate.up", "Up", "heat_cool", new JSONObject()
                .put("hvac_modes", new JSONArray().put("off").put("heat").put("cool").put("heat_cool"))))
                .climates.get(0);
        assertEquals(Arrays.asList("Heated 2 h 40 m", "Cooled 45 m"),
                rows(Thermostat.runtimes(both, spans, from, to)));
        assertEquals(Arrays.asList("Heated —", "Cooled —"),
                rows(Thermostat.runtimes(both, null, from, to)));
        // A heater only says how long it heated.
        Thermostat.Climate heater = data(climate("climate.garage", "Garage", "heat", new JSONObject()
                .put("hvac_modes", new JSONArray().put("off").put("heat")))).climates.get(0);
        assertEquals(Collections.singletonList("Heated 2 h 40 m"),
                rows(Thermostat.runtimes(heater, spans, from, to)));
        // Without hvac_modes, its mode says what it can do.
        Thermostat.Climate cooler = data(climate("climate.den", "Den", "cool", new JSONObject())).climates.get(0);
        assertEquals(Collections.singletonList("Cooled 45 m"),
                rows(Thermostat.runtimes(cooler, spans, from, to)));
    }

    private static List<String> rows(List<String[]> rows) {
        List<String> out = new java.util.ArrayList<>();
        for (String[] r : rows) out.add(r[0] + " " + r[1]);
        return out;
    }

    @Test
    public void theLastSevenDaysADayAtATime() throws Exception {
        java.time.ZoneId zone = java.time.ZoneId.of("Etc/GMT+8");
        long h = 3_600_000L, m = 60_000L;
        long today = java.time.LocalDate.of(2026, 11, 22).atStartOfDay(zone).toInstant().toEpochMilli();
        long now = today + 15 * h;
        double[] none = {Double.NaN, Double.NaN, Double.NaN};
        List<Thermostat.Point> points = Arrays.asList(
                new Thermostat.Point(today - 26 * h, 66, 65, 72, none, "idle"),
                new Thermostat.Point(today - 18 * h, 70, 65, 72, none, "idle"),
                // Unavailable for a while: no reading, so no low.
                new Thermostat.Point(today - 10 * h, Double.NaN, 65, 72, none, ""),
                new Thermostat.Point(today - 1 * h, 64, 65, 72, none, "heating"),
                new Thermostat.Point(today + 8 * h, 69, 65, 72, none, "idle"));
        List<Thermostat.Span> spans = Arrays.asList(
                // Across midnight: half an hour yesterday, an hour today.
                new Thermostat.Span(today - 30 * m, today + h, Thermostat.HEATING),
                new Thermostat.Span(today + 10 * h, today + 10 * h + 20 * m, Thermostat.COOLING),
                // Still running: it counts until now.
                new Thermostat.Span(today + 14 * h, today + 16 * h, Thermostat.HEATING));

        List<Thermostat.Day> days = Thermostat.days(points, spans, now, zone, 7);
        assertEquals(7, days.size());
        assertEquals(today, days.get(6).start);
        assertEquals(today - 6 * 24 * h, days.get(0).start);
        // Before the history starts: nothing known.
        for (int i = 0; i < 4; i++) {
            assertTrue("day " + i, !days.get(i).known);
            assertTrue(Double.isNaN(days.get(i).low));
        }
        // The day it starts, from its first reading.
        assertTrue(days.get(4).known);
        assertEquals(66, days.get(4).low, 0);
        assertEquals(66, days.get(4).high, 0);
        // Yesterday: what it carried in from the day before, then 70 and 64.
        assertEquals(64, days.get(5).low, 0);
        assertEquals(70, days.get(5).high, 0);
        assertEquals(30 * m, days.get(5).heat);
        // Today: 64 carried in from last night, then 69; heating until now.
        assertEquals(64, days.get(6).low, 0);
        assertEquals(69, days.get(6).high, 0);
        assertEquals(h + h, days.get(6).heat);
        assertEquals(20 * m, days.get(6).cool);
    }

    @Test
    public void heatingABitAtATimeDrawsAsOneBlock() {
        long m = 60_000L, t = 1_700_000_000_000L;
        List<Thermostat.Span> spans = Arrays.asList(
                new Thermostat.Span(t, t + 5 * m, Thermostat.HEATING),
                new Thermostat.Span(t + 8 * m, t + 12 * m, Thermostat.HEATING),
                // Cooling right after is its own block.
                new Thermostat.Span(t + 12 * m, t + 15 * m, Thermostat.COOLING),
                new Thermostat.Span(t + 40 * m, t + 45 * m, Thermostat.HEATING));
        List<Thermostat.Span> b = Thermostat.blocks(spans, 10 * m);
        assertEquals(3, b.size());
        assertEquals(t, b.get(0).from);
        assertEquals(t + 12 * m, b.get(0).to);
        assertEquals(Thermostat.COOLING, b.get(1).colour);
        assertEquals(t + 40 * m, b.get(2).from);
    }

    @Test
    public void theThermostatsRoomIsItsTitle() throws Exception {
        Thermostat.Data d = Thermostat.parse(new JSONObject().put("unit", "°F").put("climates", new JSONArray()
                .put(climate("climate.up", "Upstairs thermostat", "heat", new JSONObject()).put("area", "Hall"))
                .put(climate("climate.den", "Den thermostat", "heat", new JSONObject()))).toString());
        assertEquals("Hall", d.climates.get(0).title());
        assertEquals("Upstairs thermostat", d.climates.get(0).subtitle());
        assertEquals("Den thermostat", d.climates.get(1).title());
        assertEquals("", d.climates.get(1).subtitle());
    }

    @Test
    public void theGraphsDayOutOfTheWeek() {
        double[] none = {Double.NaN, Double.NaN, Double.NaN};
        List<Thermostat.Point> week = Arrays.asList(
                new Thermostat.Point(100, 60, 0, 0, none, ""), new Thermostat.Point(200, 61, 0, 0, none, ""),
                new Thermostat.Point(300, 62, 0, 0, none, ""), new Thermostat.Point(400, 63, 0, 0, none, ""));
        // From the reading the window starts with.
        assertEquals(61, Thermostat.since(week, 250).get(0).cur, 0);
        assertEquals(3, Thermostat.since(week, 250).size());
        assertEquals(62, Thermostat.since(week, 300).get(0).cur, 0);
        assertEquals(4, Thermostat.since(week, 50).size());
        assertEquals(1, Thermostat.since(week, 500).size());
    }

    private static String setting(Thermostat.Climate c) {
        StringBuilder b = new StringBuilder();
        for (Thermostat.Part p : Thermostat.setting(c)) {
            String col = p.colour == Thermostat.HEATING ? "heat" : p.colour == Thermostat.COOLING ? "cool"
                    : p.colour == 0xFFFFFFFF ? "white" : "grey";
            b.append('[').append(p.text).append(' ').append(col).append(']');
        }
        return b.toString();
    }

    @Test
    public void theSettingInItsColours() throws Exception {
        assertEquals("[68° heat][ –  grey][76° cool]", setting(data(climate("climate.a", "A", "heat_cool",
                new JSONObject().put("target_temp_low", 68).put("target_temp_high", 76))).climates.get(0)));
        assertEquals("[Heat to  grey][68° heat]", setting(data(climate("climate.a", "A", "heat",
                new JSONObject().put("temperature", 68))).climates.get(0)));
        assertEquals("[Cool to  grey][75.5° cool]", setting(data(climate("climate.a", "A", "cool",
                new JSONObject().put("temperature", 75.5))).climates.get(0)));
        assertEquals("[Set to  grey][70° white]", setting(data(climate("climate.a", "A", "auto",
                new JSONObject().put("temperature", 70))).climates.get(0)));
        assertEquals("[Off grey]", setting(data(climate("climate.a", "A", "off", new JSONObject())).climates.get(0)));
        assertEquals("", setting(data(climate("climate.a", "A", "unavailable", new JSONObject())).climates.get(0)));
    }

    @Test
    public void aDaysRuntimeInFewCharacters() {
        long m = 60_000L;
        assertEquals("0", Thermostat.brief(0));
        assertEquals("40 m", Thermostat.brief(40 * m));
        assertEquals("1 h", Thermostat.brief(60 * m));
        assertEquals("1.7 h", Thermostat.brief(100 * m));
        assertEquals("12 h", Thermostat.brief(12 * 60 * m + 10 * m));
    }

    @Test
    public void theStandInIsOneReadingForTheNumberThePointerAndTheScale() throws Exception {
        // Two probes without a room are both "Temperature": the first stands in, for the number and the dial alike.
        JSONArray sensors = new JSONArray()
                .put(new JSONObject().put("id", "sensor.probe_1").put("name", "Temperature").put("state", "41")
                        .put("unit", "°F").put("dc", "temperature"))
                .put(new JSONObject().put("id", "sensor.probe_2").put("name", "Temperature").put("state", "75")
                        .put("unit", "°F").put("dc", "temperature"));
        Thermostat.Data d = data("°F", sensors, climate("climate.garage", "Garage", "heat", new JSONObject()
                .put("temperature", 55)));
        Thermostat.Climate c = d.climates.get(0);
        assertEquals("41°", Thermostat.current(d, c)[0]);
        assertEquals(41, Thermostat.room(d, c), 0);
        // The scale widens for it, as for the thermostat's own.
        assertArrayEquals(new double[] {41, 90}, Thermostat.dial(c, "°F", Thermostat.room(d, c)), 0);
        // In another unit it's said in words, but it's no use to the dial.
        sensors.getJSONObject(0).put("state", "5").put("unit", "°C");
        d = data("°F", sensors, climate("climate.garage", "Garage", "heat", new JSONObject().put("temperature", 55)));
        assertEquals("5 °C", Thermostat.current(d, d.climates.get(0))[0]);
        assertTrue(Double.isNaN(Thermostat.room(d, d.climates.get(0))));
        // Its own temperature comes first.
        d = data("°F", sensors, climate("climate.garage", "Garage", "heat", new JSONObject()
                .put("current_temperature", 60)));
        assertEquals(60, Thermostat.room(d, d.climates.get(0)), 0);
    }

    @Test
    public void theHoursOnADaylightSavingDayAreTheClocks() {
        java.time.ZoneId la = java.time.ZoneId.of("America/Los_Angeles");
        // Clocks go back at 2 am on 1 November 2026, and forward at 2 am on 8 March 2026.
        for (String day : new String[] {"2026-10-31T21:30", "2026-03-07T21:30"}) {
            java.time.ZonedDateTime from = java.time.LocalDateTime.parse(day).atZone(la);
            List<String> hours = new java.util.ArrayList<>();
            for (long[] m : ThermostatViews.hourMarks(from.toInstant().toEpochMilli(),
                    from.plusHours(13).toInstant().toEpochMilli(), la, 3)) {
                java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(m[0]).atZone(la);
                hours.add(m[1] + "=" + t.getHour() + ":" + t.getMinute());
            }
            assertEquals(day, "[0=0:0, 3=3:0, 6=6:0, 9=9:0]", hours.toString());
        }
    }

    @Test
    public void theWeekFetchedHourlyAndTheDayEveryFewMinutesJoin() throws Exception {
        long t0 = 1_700_000_000L;
        java.util.function.BiFunction<Long, Double, JSONObject> row = (t, cur) -> {
            try {
                return new JSONObject().put("s", "heat").put("lu", t).put("a", new JSONObject()
                        .put("current_temperature", cur).put("temperature", 68).put("hvac_action", "idle"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        List<Thermostat.Point> week = Thermostat.history(new JSONArray().put(row.apply(t0, 60.0))
                .put(row.apply(t0 + 3600, 61.0)).put(row.apply(t0 + 7200, 62.0)));
        // The last 24 hours start at the state when they start (t0 + 5000), then what changed since.
        List<Thermostat.Point> day = Thermostat.history(new JSONArray().put(row.apply(t0 + 5000, 61.0))
                .put(row.apply(t0 + 7200, 62.5)).put(row.apply(t0 + 9000, 63.0)));
        List<Double> cur = new java.util.ArrayList<>();
        for (Thermostat.Point p : Thermostat.join(week, day)) cur.add(p.cur);
        assertEquals("[60.0, 61.0, 61.0, 62.5, 63.0]", cur.toString());
        // Either one missing: the other as it is.
        assertEquals(3, Thermostat.join(week, new java.util.ArrayList<>()).size());
        assertEquals(3, Thermostat.join(null, day).size());
    }

    @Test
    public void aReadingIsItsRoomAndLabel() throws Exception {
        JSONArray sensors = new JSONArray()
                .put(new JSONObject().put("id", "sensor.probe").put("area", "Office").put("name", "Probe 2 Office Temperature")
                        .put("label", "Temperature").put("state", "72").put("unit", "°F").put("dc", "temperature"))
                // Without a label, its name.
                .put(new JSONObject().put("id", "sensor.out").put("name", "Outside").put("state", "50").put("unit", "°F"));
        Thermostat.Data d = data("°F", sensors);
        assertEquals("Office · Temperature", d.sensors.get(0).title());
        assertEquals("Outside", d.sensors.get(1).title());
    }
}
