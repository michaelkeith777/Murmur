package com.zelvari.murmur;

import java.util.ArrayList;
import java.util.List;

/**
 * Swipe (glide) typing: turns the path a finger draws over the letters into the words it most
 * likely spells. Each candidate word must pass close to every one of its letters, in order;
 * survivors are ranked by how well their ideal path lines up with the drawn one, and by how
 * common they are. Works in key units (x / key width, y / row height). Pure Java.
 */
final class SwipeDecoder {
    private final float[] cx = new float[26];
    private final float[] cy = new float[26];
    private boolean ready;

    /** Sets letter centres in key units. */
    void setGeometry(float[] xs, float[] ys) {
        System.arraycopy(xs, 0, cx, 0, 26);
        System.arraycopy(ys, 0, cy, 0, 26);
        ready = true;
    }

    boolean ready() {
        return ready;
    }

    private static final int M = 32;
    private static final float NEAR = 1.15f;
    static float FREQ_WEIGHT = 0.5f, LOC_WEIGHT = 3.0f, FIT_WEIGHT = 2.5f, LEN_WEIGHT = 2.0f;

    /** Points in key units; returns up to {@code max} words, best first. */
    List<String> decode(Dict dict, float[] px, float[] py, int n, int max) {
        List<String> out = new ArrayList<>();
        if (!ready || n < 2) return out;
        float[][] g = resample(px, py, n, 0.2f);
        int gn = g[0].length;
        float glen = length(px, py, n);
        float[][] gm = resampleCount(g[0], g[1], gn, M);

        boolean[] starts = near(g[0][0], g[1][0]);
        boolean[] ends = near(g[0][gn - 1], g[1][gn - 1]);

        List<String> words = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        synchronized (dict) {
            for (int b = 0; b < 26; b++) {
                if (!starts[b]) continue;
                for (Dict.Entry e : dict.byFirst[b]) {
                    if (e.quiet) continue;
                    String k = e.key;
                    if (k.length() < 2) continue;
                    int last = k.charAt(k.length() - 1) - 'a';
                    if (last < 0 || last >= 26 || !ends[last]) continue;
                    float s = score(k, g, gn, gm, glen);
                    if (Float.isNaN(s)) continue;
                    s += FREQ_WEIGHT * (e.freq / 100f);
                    keep(words, scores, e.word, s, max);
                }
            }
        }
        out.addAll(words);
        return out;
    }

    private boolean[] near(float x, float y) {
        boolean[] ok = new boolean[26];
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int c = 0; c < 26; c++) {
            float d = dist(x, y, cx[c], cy[c]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
            if (d <= NEAR) ok[c] = true;
        }
        ok[best] = true;
        return ok;
    }

    /** Higher is better; NaN when the word can't be what was drawn. */
    private float score(String key, float[][] g, int gn, float[][] gm, float glen) {
        // Letters with repeats collapsed: a finger can't draw "ll".
        int[] letters = new int[key.length()];
        int ln = 0;
        for (int i = 0; i < key.length(); i++) {
            int c = key.charAt(i) - 'a';
            if (c < 0 || c >= 26) return Float.NaN;
            if (ln > 0 && letters[ln - 1] == c) continue;
            letters[ln++] = c;
        }
        if (ln < 2) return Float.NaN;

        float ilen = 0;
        for (int i = 1; i < ln; i++) {
            ilen += dist(cx[letters[i - 1]], cy[letters[i - 1]], cx[letters[i]], cy[letters[i]]);
        }
        if (ilen > glen * 1.8f + 1.2f || ilen < glen * 0.45f - 1.2f) return Float.NaN;

        // Every letter must be passed close by, in order: the best monotonic alignment of
        // letters to points on the path (a small dynamic program).
        float[] prev = new float[gn], cur = new float[gn];
        for (int i = 0; i < ln; i++) {
            float runMin = Float.MAX_VALUE;
            for (int j = 0; j < gn; j++) {
                if (i > 0) runMin = Math.min(runMin, prev[j]);
                float base = i == 0 ? 0f : runMin;
                float d = dist(g[0][j], g[1][j], cx[letters[i]], cy[letters[i]]);
                cur[j] = d > NEAR || base == Float.MAX_VALUE ? Float.MAX_VALUE : base + d;
            }
            float[] t = prev;
            prev = cur;
            cur = t;
        }
        float bestFit = Float.MAX_VALUE;
        for (int j = 0; j < gn; j++) bestFit = Math.min(bestFit, prev[j]);
        if (bestFit == Float.MAX_VALUE) return Float.NaN;
        float letterFit = bestFit / ln;

        // How well the whole ideal path lines up with the drawn path.
        float[] ix = new float[ln], iy = new float[ln];
        for (int i = 0; i < ln; i++) {
            ix[i] = cx[letters[i]];
            iy[i] = cy[letters[i]];
        }
        float[][] im = resampleCount(ix, iy, ln, M);
        float loc = 0;
        for (int i = 0; i < M; i++) loc += dist(im[0][i], im[1][i], gm[0][i], gm[1][i]);
        loc /= M;

        // A word whose ideal path is much shorter or longer than the drawn one is unlikely.
        float lenGap = Math.abs(ilen - glen) / Math.max(glen, 1f);
        return -(LOC_WEIGHT * loc + FIT_WEIGHT * letterFit + LEN_WEIGHT * lenGap);
    }

    private static void keep(List<String> words, List<Float> scores, String w, float s, int max) {
        int at = words.size();
        while (at > 0 && scores.get(at - 1) < s) at--;
        if (at >= max) return;
        if (words.contains(w)) return;
        words.add(at, w);
        scores.add(at, s);
        if (words.size() > max) {
            words.remove(words.size() - 1);
            scores.remove(scores.size() - 1);
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float length(float[] x, float[] y, int n) {
        float l = 0;
        for (int i = 1; i < n; i++) l += dist(x[i - 1], y[i - 1], x[i], y[i]);
        return l;
    }

    /** Points every {@code step} along the path (always keeps both ends). */
    private static float[][] resample(float[] x, float[] y, int n, float step) {
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x[0], y[0]});
        float carry = 0;
        for (int i = 1; i < n; i++) {
            float sx = x[i - 1], sy = y[i - 1];
            float seg = dist(sx, sy, x[i], y[i]);
            if (seg == 0) continue;
            float t = step - carry;
            while (t <= seg) {
                pts.add(new float[]{sx + (x[i] - sx) * t / seg, sy + (y[i] - sy) * t / seg});
                t += step;
            }
            carry = seg - (t - step);
            if (pts.size() > 400) break;
        }
        pts.add(new float[]{x[n - 1], y[n - 1]});
        float[][] out = new float[2][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            out[0][i] = pts.get(i)[0];
            out[1][i] = pts.get(i)[1];
        }
        return out;
    }

    /** Exactly {@code m} points evenly spaced along the path. */
    private static float[][] resampleCount(float[] x, float[] y, int n, int m) {
        float[][] out = new float[2][m];
        float total = length(x, y, n);
        if (total == 0) {
            for (int i = 0; i < m; i++) {
                out[0][i] = x[0];
                out[1][i] = y[0];
            }
            return out;
        }
        float step = total / (m - 1);
        int seg = 1;
        float segStart = 0;
        for (int i = 0; i < m; i++) {
            float target = i * step;
            while (seg < n - 1 && segStart + dist(x[seg - 1], y[seg - 1], x[seg], y[seg]) < target) {
                segStart += dist(x[seg - 1], y[seg - 1], x[seg], y[seg]);
                seg++;
            }
            float sl = dist(x[seg - 1], y[seg - 1], x[seg], y[seg]);
            float t = sl == 0 ? 0 : Math.min(1f, (target - segStart) / sl);
            out[0][i] = x[seg - 1] + (x[seg] - x[seg - 1]) * t;
            out[1][i] = y[seg - 1] + (y[seg] - y[seg - 1]) * t;
        }
        return out;
    }
}
