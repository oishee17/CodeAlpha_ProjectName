package chatbot.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small JSON reader/writer so the bot runs on a plain JDK with
 * no third-party dependencies.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------- writing

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String object(Map<String, ?> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public static String array(List<?> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(value(values.get(i)));
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return quote((String) v);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Map) {
            return object((Map<String, ?>) v);
        }
        if (v instanceof List) {
            return array((List<?>) v);
        }
        return quote(v.toString());
    }

    // ------------------------------------------------------------- reading

    /** Parses an object such as {@code {"message":"hi"}}; returns an empty map for bad input. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        try {
            Object parsed = new Parser(text).parseValue();
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (RuntimeException ignored) {
            // fall through: callers get an empty object
        }
        return new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src == null ? "" : src;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalStateException("unexpected end of input");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return parseObjectBody();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObjectBody() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalStateException("expected ':'");
                }
                pos++;
                map.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalStateException("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalStateException("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            if (peek() != '"') {
                throw new IllegalStateException("expected string");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            String token = src.substring(start, pos);
            if (token.isEmpty()) {
                throw new IllegalStateException("expected value");
            }
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return Double.parseDouble(token);
            }
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalStateException("expected " + literal);
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalStateException("unexpected end of input");
            }
            return src.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
