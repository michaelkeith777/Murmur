package com.zelvari.murmur;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the messages that just arrived at the bottom of a chat on screen. It keeps the bottom of
 * the chat as last seen and lines the screen up against it, so a reply that repeats an earlier
 * one ("ok", "sí") still counts, and scrolling up and back down finds nothing new. (1.8 and 1.9
 * compared texts only, so a repeated reply was taken as already read and never translated.)
 */
final class NewReplies {
    private static final int KEEP = 60;
    private final List<String> tail = new ArrayList<>();

    /** Starts over from what's on screen now (another chat was opened). */
    void reset(List<String> screen) {
        tail.clear();
        tail.addAll(screen);
        trim();
    }

    boolean isEmpty() {
        return tail.isEmpty();
    }

    /**
     * The positions on screen that are new, after the last message seen before. Empty when the
     * screen doesn't reach down to it (scrolled up) or nothing came after it. {@code null} when
     * nothing on screen lines up with what was seen (a different chat, most likely). With
     * {@code otherName} (the chat's name read differently) only a clear line-up counts.
     */
    List<Integer> update(List<String> screen, boolean otherName) {
        List<Integer> fresh = new ArrayList<>();
        if (tail.isEmpty()) {
            reset(screen);
            return fresh;
        }
        int at = align(screen);
        if (at < 0) {
            // Scrolled up past the bottom, or another chat: line up again only if nothing matches.
            if (otherName) return null;
            for (String s : screen) if (tail.contains(s)) return fresh;
            return null;
        }
        for (int i = at + 1; i < screen.size(); i++) {
            fresh.add(i);
            tail.add(screen.get(i));
        }
        trim();
        return fresh;
    }

    /** Where the last message seen sits on screen (checked with the ones above it), or -1. */
    private int align(List<String> screen) {
        int n = tail.size();
        int ctx = Math.min(3, n);
        for (int i = screen.size() - 1; i >= 0; i--) {
            boolean ok = true;
            for (int k = 0; k < ctx && ok; k++) {
                int si = i - k;
                if (si < 0) break; // the rest is above the top of the screen
                ok = screen.get(si).equals(tail.get(n - 1 - k));
            }
            if (ok) return i;
        }
        return -1;
    }

    private void trim() {
        while (tail.size() > KEEP) tail.remove(0);
    }
}
