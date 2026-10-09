package org.json;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM test scaffolding only. See {@link JSONObject}.
 */
public class JSONArray {

    private final List<Object> list;

    public JSONArray() {
        this.list = new ArrayList<Object>();
    }

    JSONArray(List<Object> l) {
        this.list = l;
    }

    public int length() {
        return list.size();
    }

    public Object opt(int index) {
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(int index) {
        Object v = opt(index);
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        if (v == null) return fallback;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    public boolean optBoolean(int index, boolean fallback) {
        Object v = opt(index);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return fallback;
    }

    public int optInt(int index, int fallback) {
        Object v = opt(index);
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
        return Json.write(list);
    }
}
