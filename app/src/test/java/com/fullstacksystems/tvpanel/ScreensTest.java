package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 0.7.0: the TV's screens, a list the owner adds thermostat and air quality screens to, each opened by its id. */
public class ScreensTest {
    private static final String CONFIG = "{\"chips\": ["
            // A screen apps before 0.7.0 don't have goes as "none" with the screen, so to them OK does nothing.
            + "{\"type\": \"entity\", \"entity\": \"climate.up\", \"ok\": {\"action\": \"none\", \"screen\": \"thermostat\"}},"
            + "{\"type\": \"entity\", \"entity\": \"sensor.co2\", \"ok\": {\"action\": \"screen\", \"screen\": \"air_quality\"}},"
            + "{\"type\": \"entity\", \"entity\": \"light.hall\", \"ok\": {\"action\": \"none\"}}],"
            + " \"entities\": [\"climate.up\", \"sensor.co2\", \"light.hall\"],"
            + " \"screens\": [{\"id\": \"weather\", \"type\": \"weather\"},"
            + " {\"id\": \"thermostat\", \"type\": \"thermostat\", \"title\": \"Upstairs\", \"climates\": [\"climate.up\"],"
            + " \"sensors\": [\"sensor.outside\"]},"
            + " {\"id\": \"air_quality\", \"type\": \"air_quality\", \"title\": \"Air\", \"sensors\": [\"sensor.co2\"]}]}";

    private static String reply(JSONObject... items) throws Exception {
        return new JSONObject().put("on", "on").put("ordered", true).put("items", new JSONArray(Arrays.asList(items)))
                .toString();
    }

    private static JSONObject item(String id, String name, String state) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("state", state);
    }

    @Test
    public void theListAsTheIntegrationSendsIt() throws Exception {
        Settings s = Settings.parse(CONFIG);
        assertEquals(3, s.screens.size());
        Settings.Screen up = s.screen("thermostat");
        assertEquals("thermostat", up.type);
        assertEquals("Upstairs", up.title);
        assertEquals(Arrays.asList("climate.up"), up.climates);
        assertEquals(Arrays.asList("sensor.outside"), up.sensors);
        assertEquals("air_quality", s.screen("air_quality").type);
        assertNull(s.screen("solar"));
    }

    @Test
    public void withoutAListTheFourBuiltInOnes() throws Exception {
        // Integrations before 0.7.0, or none at all.
        for (Settings s : new Settings[] {Settings.parse("{\"entities\": []}"), Settings.NONE}) {
            assertEquals(4, s.screens.size());
            assertEquals("solar", s.screen("solar").type);
            assertNull(s.screen("thermostat"));
        }
    }

    @Test
    public void okOpensAnAddedScreenByItsId() throws Exception {
        Settings s = Settings.parse(CONFIG);
        List<Ha.Widget> w = Ha.parseWidgets(reply(item("climate.up", "Up", "heat"), item("sensor.co2", "CO2", "600"),
                item("light.hall", "Hall", "on")), s);
        assertEquals(Ha.ADDED_SCREEN + "thermostat", w.get(0).screen);
        assertEquals(Ha.ADDED_SCREEN + "air_quality", w.get(1).screen);
        // "none" without a screen still does nothing but the event.
        assertEquals(Ha.EVENT_ONLY, w.get(2).screen);
        assertTrue(s.oks.get("climate.up").isScreen());
    }

    @Test
    public void anAddedScreensTypeFromTheListElseFromItsId() throws Exception {
        Settings s = Settings.parse(CONFIG);
        assertEquals("thermostat", Ha.screenType(s, "thermostat"));
        assertEquals("air_quality", Ha.screenType(s, "air_quality"));
        // Not in the list the app has (it was restarted and hasn't asked yet): the page makes ids from the type.
        assertEquals("thermostat", Ha.screenType(Settings.NONE, "thermostat_2"));
        assertEquals("air_quality", Ha.screenType(Settings.NONE, "air_quality_3"));
        assertEquals("", Ha.screenType(Settings.NONE, "radio"));
        // A screen the list has wins over what its id looks like.
        Settings odd = Settings.parse("{\"screens\": [{\"id\": \"thermostat_x\", \"type\": \"air_quality\","
                + " \"title\": \"Air\", \"sensors\": [\"sensor.a\"]}]}");
        assertEquals("air_quality", Ha.screenType(odd, "thermostat_x"));
    }
}
