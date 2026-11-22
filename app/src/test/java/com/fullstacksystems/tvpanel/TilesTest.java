package com.fullstacksystems.tvpanel;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Several sensors on one tile, as the screen's settings join them, and the tiles in rows. */
public class TilesTest {
    private static String rows(List<int[]> rows) {
        List<String> out = new ArrayList<>();
        for (int[] r : rows) out.add(Arrays.toString(r));
        return out.toString();
    }

    @Test
    public void tilesAsHomeAssistantGroupsThem() throws Exception {
        List<String> ids = Arrays.asList("sensor.a", "sensor.b", "sensor.c", "sensor.d");
        JSONArray tiles = new JSONArray("[[\"sensor.a\"], [\"sensor.b\", \"sensor.c\"]]");
        // A sensor the tiles leave out gets its own; one it doesn't know, or names twice, is skipped.
        assertEquals("[[0], [1, 2], [3]]", Tiles.group(ids, tiles).toString());
        assertEquals("[[0], [1, 2]]", Tiles.group(ids.subList(0, 3),
                new JSONArray("[[\"sensor.a\", \"sensor.x\"], [\"sensor.b\", \"sensor.c\", \"sensor.a\"]]")).toString());
        // Without tiles (an older integration), one each.
        assertEquals("[[0], [1], [2], [3]]", Tiles.group(ids, null).toString());
        assertEquals("[]", Tiles.group(new ArrayList<>(), null).toString());
    }

    @Test
    public void rowsKeepATileWhole() {
        // Up to 4 places a row: a tile of two takes two.
        assertEquals("[[0, 1, 2]]", rows(Tiles.pack(new int[] {1, 2, 1}, 4, 2)));
        assertEquals("[[0, 1, 2, 3], [4]]", rows(Tiles.pack(new int[] {1, 1, 1, 1, 1}, 4, 2)));
        // A tile that doesn't fit what's left starts the next row...
        assertEquals("[[0, 1], [2, 3]]", rows(Tiles.pack(new int[] {2, 1, 3, 1}, 4, 2)));
        // ...and when that takes more rows than there's room for, the rows get wider.
        assertEquals("[[0, 1], [2]]", rows(Tiles.pack(new int[] {3, 2, 3}, 4, 2)));
        assertEquals("[]", rows(Tiles.pack(new int[0], 4, 2)));
    }

    @Test
    public void placesForTheGrid() {
        assertEquals(5, Tiles.places(new int[] {1, 2, 1, 1}));
    }

    @Test
    public void tilesStayInTheSensorsOrder() throws Exception {
        // A sensor the tiles leave out keeps its place among them, rather than going to the end.
        List<String> ids = Arrays.asList("sensor.a", "sensor.b", "sensor.c", "sensor.d");
        assertEquals("[[0], [1], [2, 3]]", Tiles.group(ids, new JSONArray("[[\"sensor.a\"], [\"sensor.c\", \"sensor.d\"]]"))
                .toString());
        // A sensor listed twice: each copy is its own, in its place.
        List<String> twice = Arrays.asList("sensor.a", "sensor.b", "sensor.a");
        assertEquals("[[0], [1, 2]]", Tiles.group(twice, new JSONArray("[[\"sensor.a\"], [\"sensor.b\", \"sensor.a\"]]"))
                .toString());
        assertEquals("[[0, 1], [2]]", Tiles.group(twice, new JSONArray("[[\"sensor.a\", \"sensor.b\"]]")).toString());
    }
}
