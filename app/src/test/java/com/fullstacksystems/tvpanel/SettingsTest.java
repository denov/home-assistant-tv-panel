package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class SettingsTest {
    @Test
    public void readsTheIntegrationsAnswer() throws Exception {
        Settings s = Settings.parse("{\"entities\": [\"weather.home\", \"sensor.solar\"], \"camera_order\": [\"camera.b\"],"
                + " \"solar\": {\"arrays\": []}, \"row\": false}");
        assertEquals(Arrays.asList("weather.home", "sensor.solar"), s.entities);
        assertEquals(Arrays.asList("camera.b"), s.cameraOrder);
        assertEquals("{\"arrays\":[]}", s.solar);
        assertFalse(s.row);
    }

    @Test
    public void nullsMeanNotSetInTheIntegration() throws Exception {
        Settings s = Settings.parse("{\"entities\": null, \"camera_order\": null, \"solar\": null, \"row\": true}");
        assertNull(s.entities);
        assertNull(s.cameraOrder);
        assertNull(s.solar);
        assertTrue(s.row);
    }

    @Test
    public void noIntegrationMeansTheOldWay() {
        Settings s = Settings.NONE;
        assertNull(s.entities);
        assertNull(s.row);
    }

    @Test
    public void chipTemplateGetsTheListAndTheRow() throws Exception {
        Settings s = Settings.parse("{\"entities\": [\"sensor.b\", \"weather.a\"], \"row\": false}");
        JSONObject body = new JSONObject(Ha.templateBody(s));
        JSONObject vars = body.getJSONObject("variables");
        assertEquals("sensor.b", vars.getJSONArray("entities").getString(0));
        assertFalse(vars.getBoolean("row"));
        // Without the integration there are no variables, and the template falls back to labels and the helper.
        assertFalse(new JSONObject(Ha.templateBody(Settings.NONE)).has("variables"));
        assertTrue(Ha.TEMPLATE.contains("entities if entities is defined"));
    }

    @Test
    public void cameraTemplateGetsTheChipsAndTheOrder() throws Exception {
        Settings s = Settings.parse("{\"entities\": [\"camera.a\", \"sensor.x\"], \"camera_order\": [\"camera.b\", \"camera.a\"]}");
        JSONObject vars = new JSONObject(Cameras.templateBody(s)).getJSONObject("variables");
        assertEquals("camera.a", vars.getJSONArray("entities").getString(0));
        assertEquals("camera.b,camera.a", vars.getString("camera_order"));
    }

    @Test
    public void anEntityWithoutADeviceDoesNotBreakTheTemplate() {
        // The integration can list an entity that has no device, or no longer exists: device_attr(None) is an error
        // in Home Assistant that fails the whole request, so every device lookup is guarded.
        assertTrue(Ha.TEMPLATE.contains("if device_id(e) else none"));
        assertFalse(Ha.TEMPLATE.replace("if device_id(e) else none", "").contains("device_attr(device_id(e), 'name') or"));
    }

    @Test
    public void theIntegrationsSolarLayoutWinsOverThePushedOne() throws Exception {
        assertEquals("{\"arrays\":[]}", Settings.parse("{\"solar\": {\"arrays\": []}}").solarLayout("{\"old\":1}"));
        assertEquals("{\"old\":1}", Settings.NONE.solarLayout("{\"old\":1}"));
    }
}
