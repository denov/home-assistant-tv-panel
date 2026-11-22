package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensors' last hours for the screens' graphs: the recorder's 5-minute statistics, or, for a sensor without a
 * state_class (which has none), its history. Both over the websocket, which the TV's normal user may read.
 */
final class Series {
    private Series() {
    }

    /** Home Assistant's time zone, so the graphs' hours are the house's; the TV's own when it can't be read. */
    static ZoneId zone(HaClient ha) {
        try {
            HaClient.Reply cfg = ha.get("/api/config");
            if (cfg.ok()) return ZoneId.of(new JSONObject(cfg.body).optString("time_zone"));
        } catch (Exception ignored) {
            // the TV's own zone
        }
        return ZoneId.systemDefault();
    }

    /** Each id's values since {@code fromMs}: statistics' means where there are any, else its history. */
    static Map<String, List<Solar.Stat>> fetch(HaSocket ws, List<String> ids, long fromMs) throws Exception {
        Map<String, List<Solar.Stat>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String start = Instant.ofEpochMilli(fromMs).toString();
        Object stats = ws.call(new JSONObject()
                .put("type", "recorder/statistics_during_period")
                .put("start_time", start)
                .put("statistic_ids", new JSONArray(ids))
                .put("period", "5minute")
                .put("types", new JSONArray().put("mean")));
        JSONObject st = stats instanceof JSONObject ? (JSONObject) stats : new JSONObject();
        List<String> without = new ArrayList<>();
        for (String id : ids) {
            List<Solar.Stat> s = Solar.series(st, id, "mean");
            if (s.isEmpty()) without.add(id);
            else out.put(id, s);
        }
        if (without.isEmpty()) return out;
        JSONObject h = history(ws, without, fromMs, false);
        for (String id : without) out.put(id, fromHistory(h.optJSONArray(id)));
        return out;
    }

    /** history/history_during_period for {@code ids}: with every attribute when {@code attributes}, else states only. */
    static JSONObject history(HaSocket ws, List<String> ids, long fromMs, boolean attributes) throws Exception {
        Object r = ws.call(new JSONObject()
                .put("type", "history/history_during_period")
                .put("start_time", Instant.ofEpochMilli(fromMs).toString())
                .put("entity_ids", new JSONArray(ids))
                .put("include_start_time_state", true)
                .put("significant_changes_only", false)
                .put("minimal_response", !attributes)
                .put("no_attributes", !attributes));
        return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
    }

    /** A sensor's history rows ({"s", "lu" or "lc" in seconds}) as numbers; NaN where it wasn't one, as a gap. */
    static List<Solar.Stat> fromHistory(JSONArray rows) {
        List<Solar.Stat> out = new ArrayList<>();
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            double sec = r.optDouble("lu", r.optDouble("lc", Double.NaN));
            if (Double.isNaN(sec)) continue;
            String s = Ha.str(r, "s");
            out.add(new Solar.Stat(Math.round(sec * 1000), Ha.isNumber(s) ? Double.parseDouble(s) : Double.NaN));
        }
        return out;
    }
}
