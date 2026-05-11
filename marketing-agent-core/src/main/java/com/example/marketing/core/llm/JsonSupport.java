package com.example.marketing.core.llm;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonSupport {
    private JsonSupport() {
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    }
                    else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append("\"").toString();
    }

    public static String firstTextFromGeminiResponse(String json) {
        String marker = "\"text\"";
        int key = json.indexOf(marker);
        if (key < 0) {
            return "";
        }
        int colon = json.indexOf(':', key + marker.length());
        int start = json.indexOf('"', colon + 1);
        if (colon < 0 || start < 0) {
            return "";
        }
        return readJsonString(json, start);
    }

    public static String extractJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return "{}";
        }
        return text.substring(start, end + 1);
    }

    public static Map<String, String> flatStringMap(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        String object = extractJsonObject(json);
        int index = 0;
        while (index < object.length()) {
            int keyStart = object.indexOf('"', index);
            if (keyStart < 0) {
                break;
            }
            String key = readJsonString(object, keyStart);
            int colon = object.indexOf(':', keyStart + 1);
            if (colon < 0) {
                break;
            }
            int valueStart = nextNonWhitespace(object, colon + 1);
            if (valueStart < 0) {
                break;
            }
            String value;
            if (object.charAt(valueStart) == '"') {
                value = readJsonString(object, valueStart);
                index = valueStart + value.length() + 2;
            }
            else {
                int valueEnd = valueStart;
                while (valueEnd < object.length() && ",}".indexOf(object.charAt(valueEnd)) < 0) {
                    valueEnd++;
                }
                value = object.substring(valueStart, valueEnd).trim();
                index = valueEnd;
            }
            values.put(key, value);
        }
        return values;
    }

    private static int nextNonWhitespace(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String readJsonString(String json, int quoteStart) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaping) {
                switch (ch) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            builder.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> builder.append(ch);
                }
                escaping = false;
            }
            else if (ch == '\\') {
                escaping = true;
            }
            else if (ch == '"') {
                return builder.toString();
            }
            else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
