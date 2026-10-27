package evidence;

import java.util.*;

/** Tiny JSON helper: toJson() for responses, parse() for flat request bodies. */
public class Json {

    // ---------- Java object -> JSON text ----------
    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Collection<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            str(sb, o.toString());   // String, java.sql.Date, Timestamp, etc.
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- JSON text -> Map (flat object only; all values become Strings) ----------
    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isBlank()) return m;
        int n = s.length(), i = s.indexOf('{') + 1;
        int[] pos = new int[1];
        while (i > 0 && i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n || s.charAt(i) == '}') break;
            pos[0] = i;
            String key = readString(s, pos);
            i = pos[0];
            while (s.charAt(i) != ':') i++;
            i++;
            while (Character.isWhitespace(s.charAt(i))) i++;
            String val;
            if (s.charAt(i) == '"') {
                pos[0] = i;
                val = readString(s, pos);
                i = pos[0];
            } else {
                int j = i;
                while (j < n && ",}".indexOf(s.charAt(j)) < 0) j++;
                val = s.substring(i, j).trim();
                if (val.equals("null")) val = null;
                i = j;
            }
            m.put(key, val);
        }
        return m;
    }

    private static String readString(String s, int[] pos) {
        int i = pos[0] + 1;
        StringBuilder b = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char d = s.charAt(i++);
                switch (d) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default:  b.append(d);
                }
            } else {
                b.append(c);
            }
        }
        pos[0] = i + 1;
        return b.toString();
    }
}
