package com.zelvari.murmur;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings, stored privately on the device. */
final class Prefs {
    static final String API_KEY = "api_key";
    static final String BASE_URL = "base_url";
    static final String STT_MODEL = "stt_model";
    static final String LLM_MODEL = "llm_model";
    static final String CLEANUP = "cleanup";
    static final String STYLE = "style";
    static final String VOCAB = "vocab";
    static final String LANGUAGE = "language";
    static final String AUTO_START = "auto_start";
    static final String AUTO_RETURN = "auto_return";
    /** Time (ms) the one-tap trigger last asked the keyboard to listen as soon as it opens. */
    static final String ONE_SHOT_AT = "one_shot_at";
    /** Keyboard that was active before the one-tap trigger switched to Murmur. */
    static final String PREVIOUS_IME = "previous_ime";
    static final long ONE_SHOT_WINDOW_MS = 8000;

    static final String DEF_BASE_URL = "https://api.groq.com/openai/v1";
    static final String DEF_STT_MODEL = "whisper-large-v3-turbo";
    static final String DEF_LLM_MODEL = "openai/gpt-oss-120b";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("murmur", Context.MODE_PRIVATE);
    }

    /** Returns the trimmed value, or {@code def} when unset or blank. */
    static String get(Context c, String key, String def) {
        String v = sp(c).getString(key, null);
        if (v == null) return def;
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    static boolean flag(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }
}
