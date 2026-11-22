package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Which weather entity the weather screen shows when it's opened without a chip (tv_panel.show, voice). */
public class WeatherPickTest {
    // NWS first, as Home Assistant may list it: hourly and twice-daily forecasts only (6), and no reading right now.
    private static JSONArray states() throws Exception {
        return new JSONArray("["
            + "{\"entity_id\": \"sensor.x\", \"state\": \"1\", \"attributes\": {}},"
            + "{\"entity_id\": \"weather.nws\", \"state\": \"unknown\", \"attributes\": {\"supported_features\": 6}},"
            + "{\"entity_id\": \"weather.station\", \"state\": \"sunny\", \"attributes\": {\"supported_features\": 2}},"
            + "{\"entity_id\": \"weather.forecast_home\", \"state\": \"cloudy\", \"attributes\": {\"supported_features\": 3}},"
            + "{\"entity_id\": \"weather.gone\", \"state\": \"unavailable\", \"attributes\": {\"supported_features\": 3}}"
            + "]");
    }

    @Test
    public void theTvsOwnWeatherChipWins() throws Exception {
        assertEquals("weather.nws", Ha.pickWeather(Arrays.asList("cover.garage", "weather.nws"), states()));
    }

    @Test
    public void withoutOneItTakesAWeatherThatsReportingWithADailyForecast() throws Exception {
        assertEquals("weather.forecast_home", Ha.pickWeather(Collections.emptyList(), states()));
        assertEquals("weather.forecast_home", Ha.pickWeather(null, states()));
    }

    @Test
    public void thenOneThatsReporting() throws Exception {
        JSONArray noDaily = new JSONArray("["
                + "{\"entity_id\": \"weather.nws\", \"state\": \"unknown\", \"attributes\": {\"supported_features\": 6}},"
                + "{\"entity_id\": \"weather.station\", \"state\": \"sunny\", \"attributes\": {\"supported_features\": 2}}"
                + "]");
        assertEquals("weather.station", Ha.pickWeather(null, noDaily));
    }

    @Test
    public void anUnavailableOneIsNeverPicked() throws Exception {
        JSONArray gone = new JSONArray("[{\"entity_id\": \"weather.gone\", \"state\": \"unavailable\", \"attributes\": {}}]");
        assertNull(Ha.pickWeather(null, gone));
        assertEquals("weather.nws", Ha.pickWeather(null, new JSONArray(
                "[{\"entity_id\": \"weather.nws\", \"state\": \"unknown\", \"attributes\": {\"supported_features\": 6}}]")));
    }

    @Test
    public void aChipForAWeatherThatsGoneOrUnavailableDoesntWin() throws Exception {
        // The settings can still name a weather entity that was removed, or one that's unavailable.
        assertEquals("weather.forecast_home", Ha.pickWeather(Collections.singletonList("weather.old"), states()));
        assertEquals("weather.forecast_home", Ha.pickWeather(Collections.singletonList("weather.gone"), states()));
        assertEquals("weather.station",
                Ha.pickWeather(Arrays.asList("weather.old", "weather.station"), states()));
    }
}
