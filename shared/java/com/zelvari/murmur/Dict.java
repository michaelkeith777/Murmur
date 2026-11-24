package com.zelvari.murmur;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The word list behind autocorrect and swipe typing: the bundled English list plus the
 * user's own words (their vocabulary, learned fixes and words they told autocorrect to keep).
 * Pure Java so it can be tested off the phone.
 */
final class Dict {
    static final class Entry {
        /** How the word is written, e.g. "I'm" or "Zelvari". */
        final String word;
        /** Letters only, lower case: "im", "zelvari". What typing and swiping match against. */
        final String key;
        /** log10(count) × 100: about 200 for rare words, 740 for "you". */
        final int freq;
        /** Never offered as a suggestion (profanity); still valid when typed. */
        final boolean quiet;
        final boolean user;

        Entry(String word, int freq, boolean quiet, boolean user) {
            this.word = word;
            this.key = keyOf(word);
            this.freq = freq;
            this.quiet = quiet;
            this.user = user;
        }
    }

    /** Frequency given to the user's own words: as common as everyday words. */
    static final int USER_FREQ = 600;

    @SuppressWarnings("unchecked")
    final List<Entry>[] byFirst = new List[26];
    /** Lower-case written form → entry, for "is this a real word?". */
    private final Map<String, Entry> byLower = new HashMap<>();
    private final List<Entry> userEntries = new ArrayList<>();

    Dict() {
        for (int i = 0; i < 26; i++) byFirst[i] = new ArrayList<>();
    }

    static Dict load(InputStream in) throws IOException {
        Dict d = new Dict();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                boolean quiet = line.charAt(0) == '!';
                String w = line.substring(quiet ? 1 : 0, tab);
                int f;
                try {
                    f = Integer.parseInt(line.substring(tab + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                d.add(new Entry(w, f, quiet, false));
            }
        }
        // The file is sorted by frequency, so every bucket already is.
        return d;
    }

    private void add(Entry e) {
        if (e.key.isEmpty()) return;
        int b = e.key.charAt(0) - 'a';
        if (b < 0 || b >= 26) return;
        byFirst[b].add(e);
        String lower = e.word.toLowerCase(Locale.ROOT);
        Entry old = byLower.get(lower);
        if (old == null || old.freq < e.freq) byLower.put(lower, e);
    }

    /** Replaces the user's own words. Multi-word entries ("Zelvari Memory") add each word. */
    synchronized void setUserWords(Collection<String> words) {
        for (Entry e : userEntries) {
            byFirst[e.key.charAt(0) - 'a'].remove(e);
            String lower = e.word.toLowerCase(Locale.ROOT);
            if (byLower.get(lower) == e) byLower.remove(lower);
        }
        userEntries.clear();
        for (String phrase : words) {
            for (String w : phrase.split("[\\s,]+")) {
                w = w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
                if (w.isEmpty() || keyOf(w).isEmpty()) continue;
                Entry known = byLower.get(w.toLowerCase(Locale.ROOT));
                // Common words need no help; only add what's missing or written differently.
                if (known != null && known.word.equals(w) && known.freq >= USER_FREQ) continue;
                Entry e = new Entry(w, Math.max(USER_FREQ, known != null ? known.freq : 0), false, true);
                userEntries.add(e);
                int b = e.key.charAt(0) - 'a';
                if (b < 0 || b >= 26) continue;
                // Keep the bucket sorted by frequency.
                List<Entry> list = byFirst[b];
                int at = 0;
                while (at < list.size() && list.get(at).freq >= e.freq) at++;
                list.add(at, e);
                byLower.put(w.toLowerCase(Locale.ROOT), e);
            }
        }
    }

    /** The entry for exactly this written word (any case), or null. */
    synchronized Entry lookup(String typed) {
        return byLower.get(typed.toLowerCase(Locale.ROOT));
    }

    static String keyOf(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = Character.toLowerCase(w.charAt(i));
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        return sb.toString();
    }

    /** Writes {@code word} with the capitalization the user typed ("Hello", "HELLO"). */
    static String matchCase(String typed, String word) {
        if (typed.isEmpty() || word.isEmpty()) return word;
        int letters = 0, upper = 0;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        if (letters > 1 && upper == letters) return word.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }
}
