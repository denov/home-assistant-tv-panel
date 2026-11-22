package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class CamerasTest {
    static final String REPLY = "{\"items\": ["
            + "{\"id\": \"camera.cam_2\", \"name\": \"Camera - Back Yard\", \"state\": \"idle\", \"motion\": false},"
            + "{\"id\": \"camera.cam_0\", \"name\": \"Camera - Driveway\", \"state\": \"idle\", \"motion\": true},"
            + "{\"id\": \"camera.cam_1\", \"name\": \"Camera - Front Door\", \"state\": \"idle\", \"motion\": false},"
            + "{\"id\": \"camera.attic\", \"name\": null, \"state\": \"unavailable\", \"motion\": false}"
            + "], \"order\": \"front door, camera.cam_0 ,, Garage\"}";

    @Test
    public void ownersOrderFirstThenByName() throws Exception {
        // The helper names cameras by name (any case) or entity id; unknown entries and blanks are skipped.
        List<Cameras.Cam> c = Cameras.parse(REPLY);
        assertEquals("Front Door", c.get(0).name);
        assertEquals("Driveway", c.get(1).name);
        assertTrue(c.get(1).motion);
        // Not in the order: by name.
        assertEquals("Attic", c.get(2).name);
        assertFalse(c.get(2).ok());
        assertEquals("Back Yard", c.get(3).name);
        assertTrue(Cameras.anyMotion(c));
        // No helper: by name.
        List<Cameras.Cam> byName = Cameras.parse(REPLY.replace("front door, camera.cam_0 ,, Garage", "unknown"));
        assertEquals("Attic", byName.get(0).name);
        assertEquals("Front Door", byName.get(3).name);
    }

    @Test
    public void namesAndOrderConfig() {
        assertEquals("Hallway", Cameras.name("Camera - Hallway", "camera.x"));
        assertEquals("Studio", Cameras.name("camera: Studio", "camera.x"));
        assertEquals("Camera Roof", Cameras.name("Camera Roof", "camera.x"));
        assertEquals("Side gate", Cameras.name("", "camera.side_gate"));
        assertEquals(Arrays.asList("Front Door", "camera.b"), Cameras.orderOf(" Front Door,camera.b, "));
        assertTrue(Cameras.orderOf("unavailable").isEmpty());
        assertTrue(Cameras.orderOf("").isEmpty());
    }

    @Test
    public void camerasBecomeOneIconChip() throws Exception {
        String reply = "{\"on\": \"on\", \"items\": ["
                + "{\"id\": \"camera.cam_0\", \"name\": \"Camera - Driveway\", \"device\": \"Cam 0\", \"state\": \"idle\", \"motion\": false},"
                + "{\"id\": \"camera.cam_1\", \"name\": \"Camera - Front Door\", \"device\": \"Cam 1\", \"state\": \"idle\", \"motion\": true},"
                + "{\"id\": \"weather.home\", \"name\": \"Home\", \"device\": \"Home\", \"state\": \"sunny\", \"temp\": 70, \"tunit\": \"°F\"},"
                + "{\"id\": \"sensor.apex_ph\", \"name\": \"Apex pH\", \"device\": \"Apex\", \"state\": \"8.18\"}"
                + "]}";
        List<Ha.Widget> w = Ha.parseWidgets(reply);
        assertEquals(3, w.size());
        assertEquals("weather.home", w.get(0).key);
        Ha.Widget cams = w.get(1);
        assertEquals(Ha.CAMERAS, cams.key);
        assertEquals("", cams.value);
        assertEquals("motion_sensor", cams.icon);
        assertEquals(Ha.ORANGE, cams.colour);
        assertTrue(cams.open);
        assertEquals(Ha.CAMERAS, cams.screen);

        String still = reply.replace("\"motion\": true", "\"motion\": false");
        assertEquals("cctv", Ha.parseWidgets(still).get(1).icon);
    }

    @Test
    public void mjpegFramesWithAndWithoutLength() throws Exception {
        byte[] a = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        byte[] b = {(byte) 0xFF, (byte) 0xD8, 9, (byte) 0xFF, 0, (byte) 0xFF, (byte) 0xD9};
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(("--myboundary\r\nContent-Type: image/jpeg\r\nContent-Length: " + a.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        s.write(a);
        s.write("\r\n--myboundary\r\nContent-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        s.write(b);
        s.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        Cameras.Mjpeg m = new Cameras.Mjpeg(new ByteArrayInputStream(s.toByteArray()));
        assertTrue(m.next());
        assertArrayEquals(a, Arrays.copyOf(m.buffer(), m.length()));
        assertTrue(m.next());
        assertArrayEquals(b, Arrays.copyOf(m.buffer(), m.length()));
        assertFalse(m.next());
    }
}
