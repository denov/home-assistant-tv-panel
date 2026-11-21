package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Messages from Home Assistant: the app keeps a websocket open, subscribed to tv_panel/messages, and shows each message
 * as a toast without opening anything, so what's playing keeps playing. Here against a stand-in Home Assistant.
 */
public class MessagesTest {
    private ServerSocket ha;
    /** What the TV sent, as text messages, in order. */
    private final List<String> got = new CopyOnWriteArrayList<>();
    /** How the stand-in answers the TV's sign-in. Anything but auth_ok, and it hangs up, as Home Assistant does. */
    private String authReply = "{\"type\": \"auth_ok\"}";

    @After
    public void stop() throws Exception {
        if (ha != null) ha.close();
    }

    // ---- the messages themselves -------------------------------------------------------------------------------

    @Test
    public void anEventIsAMessageToShow() throws Exception {
        Messages.Message m = Messages.parse("{\"id\": 1, \"type\": \"event\", \"event\": "
                + "{\"title\": \"Garage\", \"message\": \"Left open\", \"pause\": false}}");
        assertEquals("Garage: Left open", m.text);
        assertFalse(m.pause);
    }

    @Test
    public void pauseOnlyWhenTheTvsSwitchSaysSo() throws Exception {
        Messages.Message m = Messages.parse("{\"id\": 1, \"type\": \"event\", \"event\": "
                + "{\"title\": null, \"message\": \"Someone's at the door\", \"pause\": true}}");
        assertEquals("Someone's at the door", m.text);
        assertTrue(m.pause);
    }

    @Test
    public void anythingElseIsNot() throws Exception {
        assertNull(Messages.parse("{\"id\": 1, \"type\": \"result\", \"success\": true}"));
        assertNull(Messages.parse("{\"id\": 2, \"type\": \"pong\"}"));
        assertNull(Messages.parse("{\"id\": 1, \"type\": \"event\", \"event\": {\"message\": \"\"}}"));
    }

    @Test
    public void triesAgainSoonThenLessOften() {
        Messages.Result dropped = new Messages.Result(Messages.Why.DROPPED, false);
        assertEquals(2_000, Messages.waitMs(dropped, 0));
        assertEquals(4_000, Messages.waitMs(dropped, 1));
        assertEquals(Messages.MAX_RETRY_MS, Messages.waitMs(dropped, 10));
        assertEquals(Messages.MAX_RETRY_MS, Messages.waitMs(dropped, 1000));
    }

    @Test
    public void anIntegrationWithoutMessagesIsAskedLessAndLess() {
        // Right after Home Assistant restarts, tv_panel/messages is unknown until the integration has loaded: try
        // again soon. An integration that never has it (before 0.6.0) is asked only every RARELY_MS in the end.
        Messages.Result unknown = new Messages.Result(Messages.Why.UNSUPPORTED, false);
        assertEquals(2_000, Messages.waitMs(unknown, 0));
        assertEquals(Messages.RARELY_MS, Messages.waitMs(unknown, 30));
        assertEquals(Messages.RARELY_MS, Messages.waitMs(new Messages.Result(Messages.Why.REFUSED, false), 30));
    }

    // ---- one connection -------------------------------------------------------------------------------------------

    @Test
    public void listensAndShowsWhatComes() throws Exception {
        serve("{\"id\": %d, \"type\": \"result\", \"success\": true, \"result\": null}",
                "{\"id\": %d, \"type\": \"event\", \"event\": {\"title\": \"Garage\", \"message\": \"Open\", \"pause\": false}}");
        List<Messages.Message> shown = new CopyOnWriteArrayList<>();

        Messages.Result out = Messages.listen(url(), "the-token", shown::add, null);

        assertEquals(1, shown.size());
        assertEquals("Garage: Open", shown.get(0).text);
        // It signed in with the TV's token, and asked for its messages.
        assertEquals("the-token", new JSONObject(got.get(0)).getString("access_token"));
        assertEquals("tv_panel/messages", new JSONObject(got.get(1)).getString("type"));
        // The connection dropped after a message: try again soon.
        assertEquals(Messages.Why.DROPPED, out.why);
        assertTrue(out.subscribed);
    }

    @Test
    public void anIntegrationWithoutMessagesIsAskedRarely() throws Exception {
        // Before 0.6.0: no such command.
        serve("{\"id\": %d, \"type\": \"result\", \"success\": false, \"error\": {\"code\": \"unknown_command\"}}");
        List<Messages.Message> shown = new CopyOnWriteArrayList<>();

        assertEquals(Messages.Why.UNSUPPORTED, Messages.listen(url(), "the-token", shown::add, null).why);
        assertTrue(shown.isEmpty());
    }

    @Test
    public void aRefusedTokenIsSaidSo() throws Exception {
        authReply = "{\"type\": \"auth_invalid\", \"message\": \"Invalid access token\"}";
        serve();
        Messages.Result out = Messages.listen(url(), "old-token", m -> { }, null);
        assertEquals(Messages.Why.REFUSED, out.why);
        assertFalse(out.subscribed);
    }

    @Test
    public void noHomeAssistantIsDropped() throws Exception {
        ha = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        int port = ha.getLocalPort();
        ha.close();
        Messages.Result out = Messages.listen("http://127.0.0.1:" + port, "t", m -> { }, null);
        assertEquals(Messages.Why.DROPPED, out.why);
        assertFalse(out.subscribed);
    }

    // ---- a stand-in Home Assistant websocket ------------------------------------------------------------------------

    private String url() {
        return "http://127.0.0.1:" + ha.getLocalPort();
    }

    /** Answers the handshake and the sign-in, then sends {@code replies} (%d is the subscription's id) and closes. */
    private void serve(String... replies) throws Exception {
        ha = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try (Socket c = ha.accept()) {
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                send(out, "{\"type\": \"auth_required\"}");
                got.add(read(in));
                send(out, authReply);
                if (!authReply.contains("auth_ok")) return;
                String subscribe = read(in);
                got.add(subscribe);
                int id = new JSONObject(subscribe).getInt("id");
                for (String r : replies) send(out, String.format(r, id));
                out.write(new byte[]{(byte) 0x88, 0});
                out.flush();
            } catch (Exception e) {
                // The test fails on what wasn't got.
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void send(OutputStream out, String text) throws Exception {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        if (b.length < 126) {
            f.write(b.length);
        } else {
            f.write(126);
            f.write(b.length >>> 8);
            f.write(b.length & 0xFF);
        }
        f.write(b, 0, b.length);
        out.write(f.toByteArray());
        out.flush();
    }

    /** One masked text frame from the TV. */
    private static String read(InputStream in) throws Exception {
        in.read();
        int len = in.read() & 0x7F;
        if (len == 126) len = (in.read() << 8) | in.read();
        byte[] mask = new byte[4];
        for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        byte[] p = new byte[len];
        for (int i = 0; i < len; i++) p[i] = (byte) (in.read() ^ mask[i & 3]);
        return new String(p, StandardCharsets.UTF_8);
    }
}
