package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 0.3.0 settings: chips with labels of their own, the tank's columns in a set order, and thermostats. */
public class ChipsTest {
    private static String reply(JSONObject... items) throws Exception {
        return new JSONObject().put("on", "on").put("ordered", true).put("items", new JSONArray(Arrays.asList(items)))
                .toString();
    }

    private static JSONObject item(String id, String name, String state) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("state", state);
    }

    private static final String SETTINGS = "{\"chips\": [{\"type\": \"entity\", \"entity\": \"climate.hall\", \"label\": \"Hall\"},"
            + " {\"type\": \"tank\", \"label\": \"Reef\"}, {\"type\": \"cameras\"}, {\"type\": \"entity\", \"entity\": \"sensor.x\"}],"
            + " \"cameras\": [\"camera.b\", \"camera.a\"], \"tank\": [\"sensor.apex_temp\", \"sensor.apex_ph\"],"
            + " \"entities\": [\"climate.hall\", \"sensor.apex_temp\", \"sensor.apex_ph\", \"camera.b\", \"camera.a\", \"sensor.x\"],"
            + " \"camera_order\": [\"camera.b\", \"camera.a\"], \"row\": true}";

    @Test
    public void settingsReadTheChipsTheirLabelsAndTheCameras() throws Exception {
        Settings s = Settings.parse(SETTINGS);
        assertEquals("Hall", s.labels.get("climate.hall"));
        assertEquals("Reef", s.labels.get(Ha.APEX));
        assertFalse(s.labels.containsKey("sensor.x"));
        assertEquals(Arrays.asList("camera.b", "camera.a"), s.cameraOrder);
    }

    @Test
    public void aChipsOwnLabelReplacesTheEntitysName() throws Exception {
        Settings s = Settings.parse(SETTINGS);
        List<Ha.Widget> w = Ha.parseWidgets(reply(
                item("climate.hall", "Hallway Thermostat", "heat").put("cur", 68).put("temp", 70),
                item("sensor.x", "Some Sensor", "3")), s);
        assertEquals("Hall", w.get(0).label);
        assertEquals("Some Sensor", w.get(1).label);
    }

    @Test
    public void theTanksColumnsFollowTheSetOrder() throws Exception {
        Settings s = Settings.parse(SETTINGS);
        List<Ha.Widget> w = Ha.parseWidgets(reply(
                item("sensor.apex_temp", "Apex Temp", "78.9").put("device", "Apex").put("apex", "Temp").put("unit", "°F"),
                item("sensor.apex_ph", "Apex pH", "8.29").put("device", "Apex").put("apex", "pH")), s);
        assertEquals(Ha.APEX, w.get(0).key);
        // Temp before pH, as set; the labels would have put pH first. A label on the tank chip doesn't replace its
        // columns.
        assertEquals("Temp\tpH", w.get(0).label);
        assertEquals("78.9 °F\t8.29", w.get(0).value);
    }

    @Test
    public void aThermostatShowsTheTemperatureAndWhereItsSet() throws Exception {
        Ha.Widget heat = Ha.widget(item("climate.hall", "Hall", "heat").put("cur", 68).put("temp", 70).put("action", "heating"));
        assertEquals("68° → 70°", heat.value);
        assertEquals("thermostat", heat.icon);
        assertEquals(Ha.ORANGE, heat.colour);

        Ha.Widget range = Ha.widget(item("climate.hall", "Hall", "heat_cool").put("cur", 71.5)
                .put("lo", 68).put("hi", 74).put("action", "idle"));
        assertEquals("71.5° → 68–74°", range.value);
        assertEquals(Ha.BLUE, range.colour);

        Ha.Widget off = Ha.widget(item("climate.hall", "Hall", "off").put("cur", 66));
        assertEquals("66° · Off", off.value);

        Ha.Widget gone = Ha.widget(item("climate.hall", "Hall", "unavailable"));
        assertEquals("—", gone.value);
        assertEquals(Ha.GREY, gone.colour);
    }

    @Test
    public void theTemplateSendsWhatAThermostatNeeds() {
        for (String attr : new String[] {"current_temperature", "target_temp_low", "target_temp_high", "hvac_action"}) {
            assertEquals(attr, true, Ha.TEMPLATE.contains("'" + attr + "'"));
        }
    }

    @Test
    public void thermostatIconIsBundled() {
        assertEquals(true, Icons.NAMES.contains("thermostat"));
    }

    @Test
    public void theCameraGridUsesItsOwnListEvenWithoutTheChip() throws Exception {
        Settings s = Settings.parse("{\"chips\": [{\"type\": \"entity\", \"entity\": \"weather.home\"}],"
                + " \"cameras\": [\"camera.b\", \"camera.a\"], \"entities\": [\"weather.home\"]}");
        JSONObject vars = new JSONObject(Cameras.templateBody(s)).getJSONObject("variables");
        assertEquals("camera.b", vars.getJSONArray("entities").getString(0));
        assertEquals(2, vars.getJSONArray("entities").length());
        assertEquals("camera.b,camera.a", vars.getString("camera_order"));
        // The chip template still gets the chips' list.
        assertEquals(1, new JSONObject(Ha.templateBody(s)).getJSONObject("variables").getJSONArray("entities").length());
    }

    @Test
    public void withoutATankOrderTheColumnsStayByLabel() throws Exception {
        // Integration 0.2.0 sends a chip list but no tank order: the columns stay alphabetical, as they were.
        Settings s = Settings.parse("{\"entities\": [\"sensor.apex_temp\", \"sensor.apex_ph\"], \"row\": true}");
        List<Ha.Widget> w = Ha.parseWidgets(reply(
                item("sensor.apex_temp", "Apex Temp", "78.9").put("device", "Apex").put("apex", "Temp"),
                item("sensor.apex_ph", "Apex pH", "8.29").put("device", "Apex").put("apex", "pH")), s);
        assertEquals("pH\tTemp", w.get(0).label);
    }

    private static final String OKS = "{\"chips\": ["
            + "{\"type\": \"entity\", \"entity\": \"weather.home\", \"ok\": {\"action\": \"none\"}},"
            + "{\"type\": \"entity\", \"entity\": \"sensor.x\", \"ok\": {\"action\": \"page\", \"page\": \"solar\"}},"
            + "{\"type\": \"entity\", \"entity\": \"light.hall\", \"ok\": {\"action\": \"toggle\", \"entity\": \"light.hall\", \"confirm\": true}},"
            + "{\"type\": \"entity\", \"entity\": \"sensor.y\", \"ok\": {\"action\": \"page\", \"page\": \"camera\", \"camera\": \"camera.drive\"}},"
            + "{\"type\": \"tank\", \"ok\": {\"action\": \"run\", \"entity\": \"script.feed\", \"confirm\": false}}],"
            + " \"tank\": [\"sensor.apex_ph\"], \"entities\": [\"weather.home\", \"sensor.x\", \"light.hall\", \"sensor.y\", \"sensor.apex_ph\"]}";

    @Test
    public void whatOkDoesComesFromTheIntegration() throws Exception {
        Settings s = Settings.parse(OKS);
        List<Ha.Widget> w = Ha.parseWidgets(reply(
                item("weather.home", "Home", "sunny").put("temp", 60),
                item("sensor.x", "X", "1"),
                item("light.hall", "Hall", "on"),
                item("sensor.y", "Y", "2"),
                item("sensor.apex_ph", "Apex pH", "8.2").put("device", "Apex").put("apex", "pH")), s);
        // Weather would open its page; here OK does nothing, but the press still reaches the integration, for its
        // event (automations can act on any chip).
        assertEquals(Ha.EVENT_ONLY, w.get(0).page);
        assertEquals(true, w.get(0).open);
        assertEquals(Ha.SOLAR_PAGE, w.get(1).page);
        // A toggle, a script or an action is a press the integration carries out.
        assertEquals(Ha.PRESS, w.get(2).page);
        assertEquals(true, s.oks.get("light.hall").confirm);
        assertEquals("camera/camera.drive", w.get(3).page);
        assertEquals(Ha.PRESS, w.get(4).page);
        assertFalse(s.oks.get(Ha.APEX).confirm);
    }

    @Test
    public void withoutTheIntegrationsAnswerOkStaysAsItWas() throws Exception {
        Settings s = Settings.parse("{\"entities\": [\"weather.home\"], \"row\": true}");
        assertEquals(true, s.oks.isEmpty());
        List<Ha.Widget> w = Ha.parseWidgets(reply(item("weather.home", "Home", "sunny").put("temp", 60)), s);
        assertEquals(Ha.WEATHER, w.get(0).page);
    }

    @Test
    public void thePressAndItsAnswer() throws Exception {
        assertEquals("light.hall", new JSONObject(Ha.pressBody("light.hall")).getString("key"));
        assertEquals("Hall: turning on", Ha.pressMessage("{\"ok\": true, \"message\": \"Hall: turning on\"}"));
        assertEquals("Hall: no such service", Ha.pressMessage("{\"ok\": false, \"message\": \"Hall: no such service\"}"));
        // A page, or nothing to do: no toast.
        assertEquals("", Ha.pressMessage("{\"ok\": true, \"page\": \"tank\"}"));
        assertEquals("", Ha.pressMessage("{\"ok\": true}"));
        assertEquals("Home Assistant's answer made no sense.", Ha.pressMessage("nope"));
    }

    @Test
    public void aPressForTheEventOnlySaysSo() throws Exception {
        // The integration fires the event and does nothing else: the TV may not have caught up with a change to the
        // chip, and must never toggle a garage door it thinks is a page.
        JSONObject page = new JSONObject(Ha.pressBody("sensor.x", "page"));
        assertEquals("sensor.x", page.getString("key"));
        assertEquals(true, page.getBoolean("event_only"));
        assertEquals("page", page.getString("action"));
        assertFalse(new JSONObject(Ha.pressBody("light.hall")).has("event_only"));
    }

    @Test
    public void afterARestartOnlyKnownPagesAreGuessed() {
        // No chips yet (the app was restarted): a guess from the key, and none for a chip that could do anything.
        assertEquals(Ha.WEATHER, Ha.guessPage("weather.home"));
        assertEquals(Ha.CAMERAS, Ha.guessPage(Ha.CAMERAS));
        assertEquals(Ha.CAMERAS, Ha.guessPage("camera.drive"));
        assertEquals(Ha.APEX, Ha.guessPage(Ha.APEX));
        assertEquals(Ha.APEX, Ha.guessPage("sensor.apex_temp"));
        assertEquals(Ha.SOLAR_PAGE, Ha.guessPage("sensor.envoy_123_current_power_production"));
        assertEquals("", Ha.guessPage("sensor.hall_temperature"));
        assertEquals("", Ha.guessPage("cover.garage_door"));
    }
}
