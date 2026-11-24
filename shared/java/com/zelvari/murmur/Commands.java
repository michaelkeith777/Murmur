package com.zelvari.murmur;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spoken edits: right after a dictation the user can say "make that shorter", "translate that
 * to Spanish" or "scratch that", and Murmur rewrites (or removes) what it just typed. Anything
 * else can be asked by starting with "Murmur,".
 */
final class Commands {
    private Commands() {}

    static final String UNDO = "\u0000undo";

    private static final int MAX_WORDS = 16;

    private static final Pattern WAKE =
            Pattern.compile("^(?:hey |ok |okay )?murmur[,.!:]?\\s+(.+)$");
    private static final Pattern UNDO_P = Pattern.compile(
            "^(?:scratch|delete|erase|undo|remove|cancel) (?:that|this|it|the last (?:bit|part|sentence|thing))$");
    private static final String REF = "(?:that|this|it|the (?:last|whole) (?:bit|part|sentence|message|text|thing))";
    private static final Pattern[] EDITS = {
            Pattern.compile("^make " + REF + " (?:a (?:bit |little )?|more |less |sound |into |an? |all )?"
                    + "(?:shorter|longer|simpler|clearer|nicer|funnier|friendlier|warmer|kinder|happier|"
                    + "sweeter|punchier|snappier|smoother|tighter|crisper|cleaner|better|politer|softer|"
                    + "stronger|bolder|calmer|cuter|formal|informal|casual|professional|polite|friendly|"
                    + "concise|list|bulleted|bullet|rhyme|caps|lowercase|uppercase|funny|fun|nice|cute|sweet|"
                    + "kind|happy|short|long|simple|clear|warm|polished|grammatical|flirty|hype|pirate)\\b.*$"),
            Pattern.compile("^make " + REF + " (?:more|less|sound|into|like|in) .+$"),
            Pattern.compile("^translate " + REF + "(?: in| into| to)? \\w+.*$"),
            Pattern.compile("^say " + REF + " (?:in|like) .+$"),
            Pattern.compile("^translate (?:in)?to \\w+$"),
            Pattern.compile("^(?:shorten|lengthen|summarize|summarise|simplify|expand|soften|polish|"
                    + "rephrase|reword|rewrite|proofread|redo|tidy up|clean up|fix|formalize|capitalize) "
                    + REF + "(?: .*)?$"),
            Pattern.compile("^(?:turn|put|convert|change|format|rewrite|write) " + REF
                    + " (?:into|in|to|as|like) .+$"),
            Pattern.compile("^fix (?:the )?(?:grammar|spelling|punctuation|typos?)(?: .*)?$"),
            Pattern.compile("^add (?:some |a few |an? )?(?:emojis?|hashtags?|a greeting|a sign ?off|"
                    + "a signature|punctuation)(?: .*)?$"),
    };

    /**
     * Returns the instruction to apply, {@link #UNDO} to remove the last dictation, or null if
     * the transcript is ordinary dictation.
     */
    static String match(String transcript) {
        String t = transcript.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[“”\"]", "")
                .replaceAll("[.!?…]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.isEmpty() || t.split(" ").length > MAX_WORDS) return null;
        Matcher wake = WAKE.matcher(t);
        if (wake.matches()) {
            String rest = wake.group(1).trim();
            if (UNDO_P.matcher(rest).matches()) return UNDO;
            return rest.isEmpty() ? null : rest;
        }
        if (UNDO_P.matcher(t).matches()) return UNDO;
        for (Pattern p : EDITS) {
            if (p.matcher(t).matches()) return t;
        }
        return null;
    }
}
