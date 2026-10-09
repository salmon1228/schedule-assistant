package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JVM test scaffolding only: tiny recursive-descent JSON parser/writer. */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        if (text == null) throw new RuntimeException("null JSON text");
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new RuntimeException("trailing garbage at " + p.i);
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private char peek() {
        if (i >= s.length()) throw new RuntimeException("unexpected end of JSON");
        return s.charAt(i);
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw new RuntimeException("bad literal at " + i);
        i += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() != ':') throw new RuntimeException("expected ':' at " + i);
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new RuntimeException("expected ',' or '}' at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; // [
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw new RuntimeException("expected ',' or ']' at " + i);
        }
    }

    private String string() {
        if (peek() != '"') throw new RuntimeException("expected string at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new RuntimeException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw new RuntimeException("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
            else break;
        }
        if (start == i) throw new RuntimeException("bad value at " + i);
        String t = s.substring(start, i);
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            return Integer.valueOf(Integer.parseInt(t));
        }
        return Double.valueOf(Double.parseDouble(t));
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, v);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v instanceof String) {
            sb.append('"').append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            return;
        }
        sb.append(String.valueOf(v));
    }
}
