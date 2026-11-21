package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Pairing with the TV Panel integration in Home Assistant, while the app's own page is open. The page shows a
 * six-digit code; the integration finds the TV over mDNS ({@value #SERVICE}, announced by {@link MdnsResponder}) or by its address, the person types
 * the code into Home Assistant, and the integration sends the address and a token of the TV's own:
 *
 * <pre>
 * GET  /info  → {"id", "name", "model", "paired"}
 * POST /pair  {"code", "url", "token"} → 200 {"id", "name"} | 403 wrong code | 400 the token does not work
 * </pre>
 *
 * Plain HTTP on the LAN, like Home Assistant itself here. Five wrong codes in a row make a new code. Nothing listens
 * while the page is closed.
 */
final class PairServer {
    static final String SERVICE = "_tvpanel._tcp";
    static final int PORT = 8766;
    private static final int MAX_TRIES = 5;
    private static final int MAX_BODY = 16 * 1024;

    interface Listener {
        /** A new code to show (on start, and after too many wrong tries). */
        void onCode(String code);

        /** Paired: the config is saved. */
        void onPaired(String url);
    }

    private final Context ctx;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile String code;
    private int wrong;
    private ServerSocket server;
    private Thread thread;
    private MdnsResponder mdns;

    PairServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** The TV's id for Home Assistant: made once, kept across reinstalls of the config (not of the app). */
    static String id(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("server", Context.MODE_PRIVATE);
        String id = p.getString("id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    static String name() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        return m.isEmpty() ? "TV" : m;
    }

    void start() {
        newCode();
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            return;
        }
        thread = new Thread(this::serve, "pair");
        thread.setDaemon(true);
        thread.start();
        advertise();
    }

    void stop() {
        if (mdns != null) mdns.stop();
        mdns = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
        server = null;
    }

    private void newCode() {
        code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
        wrong = 0;
        listener.onCode(code);
    }

    private void advertise() {
        java.net.Inet4Address ip = MdnsResponder.localIpv4(null);
        if (ip == null) return;
        java.util.Map<String, String> txt = new java.util.LinkedHashMap<>();
        txt.put("id", id(ctx));
        txt.put("name", name());
        mdns = new MdnsResponder(ctx, new Mdns.Service(Mdns.label("TV Panel " + name()), SERVICE, "tvpanel-" + id(ctx),
                ip, PORT, txt));
        mdns.start();
    }

    private void serve() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(10_000);
                handle(c);
            } catch (IOException e) {
                // Closed by stop(), or one bad client: carry on until closed.
            }
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        String head = readHead(in);
        String[] first = head.split("\r\n", 2)[0].split(" ");
        if (first.length < 2) { reply(c, 400, "{}"); return; }
        String method = first[0], path = first[1];
        int length = 0;
        for (String h : head.split("\r\n")) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { length = Integer.parseInt(h.substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        if (length < 0 || length > MAX_BODY) { reply(c, 413, "{}"); return; }
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        try {
            if ("GET".equals(method) && "/info".equals(path)) {
                reply(c, 200, info().toString());
            } else if ("POST".equals(method) && "/pair".equals(path)) {
                pair(c, new JSONObject(new String(body, StandardCharsets.UTF_8)));
            } else {
                reply(c, 404, "{}");
            }
        } catch (Exception e) {
            reply(c, 400, error("bad_request"));
        }
    }

    private JSONObject info() throws org.json.JSONException {
        return new JSONObject().put("id", id(ctx)).put("name", name()).put("model", name())
                .put("paired", Config.load(ctx).complete());
    }

    private void pair(Socket c, JSONObject o) throws Exception {
        String given = o.optString("code").trim();
        boolean right = MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!right) {
            if (++wrong >= MAX_TRIES) newCode();
            reply(c, 403, error("invalid_code"));
            return;
        }
        String url = reachableUrl(Config.normalizeUrl(o.optString("url")), c.getInetAddress());
        String token = o.optString("token").trim();
        if (url.isEmpty() || token.isEmpty()) { reply(c, 400, error("bad_request")); return; }
        // Check the token before keeping it, so Home Assistant can say so instead of leaving a TV that can't connect.
        HaClient.Reply r = new HaClient(url, token).get("/api/");
        if (!r.ok()) {
            reply(c, 400, error(r.code == 0 ? "cannot_reach_ha" : "token_refused").replace("}", ",\"url\":" + JSONObject.quote(url) + "}"));
            return;
        }
        Config.save(ctx, url, token);
        newCode();
        reply(c, 200, new JSONObject().put("id", id(ctx)).put("name", name()).put("url", url).toString());
        listener.onPaired(url);
    }

    /**
     * Home Assistant's own idea of its address can be a name the TV can't resolve (homeassistant.local: Android
     * doesn't do mDNS lookups for apps). Then use the address the request came from, with the same scheme and port.
     */
    static String reachableUrl(String url, InetAddress peer) {
        if (url.isEmpty()) return url;
        try {
            URI u = new URI(url);
            if (u.getHost() == null) return url;
            try {
                InetAddress.getByName(u.getHost());
                return url;
            } catch (IOException unresolved) {
                if (peer == null) return url;
                String host = peer.getHostAddress();
                if (host.contains(":")) host = "[" + host + "]";
                return u.getScheme() + "://" + host + (u.getPort() > 0 ? ":" + u.getPort() : "");
            }
        } catch (java.net.URISyntaxException e) {
            return url;
        }
    }

    /** The TV's own IPv4 address and pairing port ("192.168.1.50:8766"), for typing into Home Assistant. */
    static String localAddress() {
        try {
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(n.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through.
        }
        return "this TV's IP address, port " + PORT;
    }

    private static String error(String code) {
        return "{\"error\":\"" + code + "\"}";
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < MAX_BODY) {
            int ch = in.read();
            if (ch < 0) break;
            b.write(ch);
            // Stop at the blank line: \r\n\r\n.
            state = (ch == '\r' && (state == 0 || state == 2)) ? state + 1 : (ch == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
        }
        return b.toString("ISO-8859-1");
    }

    private static void reply(Socket c, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Bad Request";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = c.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
