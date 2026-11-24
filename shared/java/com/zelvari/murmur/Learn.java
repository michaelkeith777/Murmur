package com.zelvari.murmur;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Personal vocabulary: the user's own word list plus spellings learned from the fixes they
 * make to dictated text. Both feed the speech-to-text hint and the cleanup prompt.
 */
final class Learn {
    private Learn() {}

    private static final int MAX_LEARNED = 80;
    /** Whisper only reads a short prompt, so keep the hint well under its limit. */
    private static final int MAX_HINT_CHARS = 600;

    // ---- storage ---------------------------------------------------------------

    /** Learned pairs, heard → meant, oldest first. */
    static LinkedHashMap<String, String> learned(Context c) {
        return parse(Prefs.sp(c).getString(Prefs.LEARNED, ""));
    }

    static LinkedHashMap<String, String> parse(String stored) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (stored == null) return out;
        for (String line : stored.split("\n")) {
            String[] parts = splitPair(line);
            if (parts == null) continue;
            out.remove(parts[0].toLowerCase(Locale.ROOT));
            out.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
        }
        return out;
    }

    /** Accepts "heard\tmeant", "heard → meant", "heard -> meant" or "heard = meant". */
    private static String[] splitPair(String line) {
        String l = line.trim();
        if (l.isEmpty()) return null;
        String[] seps = {"\t", "→", "->", "="};
        for (String sep : seps) {
            int i = l.indexOf(sep);
            if (i > 0) {
                String a = l.substring(0, i).trim();
                String b = l.substring(i + sep.length()).trim();
                if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) return new String[]{a, b};
                return null;
            }
        }
        return null;
    }

    static String serialize(Map<String, String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        return sb.toString();
    }

    /** How the settings screen shows the list: one "heard → meant" per line. */
    static String display(Map<String, String> pairs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(" → ").append(e.getValue());
        }
        return sb.toString();
    }

    static void save(Context c, Map<String, String> pairs) {
        Prefs.sp(c).edit().putString(Prefs.LEARNED, serialize(pairs)).apply();
    }

    /** Stores the pairs (newest last, capped) and returns how many were new. */
    static int remember(Context c, List<String[]> found) {
        if (found.isEmpty()) return 0;
        LinkedHashMap<String, String> all = learned(c);
        int added = 0;
        for (String[] p : found) {
            String key = p[0].toLowerCase(Locale.ROOT);
            String old = all.remove(key);
            if (!p[1].equals(old)) added++;
            all.put(key, p[1]);
        }
        while (all.size() > MAX_LEARNED) {
            String first = all.keySet().iterator().next();
            all.remove(first);
        }
        save(c, all);
        return added;
    }

    /** Adds a word or name to the user's own list. Returns false if it was already there. */
    static boolean addWord(Context c, String word) {
        String w = word.trim();
        if (w.isEmpty()) return false;
        String vocab = Prefs.get(c, Prefs.VOCAB, "");
        for (String existing : vocab.split(",")) {
            if (existing.trim().equalsIgnoreCase(w)) return false;
        }
        String next = vocab.isEmpty() ? w : vocab + ", " + w;
        Prefs.sp(c).edit().putString(Prefs.VOCAB, next).apply();
        return true;
    }

    // ---- writing style ---------------------------------------------------------

    private static final int MAX_SAMPLES = 12;
    private static final int MAX_SAMPLE_CHARS = 280;

    static List<String> samples(Context c) {
        List<String> out = new ArrayList<>();
        for (String line : Prefs.sp(c).getString(Prefs.SAMPLES, "").split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** Keeps a finished dictation as a sample of how the user writes. */
    static void addSample(Context c, String text) {
        if (!Prefs.flag(c, Prefs.LEARN_STYLE, true)) return;
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() < 12) return; // too short to say anything about style
        if (t.length() > MAX_SAMPLE_CHARS) t = t.substring(0, MAX_SAMPLE_CHARS);
        List<String> all = samples(c);
        all.remove(t);
        all.add(t);
        while (all.size() > MAX_SAMPLES) all.remove(0);
        Prefs.sp(c).edit().putString(Prefs.SAMPLES, String.join("\n", all)).apply();
    }

    /** Swaps the newest sample for the version the user fixed by hand, which is truer to them. */
    static void fixLastSample(Context c, String typed, String fixed) {
        List<String> all = samples(c);
        String t = typed.replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_SAMPLE_CHARS) t = t.substring(0, MAX_SAMPLE_CHARS);
        int i = all.lastIndexOf(t);
        if (i < 0) return;
        String f = fixed.replaceAll("\\s+", " ").trim();
        if (f.length() > MAX_SAMPLE_CHARS) f = f.substring(0, MAX_SAMPLE_CHARS);
        all.set(i, f);
        Prefs.sp(c).edit().putString(Prefs.SAMPLES, String.join("\n", all)).apply();
    }

    /** A few recent samples for the cleanup prompt, or "" if style learning is off or empty. */
    static String styleHint(Context c) {
        if (!Prefs.flag(c, Prefs.LEARN_STYLE, true)) return "";
        List<String> all = samples(c);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, all.size() - 6); i < all.size(); i++) {
            sb.append("\n- ").append(all.get(i));
        }
        return sb.toString();
    }

    /** Forgets everything learned automatically. The user's own word list stays. */
    static void clearLearned(Context c) {
        Prefs.sp(c).edit().remove(Prefs.LEARNED).remove(Prefs.SAMPLES).remove(Prefs.TYPED_WORDS).apply();
    }

    // ---- typing ------------------------------------------------------------------

    private static final int MAX_TYPED = 200;

    /** Words autocorrect should leave alone because the user undid its fix. */
    static List<String> typedWords(Context c) {
        List<String> out = new ArrayList<>();
        for (String w : Prefs.get(c, Prefs.TYPED_WORDS, "").split("\n")) {
            if (!w.trim().isEmpty()) out.add(w.trim());
        }
        return out;
    }

    static void keepTypedWord(Context c, String word) {
        String w = word.trim();
        if (w.isEmpty() || w.length() > 40) return;
        List<String> all = typedWords(c);
        all.remove(w);
        all.add(w);
        while (all.size() > MAX_TYPED) all.remove(0);
        Prefs.sp(c).edit().putString(Prefs.TYPED_WORDS, String.join("\n", all)).apply();
    }

    /** Everything the keyboard should know as a real word: the word list, learned spellings, kept words. */
    static List<String> userWords(Context c) {
        List<String> out = new ArrayList<>();
        for (String w : Prefs.get(c, Prefs.VOCAB, "").split(",")) {
            if (!w.trim().isEmpty()) out.add(w.trim());
        }
        out.addAll(learned(c).values());
        out.addAll(typedWords(c));
        return out;
    }

    /** Applies learned fixes word-for-word; used when the AI cleanup pass didn't run. */
    static String applyFixes(Context c, String text) {
        String out = text;
        for (Map.Entry<String, String> e : learned(c).entrySet()) {
            String heard = java.util.regex.Pattern.quote(e.getKey());
            out = out.replaceAll("(?iu)(?<![\\p{L}\\p{N}])" + heard + "(?![\\p{L}\\p{N}])",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    // ---- prompts ---------------------------------------------------------------

    /** Spelling hint for speech-to-text: the user's words, then learned spellings. */
    static String sttHint(Context c) {
        StringBuilder sb = new StringBuilder(Prefs.get(c, Prefs.VOCAB, ""));
        List<String> meant = new ArrayList<>(learned(c).values());
        // Newest first, so the most recent fixes survive the length cap.
        for (int i = meant.size() - 1; i >= 0; i--) {
            String w = meant.get(i);
            if (sb.indexOf(w) >= 0) continue;
            if (sb.length() + w.length() + 2 > MAX_HINT_CHARS) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(w);
        }
        // A line of the user's own recent writing helps Whisper with their spelling and punctuation.
        List<String> recent = Prefs.flag(c, Prefs.LEARN_STYLE, true) ? samples(c) : new ArrayList<>();
        if (!recent.isEmpty()) {
            String last = recent.get(recent.size() - 1);
            if (sb.length() + last.length() + 2 <= MAX_HINT_CHARS) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(last);
            }
        }
        String s = sb.toString();
        return s.length() > MAX_HINT_CHARS ? s.substring(0, MAX_HINT_CHARS) : s;
    }

    /** Learned fixes as prompt lines, or "" if there are none. */
    static String cleanupHint(Context c) {
        LinkedHashMap<String, String> pairs = learned(c);
        if (pairs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            sb.append("\n- \"").append(e.getKey()).append("\" → \"").append(e.getValue()).append('"');
        }
        return sb.toString();
    }

    // ---- learning from fixes -----------------------------------------------------

    /**
     * Compares what Murmur typed with what the field holds now and returns the small word
     * fixes the user made, as {heard, meant} pairs. Only fixes that look like names, brands or
     * terms are kept, so ordinary grammar edits don't pile up as "vocabulary".
     */
    static List<String[]> findFixes(String typed, String now) {
        List<String[]> out = new ArrayList<>();
        String[] a = words(typed);
        String[] b = words(now);
        if (a.length == 0 || b.length == 0) return out;
        // Only look at as much of the field as Murmur typed, plus a little slack.
        if (b.length > a.length + 6) {
            String[] cut = new String[a.length + 6];
            System.arraycopy(b, 0, cut, 0, cut.length);
            b = cut;
        }
        String[] na = norm(a);
        String[] nb = norm(b);

        int n = na.length, m = nb.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = na[i].equals(nb[j]) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        // The user rewrote it rather than fixing a word or two: learn nothing.
        int changed = n - lcs[0][0];
        if (changed > Math.max(3, n * 2 / 5)) return out;

        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && na[i].equals(nb[j])) {
                i++;
                j++;
                continue;
            }
            int i0 = i, j0 = j;
            while (i < n || j < m) {
                if (i < n && j < m && na[i].equals(nb[j])) break;
                if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) i++;
                else j++;
            }
            int lenA = i - i0, lenB = j - j0;
            // Trailing words the user added after the dictation are not fixes.
            if (lenA == 0 || lenB == 0 || lenA > 3 || lenB > 3) continue;
            String heard = join(na, i0, i, true);
            String meant = join(nb, j0, j, false);
            if (heard.isEmpty() || meant.isEmpty() || heard.equals(meant)) continue;
            boolean sentenceStart = j0 == 0 || endsSentence(b[j0 - 1]);
            if (looksLikeTerm(nb, j0, j, sentenceStart)) out.add(new String[]{heard, meant});
        }
        return out;
    }

    private static String[] words(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    /** Strips surrounding punctuation but keeps case, so "iphone" → "iPhone" counts as a fix. */
    private static String[] norm(String[] w) {
        String[] out = new String[w.length];
        for (int i = 0; i < w.length; i++) {
            out[i] = w[i].replaceAll("^[\\p{Punct}“”‘’«»…]+|[\\p{Punct}“”‘’«»…]+$", "");
        }
        return out;
    }

    private static boolean endsSentence(String w) {
        return w.matches(".*[.!?…]['\"”’)]*$");
    }

    private static String join(String[] w, int from, int to, boolean lower) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++) {
            if (w[k].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w[k]);
        }
        String s = sb.toString();
        return lower ? s.toLowerCase(Locale.ROOT) : s;
    }

    private static boolean looksLikeTerm(String[] w, int from, int to, boolean sentenceStart) {
        for (int k = from; k < to; k++) {
            String t = w[k];
            if (t.isEmpty()) continue;
            for (int x = 0; x < t.length(); x++) {
                char ch = t.charAt(x);
                if (Character.isDigit(ch)) return true;
                if (Character.isUpperCase(ch) && (x > 0 || k > from || !sentenceStart)) return true;
                if (!Character.isLetter(ch) && ch != '\'' && ch != '’') return true;
            }
        }
        return false;
    }
}
