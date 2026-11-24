package com.zelvari.murmur;

import android.content.Context;

/** The keyboard's colour themes and one-handed mode. */
final class Theme {
    private Theme() {}

    static final String[] IDS = {"midnight", "amoled", "ocean", "forest", "sunset", "light"};
    static final String[] LABELS = {"Midnight", "Pure black", "Ocean", "Forest", "Sunset", "Light"};

    // bg, surface (modifier keys), key, pressed, text, dim
    private static final int[][] COLORS = {
            {0xFF0F1115, 0xFF1A1D24, 0xFF262A33, 0xFF3A3F4B, 0xFFF2F3F5, 0xFF9AA0AC},
            {0xFF000000, 0xFF0E0E10, 0xFF1A1A1D, 0xFF2E2E33, 0xFFFFFFFF, 0xFF8E8E93},
            {0xFF0B1622, 0xFF12233A, 0xFF1B3150, 0xFF2A4A74, 0xFFEAF2FF, 0xFF8FA8C8},
            {0xFF0E1612, 0xFF16231C, 0xFF203328, 0xFF2F4B3B, 0xFFEAF5EE, 0xFF92AE9C},
            {0xFF1A0F14, 0xFF2A161F, 0xFF3A1F2B, 0xFF552A3E, 0xFFFFEFF4, 0xFFC49AAA},
            {0xFFE9EBEF, 0xFFD3D7DE, 0xFFFFFFFF, 0xFFC2C7D0, 0xFF1B1E24, 0xFF5F6673},
    };

    static final int BG = 0, SURFACE = 1, KEY = 2, PRESSED = 3, TEXT = 4, DIM = 5;

    static final String HAND_OFF = "off", HAND_LEFT = "left", HAND_RIGHT = "right";

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }

    static String current(Context c) {
        return IDS[index(Prefs.get(c, Prefs.THEME, IDS[0]))];
    }

    static void set(Context c, String id) {
        Prefs.sp(c).edit().putString(Prefs.THEME, IDS[index(id)]).apply();
    }

    static int[] colors(String id) {
        return COLORS[index(id)].clone();
    }

    static String hand(Context c) {
        String h = Prefs.get(c, Prefs.ONE_HAND, HAND_OFF);
        return HAND_LEFT.equals(h) || HAND_RIGHT.equals(h) ? h : HAND_OFF;
    }

    static void setHand(Context c, String h) {
        Prefs.sp(c).edit().putString(Prefs.ONE_HAND, h).apply();
    }
}
