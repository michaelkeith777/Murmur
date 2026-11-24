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
    /** Tone the cleanup pass writes in; see {@link Tones}. */
    static final String TONE = "tone";
    /** Spoken edits such as "make that shorter" act on the last dictation. */
    static final String COMMANDS = "commands";
    /** Learn spellings from the fixes the user makes to dictated text. */
    static final String LEARN = "learn";
    /** Learned corrections, one "heard\tmeant" pair per line, newest last. */
    static final String LEARNED = "learned";
    /** Learn how the user writes from their recent dictations. */
    static final String LEARN_STYLE = "learn_style";
    /** Recent finished dictations, one per line, newest last: the user's own voice. */
    static final String SAMPLES = "samples";
    /** Fix typos on space with the word list (typing keys). */
    static final String AUTOCORRECT = "autocorrect";
    /** Slide across the letters to type a word. */
    static final String SWIPE = "swipe";
    /** A light buzz on every key. */
    static final String KEY_VIBRATE = "key_vibrate";
    /** Replace a typo by itself on space. Off by default: Murmur only suggests fixes above the keys. */
    static final String AUTO_REPLACE = "auto_replace";
    /** Suggestions the user tapped for a typed word, "typed\tpicked" per line, newest last. */
    static final String PICKS = "picks";
    /** Speak to translate: the target language, and whether it's on. */
    static final String TRANSLATE_TO = "translate_to";
    static final String TRANSLATE_ON = "translate_on";
    /** Conversation mode: Michael's own language, whether to read translations aloud, hands-free turns. */
    static final String CONVO_MINE = "convo_mine";
    static final String CONVO_SPEAK = "convo_speak";
    static final String CONVO_HANDS_FREE = "convo_hands_free";
    static final String CONVO_FACE = "convo_face";
    /** Texting mode: what he sends goes out in their language, and their replies come back in his. */
    static final String TEXTING = "texting";
    /** The latest incoming message, translated (set by the notification reader or a copy). */
    static final String IN_TEXT = "in_text";
    static final String IN_FROM = "in_from";
    static final String IN_APP = "in_app";
    static final String IN_AT = "in_at";
    /** Key height on narrow screens like a foldable's cover screen, in percent (80 to 130). */
    static final String NARROW_KEY_SIZE = "narrow_key_size";
    static final int NARROW_KEY_SIZE_DEFAULT = 110;
    /** Show the number row on narrow screens. */
    static final String NARROW_NUMBERS = "narrow_numbers";
    /** A click sound on every key. */
    static final String KEY_SOUND = "key_sound";
    /** Words the user kept by undoing an autocorrect, one per line, newest last. */
    static final String TYPED_WORDS = "typed_words";
    /** Keyboard colour theme; see {@link Theme}. */
    static final String THEME = "theme";
    /** One-handed mode: off, left or right. */
    static final String ONE_HAND = "one_hand";
    /** Keep a history of copied text on the phone. */
    static final String CLIP_HISTORY = "clip_history";
    /** The clipboard history itself (JSON), on the phone only. */
    static final String CLIPS = "clips";
    /** Text shortcuts, one "code = phrase" per line. */
    static final String SHORTCUTS = "shortcuts";
    /** Settings version, for one-time upgrades of stored defaults. */
    static final String SCHEMA = "schema";
    /** Time (ms) the one-tap trigger last asked the keyboard to listen as soon as it opens. */
    static final String ONE_SHOT_AT = "one_shot_at";
    /** Keyboard that was active before the one-tap trigger switched to Murmur. */
    static final String PREVIOUS_IME = "previous_ime";
    static final long ONE_SHOT_WINDOW_MS = 8000;

    static final String DEF_BASE_URL = "https://api.groq.com/openai/v1";
    static final String DEF_STT_MODEL = "whisper-large-v3";
    /** The faster, less accurate model 1.0 and 1.1 used by default. */
    static final String OLD_STT_MODEL = "whisper-large-v3-turbo";
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

    /**
     * One-time upgrades. 1.2 moves people still on the old default speech model (the settings
     * screen saved the default, so it looks like a choice) to the more accurate full model.
     */
    static void migrate(Context c) {
        SharedPreferences p = sp(c);
        if (p.getInt(SCHEMA, 0) >= 2) return;
        SharedPreferences.Editor e = p.edit();
        if (OLD_STT_MODEL.equals(p.getString(STT_MODEL, "").trim())) e.putString(STT_MODEL, DEF_STT_MODEL);
        e.putInt(SCHEMA, 2).apply();
    }

    /**
     * Language for speech-to-text. Blank means the phone's own language, which is far more
     * reliable on short clips than auto-detect; "auto" asks the server to guess.
     */
    static String language(Context c) {
        String v = get(c, LANGUAGE, "").toLowerCase(java.util.Locale.ROOT);
        if (v.equals("auto")) return "";
        if (!v.isEmpty()) return v;
        String phone = java.util.Locale.getDefault().getLanguage();
        if ("iw".equals(phone)) phone = "he";
        if ("in".equals(phone)) phone = "id";
        if ("ji".equals(phone)) phone = "yi";
        return phone != null && phone.length() == 2 ? phone : "";
    }
}
