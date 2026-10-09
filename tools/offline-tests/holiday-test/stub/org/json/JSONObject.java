package org.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM test scaffolding only.
 *
 * android.jar ships org.json as compile-time stubs that throw at runtime, so the
 * offline JVM test provides a minimal but behaviour-compatible implementation of
 * the subset used by HolidayRepo. Not part of the app; never packaged.
 */
public class JSONObject {

    private final Map<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<String, Object>();
    }

    public JSONObject(String source) {
        Object parsed = Json.parse(source);
        if (!(parsed instanceof Map)) {
            throw new RuntimeException("JSONObject text must begin with '{'");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        this.map = m;
    }

    JSONObject(Map<String, Object> m) {
        this.map = m;
    }

    Map<String, Object> raw() {
        return map;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public int length() {
        return map.size();
    }

    public Iterator<String> keys() {
        return new ArrayList<String>(map.keySet()).iterator();
    }

    public Object opt(String name) {
        return map.get(name);
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        if (v == null) return fallback;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return fallback;
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    @Override
    public String toString() {
        return Json.write(map);
    }
}
