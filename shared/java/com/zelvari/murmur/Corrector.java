package com.zelvari.murmur;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autocorrect and the suggestion strip: finds the words the user most likely meant, scoring
 * each by how common it is and how far it is from what was typed, where hitting a key next
 * to the right one costs less than a far miss. Pure Java so it can be tested off the phone.
 */
final class Corrector {
    private Corrector() {}

    static final class Result {
        /** Best first, written the way the user typed (case). Never includes the typed word. */
        final List<String> suggestions = new ArrayList<>();
        /** What a space would turn the word into, or null to leave it. */
        String autocorrect;
        /** The typed word is a known word. */
        boolean valid;
    }

    // QWERTY key centres in key units (row 2 starts half a key in, row 3 one and a half).
    private static final float[] KX = new float[26], KY = new float[26];
    static {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] off = {0f, 0.5f, 1.5f};
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                int c = rows[r].charAt(i) - 'a';
                KX[c] = off[r] + i;
                KY[c] = r;
            }
        }
    }

    static boolean adjacent(char a, char b) {
        if (a < 'a' || a > 'z' || b < 'a' || b > 'z') return false;
        float dx = KX[a - 'a'] - KX[b - 'a'], dy = KY[a - 'a'] - KY[b - 'a'];
        return dx * dx + dy * dy <= 1.6f;
    }

    private static final float SUB_NEAR = 0.5f, SUB_FAR = 1f, INDEL = 1f, DOUBLE = 0.3f, SWAP = 0.6f;
    private static final float WEIGHT = 2.4f;

    /** Edit distance where neighbouring-key slips, doubled letters and swaps are cheap. */
    static float distance(String a, String b, float limit) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > limit + 0.01f) return Float.MAX_VALUE;
        float[][] d = new float[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i * INDEL;
        for (int j = 0; j <= m; j++) d[0][j] = j * INDEL;
        for (int i = 1; i <= n; i++) {
            float rowMin = Float.MAX_VALUE;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                float sub = ca == cb ? 0f : adjacent(ca, cb) ? SUB_NEAR : SUB_FAR;
                float best = d[i - 1][j - 1] + sub;
                // Typed an extra letter (a doubled one is the cheap, common case).
                float del = i > 1 && a.charAt(i - 2) == ca ? DOUBLE : INDEL;
                best = Math.min(best, d[i - 1][j] + del);
                // Missed a letter (again, a doubled one is cheap).
                float ins = j > 1 && b.charAt(j - 2) == cb ? DOUBLE : INDEL;
                best = Math.min(best, d[i][j - 1] + ins);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    best = Math.min(best, d[i - 2][j - 2] + SWAP);
                }
                d[i][j] = best;
                if (best < rowMin) rowMin = best;
            }
            if (rowMin > limit) return Float.MAX_VALUE;
        }
        return d[n][m];
    }

    private static float limitFor(int len) {
        if (len <= 2) return 0.6f;
        if (len == 3) return 1.0f;
        if (len <= 5) return 1.5f;
        return 2.0f;
    }

    private static final class Cand {
        final Dict.Entry e;
        final float dist;
        final float score;
        final boolean completion;

        Cand(Dict.Entry e, float dist, float score, boolean completion) {
            this.e = e;
            this.dist = dist;
            this.score = score;
            this.completion = completion;
        }
    }

    static Result suggest(Dict dict, String typed, int max) {
        Result r = new Result();
        String key = Dict.keyOf(typed);
        if (key.isEmpty()) return r;
        boolean plain = typed.matches("[\\p{L}']+");
        List<Cand> best = new ArrayList<>();
        Dict.Entry exact;
        synchronized (dict) {
            exact = dict.lookup(typed);
            r.valid = exact != null;
            float limit = Math.max(limitFor(key.length()), 1.0f);
            char first = key.charAt(0);
            for (int b = 0; b < 26; b++) {
                char fc = (char) ('a' + b);
                if (fc != first && !adjacent(fc, first)) continue;
                for (Dict.Entry e : dict.byFirst[b]) {
                    if (e.quiet) continue;
                    String k = e.key;
                    float score;
                    float dist;
                    boolean completion = false;
                    if (k.length() > key.length() && fc == first && k.startsWith(key)) {
                        // Finishing the word: cheap, more so the less is left to type.
                        dist = 0f;
                        completion = true;
                        score = e.freq / 100f - 0.12f * (k.length() - key.length()) - 0.6f;
                    } else {
                        if (Math.abs(k.length() - key.length()) > limit) continue;
                        dist = distance(key, k, limit);
                        if (dist == Float.MAX_VALUE) continue;
                        score = e.freq / 100f - WEIGHT * dist;
                    }
                    insert(best, new Cand(e, dist, score, completion), max + 3);
                }
            }
        }

        for (Cand c : best) {
            String w = Dict.matchCase(typed, c.e.word);
            if (w.equals(typed)) continue;
            if (!r.suggestions.contains(w)) r.suggestions.add(w);
            if (r.suggestions.size() >= max) break;
        }

        if (!plain) return r;
        if (exact != null) {
            // "i" → "I", "i'm" → "I'm": same word, the list knows how to write it.
            if (!exact.word.equals(typed) && exact.word.equalsIgnoreCase(typed)
                    && !exact.word.equals(exact.word.toLowerCase(Locale.ROOT))
                    && !Character.isUpperCase(typed.charAt(0))) {
                r.autocorrect = Dict.matchCase(typed, exact.word);
                return r;
            }
            // A rare spelling right next to a very common word is usually a slip ("helo").
            Cand top = firstCorrection(best);
            if (top != null && exact.freq < 290 && !exact.user && top.dist <= 0.7f
                    && top.e.freq - exact.freq >= 230 && !best.get(0).completion
                    && top.e.key.charAt(0) == key.charAt(0)) {
                r.autocorrect = Dict.matchCase(typed, top.e.word);
            }
            return r;
        }
        // Single letters ("u", "k") and word starts the user is keeping ("Zel") stay as typed.
        if (key.length() < 2 || best.isEmpty() || best.get(0).completion) return r;
        Cand top = firstCorrection(best);
        if (top != null && top.dist <= limitFor(key.length())) {
            r.autocorrect = Dict.matchCase(typed, top.e.word);
        }
        return r;
    }

    private static Cand firstCorrection(List<Cand> best) {
        for (Cand c : best) if (!c.completion) return c;
        return null;
    }

    private static void insert(List<Cand> best, Cand c, int cap) {
        int at = best.size();
        while (at > 0 && best.get(at - 1).score < c.score) at--;
        if (at >= cap) return;
        for (Cand o : best) if (o.e.word.equals(c.e.word)) return;
        best.add(at, c);
        if (best.size() > cap) best.remove(best.size() - 1);
    }
}
