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
        return Prefs.flag(c, Prefs.TRANSLATE_ON, false) ? last(c) : "";
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

    static String code(String language) {
        for (String[] l : LANGUAGES) if (l[0].equals(language)) return l[1];
        return null;
    }
}
