package com.hhst.youtubelite.player.engine.datasource;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NullFilteringHeadersMapTest {
    private NullFilteringHeadersMap headers() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put(null, List.of("HTTP/1.1 206 Partial Content"));
        raw.put("Content-Type", List.of("video/mp4"));
        return new NullFilteringHeadersMap(raw);
    }

    @Test
    public void copyingKeysDoesNotOverflowStack() {
        assertEquals(List.of("Content-Type"), new ArrayList<>(headers().keySet()));
        assertArrayEquals(new String[]{"Content-Type"}, headers().keySet().toArray(new String[0]));
    }

    @Test
    public void copyingEntriesDoesNotOverflowStack() {
        Object[] expected = {Map.entry("Content-Type", List.of("video/mp4"))};
        assertArrayEquals(expected, headers().entrySet().toArray());
        assertArrayEquals(expected, headers().entrySet().toArray(new Map.Entry<?, ?>[0]));
    }

    @Test
    public void mapViewsAllExcludeStatusLine() {
        NullFilteringHeadersMap filtered = headers();
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.keySet().size());
        assertEquals(1, filtered.entrySet().size());
        assertFalse(filtered.containsKey(null));
        assertNull(filtered.get(null));
        assertFalse(filtered.containsValue(List.of("HTTP/1.1 206 Partial Content")));
        assertEquals(List.of(List.of("video/mp4")), new ArrayList<>(filtered.values()));
        Map<String, List<String>> copy = new LinkedHashMap<>();
        filtered.forEach(copy::put);
        assertEquals(Map.of("Content-Type", List.of("video/mp4")), copy);
    }

    @Test
    public void statusLineOnlyIsEmpty() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put(null, List.of("HTTP/1.1 200 OK"));
        NullFilteringHeadersMap filtered = new NullFilteringHeadersMap(raw);
        assertTrue(filtered.isEmpty());
        assertTrue(filtered.keySet().isEmpty());
        assertTrue(filtered.entrySet().isEmpty());
        assertTrue(filtered.values().isEmpty());
    }

    @Test
    public void typedArrayRetainsCollectionContract() {
        String[] target = {"old", "old", "untouched"};
        assertSame(target, headers().keySet().toArray(target));
        assertEquals(Arrays.asList("Content-Type", null, "untouched"), Arrays.asList(target));
    }

    @Test
    public void preservesMultipleHeaderValues() {
        Map<String, List<String>> raw = Map.of("Vary", List.of("Origin", "Accept-Encoding"));
        NullFilteringHeadersMap filtered = new NullFilteringHeadersMap(raw);
        assertEquals(raw, filtered);
        assertEquals(raw.hashCode(), filtered.hashCode());
        assertEquals(raw, new LinkedHashMap<>(filtered));
    }
}
