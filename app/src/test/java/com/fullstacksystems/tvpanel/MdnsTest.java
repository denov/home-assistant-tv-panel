package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MdnsTest {
    private static Mdns.Service service() throws Exception {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("id", "abc123");
        txt.put("name", "BRAVIA 4K VH2");
        return new Mdns.Service("TV Panel BRAVIA 4K VH2", "_tvpanel._tcp", "tvpanel-abc123",
                (Inet4Address) InetAddress.getByName("192.168.1.50"), 8766, txt);
    }

    /** A query as a browser sends it: one PTR question for the service type. */
    private static byte[] query(String name, int type) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0); b.write(0); b.write(0); b.write(0);   // id, flags: a query
        b.write(0); b.write(1);                           // one question
        for (int i = 0; i < 6; i++) b.write(0);
        for (String l : name.split("\\.")) { b.write(l.length()); b.write(l.getBytes(), 0, l.length()); }
        b.write(0);
        b.write(0); b.write(type);
        b.write(0); b.write(1);
        return b.toByteArray();
    }

    @Test
    public void answersABrowseForItsType() throws Exception {
        byte[] q = query("_tvpanel._tcp.local", Mdns.TYPE_PTR);
        List<Mdns.Question> qs = Mdns.questions(q, q.length);
        assertEquals(1, qs.size());
        assertEquals("_tvpanel._tcp.local", qs.get(0).name);
        assertTrue(Mdns.asksFor(qs, service()));
        byte[] other = query("_googlecast._tcp.local", Mdns.TYPE_PTR);
        assertFalse(Mdns.asksFor(Mdns.questions(other, other.length), service()));
    }

    @Test
    public void responseCarriesAllFourRecords() throws Exception {
        byte[] r = Mdns.response(service(), false);
        // A response, with four answers.
        assertEquals(0x84, r[2] & 0xFF);
        assertEquals(4, r[7]);
        // Read the first answer's name back: the service type.
        int[] at = {12};
        assertEquals("_tvpanel._tcp.local", Mdns.readName(r, r.length, at));
        String all = new String(r, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(all.contains("TV Panel BRAVIA 4K VH2"));
        assertTrue(all.contains("id=abc123"));
        assertTrue(all.contains("tvpanel-abc123"));
        // Its own response isn't taken for a query.
        assertTrue(Mdns.questions(r, r.length).isEmpty());
    }

    @Test
    public void readsCompressedNames() {
        // "local" at offset 12, then a name "x" + pointer to offset 12.
        byte[] p = new byte[12 + 7 + 4];
        int i = 12;
        p[i++] = 5; for (char c : "local".toCharArray()) p[i++] = (byte) c; p[i++] = 0;
        int start = i;
        p[i++] = 1; p[i++] = 'x'; p[i++] = (byte) 0xC0; p[i++] = 12;
        int[] at = {start};
        assertEquals("x.local", Mdns.readName(p, p.length, at));
        assertEquals(p.length, at[0]);
    }

    @Test
    public void labelsFitDns() {
        assertEquals("TV Panel A B", Mdns.label("TV Panel A.B"));
        assertEquals(63, Mdns.label("TV Panel " + "x".repeat(80)).length());
    }
}
