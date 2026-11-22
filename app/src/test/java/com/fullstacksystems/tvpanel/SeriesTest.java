package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;

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
}
