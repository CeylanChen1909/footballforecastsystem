package com.chen.football.agent.service;

public final class AgentJsonTools {

    private AgentJsonTools() {
    }

    public static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int fencedStart = text.indexOf("```");
        if (fencedStart >= 0) {
            int contentStart = text.indexOf('\n', fencedStart);
            if (contentStart >= 0) {
                int jsonStart = firstJsonStart(text, contentStart + 1);
                String fenced = findBalancedJson(text, jsonStart);
                if (fenced != null) return fenced;
            }
        }
        String balanced = findBalancedJson(text, firstJsonStart(text, 0));
        if (balanced != null) return balanced;
        return text.trim();
    }

    private static int firstJsonStart(String text, int from) {
        if (text == null) return -1;
        int object = text.indexOf('{', Math.max(0, from));
        int array = text.indexOf('[', Math.max(0, from));
        if (object < 0) return array;
        if (array < 0) return object;
        return Math.min(object, array);
    }

    private static String findBalancedJson(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) return null;
        char opening = text.charAt(start);
        if (opening != '{' && opening != '[') return null;
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return text.substring(start, i + 1).trim();
        }
        return null;
    }

    public static boolean looksLikeJson(String text) {
        String json = extractJson(text);
        return json != null && (json.startsWith("{") || json.startsWith("["));
    }
}
