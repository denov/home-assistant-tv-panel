package com.fullstacksystems.tvpanel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocketFactory;

/**
 * Just enough of Home Assistant's websocket API for one-shot commands (recorder statistics): connect, log in, send a
 * command, wait for its result, close. Some data, long-term statistics among them, is only available here, not over
 * REST. Blocking: call it off the main thread. No library, so the app stays small.
 */
final class HaSocket implements Closeable {
    private static final int CONNECT_MS = 6000;
    private static final int READ_MS = 20_000;
    /** A month of 5-minute statistics for a few dozen entities is a few MB; anything larger is refused. */
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();
    private int nextId = 1;

    private HaSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Connected and logged in, or an IOException that says why. */
    static HaSocket open(String baseUrl, String token) throws IOException {
        URI u = URI.create(baseUrl);
        boolean tls = "https".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        if (host == null) throw new IOException("bad address " + baseUrl);
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        HaSocket ws = null;
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            ws = new HaSocket(s);
            ws.handshake(host + (u.getPort() > 0 ? ":" + port : ""));
            JSONObject hello = new JSONObject(ws.readMessage());
            if (!"auth_required".equals(hello.optString("type"))) throw new IOException("unexpected " + hello.optString("type"));
            ws.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
            JSONObject auth = new JSONObject(ws.readMessage());
            if (!"auth_ok".equals(auth.optString("type"))) throw new IOException("Home Assistant refused the token");
            return ws;
        } catch (JSONException e) {
            closeQuietly(s);
            throw new IOException("unreadable answer: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e;
        }
    }

    /** Sends {@code msg} with a fresh id and returns its "result", or throws with Home Assistant's error. */
    Object call(JSONObject msg) throws IOException {
        int id = nextId++;
        try {
            msg.put("id", id);
            sendText(msg.toString());
            while (true) {
                JSONObject r = new JSONObject(readMessage());
                if (r.optInt("id", -1) != id || !"result".equals(r.optString("type"))) continue;
                if (!r.optBoolean("success")) {
                    JSONObject err = r.optJSONObject("error");
                    throw new IOException(err == null ? "command failed" : err.optString("message", "command failed"));
                }
                return r.opt("result");
            }
        } catch (JSONException e) {
            throw new IOException("unreadable answer: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            sendFrame(0x8, new byte[0]);
        } catch (IOException ignored) {
            // closing anyway
        }
        closeQuietly(socket);
    }

    // ---- RFC 6455, client side ---------------------------------------------------------------------------------

    private void handshake(String hostHeader) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String req = "GET /api/websocket HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        // Headers end with a blank line; the status line must say 101.
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed during handshake");
            head.append((char) b);
            if (head.length() > 16384) throw new IOException("handshake too long");
        }
        String status = head.substring(0, Math.max(0, head.indexOf("\r\n")));
        if (!status.contains(" 101")) throw new IOException("no websocket: " + status);
    }

    private void sendText(String s) throws IOException {
        sendFrame(0x1, s.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write(n >>> 8);
            f.write(n);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (n >>> (8 * i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** One whole text message, joining fragments and answering pings on the way. */
    private String readMessage() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(mask);
            }
            if (len < 0 || msg.size() + len > MAX_MESSAGE) throw new IOException("message too large");
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            switch (opcode) {
                case 0x8:
                    throw new EOFException("Home Assistant closed the connection");
                case 0x9:
                    sendFrame(0xA, payload);
                    continue;
                case 0xA:
                    continue;
                default:
                    msg.write(payload, 0, payload.length);
                    if (fin) return msg.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("connection closed");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("connection closed");
            off += n;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
