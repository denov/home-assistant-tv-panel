package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SolarTest {
    private static final String LIVE = "{\"items\": ["
            + "[\"sensor.envoy_1_current_power_production\", \"2.4\", \"kW\"],"
            + "[\"sensor.envoy_1_current_power_consumption\", \"1.849\", \"kW\"],"
            + "[\"sensor.envoy_1_current_net_power_consumption\", \"-0.551\", \"kW\"],"
            + "[\"sensor.envoy_1_lifetime_energy_production\", \"97.069321\", \"MWh\"],"
            + "[\"sensor.envoy_1_lifetime_energy_consumption\", \"104.4\", \"MWh\"],"
            + "[\"sensor.envoy_1_lifetime_net_energy_consumption\", \"55.3\", \"MWh\"],"
            + "[\"sensor.envoy_1_lifetime_net_energy_production\", \"48.0\", \"MWh\"],"
            + "[\"sensor.envoy_1_energy_production_today\", \"97069.3\", \"kWh\"],"
            + "[\"sensor.envoy_1_battery\", \"60\", \"%\"],"
            + "[\"sensor.envoy_1_reserve_battery_level\", \"60\", \"%\"],"
            + "[\"sensor.envoy_1_available_battery_energy\", \"12129\", \"Wh\"],"
            + "[\"sensor.envoy_1_battery_capacity\", \"20160\", \"Wh\"],"
            + "[\"sensor.batteries_encharge_charge_energy\", \"2519380\", \"Wh\"],"
            + "[\"sensor.batteries_encharge_discharge_energy\", \"2267398\", \"Wh\"],"
            + "[\"sensor.batteries_encharge_charge_power\", \"300\", \"W\"],"
            + "[\"sensor.batteries_encharge_discharge_power\", \"0\", \"W\"],"
            + "[\"sensor.encharge_9_battery\", \"61\", \"%\"],"
            + "[\"binary_sensor.enpower_2_grid_status\", \"on\", null],"
            + "[\"select.enpower_2_storage_mode\", \"self_consumption\", null],"
            + "[\"sensor.inverter_3\", \"212\", \"W\"],"
            + "[\"sensor.inverter_1\", \"unavailable\", \"W\"]]}";

    @Test
    public void liveFindsEveryRole() throws Exception {
        Solar.Live l = Solar.live(LIVE);
        assertEquals(2400, l.productionW, 0.01);
        assertEquals(1849, l.consumptionW, 0.01);
        assertEquals(-551, l.gridW(), 0.01);
        assertEquals(-300, l.batteryW(), 0.01);
        assertEquals(60, l.soc, 0.01);
        assertEquals("sensor.envoy_1_battery", l.socId);
        assertEquals(12.129, l.availableKWh, 0.001);
        assertEquals(97069.321, l.lifetimeKWh, 0.001);
        assertEquals(Boolean.TRUE, l.onGrid);
        assertEquals("Self-consumption", Solar.mode(l.mode));
        assertEquals("sensor.envoy_1_lifetime_energy_production", l.energyIds[Solar.PRODUCED]);
        assertEquals("sensor.envoy_1_lifetime_net_energy_consumption", l.energyIds[Solar.IMPORTED]);
        assertEquals("sensor.envoy_1_lifetime_net_energy_production", l.energyIds[Solar.EXPORTED]);
        assertEquals("sensor.batteries_encharge_charge_energy", l.energyIds[Solar.CHARGED]);
        assertEquals("sensor.batteries_encharge_discharge_energy", l.energyIds[Solar.DISCHARGED]);
        assertEquals(Arrays.asList("1", "3"), Arrays.asList(l.inverters.keySet().toArray()));
        assertTrue(Double.isNaN(l.inverters.get("1")));
        assertTrue(l.hasEnvoy());
        assertTrue(l.hasBattery());
    }

    @Test
    public void totalsSkipResetsAndSplitWeekAndMonth() throws Exception {
        long day = 86_400_000L;
        long base = 1_790_000_000_000L;
        JSONObject five = new JSONObject("{\"p\": [{\"start\": 1000, \"change\": 0.5}, {\"start\": 2000, \"change\": 1.5},"
                + " {\"start\": 3000, \"change\": -4}]}");
        JSONObject days = new JSONObject("{\"p\": [{\"start\": " + (base + 10 * day) + ", \"change\": 20},"
                + " {\"start\": " + (base + 11 * day) + ", \"change\": 96000}, {\"start\": " + (base + 12 * day) + ", \"change\": 30}]}");
        Solar.Totals t = Solar.totals(new String[] {"p", null, null, null, null, null}, five, days, base + 12 * day, base + 10 * day);
        assertEquals(2.0, t.today[0], 1e-9);
        assertEquals(32.0, t.week[0], 1e-9);
        assertEquals(52.0, t.month[0], 1e-9);
        assertTrue(Double.isNaN(t.today[1]));
    }

    @Test
    public void quarterHoursAndPanelEnergy() throws Exception {
        long mid = 1_000_000_000_000L;
        JSONObject r = new JSONObject("{\"x\": [{\"start\": " + mid + ", \"change\": 0.1}, {\"start\": " + (mid + 300_000)
                + ", \"change\": 0.2}, {\"start\": " + (mid + 900_000) + ", \"change\": 0.4}]}");
        double[] q = Solar.quarterHours(Solar.series(r, "x", "change"), mid);
        assertEquals(96, q.length);
        assertEquals(0.3, q[0], 1e-9);
        assertEquals(0.4, q[1], 1e-9);
        // Two hours at 300 W and two 5-minute means of 120 W: 600 + 20 Wh.
        List<Solar.Stat> hours = Arrays.asList(new Solar.Stat(0, 300), new Solar.Stat(1, 300));
        List<Solar.Stat> five = Arrays.asList(new Solar.Stat(2, 120), new Solar.Stat(3, 120));
        assertEquals(0.62, Solar.energyFromMeans(hours, five), 1e-9);
    }

    @Test
    public void layoutFromConfigAndDefault() {
        String cfg = "{\"arrays\": [{\"name\": \"East\", \"panels\": [{\"id\": \"a\", \"x\": 0, \"y\": 0, \"w\": 2, \"h\": 1},"
                + " {\"id\": \"sensor.inverter_b\", \"x\": 0, \"y\": 1, \"w\": 2, \"h\": 1}]},"
                + " {\"name\": \"West\", \"panels\": [{\"id\": \"c\", \"x\": 0, \"y\": 0}]}]}";
        List<Solar.Group> g = Solar.layout(cfg, Arrays.asList("a", "b", "c", "d"));
        assertEquals(3, g.size());
        assertEquals("East", g.get(0).name);
        assertEquals("b", g.get(0).panels.get(1).serial);
        assertEquals("Other", g.get(2).name);
        double[] size = Solar.place(g, 1);
        assertEquals(0, g.get(0).x, 1e-9);
        assertEquals(3, g.get(1).x, 1e-9);
        assertEquals(5, g.get(2).x, 1e-9);
        assertEquals(0, g.get(1).y, 1e-9);
        assertEquals(6, size[0], 1e-9);
        assertEquals(2, size[1], 1e-9);

        List<Solar.Group> d = Solar.layout("", Arrays.asList("x", "y"));
        assertEquals("Panels", d.get(0).name);
        assertEquals(1, d.get(0).panels.get(1).x, 1e-9);
        List<Solar.Group> bad = Solar.layout("not json", Arrays.asList("x"));
        assertEquals("Panels", bad.get(0).name);
        assertEquals("x", bad.get(0).panels.get(0).serial);
    }

    @Test
    public void balanceFillsWhatTheMeterMisses() throws Exception {
        long mid = 1_790_000_000_000L;
        JSONObject r = new JSONObject("{\"p\": [{\"start\": " + mid + ", \"change\": 1.0}],"
                + " \"i\": [{\"start\": " + mid + ", \"change\": 0.2}], \"e\": [{\"start\": " + mid + ", \"change\": 0.3}],"
                + " \"c\": [{\"start\": " + mid + ", \"change\": 0.4}], \"d\": [{\"start\": " + (mid + 900_000) + ", \"change\": 0.5}]}");
        double[] use = Solar.balance(r, new String[] {"p", null, "i", "e", "c", "d"}, mid);
        assertEquals(0.5, use[0], 1e-9);
        assertEquals(0.5, use[1], 1e-9);
        assertEquals(null, Solar.balance(r, new String[] {"p", null, null, "e", null, null}, mid));
    }

    @Test
    public void words() {
        assertEquals("640 W", Solar.power(640));
        assertEquals("1.85 kW", Solar.power(-1849));
        assertEquals("26.7", Solar.energy(26.71));
        assertEquals("1,737", Solar.energy(1737.2));
        assertEquals("97.1 MWh", Solar.lifetime(97069));
        assertEquals(2.5, Solar.nice(2.2), 1e-9);
        assertEquals(0.5, Solar.nice(0.31), 1e-9);
    }
}
