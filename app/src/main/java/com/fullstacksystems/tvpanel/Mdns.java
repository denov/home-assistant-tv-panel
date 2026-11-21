package com.fullstacksystems.tvpanel;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough multicast DNS (RFC 6762) and DNS-SD (RFC 6763) to announce one service and answer queries for it.
 * Pure Java, so the JVM tests cover it. {@link MdnsResponder} does the sockets.
 *
 * Android's NsdManager hands registrations to mdnsd, and on some TVs (a Sony BRAVIA on Google TV 12, for one) mdnsd
 * accepts them but nothing reaches the network; the system's own Cast service runs its own responder there too.
 */
final class Mdns {
    static final int PORT = 5353;
    static final String GROUP = "224.0.0.251";
    static final int TYPE_A = 1, TYPE_PTR = 12, TYPE_TXT = 16, TYPE_SRV = 33, TYPE_ANY = 255;
    private static final int CLASS_IN = 1, CACHE_FLUSH = 0x8000;
    /** Seconds: RFC 6762's suggestions for host records and for the rest. */
    static final int TTL_HOST = 120, TTL_OTHER = 4500;

    private Mdns() {
    }

    /** One service: "<instance>.<type>.local" on "<host>.local" at an IPv4 address. */
    static final class Service {
        final String instance;
        final String type;
        final String host;
        final Inet4Address address;
        final int port;
        final Map<String, String> txt;

        Service(String instance, String type, String host, Inet4Address address, int port, Map<String, String> txt) {
            this.instance = instance;
            this.type = type;
            this.host = host;
            this.address = address;
            this.port = port;
            this.txt = txt;
        }

        String typeName() {
            return type + ".local";
        }

        String instanceName() {
            return instance + "." + type + ".local";
        }

        String hostName() {
            return host + ".local";
        }
    }

    /** A question from a query: its name (lower case, no trailing dot) and type. */
    static final class Question {
        final String name;
        final int type;

        Question(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    /** The questions in a query, or none when the packet is a response or can't be read. */
    static List<Question> questions(byte[] p, int length) {
        List<Question> out = new ArrayList<>();
        if (length < 12) return out;
        int flags = u16(p, 2);
        if ((flags & 0x8000) != 0) return out; // a response, not a query
        int count = u16(p, 4);
        int[] at = {12};
        for (int i = 0; i < count; i++) {
            String name = readName(p, length, at);
            if (name == null || at[0] + 4 > length) return out;
            int type = u16(p, at[0]);
            at[0] += 4;
            out.add(new Question(name.toLowerCase(Locale.ROOT), type));
        }
        return out;
    }

    /** Whether a query asks about this service: its type, its instance or its host. */
    static boolean asksFor(List<Question> qs, Service s) {
        String type = s.typeName().toLowerCase(Locale.ROOT);
        String inst = s.instanceName().toLowerCase(Locale.ROOT);
        String host = s.hostName().toLowerCase(Locale.ROOT);
        for (Question q : qs) {
            if (q.name.equals(type) && (q.type == TYPE_PTR || q.type == TYPE_ANY)) return true;
            if (q.name.equals(inst)) return true;
            if (q.name.equals(host) && (q.type == TYPE_A || q.type == TYPE_ANY)) return true;
        }
        return false;
    }

    /**
     * A response carrying all four records (PTR, SRV, TXT, A). {@code goodbye} sends them with TTL 0, which tells
     * listeners the service is gone.
     */
    static byte[] response(Service s, boolean goodbye) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(512);
        u16(b, 0);          // id
        u16(b, 0x8400);     // response, authoritative
        u16(b, 0);          // questions
        u16(b, 4);          // answers
        u16(b, 0);
        u16(b, 0);
        int host = goodbye ? 0 : TTL_HOST, other = goodbye ? 0 : TTL_OTHER;

        name(b, s.typeName());
        record(b, TYPE_PTR, CLASS_IN, other, nameBytes(s.instanceName()));

        ByteArrayOutputStream srv = new ByteArrayOutputStream();
        u16(srv, 0);
        u16(srv, 0);
        u16(srv, s.port);
        name(srv, s.hostName());
        name(b, s.instanceName());
        record(b, TYPE_SRV, CLASS_IN | CACHE_FLUSH, host, srv.toByteArray());

        ByteArrayOutputStream txt = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : s.txt.entrySet()) {
            byte[] kv = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8);
            int n = Math.min(kv.length, 255);
            txt.write(n);
            txt.write(kv, 0, n);
        }
        if (txt.size() == 0) txt.write(0);
        name(b, s.instanceName());
        record(b, TYPE_TXT, CLASS_IN | CACHE_FLUSH, other, txt.toByteArray());

        name(b, s.hostName());
        record(b, TYPE_A, CLASS_IN | CACHE_FLUSH, host, s.address.getAddress());
        return b.toByteArray();
    }

    /** A DNS label is at most 63 bytes: "TV Panel <model>" is cut to fit. */
    static String label(String s) {
        String t = s.replace(".", " ").trim();
        while (t.getBytes(StandardCharsets.UTF_8).length > 63) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static void record(ByteArrayOutputStream b, int type, int cls, int ttl, byte[] data) {
        u16(b, type);
        u16(b, cls);
        u16(b, ttl >>> 16);
        u16(b, ttl & 0xFFFF);
        u16(b, data.length);
        b.write(data, 0, data.length);
    }

    private static byte[] nameBytes(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        name(b, name);
        return b.toByteArray();
    }

    /** "TV Panel x._tvpanel._tcp.local": the instance label may hold spaces, so split off the known suffixes. */
    private static void name(ByteArrayOutputStream b, String name) {
        for (String label : labels(name)) {
            byte[] l = label.getBytes(StandardCharsets.UTF_8);
            b.write(l.length);
            b.write(l, 0, l.length);
        }
        b.write(0);
    }

    static List<String> labels(String name) {
        List<String> out = new ArrayList<>();
        // The first label of an instance name may contain dots in principle; ours never does (label() drops them).
        for (String l : name.split("\\.")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    /** A name at at[0], following compression pointers; at[0] moves past it. Null when malformed. */
    static String readName(byte[] p, int length, int[] at) {
        StringBuilder out = new StringBuilder();
        int pos = at[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            if (pos >= length) return null;
            int len = p[pos] & 0xFF;
            if (len == 0) {
                if (!jumped) at[0] = pos + 1;
                return out.toString();
            }
            if ((len & 0xC0) == 0xC0) {
                if (pos + 1 >= length || ++jumps > 16) return null;
                int target = ((len & 0x3F) << 8) | (p[pos + 1] & 0xFF);
                if (!jumped) at[0] = pos + 2;
                jumped = true;
                pos = target;
                continue;
            }
            if (pos + 1 + len > length) return null;
            if (out.length() > 0) out.append('.');
            out.append(new String(p, pos + 1, len, StandardCharsets.UTF_8));
            pos += 1 + len;
        }
    }

    private static int u16(byte[] p, int at) {
        return ((p[at] & 0xFF) << 8) | (p[at + 1] & 0xFF);
    }

    private static void u16(ByteArrayOutputStream b, int v) {
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }
}
