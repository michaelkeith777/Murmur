package com.zelvari.murmur;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Clipboard history and text shortcuts. Both stay on the phone: the history is kept in the
 * keyboard's own settings and is never sent anywhere.
 */
final class Clips {
    private Clips() {}

    static final class Clip {
        final String text;
        final boolean pinned;
        /** When it was copied (0 for copies saved before 2.1). */
        final long at;

        Clip(String text, boolean pinned) {
            this(text, pinned, 0);
        }

        Clip(String text, boolean pinned, long at) {
            this.text = text;
            this.pinned = pinned;
            this.at = at;
        }
    }

    private static final int MAX_RECENT = 25;
    private static final int MAX_PINNED = 30;
    private static final int MAX_CHARS = 4000;

    /** Pinned first, then newest first. */
    static List<Clip> list(Context c) {
        List<Clip> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c, Prefs.CLIPS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("t", "");
                if (!t.isEmpty()) out.add(new Clip(t, o.optBoolean("p", false), o.optLong("at", 0)));
            }
        } catch (JSONException e) {
            return out;
        }
        return out;
    }

    private static void save(Context c, List<Clip> clips) {
        JSONArray a = new JSONArray();
        try {
            for (Clip k : clips) a.put(new JSONObject().put("t", k.text).put("p", k.pinned).put("at", k.at));
        } catch (JSONException e) {
            return;
        }
        Prefs.sp(c).edit().putString(Prefs.CLIPS, a.toString()).apply();
    }

    private static List<Clip> ordered(List<Clip> pinned, List<Clip> recent) {
        List<Clip> out = new ArrayList<>(pinned);
        out.addAll(recent);
        return out;
    }

    /** The newest copies made in the last {@code withinMs}, newest first, at most {@code max}. */
    static List<String> recent(Context c, long withinMs, int max) {
        List<Clip> all = list(c);
        java.util.ArrayList<Clip> fresh = new java.util.ArrayList<>();
        long since = System.currentTimeMillis() - withinMs;
        for (Clip k : all) if (k.at >= since && k.at > 0) fresh.add(k);
        fresh.sort((x, y) -> Long.compare(y.at, x.at));
        List<String> out = new ArrayList<>();
        for (Clip k : fresh) {
            if (out.size() >= max) break;
            out.add(k.text);
        }
        return out;
    }

    /** Adds a copied text to the top of the history. Returns true if the history changed. */
    static boolean add(Context c, String text) {
        if (text == null) return false;
        String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        if (t.trim().isEmpty()) return false;
        List<Clip> pinned = new ArrayList<>(), recent = new ArrayList<>();
        boolean wasPinned = false;
        for (Clip k : list(c)) {
            if (k.text.equals(t)) {
                wasPinned |= k.pinned;
                continue;
            }
            (k.pinned ? pinned : recent).add(k);
        }
        long now = System.currentTimeMillis();
        if (wasPinned) {
            pinned.add(0, new Clip(t, true, now));
        } else {
            recent.add(0, new Clip(t, false, now));
        }
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        save(c, ordered(pinned, recent));
        return true;
    }

    static void togglePin(Context c, String text) {
        List<Clip> pinned = new ArrayList<>(), recent = new ArrayList<>();
        for (Clip k : list(c)) {
            if (k.text.equals(text)) {
                if (k.pinned) {
                    recent.add(0, new Clip(k.text, false, k.at));
                } else if (pinned.size() < MAX_PINNED) {
                    pinned.add(0, new Clip(k.text, true, k.at));
                } else {
                    recent.add(k);
                }
                continue;
            }
            (k.pinned ? pinned : recent).add(k);
        }
        save(c, ordered(pinned, recent));
    }

    static void remove(Context c, String text) {
        List<Clip> keep = new ArrayList<>();
        for (Clip k : list(c)) if (!k.text.equals(text)) keep.add(k);
        save(c, keep);
    }

    /** Clears the history but keeps pinned clips. */
    static void clearRecent(Context c) {
        List<Clip> keep = new ArrayList<>();
        for (Clip k : list(c)) if (k.pinned) keep.add(k);
        save(c, keep);
    }

    // ---- shortcuts ------------------------------------------------------------------

    /** Lower-case code → phrase, from lines like "myemail = me@example.com". */
    static LinkedHashMap<String, String> shortcuts(Context c) {
        return parseShortcuts(Prefs.get(c, Prefs.SHORTCUTS, ""));
    }

    /**
     * Reads shortcuts written almost any way: "code = phrase", "code: phrase", "code - phrase",
     * "code → phrase" or just "code phrase". Codes are matched without case or spaces.
     */
    static LinkedHashMap<String, String> parseShortcuts(String stored) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String raw : stored.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int at = -1, len = 1;
            String[] seps = {"=", "→", "->", ":", " - "};
            for (String sep : seps) {
                int i = line.indexOf(sep);
                if (i > 0 && (at < 0 || i < at)) {
                    at = i;
                    len = sep.length();
                }
            }
            // A code is one or two words; anything longer means the separator was in the phrase.
            if (at > 0 && line.substring(0, at).trim().split("\\s+").length > 2) at = -1;
            if (at < 0) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                at = sp;
                len = 1;
            }
            String code = codeOf(line.substring(0, at));
            String phrase = line.substring(at + len).trim().replace("\\n", "\n");
            if (code.isEmpty() || phrase.isEmpty()) continue;
            out.put(code, phrase);
        }
        return out;
    }

    /** How a code is matched: lower case, no spaces. */
    static String codeOf(String s) {
        return s.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static String displayShortcuts(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(" = ").append(e.getValue().replace("\n", "\\n"));
        }
        return sb.toString();
    }
}
