package com.fullstacksystems.tvpanel;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sensor tiles with one sensor or several side by side (up to 3, as the screen's settings join them), and how they
 * fill rows: a tile of two takes two places, and a row never splits a tile.
 */
final class Tiles {
    private Tiles() {
    }

    /**
     * The tiles, as indexes into {@code ids}, from the integration's {@code tiles} (lists of entity ids), in the sensors'
     * order: a tile where its first sensor is. An id it doesn't know, or names more often than it's listed, is skipped
     * (each name takes the next copy of a sensor listed twice); a sensor it leaves out, or all of them without
     * {@code tiles}, gets a tile of its own in its place.
     */
    static List<List<Integer>> group(List<String> ids, JSONArray tiles) {
        List<List<Integer>> groups = new ArrayList<>();
        int[] owner = new int[ids.size()];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; tiles != null && i < tiles.length(); i++) {
            JSONArray t = tiles.optJSONArray(i);
            List<Integer> g = new ArrayList<>();
            for (int j = 0; t != null && j < t.length(); j++) {
                String id = t.optString(j);
                for (int k = 0; k < ids.size(); k++) {
                    if (owner[k] < 0 && ids.get(k).equals(id)) {
                        owner[k] = groups.size();
                        g.add(k);
                        break;
                    }
                }
            }
            if (!g.isEmpty()) groups.add(g);
        }
        List<List<Integer>> out = new ArrayList<>();
        boolean[] placed = new boolean[groups.size()];
        for (int k = 0; k < ids.size(); k++) {
            if (owner[k] < 0) out.add(Collections.singletonList(k));
            else if (!placed[owner[k]]) {
                placed[owner[k]] = true;
                out.add(groups.get(owner[k]));
            }
        }
        return out;
    }

    /** The places the tiles take, one a sensor. */
    static int places(int[] sizes) {
        int n = 0;
        for (int s : sizes) n += s;
        return n;
    }

    /**
     * The tiles in rows of {@code columns} places, in order; a tile that doesn't fit what's left of a row starts the
     * next. When that takes more than {@code maxRows} rows, the rows get a place wider until it doesn't.
     */
    static List<int[]> pack(int[] sizes, int columns, int maxRows) {
        int widest = 1;
        for (int s : sizes) widest = Math.max(widest, s);
        int total = places(sizes);
        for (int c = Math.max(columns, widest); ; c++) {
            List<int[]> rows = rows(sizes, c);
            if (rows.size() <= maxRows || c >= total) return rows;
        }
    }

    private static List<int[]> rows(int[] sizes, int columns) {
        List<int[]> out = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (!row.isEmpty() && used + sizes[i] > columns) {
                out.add(toArray(row));
                row.clear();
                used = 0;
            }
            row.add(i);
            used += sizes[i];
        }
        if (!row.isEmpty()) out.add(toArray(row));
        return out;
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
