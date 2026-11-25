package com.zelvari.murmur;

import android.content.Context;

/** Speak to translate: dictation comes out in another language. */
final class Translate {
    private Translate() {}

    /** Language name (what the AI is told) and the short code shown on the top bar button. */
    static final String[][] LANGUAGES = {
            {"Spanish", "ES"}, {"English", "EN"}, {"French", "FR"}, {"German", "DE"},
            {"Italian", "IT"}, {"Portuguese", "PT"}, {"Chinese (Simplified)", "ZH"},
            {"Japanese", "JA"}, {"Korean", "KO"}, {"Arabic", "AR"}, {"Hindi", "HI"},
            {"Russian", "RU"}, {"Vietnamese", "VI"}, {"Tagalog", "TL"}, {"Dutch", "NL"},
            {"Polish", "PL"}, {"Turkish", "TR"}, {"Ukrainian", "UK"}, {"Haitian Creole", "HT"},
    };

    /** The language dictation is translated into right now, or "" when translate is off. */
    static String target(Context c) {
        // Live Translate: one switch (⇄) that stays exactly as he set it, in every chat. 1.9 tried
        // to switch by itself per chat, and reading which chat is open wasn't reliable enough.
        if (texting(c)) return sending(c) ? theirsDefault(c) : "";
        return Prefs.flag(c, Prefs.TRANSLATE_ON, false) ? last(c) : "";
    }

    /** The chat he's typing in: its own language ("" for the default) and whether it's switched off. */
    static volatile String chatLang = "";
    static volatile boolean chatOff;
    static volatile boolean chatSend;

    /** Texting mode: send in their language, read their replies in yours. */
    static boolean texting(Context c) {
        return Prefs.flag(c, Prefs.TEXTING, false);
    }

    /** Texting mode: whether what he sends goes out in their language. */
    static boolean sending(Context c) {
        return Prefs.flag(c, Prefs.SEND_LAST, false);
    }

    static void setSending(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean(Prefs.SEND_LAST, on).apply();
    }

    static void setTexting(Context c, boolean on) {
        android.content.SharedPreferences.Editor e = Prefs.sp(c).edit().putBoolean(Prefs.TEXTING, on);
        if (on && last(c).isEmpty()) e.putString(Prefs.TRANSLATE_TO, "Spanish");
        e.apply();
    }

    /** Remembers what he just sent in their language, so it isn't mistaken for a reply. */
    static void rememberSent(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String old = Prefs.get(c, Prefs.SENT_RECENT, "");
        StringBuilder b = new StringBuilder(norm(text));
        String[] lines = old.split("\n");
        for (int i = 0; i < lines.length && i < 14; i++) if (!lines[i].isEmpty()) b.append('\n').append(lines[i]);
        Prefs.sp(c).edit().putString(Prefs.SENT_RECENT, b.toString()).apply();
    }

    static boolean wasSent(Context c, String text) {
        String n = norm(text);
        if (n.isEmpty()) return false;
        for (String l : Prefs.get(c, Prefs.SENT_RECENT, "").split("\n")) {
            if (!l.isEmpty() && (l.equals(n) || (n.length() > 12 && (l.startsWith(n) || n.startsWith(l))))) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern SPANISH_WORD = java.util.regex.Pattern.compile(
            "(?iu)(^|[^\\p{L}])(que|qué|como|cómo|estás|estas|está|esta|hola|gracias|por|para|pero|porque|"
            + "bueno|bien|mañana|manana|hoy|ahora|tengo|tienes|quiero|vamos|voy|eres|soy|muy|mucho|nada|"
            + "también|tambien|donde|dónde|cuando|cuándo|sí|si|claro|amor|mi|tu|te|yo|el|la|los|las|un|una|"
            + "es|de|en|con|del|al|lo|le|me|se|no sé|vale|oye|jaja|jeje|ya|aquí|aqui|allí|nos|hacer|hace|"
            + "puedo|puedes|ver|vez|día|dia|noche|casa|trabajo|ella|él|ellos|usted|ustedes|algo|todo)(?=$|[^\\p{L}])");

    /**
     * True when a message plainly isn't in English: Spanish accents and marks, or Spanish words
     * that English doesn't have. Only used when his language is English, to catch a reply the
     * translator left as is.
     */
    static boolean looksForeign(String text, String mine) {
        if (text == null || mine == null || !mine.toLowerCase(java.util.Locale.ROOT).startsWith("english")) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        int letters = 0, odd = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (ch > 0x7F) odd++;
            }
            if (ch == '¿' || ch == '¡') return true;
        }
        if (letters == 0) return false;
        if (odd > 0) return true;
        java.util.regex.Matcher m = SPANISH_WORD.matcher(t);
        int hits = 0;
        while (m.find()) hits++;
        int words = t.split("\\s+").length;
        return hits >= 2 || (hits == 1 && words <= 3 && !t.matches("(?i).*\\b(i|you|the|is|are|and|to|ok|okay|lol)\\b.*"));
    }

    static boolean sameText(String a, String b) {
        return norm(a).replaceAll("[^\\p{L}\\p{N} ]", "").equals(norm(b).replaceAll("[^\\p{L}\\p{N} ]", ""));
    }

    private static String norm(String t) {
        return t == null ? "" : t.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** Saves a translated incoming message for the keyboard's top bar. */
    static void saveIncoming(Context c, String from, String text, String app) {
        Prefs.sp(c).edit().putString(Prefs.IN_FROM, from).putString(Prefs.IN_TEXT, text)
                .putString(Prefs.IN_APP, app).putLong(Prefs.IN_AT, System.currentTimeMillis()).apply();
    }

    /** The language picked last time, so one tap turns it back on. */
    static String last(Context c) {
        String l = Prefs.get(c, Prefs.TRANSLATE_TO, "");
        return code(l) == null ? "" : l;
    }

    static boolean picked(Context c) {
        return !last(c).isEmpty();
    }

    static void set(Context c, String language, boolean on) {
        Prefs.sp(c).edit().putString(Prefs.TRANSLATE_TO, language).putBoolean(Prefs.TRANSLATE_ON, on).apply();
    }

    static void setOn(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean(Prefs.TRANSLATE_ON, on).apply();
    }

    /** The language Michael speaks in conversation mode (the other side is {@link #last}). */
    static String mine(Context c) {
        String l = Prefs.get(c, Prefs.CONVO_MINE, "English");
        return code(l) == null ? "English" : l;
    }

    /** The other person's language in conversation mode: the keyboard's last pick, or Spanish. */
    static String theirs(Context c) {
        String cl = chatLang;
        if (!cl.isEmpty() && code(cl) != null) return cl;
        String l = last(c);
        return l.isEmpty() ? "Spanish" : l;
    }

    /** The language new chats send in (ignoring the open chat's own choice). */
    static String theirsDefault(Context c) {
        String l = last(c);
        return l.isEmpty() ? "Spanish" : l;
    }

    /** ISO 639-1 code for speech-to-text and the voice ("es"). */
    static String iso(String language) {
        String code = code(language);
        return code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
    }

    static java.util.Locale locale(String language) {
        String iso = iso(language);
        if (iso.equals("tl")) return new java.util.Locale("fil");
        if (iso.equals("zh")) return java.util.Locale.SIMPLIFIED_CHINESE;
        return new java.util.Locale(iso);
    }

    static String code(String language) {
        for (String[] l : LANGUAGES) if (l[0].equals(language)) return l[1];
        return null;
    }
}
