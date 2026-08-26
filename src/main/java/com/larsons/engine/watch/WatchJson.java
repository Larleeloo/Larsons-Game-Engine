package com.larsons.engine.watch;

import com.larsons.engine.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reading values out of parsed JSON, tolerantly.
 *
 * <p>{@link Json} parses and prints; what it does not do is answer "what
 * number is under this key, and what should I use if there isn't one" — so
 * every class in the engine that loads JSON has grown its own private
 * {@code str}/{@code num}/{@code bool} trio. This game has some thirty types
 * that load or arrive over the wire, and thirty copies of the same four methods
 * is thirty places for one of them to differ.
 *
 * <p><b>Everything here is total.</b> A missing key, a null, a string where a
 * number was expected, a number that overflows — all of them return the caller's
 * default rather than throwing. That is the right shape for both callers: a
 * save file from an older build is missing keys the current one writes, and a
 * message off a socket is whatever the other end felt like sending.
 */
public final class WatchJson {

    private WatchJson() {}

    /** The object under {@code key}, or an empty map. */
    public static Map<String, Object> map(Map<String, Object> from, String key) {
        Object o = from == null ? null : from.get(key);
        return o instanceof Map<?, ?> ? Json.asObject(o) : Map.of();
    }

    /** The array under {@code key}, or an empty list. */
    public static List<Object> list(Map<String, Object> from, String key) {
        Object o = from == null ? null : from.get(key);
        return o instanceof List<?> ? Json.asArray(o) : List.of();
    }

    /** The array under {@code key} as objects, skipping anything that is not one. */
    public static List<Map<String, Object>> objects(Map<String, Object> from, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(from, key)) {
            if (o instanceof Map<?, ?>) out.add(Json.asObject(o));
        }
        return out;
    }

    /** The array under {@code key} as strings, skipping anything that is not one. */
    public static List<String> strings(Map<String, Object> from, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(from, key)) {
            if (o instanceof String s) out.add(s);
        }
        return out;
    }

    public static String str(Map<String, Object> from, String key, String fallback) {
        Object o = from == null ? null : from.get(key);
        return o instanceof String s ? s : fallback;
    }

    public static double num(Map<String, Object> from, String key, double fallback) {
        Object o = from == null ? null : from.get(key);
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    public static int integer(Map<String, Object> from, String key, int fallback) {
        Object o = from == null ? null : from.get(key);
        return o instanceof Number n ? n.intValue() : fallback;
    }

    public static long big(Map<String, Object> from, String key, long fallback) {
        Object o = from == null ? null : from.get(key);
        return o instanceof Number n ? n.longValue() : fallback;
    }

    public static boolean bool(Map<String, Object> from, String key, boolean fallback) {
        Object o = from == null ? null : from.get(key);
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        return fallback;
    }
}
