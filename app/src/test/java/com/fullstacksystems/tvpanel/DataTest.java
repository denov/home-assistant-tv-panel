package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the TV draws comes from the integration (/api/tv_panel/data/<name>), so the TV's user needn't be an admin.
 * Without the integration (an older setup with a pushed admin token, or an integration before 0.6.0), the app's own
 * template still works.
 */
public class DataTest {
    private ServerSocket ha;
    /** Path to [status, body]: what the stand-in Home Assistant answers. */
    private final Map<String, Object[]> answers = new HashMap<>();
    /** "METHOD path body", in order. */
    private final List<String> asked = new CopyOnWriteArrayList<>();

    /** A stand-in Home Assistant: one request per connection, as HaClient makes them. */
    @Before
    public void start() throws Exception {
        ha = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!ha.isClosed()) {
                try (Socket c = ha.accept()) {
                    answer(c);
                } catch (Exception e) {
                    // Closed by stop().
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void answer(Socket c) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String[] line = in.readLine().split(" ");
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        char[] body = new char[length];
        for (int n = 0; n < length; ) n += in.read(body, n, length - n);
        asked.add(line[0] + " " + line[1] + " " + new String(body));
        Object[] a = answers.getOrDefault(line[1], new Object[]{404, "{\"message\": \"Not found\"}"});
        byte[] out = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 " + a[0] + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(out);
        o.flush();
    }

    @After
    public void stop() throws Exception {
        ha.close();
    }

    private HaClient client() {
        return new HaClient("http://127.0.0.1:" + ha.getLocalPort(), "token");
    }

    @Test
    public void theIntegrationAnswersWhenItIsThere() throws Exception {
        answers.put("/api/tv_panel/data/chips", new Object[]{200, "{\"items\": []}"});

        HaClient.Reply r = Ha.data(client(), Ha.DATA_CHIPS, Ha.templateBody());

        assertTrue(r.ok());
        assertEquals(0, new JSONObject(r.body).getJSONArray("items").length());
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).startsWith("GET /api/tv_panel/data/chips"));
    }

    @Test
    public void withoutTheIntegrationTheAppsOwnTemplate() throws Exception {
        answers.put("/api/template", new Object[]{200, "{\"items\": [], \"on\": \"on\"}"});

        HaClient.Reply r = Ha.data(client(), Ha.DATA_CHIPS, Ha.templateBody());

        assertTrue(r.ok());
        assertEquals("on", new JSONObject(r.body).getString("on"));
        assertEquals(2, asked.size());
        assertTrue(asked.get(1).startsWith("POST /api/template "));
        assertTrue(asked.get(1).contains("label_entities"));
    }

    @Test
    public void anErrorIsShownNotWorkedAround() {
        // Home Assistant refused the token, or is restarting: the template would fail the same way, so don't ask it.
        answers.put("/api/tv_panel/data/tank", new Object[]{401, "401: Unauthorized"});

        HaClient.Reply r = Ha.data(client(), Ha.DATA_TANK, Apex.templateBody());

        assertEquals(401, r.code);
        assertEquals(1, asked.size());
    }

    @Test
    public void eachScreenHasItsName() {
        assertEquals("chips", Ha.DATA_CHIPS);
        assertEquals("cameras", Ha.DATA_CAMERAS);
        assertEquals("tank", Ha.DATA_TANK);
        assertEquals("solar", Ha.DATA_SOLAR);
    }

    @Test
    public void pairingTellsTheIntegrationNoAdminIsNeeded() throws Exception {
        JSONObject answer = PairServer.answer("abc123", "BRAVIA", "http://ha:8123");
        assertEquals("abc123", answer.getString("id"));
        assertEquals("BRAVIA", answer.getString("name"));
        assertEquals("http://ha:8123", answer.getString("url"));
        assertFalse(answer.getBoolean("needs_admin"));
    }
}
