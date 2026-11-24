package com.zelvari.murmur;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Murmur keyboard: a full typing keyboard with a big dictation button on the right.
 * Tap the mic, talk, tap again; the recording is transcribed, polished in the chosen tone,
 * and typed into whatever field has focus. Spoken edits ("make that shorter") rewrite the
 * last dictation, and fixes the user makes teach Murmur their names and terms.
 * The watch layout is just the mic and a few keys, so every view here is optional.
 */
public class MurmurIME extends InputMethodService {
    private static final String IDLE_HINT = "Tap the mic and talk";
    private static final long MIN_RECORDING_MS = 500;
    private static final long DOUBLE_TAP_MS = 350;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView cancel;
    private ImageView mic;
    private TextView toneChip;
    private View statusRow;
    private HorizontalScrollView tonePicker;
    private LinearLayout toneList;
    private KeyboardView keys;
    private LinearLayout suggestions;

    private MediaRecorder recorder;
    private File audioFile;
    private long startedAt;
    private boolean recording;
    private boolean busy;
    /** Bumped whenever in-flight work should be ignored (cancel, keyboard closed). */
    private int generation;
    private int lastInsertedLength;
    /** The last dictation as typed (without the leading space), for spoken edits. */
    private String lastText;
    /** What a spoken edit replaced, so ↶ can bring it back. */
    private String undoRestore;
    /** Watch layout: the keyboard fills the whole screen. */
    private boolean fillScreen;
    /** Opened by the one-tap trigger: listen right away, then hand back to the regular keyboard. */
    private boolean oneShot;
    /** A selected word the user can add to their word list by tapping the status line. */
    private String offeredWord;

    // Learning from fixes: where the last dictation went and what it said.
    private String learnText;
    private int learnStart = -1;
    private String learnField;
    private int selStart = -1;
    private int selEnd = -1;

    // Typing keyboard state.
    private static final int SHIFT_OFF = 0, SHIFT_ONCE = 1, SHIFT_LOCK = 2;
    private int shift = SHIFT_OFF;
    private boolean symbols;
    private long lastShiftTap;
    private long lastSpaceTap;
    private int toneColor;

    private final Runnable meter = new Runnable() {
        @Override
        public void run() {
            if (!recording || recorder == null) return;
            int amp = 0;
            try {
                amp = recorder.getMaxAmplitude();
            } catch (RuntimeException ignored) {
                // recorder not ready yet
            }
            // The phone's tall button only breathes a little; the round watch button pulses more.
            float reach = keys != null ? 0.07f : 0.22f;
            float scale = 1f + Math.min(1f, amp / 12000f) * reach;
            mic.animate().scaleX(scale).scaleY(scale).setDuration(90).start();
            long sec = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            status.setText(String.format(Locale.US, "Listening %d:%02d · tap to finish", sec / 60, sec % 60));
            ui.postDelayed(this, 100);
        }
    };

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            deleteBackward();
            ui.postDelayed(this, 55);
        }
    };

    @Override
    public View onCreateInputView() {
        Prefs.migrate(this);
        View root = getLayoutInflater().inflate(R.layout.keyboard, null);
        status = root.findViewById(R.id.status);
        cancel = root.findViewById(R.id.cancel);
        mic = root.findViewById(R.id.mic);
        toneChip = root.findViewById(R.id.tone);
        statusRow = root.findViewById(R.id.status_row);
        tonePicker = root.findViewById(R.id.tone_picker);
        toneList = root.findViewById(R.id.tone_list);
        keys = root.findViewById(R.id.keys);
        suggestions = root.findViewById(R.id.suggestions);
        toneColor = Tones.color(Tones.current(this));

        fillScreen = getResources().getBoolean(R.bool.fill_screen);
        if (fillScreen) {
            View panel = root.findViewById(R.id.panel);
            if (panel != null) {
                panel.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels;
            }
        }

        mic.setOnClickListener(v -> onMicTapped());
        cancel.setOnClickListener(v -> cancelEverything("Cancelled"));
        status.setOnClickListener(v -> onStatusTapped());

        // The watch layout leaves some of these keys out, so each one is optional.
        onClick(root, R.id.k_space, v -> type(" "));
        onClick(root, R.id.k_comma, v -> type(","));
        onClick(root, R.id.k_period, v -> type("."));
        onClick(root, R.id.k_enter, v -> pressEnter());
        onClick(root, R.id.k_undo, v -> undoLast());

        View switcher = root.findViewById(R.id.k_switch);
        if (switcher != null) setupSwitcher(switcher);

        View del = root.findViewById(R.id.k_del);
        if (del != null) setupDelete(del);

        if (toneChip != null) {
            toneChip.setOnClickListener(v -> showTonePicker(true));
            toneChip.setOnLongClickListener(v -> {
                String now = Tones.current(this);
                pickTone(Tones.PROFESSIONAL.equals(now) ? Tones.CASUAL : Tones.PROFESSIONAL);
                return true;
            });
            paintToneChip(false);
        }
        if (keys != null) setupKeys();

        render(IDLE_HINT);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;
        String learned = learnFromFixes(info);
        if (word.length() > 0) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
        resetWord();
        clearRevert();
        autoSpace = false;
        hideStrip();
        if (!restarting) symbols = false;
        if (shift != SHIFT_LOCK) shift = SHIFT_OFF;
        showTonePicker(false);
        refreshTone();
        readTypingOptions(info);
        if (keys != null) {
            keys.setSymbols(symbols);
            keys.setShift(shift);
            keys.setAccent(toneColor);
            refreshUserWords();
        }
        updateAutoShift();
        if (!restarting && !recording && !busy) {
            render(learned != null ? learned : IDLE_HINT);
            oneShot = takeOneShot();
            if (oneShot || Prefs.flag(this, Prefs.AUTO_START, false)) startRecording();
        } else if (learned != null && !recording && !busy) {
            render(learned);
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        selStart = newSelStart;
        selEnd = newSelEnd;
        if (word.length() > 0 && cursorLeftWord(newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
            // The user moved the cursor: leave the word as it is.
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            resetWord();
            hideStrip();
        }
        if (word.length() == 0) clearRevertIfMoved(newSelStart, newSelEnd);
        // Don't undo a Shift tap that the editor hasn't caught up with yet.
        if (SystemClock.uptimeMillis() - lastShiftTap > 500) updateAutoShift();
        if (recording || busy || status == null) return;
        if (newSelStart != newSelEnd) {
            offerSelectedWord();
        } else if (offeredWord != null) {
            render(IDLE_HINT);
        }
    }

    /** True when the cursor is no longer at the end of the word being typed. */
    private boolean cursorLeftWord(int selStart, int selEnd, int candStart, int candEnd) {
        if (candStart >= 0) return selStart != selEnd || selEnd != candEnd;
        // No word showing: either the editor is still catching up with us, or it dropped the
        // word because the user tapped elsewhere. The text before the cursor tells them apart.
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return true;
        CharSequence before = ic.getTextBeforeCursor(word.length(), 0);
        return before == null || !before.toString().equals(word.toString());
    }

    private void clearRevertIfMoved(int selStart, int selEnd) {
        if (revertFrom == null) return;
        if (selStart != selEnd) clearRevert();
    }

    /** True once if the one-tap trigger fired in the last few seconds. */
    private boolean takeOneShot() {
        long at = Prefs.sp(this).getLong(Prefs.ONE_SHOT_AT, 0);
        if (at == 0) return false;
        Prefs.sp(this).edit().remove(Prefs.ONE_SHOT_AT).apply();
        long age = System.currentTimeMillis() - at;
        return age >= 0 && age < Prefs.ONE_SHOT_WINDOW_MS;
    }

    private static void onClick(View root, int id, View.OnClickListener listener) {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(listener);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (word.length() > 0) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
        resetWord();
        hideStrip();
        learnFromFixes(getCurrentInputEditorInfo());
        super.onFinishInputView(finishingInput);
        ui.removeCallbacks(repeatDelete);
        // Never keep the microphone open once the keyboard is hidden.
        if (recording) cancelEverything(IDLE_HINT);
    }

    @Override
    public void onDestroy() {
        if (recording) discardRecording();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        typing.shutdownNow();
        super.onDestroy();
    }

    // ---- mic flow --------------------------------------------------------------

    private void onMicTapped() {
        if (busy) return;
        showTonePicker(false);
        finishWord(false);
        if (recording) {
            finishRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (recording || busy || mic == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            render("Microphone access needed. Opening Murmur…");
            openApp();
            return;
        }
        if (Prefs.get(this, Prefs.API_KEY, "").isEmpty()) {
            render("Add your API key in the Murmur app first");
            openApp();
            return;
        }
        // Learn from any fixes to the previous dictation before it scrolls out of reach.
        learnFromFixes(getCurrentInputEditorInfo());
        try {
            audioFile = new File(getCacheDir(), "dictation.m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            // Whisper listens at 16 kHz; a higher bit rate keeps consonants crisp for names.
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            releaseRecorder();
            render("Couldn't start the microphone. Is another app using it?");
            return;
        }
        recording = true;
        startedAt = SystemClock.elapsedRealtime();
        render("Listening…");
        ui.post(meter);
    }

    private void finishRecording() {
        long length = SystemClock.elapsedRealtime() - startedAt;
        boolean ok = stopRecorder();
        if (!ok || length < MIN_RECORDING_MS) {
            render("Too short. Tap, talk, then tap again");
            return;
        }

        busy = true;
        final int gen = ++generation;
        render("Transcribing…");

        final Context ctx = this;
        final File file = audioFile;
        final String key = Prefs.get(this, Prefs.API_KEY, "");
        final String base = stripSlash(Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL));
        final String sttModel = Prefs.get(this, Prefs.STT_MODEL, Prefs.DEF_STT_MODEL);
        final String llmModel = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String tone = Tones.current(this);
        final String toneRule = Tones.rule(tone);
        // Picking a tone on the keyboard turns the AI pass on even if plain cleanup is off.
        final boolean polish = Prefs.flag(this, Prefs.CLEANUP, true) || !toneRule.isEmpty();
        final String style = Prefs.get(this, Prefs.STYLE, "");
        final String vocab = Prefs.get(this, Prefs.VOCAB, "");
        final String sttHint = Learn.sttHint(this);
        final String learned = Learn.cleanupHint(this);
        final String samples = Learn.styleHint(this);
        final String language = Prefs.language(this);
        EditorInfo info = getCurrentInputEditorInfo();
        final String app = info != null && info.packageName != null ? info.packageName : "";

        // What a spoken edit would act on: the selection, or the dictation right before the cursor.
        String target = null;
        boolean targetIsSelection = false;
        InputConnection ic = getCurrentInputConnection();
        if (Prefs.flag(this, Prefs.COMMANDS, true) && ic != null) {
            CharSequence sel = ic.getSelectedText(0);
            if (sel != null && sel.length() > 0) {
                target = sel.toString();
                targetIsSelection = true;
            } else if (lastText != null && lastInsertedLength > 0) {
                CharSequence before = ic.getTextBeforeCursor(lastText.length(), 0);
                if (before != null && before.toString().equals(lastText)) target = lastText;
            }
        }
        final String fTarget = target;
        final boolean fTargetIsSelection = targetIsSelection;

        worker.execute(() -> {
            String text = null;
            String edited = null;
            boolean undo = false;
            boolean cleaned = false;
            String note = null;
            String error = null;
            try {
                String raw = AiClient.transcribe(base, key, sttModel, file, language, sttHint);
                String instruction = raw.isEmpty() || fTarget == null ? null : Commands.match(raw);
                if (raw.isEmpty()) {
                    error = "Didn't catch anything. Try again";
                } else if (Commands.UNDO.equals(instruction)) {
                    undo = true;
                } else if (instruction != null) {
                    ui.post(() -> { if (gen == generation) render("Editing: " + instruction); });
                    edited = AiClient.edit(base, key, llmModel, fTarget, instruction, vocab);
                    if (edited.isEmpty()) error = "The edit came back empty. Nothing changed";
                } else {
                    text = raw;
                    if (polish) {
                        String label = toneRule.isEmpty() ? "Polishing…"
                                : "Writing it " + Tones.emoji(tone) + " " + Tones.label(tone) + "…";
                        ui.post(() -> { if (gen == generation) render(label); });
                        try {
                            String out = AiClient.cleanup(base, key, llmModel, raw, style, vocab,
                                    learned, samples, app, toneRule);
                            if (!out.isEmpty()) {
                                text = out;
                                cleaned = true;
                            }
                        } catch (Exception e) {
                            note = "Inserted unpolished text (cleanup failed: " + e.getMessage() + ")";
                        }
                    }
                    // Without the AI pass, still apply the spellings the user taught us.
                    if (!cleaned) text = Learn.applyFixes(ctx, text);
                }
            } catch (Exception e) {
                String m = e.getMessage();
                error = m == null || m.isEmpty() ? "Network problem. Try again" : m;
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();

            final String fText = text;
            final String fEdited = edited;
            final boolean fUndo = undo;
            final String fNote = note;
            final String fError = error;
            // Only the user's own voice is a style sample, not pirate or Shakespeare.
            final boolean sample = Tones.AUTO.equals(tone) || Tones.CASUAL.equals(tone);
            ui.post(() -> {
                if (gen != generation) return;
                busy = false;
                if (fError != null) {
                    render(fError);
                    return;
                }
                if (fUndo) {
                    if (fTargetIsSelection) {
                        InputConnection c = getCurrentInputConnection();
                        if (c != null) c.commitText("", 1);
                        render("Deleted the selection");
                    } else {
                        undoLast();
                    }
                    return;
                }
                if (fEdited != null) {
                    if (replaceTarget(fTarget, fTargetIsSelection, fEdited)) {
                        render(fillScreen ? fEdited : "Edited · ↶ brings back the original");
                    } else {
                        render("The text moved, so I left it as it was");
                    }
                    return;
                }
                boolean inserted = insert(fText);
                if (!inserted) {
                    render("No text field to type into");
                } else {
                    if (sample) Learn.addSample(this, fText);
                    render(fNote != null ? fNote : fillScreen ? fText : "Done · ↶ undoes it");
                    boolean handBack = oneShot || Prefs.flag(this, Prefs.AUTO_RETURN, false);
                    oneShot = false;
                    if (handBack && !fillScreen) switchToPreviousKeyboard();
                }
            });
        });
    }

    private void cancelEverything(String message) {
        generation++;
        busy = false;
        oneShot = false;
        if (recording) discardRecording();
        render(message);
    }

    private void discardRecording() {
        stopRecorder();
        if (audioFile != null) {
            //noinspection ResultOfMethodCallIgnored
            audioFile.delete();
        }
    }

    /** Stops and releases the recorder. Returns false if nothing usable was captured. */
    private boolean stopRecorder() {
        recording = false;
        ui.removeCallbacks(meter);
        boolean ok = true;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                ok = false; // stop() throws when no audio was captured
            }
        } else {
            ok = false;
        }
        releaseRecorder();
        return ok;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // already released
            }
            recorder = null;
        }
    }

    // ---- dictated text -------------------------------------------------------------

    private boolean insert(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null || text.isEmpty()) return false;
        finishWord(false);
        clearRevert();
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        boolean needsSpace = before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(0))
                && !Character.isWhitespace(text.charAt(0));
        String out = needsSpace ? " " + text : text;
        int at = Math.min(selStart, selEnd);
        ic.commitText(out, 1);
        lastInsertedLength = out.length();
        lastText = text;
        undoRestore = null;
        EditorInfo info = getCurrentInputEditorInfo();
        if (at >= 0 && info != null) {
            learnText = text;
            learnStart = at + (needsSpace ? 1 : 0);
            learnField = fieldKey(info);
        } else {
            learnText = null;
        }
        return true;
    }

    /** Swaps the edit target for the edited text. Returns false if the text is no longer there. */
    private boolean replaceTarget(String target, boolean isSelection, String edited) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        if (isSelection) {
            CharSequence sel = ic.getSelectedText(0);
            if (sel == null || !sel.toString().equals(target)) return false;
            ic.commitText(edited, 1);
            lastInsertedLength = edited.length();
        } else {
            CharSequence before = ic.getTextBeforeCursor(target.length(), 0);
            if (before == null || !before.toString().equals(target)) return false;
            ic.beginBatchEdit();
            ic.deleteSurroundingText(target.length(), 0);
            ic.commitText(edited, 1);
            ic.endBatchEdit();
            lastInsertedLength = Math.max(0, lastInsertedLength - target.length()) + edited.length();
        }
        lastText = edited;
        undoRestore = target;
        learnText = null;
        return true;
    }

    private void undoLast() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || lastInsertedLength == 0) return;
        if (undoRestore != null && lastText != null) {
            CharSequence before = ic.getTextBeforeCursor(lastText.length(), 0);
            if (before != null && before.toString().equals(lastText)) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(lastText.length(), 0);
                ic.commitText(undoRestore, 1);
                ic.endBatchEdit();
                lastInsertedLength = Math.max(0, lastInsertedLength - lastText.length()) + undoRestore.length();
                lastText = undoRestore;
                undoRestore = null;
                render("Brought back the original");
                return;
            }
        }
        ic.deleteSurroundingText(lastInsertedLength, 0);
        lastInsertedLength = 0;
        lastText = null;
        undoRestore = null;
        learnText = null;
        render("Removed");
    }

    // ---- personal vocabulary -------------------------------------------------------

    private static String fieldKey(EditorInfo info) {
        return info.packageName + "#" + info.fieldId;
    }

    /**
     * Compares the last dictation with what its field holds now and learns the names and terms
     * the user fixed. Runs at calm moments: before the next dictation, before Enter, and when
     * the keyboard opens or closes. Returns a short note for the status line, or null.
     */
    private String learnFromFixes(EditorInfo info) {
        if (learnText == null || learnStart < 0 || info == null) return null;
        if (!fieldKey(info).equals(learnField)) return null;
        if (!Prefs.flag(this, Prefs.LEARN, true)) {
            learnText = null;
            return null;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return null;
        ExtractedText et;
        try {
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.hintMaxChars = 20000;
            et = ic.getExtractedText(req, 0);
        } catch (RuntimeException e) {
            return null;
        }
        if (et == null || et.text == null) return null;
        int start = learnStart - et.startOffset;
        if (start < 0 || start > et.text.length()) return null;
        int end = Math.min(et.text.length(), start + learnText.length() * 2 + 40);
        String now = et.text.subSequence(start, end).toString();
        if (now.startsWith(learnText)) return null; // untouched so far
        String typed = learnText;
        List<String[]> fixes = Learn.findFixes(typed, now);
        learnText = null;
        if (fixes.isEmpty()) return null;
        int added = Learn.remember(this, fixes);
        String fixed = now.split("\n", 2)[0];
        if (fixed.length() > typed.length() + 20) fixed = fixed.substring(0, typed.length() + 20);
        Learn.fixLastSample(this, typed, fixed);
        if (added == 0) return null;
        String first = fixes.get(fixes.size() - 1)[1];
        return "🧠 Learned “" + first + "”" + (fixes.size() > 1 ? " and " + (fixes.size() - 1) + " more" : "");
    }

    private void offerSelectedWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel == null) return;
        String w = sel.toString().trim();
        if (w.isEmpty() || w.length() > 40 || w.contains("\n") || w.split("\\s+").length > 4) return;
        render("Tap here to add “" + w + "” to your words");
        offeredWord = w;
    }

    private void onStatusTapped() {
        if (offeredWord == null) return;
        String w = offeredWord;
        boolean added = Learn.addWord(this, w);
        render(added ? "Added “" + w + "” to your words" : "“" + w + "” is already in your words");
    }

    // ---- tone ----------------------------------------------------------------------

    /** Picks up a tone changed in the Murmur app while the keyboard was away. */
    private void refreshTone() {
        int c = Tones.color(Tones.current(this));
        if (c != toneColor) {
            toneColor = c;
            paintToneChip(false);
        }
    }

    private void pickTone(String id) {
        Tones.set(this, id);
        int from = toneColor;
        toneColor = Tones.color(id);
        showTonePicker(false);
        paintToneChip(true);
        if (keys != null) keys.setAccent(toneColor);
        haptic(toneChip);
        if (!recording && !busy) {
            render(Tones.AUTO.equals(id) ? "✨ Auto: fits the app you're typing in"
                    : Tones.emoji(id) + " " + Tones.label(id) + " on. Everything you say comes out that way");
        }
        animateMicColor(from, toneColor);
    }

    private void paintToneChip(boolean bounce) {
        if (toneChip == null) return;
        String id = Tones.current(this);
        toneChip.setText(compactChip ? Tones.emoji(id) : Tones.emoji(id) + "  " + Tones.label(id) + "  ▾");
        toneChip.setBackground(pill(Tones.color(id), false));
        if (bounce) {
            toneChip.setScaleX(0.8f);
            toneChip.setScaleY(0.8f);
            toneChip.animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
        }
    }

    private void showTonePicker(boolean show) {
        if (tonePicker == null || statusRow == null || toneList == null) return;
        if (show == (tonePicker.getVisibility() == View.VISIBLE)) return;
        if (!show) {
            tonePicker.setVisibility(View.GONE);
            statusRow.setVisibility(View.VISIBLE);
            return;
        }
        toneList.removeAllViews();
        String current = Tones.current(this);
        for (int i = 0; i < Tones.IDS.length; i++) {
            final String id = Tones.IDS[i];
            TextView chip = new TextView(this);
            chip.setText(Tones.EMOJI[i] + "  " + Tones.LABELS[i]);
            chip.setTextColor(getColor(R.color.text));
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(pill(Tones.COLORS[i], id.equals(current)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMargins(dp(3), 0, dp(3), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> pickTone(id));
            toneList.addView(chip);
            // The chips pop in one after another.
            chip.setAlpha(0f);
            chip.setTranslationY(dp(10));
            chip.animate().alpha(1f).translationY(0).setStartDelay(i * 28L).setDuration(180).start();
        }
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(getColor(R.color.dim));
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(34)));
        close.setContentDescription("Close tones");
        close.setOnClickListener(v -> showTonePicker(false));
        toneList.addView(close);
        tonePicker.setVisibility(View.VISIBLE);
        statusRow.setVisibility(View.INVISIBLE);
        final int index = Tones.index(current);
        tonePicker.post(() -> {
            View sel = toneList.getChildAt(index);
            if (sel != null) tonePicker.smoothScrollTo(Math.max(0, sel.getLeft() - dp(40)), 0);
        });
    }

    private void animateMicColor(int from, int to) {
        if (mic == null || recording || busy) return;
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(350);
        a.addUpdateListener(an -> {
            if (!recording && !busy) {
                mic.setBackgroundTintList(ColorStateList.valueOf((Integer) an.getAnimatedValue()));
            }
        });
        a.start();
    }

    /** A rounded pill in the tone's colour: tinted when idle, solid when selected. */
    private GradientDrawable pill(int color, boolean solid) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(17));
        g.setColor(solid ? color : (color & 0x00FFFFFF) | 0x38000000);
        if (!solid) g.setStroke(dp(1.5f), color);
        return g;
    }

    // ---- typing keyboard -------------------------------------------------------------

    /** The word list is shared by every keyboard instance and loaded once, off the main thread. */
    private static volatile Dict dict;
    private static boolean dictLoading;
    private static final Object DICT_LOCK = new Object();

    private final ExecutorService typing = Executors.newSingleThreadExecutor();
    private final SwipeDecoder swipe = new SwipeDecoder();
    /** The word being typed, shown underlined (composing) until a space or punctuation ends it. */
    private final StringBuilder word = new StringBuilder();
    private boolean wordSwiped;
    /** Don't autocorrect this word: it touches a number or other letters, or the user kept it. */
    private boolean wordNoCorrect;
    private String keepAsTyped;
    private final List<String> swipeAlts = new ArrayList<>();
    private boolean smartField, suggestOn, autocorrectOn;
    /** The last autocorrect, so backspace right after it brings back what was typed. */
    private String revertFrom, revertTo, revertSep;
    /** A space added after a picked suggestion; punctuation typed next moves in front of it. */
    private boolean autoSpace;
    private int lastCharShift;
    private int suggestSeq;
    private String cachedFor;
    private Corrector.Result cached;
    private boolean compactChip;

    private static final int CELL_WORD = 0, CELL_KEEP = 1, CELL_SWIPE = 2;
    private final TextView[] cells = new TextView[3];
    private final String[] cellWord = new String[3];
    private final int[] cellKind = new int[3];

    private void setupKeys() {
        keys.setColors(getColor(R.color.key), getColor(R.color.surface), getColor(R.color.key_pressed),
                getColor(R.color.text), getColor(R.color.dim));
        keys.setAccent(toneColor);
        keys.setListener(new KeyboardView.Listener() {
            @Override
            public void onKeyChar(String s) {
                onTypedChar(s);
            }

            @Override
            public void onKeySpace() {
                onSpace();
            }

            @Override
            public void onKeyEnter() {
                pressEnter();
            }

            @Override
            public void onKeyDelete() {
                onDeleteKey();
            }

            @Override
            public void onKeyShift() {
                onShift();
            }

            @Override
            public void onKeyMode() {
                keys.setSymbols(!keys.symbols());
                symbols = keys.symbols();
                if (!symbols) updateAutoShift();
            }

            @Override
            public void onKeySwitch() {
                finishWord(false);
                switchToPreviousKeyboard();
            }

            @Override
            public void onKeyPicker() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }

            @Override
            public void onSwipeStart() {
                onGestureStart();
            }

            @Override
            public void onSwipe(float[] xs, float[] ys, int n) {
                onGesture(xs, ys, n);
            }
        });
        if (suggestions != null) {
            suggestions.removeAllViews();
            for (int i = 0; i < 3; i++) {
                final int at = i;
                TextView cell = new TextView(this);
                cell.setGravity(Gravity.CENTER);
                cell.setTextColor(getColor(R.color.text));
                cell.setTextSize(16);
                cell.setSingleLine(true);
                cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.setPadding(dp(4), 0, dp(4), 0);
                cell.setSoundEffectsEnabled(false);
                cell.setBackground(keyBg(getColor(R.color.bg)));
                cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                cell.setOnClickListener(v -> pickSuggestion(at));
                cells[i] = cell;
                suggestions.addView(cell);
                if (i < 2) {
                    View line = new View(this);
                    line.setBackgroundColor(getColor(R.color.key));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(22));
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    line.setLayoutParams(lp);
                    suggestions.addView(line);
                }
            }
        }
        loadDict();
    }

    private void loadDict() {
        final Context app = getApplicationContext();
        synchronized (DICT_LOCK) {
            if (dict != null) {
                refreshUserWords();
                return;
            }
            if (dictLoading) return;
            dictLoading = true;
        }
        typing.execute(() -> {
            Dict d;
            try (InputStream in = app.getAssets().open("words.txt")) {
                d = Dict.load(in);
            } catch (IOException | RuntimeException e) {
                d = new Dict(); // the watch has no word list; the user's own words still work
            }
            d.setUserWords(Learn.userWords(app));
            synchronized (DICT_LOCK) {
                dict = d;
                dictLoading = false;
            }
        });
    }

    /** Picks up new words from the app, learned fixes and kept words. */
    private void refreshUserWords() {
        final Dict d = dict;
        if (d == null) return;
        final Context app = getApplicationContext();
        typing.execute(() -> d.setUserWords(Learn.userWords(app)));
    }

    /** Which typing help fits this field: none in passwords, emails and web addresses. */
    private void readTypingOptions(EditorInfo info) {
        int type = info != null ? info.inputType : 0;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int var = type & InputType.TYPE_MASK_VARIATION;
        boolean secret = var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        boolean address = var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_URI;
        smartField = cls == InputType.TYPE_CLASS_TEXT && !secret && !address;
        suggestOn = smartField && (type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0;
        autocorrectOn = suggestOn && Prefs.flag(this, Prefs.AUTOCORRECT, true);
        if (keys != null) {
            keys.setOptions(smartField && Prefs.flag(this, Prefs.SWIPE, true),
                    Prefs.flag(this, Prefs.KEY_VIBRATE, true), Prefs.flag(this, Prefs.KEY_SOUND, false));
        }
    }

    private void resetWord() {
        word.setLength(0);
        wordSwiped = false;
        wordNoCorrect = false;
        swipeAlts.clear();
        cachedFor = null;
    }

    private void clearRevert() {
        revertFrom = null;
        revertTo = null;
        revertSep = null;
    }

    private void onTypedChar(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastCharShift = shift;
        char c = s.charAt(0);
        boolean letter = Character.isLetter(c);
        boolean wordChar = letter || (c == '\'' && word.length() > 0 && !wordSwiped);
        boolean punct = ".,!?;:)".indexOf(c) >= 0;
        clearRevert();
        if (smartField && wordChar) {
            if (wordSwiped) {
                // A tap after a swiped word starts the next word.
                finishWord(false);
                ic.commitText(" ", 1);
            }
            if (word.length() == 0) startWord(ic);
            word.append(s);
            ic.setComposingText(word, 1);
            autoSpace = false;
        } else {
            if (word.length() > 0) finishWord(punct || c == '"');
            if (punct && autoSpace) {
                // "word ." becomes "word. "
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                if (before != null && before.length() == 1 && before.charAt(0) == ' ') {
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(s + " ", 1);
                    ic.endBatchEdit();
                    autoSpace = false;
                    lastInsertedLength = 0;
                    afterKey(letter);
                    return;
                }
            }
            ic.commitText(s, 1);
            if (revertFrom != null && revertSep == null) revertSep = s;
            autoSpace = false;
        }
        lastInsertedLength = 0;
        afterKey(letter);
    }

    private void afterKey(boolean letter) {
        if (shift == SHIFT_ONCE && letter) {
            shift = SHIFT_OFF;
            if (keys != null) keys.setShift(shift);
        }
        scheduleSuggestions();
    }

    /** A new word begins; it is left alone if it's glued to a number or other letters. */
    private void startWord(InputConnection ic) {
        wordNoCorrect = false;
        keepAsTyped = null;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (before != null && before.length() == 1 && Character.isLetterOrDigit(before.charAt(0))) wordNoCorrect = true;
        if (after != null && after.length() == 1 && Character.isLetterOrDigit(after.charAt(0))) wordNoCorrect = true;
    }

    /** Ends the word being typed, fixing it first if asked and autocorrect is sure. */
    private void finishWord(boolean correct) {
        if (word.length() == 0) return;
        InputConnection ic = getCurrentInputConnection();
        String typed = word.toString();
        String out = typed;
        boolean keep = typed.equals(keepAsTyped);
        if (correct && autocorrectOn && !wordSwiped && !wordNoCorrect && !keep && dict != null) {
            Corrector.Result r = resultFor(typed);
            if (r != null && r.autocorrect != null) out = r.autocorrect;
        }
        if (keep) {
            Learn.keepTypedWord(this, typed);
            refreshUserWords();
        }
        resetWord();
        keepAsTyped = null;
        if (ic != null) {
            if (out.equals(typed)) {
                ic.finishComposingText();
            } else {
                ic.commitText(out, 1);
                revertFrom = out;
                revertTo = typed;
                revertSep = null;
            }
        }
        hideStrip();
    }

    private Corrector.Result resultFor(String typed) {
        if (typed.equals(cachedFor) && cached != null) return cached;
        Dict d = dict;
        return d == null ? null : Corrector.suggest(d, typed, 3);
    }

    private void onDeleteKey() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        autoSpace = false;
        if (word.length() > 0) {
            if (wordSwiped) {
                // A swiped word goes in one piece, so it comes out in one piece.
                resetWord();
            } else {
                int end = word.length();
                int start = end >= 2 && Character.isSurrogatePair(word.charAt(end - 2), word.charAt(end - 1)) ? end - 2 : end - 1;
                word.delete(start, end);
            }
            if (word.length() == 0) {
                ic.setComposingText("", 1);
                ic.finishComposingText();
                resetWord();
                hideStrip();
                updateAutoShift();
            } else {
                ic.setComposingText(word, 1);
                scheduleSuggestions();
            }
            lastInsertedLength = 0;
            return;
        }
        if (revertFrom != null && revertTo != null) {
            // Backspace right after an autocorrect puts back exactly what was typed.
            String sep = revertSep != null ? revertSep : "";
            String fixed = revertFrom + sep;
            CharSequence before = ic.getTextBeforeCursor(fixed.length(), 0);
            if (before != null && before.toString().equals(fixed)) {
                String typed = revertTo;
                clearRevert();
                ic.beginBatchEdit();
                ic.deleteSurroundingText(fixed.length(), 0);
                word.setLength(0);
                word.append(typed);
                wordNoCorrect = false;
                keepAsTyped = typed;
                ic.setComposingText(word, 1);
                ic.endBatchEdit();
                lastInsertedLength = 0;
                scheduleSuggestions();
                return;
            }
        }
        clearRevert();
        deleteBackward();
    }

    private void onGestureStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // The touch-down already typed the first letter; the swipe replaces it.
        if (word.length() > 0 && !wordSwiped) word.deleteCharAt(word.length() - 1);
        shift = lastCharShift;
        if (keys != null) keys.setShift(shift);
        if (word.length() > 0) {
            boolean wasSwiped = wordSwiped;
            ic.setComposingText(word, 1);
            finishWord(!wasSwiped);
            ic.commitText(" ", 1);
            if (revertFrom != null && revertSep == null) revertSep = " ";
        } else {
            ic.setComposingText("", 1);
            ic.finishComposingText();
            resetWord();
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() == 1) {
                char b = before.charAt(0);
                if (Character.isLetterOrDigit(b) || ".,!?;:)".indexOf(b) >= 0) ic.commitText(" ", 1);
            }
        }
        autoSpace = false;
    }

    private void onGesture(float[] xs, float[] ys, int n) {
        InputConnection ic = getCurrentInputConnection();
        Dict d = dict;
        if (ic == null || keys == null) return;
        if (d == null) {
            render("Swipe is warming up. Try again in a second");
            return;
        }
        float[][] g = keys.letterGeometry();
        swipe.setGeometry(g[0], g[1]);
        List<String> found = swipe.decode(d, xs, ys, n, 4);
        if (found.isEmpty()) {
            render("Didn't catch that swipe. Try again");
            return;
        }
        String caseHint = shift == SHIFT_LOCK ? "AA" : shift == SHIFT_ONCE ? "A" : "a";
        resetWord();
        for (String w : found) swipeAlts.add(Dict.matchCase(caseHint, w));
        word.append(swipeAlts.get(0));
        wordSwiped = true;
        ic.setComposingText(word, 1);
        lastInsertedLength = 0;
        clearRevert();
        if (shift == SHIFT_ONCE) {
            shift = SHIFT_OFF;
            keys.setShift(shift);
        }
        showSwipeStrip();
    }

    // ---- suggestion strip ------------------------------------------------------------

    private void scheduleSuggestions() {
        if (suggestions == null) return;
        if (!suggestOn || word.length() == 0 || wordSwiped) {
            if (!wordSwiped) hideStrip();
            return;
        }
        final Dict d = dict;
        if (d == null) return;
        final String typed = word.toString();
        final int seq = ++suggestSeq;
        typing.execute(() -> {
            final Corrector.Result r = Corrector.suggest(d, typed, 3);
            ui.post(() -> {
                if (seq != suggestSeq || !typed.equals(word.toString())) return;
                cachedFor = typed;
                cached = r;
                showTypedStrip(typed, r);
            });
        });
    }

    private void showTypedStrip(String typed, Corrector.Result r) {
        if (recording || busy) return;
        int n = 0;
        if (r.autocorrect != null && autocorrectOn && !wordNoCorrect && !typed.equals(keepAsTyped)) {
            setCell(n++, "“" + typed + "”", typed, CELL_KEEP, false);
            setCell(n++, r.autocorrect, r.autocorrect, CELL_WORD, true);
            for (String s : r.suggestions) {
                if (n >= 3) break;
                if (!s.equals(r.autocorrect)) setCell(n++, s, s, CELL_WORD, false);
            }
        } else {
            if (!r.valid && !r.suggestions.isEmpty()) setCell(n++, "“" + typed + "”", typed, CELL_KEEP, false);
            for (String s : r.suggestions) {
                if (n >= 3) break;
                setCell(n++, s, s, CELL_WORD, false);
            }
        }
        if (n == 0) {
            hideStrip();
            return;
        }
        while (n < 3) setCell(n++, "", null, CELL_WORD, false);
        showStrip();
    }

    private void showSwipeStrip() {
        if (suggestions == null || swipeAlts.size() < 2) {
            hideStrip();
            return;
        }
        int n = 0;
        for (int i = 1; i < swipeAlts.size() && n < 3; i++) setCell(n++, swipeAlts.get(i), swipeAlts.get(i), CELL_SWIPE, false);
        while (n < 3) setCell(n++, "", null, CELL_SWIPE, false);
        showStrip();
    }

    private void setCell(int i, String label, String value, int kind, boolean strong) {
        TextView c = cells[i];
        if (c == null) return;
        c.setText(label);
        c.setTypeface(strong ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.setTextColor(strong ? toneColor : getColor(R.color.text));
        c.setEnabled(value != null);
        cellWord[i] = value;
        cellKind[i] = kind;
    }

    private void showStrip() {
        if (suggestions == null || status == null) return;
        suggestions.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        if (!compactChip) {
            compactChip = true;
            paintToneChip(false);
        }
    }

    private void hideStrip() {
        if (suggestions == null || status == null) return;
        if (suggestions.getVisibility() != View.VISIBLE && !compactChip) return;
        suggestions.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        if (compactChip) {
            compactChip = false;
            paintToneChip(false);
        }
    }

    private void pickSuggestion(int i) {
        String w = cellWord[i];
        InputConnection ic = getCurrentInputConnection();
        if (w == null || ic == null) return;
        if (keys != null) keys.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (cellKind[i] == CELL_SWIPE && wordSwiped) {
            // Swap in the other word and keep it open, like a fresh swipe.
            int at = swipeAlts.indexOf(w);
            if (at > 0) {
                swipeAlts.remove(at);
                swipeAlts.add(0, w);
            }
            word.setLength(0);
            word.append(w);
            ic.setComposingText(word, 1);
            showSwipeStrip();
            return;
        }
        boolean keep = cellKind[i] == CELL_KEEP;
        resetWord();
        keepAsTyped = null;
        clearRevert();
        ic.beginBatchEdit();
        ic.commitText(w, 1);
        ic.commitText(" ", 1);
        ic.endBatchEdit();
        autoSpace = true;
        lastInsertedLength = 0;
        if (keep) {
            Learn.keepTypedWord(this, w);
            refreshUserWords();
        }
        hideStrip();
        updateAutoShift();
    }

    private void onShift() {
        long now = SystemClock.uptimeMillis();
        if (shift == SHIFT_LOCK) {
            shift = SHIFT_OFF;
        } else if (shift == SHIFT_ONCE && now - lastShiftTap < DOUBLE_TAP_MS) {
            shift = SHIFT_LOCK;
        } else if (shift == SHIFT_ONCE) {
            shift = SHIFT_OFF;
        } else {
            shift = SHIFT_ONCE;
        }
        lastShiftTap = now;
        if (keys != null) keys.setShift(shift);
    }

    private void onSpace() {
        long now = SystemClock.uptimeMillis();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean hadWord = word.length() > 0;
        if (hadWord) {
            clearRevert();
            finishWord(true);
        }
        // Double-tap space for ". ", like most phone keyboards.
        if (!hadWord && now - lastSpaceTap < 450) {
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && Character.isLetterOrDigit(before.charAt(0))) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(1, 0);
                ic.commitText(". ", 1);
                ic.endBatchEdit();
                lastSpaceTap = 0;
                clearRevert();
                autoSpace = false;
                lastInsertedLength = 0;
                updateAutoShift();
                return;
            }
        }
        if (!hadWord) clearRevert();
        lastSpaceTap = now;
        ic.commitText(" ", 1);
        if (revertFrom != null && revertSep == null) revertSep = " ";
        autoSpace = false;
        lastInsertedLength = 0;
        updateAutoShift();
    }

    /** Capitalizes the next letter at the start of a sentence, when the field wants that. */
    private void updateAutoShift() {
        if (keys == null || shift == SHIFT_LOCK || symbols || word.length() > 0) return;
        int want = SHIFT_OFF;
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (info != null && ic != null
                && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && ic.getCursorCapsMode(info.inputType) != 0) {
            want = SHIFT_ONCE;
        }
        if (want != shift) {
            shift = want;
            keys.setShift(shift);
        }
    }

    private RippleDrawable keyBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(color);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    private void setupSwitcher(View switcher) {
        switcher.setOnClickListener(v -> switchToPreviousKeyboard());
        switcher.setOnLongClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return true;
        });
    }

    private void setupDelete(View del) {
        del.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    haptic(v);
                    deleteBackward();
                    ui.postDelayed(repeatDelete, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(repeatDelete);
                    return true;
                default:
                    return false;
            }
        });
    }

    private void deleteBackward() {
        InputConnection ic = getCurrentInputConnection();
        CharSequence sel = ic != null ? ic.getSelectedText(0) : null;
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
        lastInsertedLength = 0;
    }

    private void pressEnter() {
        finishWord(true);
        clearRevert();
        autoSpace = false;
        // Sending usually clears the field, so learn from the user's fixes first.
        learnFromFixes(getCurrentInputEditorInfo());
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        if (fillScreen) requestHideSelf(0);
    }

    private void type(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
        lastInsertedLength = 0;
    }

    private void haptic(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void switchToPreviousKeyboard() {
        boolean switched = false;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                switched = switchToPreviousInputMethod();
            } else {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                IBinder token = getWindow().getWindow().getAttributes().token;
                if (imm != null) switched = imm.switchToLastInputMethod(token);
            }
        } catch (RuntimeException ignored) {
            // fall through to the picker
        }
        if (!switched) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Updates the status line and the mic button to match the current state. */
    private void render(String message) {
        if (status == null) return;
        offeredWord = null;
        hideStrip();
        status.setText(message);
        cancel.setVisibility(recording || busy ? View.VISIBLE : View.GONE);
        if (!recording) {
            mic.animate().cancel();
            mic.setScaleX(1f);
            mic.setScaleY(1f);
        }
        if (recording) {
            mic.setBackgroundResource(R.drawable.bg_mic_rec);
            mic.setBackgroundTintList(null);
            mic.setImageResource(R.drawable.ic_stop);
            mic.setAlpha(1f);
        } else if (busy) {
            mic.setBackgroundResource(R.drawable.bg_mic_busy);
            mic.setBackgroundTintList(null);
            mic.setImageResource(R.drawable.ic_mic);
            mic.setAlpha(0.6f);
        } else {
            mic.setBackgroundResource(R.drawable.bg_mic_idle);
            mic.setBackgroundTintList(ColorStateList.valueOf(toneColor));
            mic.setImageResource(R.drawable.ic_mic);
            mic.setAlpha(1f);
        }
    }
}
