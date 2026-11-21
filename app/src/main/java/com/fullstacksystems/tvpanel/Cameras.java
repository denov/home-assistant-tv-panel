package com.fullstacksystems.tvpanel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The cameras page's data: every camera entity with the label {@value Ha#LABEL}, in the order the optional text helper
 * {@value #ORDER} gives (camera names or entity ids, comma-separated; cameras it leaves out follow by name). Motion is any motion binary sensor on the
 * camera's device being on. Also reads the MJPEG stream Home Assistant serves for the full-screen view. Pure Java
 * (no Android types) so the JVM tests cover it.
 */
final class Cameras {
    /** Optional: "Front Door, Driveway, camera.back_yard" sets the grid's order. Without it, cameras go by name. */
    static final String ORDER = "input_text.tv_camera_order";

    /** The grid is 3 across and 2 down; slots without a camera are drawn empty. */
    static final int COLUMNS = 3, SLOTS = 6;

    /** Jinja: the number of motion sensors on entity {@code e}'s device that are on (0 for anything but a camera). */
    static final String MOTION = "((expand(device_entities(device_id(e))) | selectattr('domain', 'eq', 'binary_sensor')"
            + " | selectattr('attributes.device_class', 'defined') | selectattr('attributes.device_class', 'eq', 'motion')"
            + " | selectattr('state', 'eq', 'on') | list | count) if e.startswith('camera.') and device_id(e) else 0)";

    /** Without the integration only (see {@link Ha#data}; its data.py builds the same answer). */
    static final String TEMPLATE = "{% set ns = namespace(out=[]) %}"
            + "{% for e in (entities if entities is defined else label_entities('" + Ha.LABEL + "'))"
            + " | select('match', 'camera[.]') %}"
            + "{% set ns.out = ns.out + [{'id': e, 'name': state_attr(e, 'friendly_name'), 'state': states(e),"
            + " 'motion': " + MOTION + " > 0}] %}"
            + "{% endfor %}"
            + "{{ {'items': ns.out, 'order': (camera_order if camera_order is defined else states('" + ORDER + "'))}"
            + " | tojson }}";

    private Cameras() {
    }

    static String templateBody() {
        return templateBody(Settings.NONE);
    }

    /** With the integration's settings: its chips (the cameras among them) and the grid's order. */
    static String templateBody(Settings settings) {
        return Settings.body(TEMPLATE, settings, true);
    }

    static final class Cam {
        final String id;
        final String name;
        final String state;
        final boolean motion;

        Cam(String id, String name, String state, boolean motion) {
            this.id = id;
            this.name = name;
            this.state = state;
            this.motion = motion;
        }

        /** Home Assistant has the camera ("idle", "streaming", "recording"), so its picture can be asked for. */
        boolean ok() {
            return !state.isEmpty() && !"unavailable".equals(state) && !"unknown".equals(state);
        }
    }

    /** The template's answer, in the order the helper gives. */
    static List<Cam> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        List<Cam> all = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null || Ha.str(o, "id").isEmpty()) continue;
                String id = Ha.str(o, "id");
                all.add(new Cam(id, name(Ha.str(o, "name"), id), Ha.str(o, "state"), o.optBoolean("motion")));
            }
        }
        return order(all, orderOf(Ha.str(root, "order")));
    }

    /** Cameras the order names come first, in its order; the rest follow by name. Each entry is an id or a name. */
    static List<Cam> order(List<Cam> cams, List<String> order) {
        List<Cam> rest = new ArrayList<>(cams);
        rest.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        List<Cam> out = new ArrayList<>();
        for (String want : order) {
            for (Cam c : rest) {
                if (c.id.equalsIgnoreCase(want) || c.name.equalsIgnoreCase(want)) {
                    out.add(c);
                    rest.remove(c);
                    break;
                }
            }
        }
        out.addAll(rest);
        return out;
    }

    /** The helper's text split on commas: "Front Door, Driveway" gives two entries. Unknown and unavailable give none. */
    static List<String> orderOf(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || "unknown".equals(text) || "unavailable".equals(text)) return out;
        for (String part : text.split(",")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out;
    }

    /** "Front Door" from "Camera - Front Door"; the entity id's words when there is no name. */
    static String name(String friendly, String id) {
        String n = friendly == null ? "" : friendly.trim();
        n = n.replaceFirst("(?i)^camera\\s*[-:–—]\\s*", "").trim();
        if (n.isEmpty()) n = Ha.capitalise(id.substring(id.indexOf('.') + 1).replace('_', ' '));
        return n;
    }

    /** True when any camera sees motion now. */
    static boolean anyMotion(List<Cam> cams) {
        for (Cam c : cams) if (c.motion) return true;
        return false;
    }

    // ---- MJPEG ---------------------------------------------------------------------------------------------------

    /**
     * Reads JPEG frames from a multipart/x-mixed-replace stream (Home Assistant's /api/camera_proxy_stream). Each
     * part's Content-Length is used when it has one; otherwise the frame runs to the JPEG end marker. One buffer is
     * reused: a frame is valid until the next call.
     */
    static final class Mjpeg {
        private static final int MAX_FRAME = 16 * 1024 * 1024;
        private static final int MAX_LINE = 1024;

        private final InputStream in;
        private byte[] buf = new byte[512 * 1024];
        private int length;

        Mjpeg(InputStream in) {
            this.in = in;
        }

        byte[] buffer() {
            return buf;
        }

        int length() {
            return length;
        }

        /** Reads the next frame; false at the end of the stream. */
        boolean next() throws IOException {
            int contentLength = -1;
            boolean inHeaders = false;
            while (true) {
                String line = readLine();
                if (line == null) return false;
                if (line.isEmpty()) {
                    if (inHeaders) break;
                    continue;
                }
                inHeaders = true;
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equals(line.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        contentLength = -1;
                    }
                }
            }
            if (contentLength > MAX_FRAME) throw new IOException("Frame too large: " + contentLength);
            if (contentLength > 0) {
                ensure(contentLength);
                int got = 0;
                while (got < contentLength) {
                    int n = in.read(buf, got, contentLength - got);
                    if (n < 0) return false;
                    got += n;
                }
                length = contentLength;
                return true;
            }
            return toEndMarker();
        }

        /** No Content-Length: the bytes up to and including the JPEG end marker FF D9. */
        private boolean toEndMarker() throws IOException {
            int n = 0, prev = -1;
            while (true) {
                int b = in.read();
                if (b < 0) return false;
                ensure(n + 1);
                buf[n++] = (byte) b;
                if (prev == 0xFF && b == 0xD9) {
                    length = n;
                    return true;
                }
                if (n > MAX_FRAME) throw new IOException("Frame too large");
                prev = b;
            }
        }

        private void ensure(int size) {
            if (buf.length < size) buf = Arrays.copyOf(buf, Math.max(size, buf.length * 2));
        }

        /** A header line without its CR LF, or null at the end of the stream. */
        private String readLine() throws IOException {
            byte[] line = new byte[MAX_LINE];
            int n = 0;
            while (true) {
                int b = in.read();
                if (b < 0) return n == 0 ? null : new String(line, 0, n, StandardCharsets.ISO_8859_1);
                if (b == '\n') break;
                if (b != '\r' && n < MAX_LINE) line[n++] = (byte) b;
            }
            return new String(line, 0, n, StandardCharsets.ISO_8859_1);
        }
    }
}
