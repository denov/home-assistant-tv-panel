package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** A weather entity's current state and forecasts, from /api/states and weather.get_forecasts. Pure Java. */
final class Weather {
    static final class Now {
        String place = "";
        String condition = "";
        double temp = Double.NaN, feels = Double.NaN, humidity = Double.NaN, dewPoint = Double.NaN;
        double pressure = Double.NaN, wind = Double.NaN, bearing = Double.NaN, uv = Double.NaN, cloud = Double.NaN;
        String tempUnit = "", pressureUnit = "", windUnit = "", precipUnit = "";
        String attribution = "";
    }

    static final class Point {
        ZonedDateTime time;
        String condition = "";
        double temp = Double.NaN, low = Double.NaN, precip = Double.NaN, precipChance = Double.NaN, wind = Double.NaN;
    }

    private Weather() {
    }

    static Now now(String stateJson) throws JSONException {
        JSONObject o = new JSONObject(stateJson);
        JSONObject a = o.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        Now n = new Now();
        n.condition = o.optString("state", "");
        n.place = a.optString("friendly_name", "");
        n.temp = num(a, "temperature");
        n.feels = num(a, "apparent_temperature");
        n.humidity = num(a, "humidity");
        n.dewPoint = num(a, "dew_point");
        n.pressure = num(a, "pressure");
        n.wind = num(a, "wind_speed");
        n.bearing = num(a, "wind_bearing");
        n.uv = num(a, "uv_index");
        n.cloud = num(a, "cloud_coverage");
        n.tempUnit = a.optString("temperature_unit", "");
        n.pressureUnit = a.optString("pressure_unit", "");
        n.windUnit = a.optString("wind_speed_unit", "");
        n.precipUnit = a.optString("precipitation_unit", "");
        n.attribution = a.optString("attribution", "");
        return n;
    }

    /** The service response {"service_response": {"weather.x": {"forecast": [...]}}}, times in {@code zone}. */
    static List<Point> forecast(String json, String entityId, ZoneId zone) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject resp = root.optJSONObject("service_response");
        if (resp == null) resp = root;
        JSONObject ent = resp.optJSONObject(entityId);
        JSONArray list = ent == null ? null : ent.optJSONArray("forecast");
        List<Point> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            Point p = new Point();
            try {
                p.time = OffsetDateTime.parse(f.optString("datetime")).atZoneSameInstant(zone);
            } catch (DateTimeParseException e) {
                continue;
            }
            p.condition = f.optString("condition", "");
            p.temp = num(f, "temperature");
            p.low = num(f, "templow");
            p.precip = num(f, "precipitation");
            p.precipChance = num(f, "precipitation_probability");
            p.wind = num(f, "wind_speed");
            out.add(p);
        }
        return out;
    }

    static String forecastBody(String entityId, String type) {
        try {
            return new JSONObject().put("entity_id", entityId).put("type", type).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static double num(JSONObject o, String k) {
        if (o == null || o.isNull(k) || !o.has(k)) return Double.NaN;
        return o.optDouble(k, Double.NaN);
    }

    /** "67°" with no decimals for temperatures. */
    static String deg(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v) + "°";
    }
}
