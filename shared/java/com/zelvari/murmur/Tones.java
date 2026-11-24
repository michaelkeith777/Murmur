package com.zelvari.murmur;

import android.content.Context;

/** The writing tones the keyboard's tone chip switches between. */
final class Tones {
    private Tones() {}

    static final String AUTO = "auto";
    static final String PROFESSIONAL = "professional";
    static final String CASUAL = "casual";

    static final String[] IDS = {
            AUTO, PROFESSIONAL, CASUAL, "hype", "short", "pirate", "shakespeare", "flirty", "kind"
    };
    static final String[] LABELS = {
            "Auto", "Professional", "Casual", "Hype", "Short & sweet", "Pirate", "Shakespeare", "Flirty", "Kind"
    };
    static final String[] EMOJI = {
            "✨", "💼", "😎", "🔥", "✂️", "☠️", "🎭", "😘", "🕊️"
    };
    static final int[] COLORS = {
            0xFF7C5CFF, 0xFF3B82F6, 0xFF22C55E, 0xFFF97316, 0xFF14B8A6,
            0xFFB7791F, 0xFFA855F7, 0xFFEC4899, 0xFF38BDF8
    };
    private static final String[] RULES = {
            "",
            "Professional: polished, clear and grammatically correct, the way a capable professional "
                    + "writes an email or a work message. Use complete sentences and professional word "
                    + "choice, replace slang and casual phrasing, and keep it concise and courteous.",
            "Casual: relaxed and conversational, like texting a friend. Use contractions, everyday "
                    + "words and short sentences, keep punctuation light, and avoid stiff or formal phrasing.",
            "Hype: upbeat, energetic and enthusiastic, with a few fitting emojis. Exclamation marks "
                    + "are welcome. Keep it fun but keep the meaning.",
            "Short and sweet: cut it down to the bare minimum that still says everything that matters. "
                    + "Drop pleasantries, repetition and padding. Fragments are fine.",
            "Pirate: rewrite it in playful pirate speak (ahoy, matey, arr, ye, me hearty) while keeping "
                    + "the meaning easy to understand.",
            "Shakespeare: rewrite it in playful Shakespearean English (thee, thou, doth, prithee, "
                    + "forsooth) while keeping the meaning easy to understand.",
            "Flirty: warm, playful and a little flirtatious, for texting someone special. Keep it sweet "
                    + "and tasteful, never crude.",
            "Kind: warm, calm and considerate. Soften anything harsh, angry or blunt so it reads "
                    + "respectfully, while still saying what the speaker needs to say. No sarcasm."
    };

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String current(Context c) {
        return IDS[index(Prefs.get(c, Prefs.TONE, AUTO))];
    }

    static void set(Context c, String id) {
        Prefs.sp(c).edit().putString(Prefs.TONE, IDS[index(id)]).apply();
    }

    static String label(String id) {
        return LABELS[index(id)];
    }

    static String emoji(String id) {
        return EMOJI[index(id)];
    }

    static int color(String id) {
        return COLORS[index(id)];
    }

    /** Prompt rule for the tone, or "" for Auto (match the app being typed into). */
    static String rule(String id) {
        return RULES[index(id)];
    }
}
