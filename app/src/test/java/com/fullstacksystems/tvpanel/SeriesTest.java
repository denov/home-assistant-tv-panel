package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** A sensor's last 24 hours for a graph: 5-minute statistics, or its history when it has none. */
public class SeriesTest {
    @Test
    public void aSensorsHistoryAsNumbers() throws Exception {
        long t0 = 1_700_000_000L;
        // minimal_response: only the first row has attributes.
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("s", "42").put("a", new JSONObject()).put("lu", t0 + 0.25))
                .put(new JSONObject().put("s", "unavailable").put("lu", t0 + 60))
                .put(new JSONObject().put("s", "44.5").put("lu", t0 + 120))
                .put(new JSONObject().put("s", "44.5").put("lc", t0 + 180));
        List<Solar.Stat> s = Series.fromHistory(rows);
        assertEquals(4, s.size());
        assertEquals(t0 * 1000 + 250, s.get(0).start);
        assertEquals(42, s.get(0).value, 0);
        // A gap where it wasn't a number, so the line breaks there.
        assertEquals(Double.NaN, s.get(1).value, 0);
        assertEquals(44.5, s.get(2).value, 0);
        assertEquals((t0 + 180) * 1000, s.get(3).start);
        assertEquals(0, Series.fromHistory(null).size());
    }

    private static java.util.List<Solar.Stat> rows(long t0, double... v) {
        java.util.List<Solar.Stat> out = new java.util.ArrayList<>();
        for (int i = 0; i < v.length; i++) out.add(new Solar.Stat(t0 + i * 600_000L, v[i]));
        return out;
    }

    @Test
    public void theLowAndHighOfTheDay() {
        long t0 = 1_700_000_000_000L;
        // Rows every 10 minutes; the window starts at the third. The row it starts with counts, older ones don't.
        List<Solar.Stat> s = rows(t0, 900, 50, 61, Double.NaN, 75, 58);
        assertArrayEquals(new double[] {50, 75}, Series.lowHigh(s, t0 + 1_000_000L), 0);
        assertArrayEquals(new double[] {58, 75}, Series.lowHigh(s, t0 + 1_200_000L), 0);
        assertNull(Series.lowHigh(rows(t0), t0));
        assertNull(Series.lowHigh(rows(t0, Double.NaN), t0));
    }

    @Test
    public void whatItWasAtATime() {
        long t0 = 1_700_000_000_000L;
        List<Solar.Stat> s = rows(t0, 50, 61, Double.NaN, 75);
        // Held until the next row.
        assertEquals(61, Series.at(s, t0 + 900_000L), 0);
        assertEquals(50, Series.at(s, t0), 0);
        // Before the first row, or in a gap: not known.
        assertTrue(Double.isNaN(Series.at(s, t0 - 1)));
        assertTrue(Double.isNaN(Series.at(s, t0 + 1_300_000L)));
        assertEquals(75, Series.at(s, t0 + 99_000_000L), 0);
    }

    @Test
    public void statisticsThatStopAreAGapNotAHold() {
        long t0 = 1_700_000_000_000L, p = 300_000L;
        List<Solar.Stat> s = new java.util.ArrayList<>();
        s.add(new Solar.Stat(t0, 600));
        s.add(new Solar.Stat(t0 + p, 610));
        s.add(new Solar.Stat(t0 + 43 * p, 950));
        // Offline for three and a half hours: an hour before it's back isn't the last reading before it went.
        List<Solar.Stat> g = Series.gaps(s, p, t0 + 44 * p);
        assertEquals(610, Series.at(g, t0 + p + 60_000L), 0);
        assertTrue(Double.isNaN(Series.at(g, t0 + 31 * p)));
        assertEquals(950, Series.at(g, t0 + 43 * p), 0);
        // Rows that stop well before now end in a gap too...
        g = Series.gaps(s.subList(0, 2), p, t0 + 40 * p);
        assertTrue(Double.isNaN(Series.at(g, t0 + 28 * p)));
        // ...and ones that are up to date, or only a period or two behind, are left as they are.
        assertEquals(2, Series.gaps(s.subList(0, 2), p, t0 + 3 * p).size());
        assertEquals(0, Series.gaps(new java.util.ArrayList<>(), p, t0).size());
    }
}
