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
    private LinearLayout keys;

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
        if (keys != null) buildKeys();

        render(IDLE_HINT);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        selStart = info.initialSelStart;
        selEnd = info.initialSelEnd;
        String learned = learnFromFixes(info);
        if (!restarting) symbols = false;
        if (shift != SHIFT_LOCK) shift = SHIFT_OFF;
        showTonePicker(false);
        refreshTone();
        if (keys != null) buildKeys();
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
        updateAutoShift();
        if (recording || busy || status == null) return;
        if (newSelStart != newSelEnd) {
            offerSelectedWord();
        } else if (offeredWord != null) {
            render(IDLE_HINT);
        }
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
        super.onDestroy();
    }

    // ---- mic flow --------------------------------------------------------------

    private void onMicTapped() {
        if (busy) return;
        showTonePicker(false);
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
        if (keys != null) buildKeys();
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
        toneChip.setText(Tones.emoji(id) + "  " + Tones.label(id) + "  ▾");
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

    private static final String NUMBERS = "1234567890";
    private static final String[] LETTERS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    private static final String[] SYMBOLS = {"@#$%&-+()/", "*\"':;!?=_", "<>[]{}|\\"};

    private void buildKeys() {
        keys.removeAllViews();
        LinearLayout numbers = row(42);
        for (char ch : NUMBERS.toCharArray()) numbers.addView(charKey(String.valueOf(ch), true));
        keys.addView(numbers);

        String[] layout = symbols ? SYMBOLS : LETTERS;

        LinearLayout r1 = row(50);
        for (char ch : layout[0].toCharArray()) r1.addView(charKey(String.valueOf(ch), false));
        keys.addView(r1);

        LinearLayout r2 = row(50);
        boolean inset = layout[1].length() < 10;
        if (inset) r2.addView(spacer(0.5f));
        for (char ch : layout[1].toCharArray()) r2.addView(charKey(String.valueOf(ch), false));
        if (inset) r2.addView(spacer(0.5f));
        keys.addView(r2);

        LinearLayout r3 = row(50);
        if (symbols) {
            r3.addView(modKey("…", 1.5f, v -> type("…"), "Ellipsis"));
        } else {
            TextView s = modKey(shift == SHIFT_LOCK ? "⇪" : "⇧", 1.5f, v -> onShift(), "Shift");
            if (shift != SHIFT_OFF) {
                s.setTextColor(toneColor);
                s.setTypeface(Typeface.DEFAULT_BOLD);
            }
            r3.addView(s);
        }
        for (char ch : layout[2].toCharArray()) r3.addView(charKey(String.valueOf(ch), false));
        TextView del = modKey("⌫", 1.5f, null, "Backspace");
        setupDelete(del);
        r3.addView(del);
        keys.addView(r3);

        LinearLayout r4 = row(50);
        r4.addView(modKey(symbols ? "ABC" : "?123", 1.4f, v -> {
            symbols = !symbols;
            buildKeys();
        }, "Switch letters and symbols"));
        TextView sw = modKey("⌨", 1f, null, "Switch keyboard");
        setupSwitcher(sw);
        r4.addView(sw);
        r4.addView(charKey(",", false));
        TextView space = modKey("space", 4f, v -> onSpace(), "Space");
        space.setTextColor(getColor(R.color.dim));
        space.setBackground(keyBg(getColor(R.color.key)));
        space.setOnLongClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return true;
        });
        r4.addView(space);
        r4.addView(charKey(".", false));
        TextView enter = modKey("⏎", 1.5f, v -> pressEnter(), "Enter");
        enter.setBackground(keyBg(toneColor));
        enter.setTextColor(0xFFFFFFFF);
        r4.addView(enter);
        keys.addView(r4);
    }

    private LinearLayout row(int heightDp) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return r;
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return v;
    }

    private TextView baseKey(String label, float weight) {
        TextView k = new TextView(this);
        k.setText(label);
        k.setGravity(Gravity.CENTER);
        k.setTextColor(getColor(R.color.text));
        k.setSoundEffectsEnabled(false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        k.setLayoutParams(lp);
        return k;
    }

    private TextView charKey(final String ch, boolean small) {
        final boolean letter = Character.isLetter(ch.charAt(0));
        String shown = letter && shift != SHIFT_OFF ? ch.toUpperCase(Locale.ROOT) : ch;
        TextView k = baseKey(shown, 1f);
        k.setTextSize(small ? 17 : 21);
        k.setBackground(keyBg(getColor(R.color.key)));
        k.setOnTouchListener(this::pressAnimation);
        k.setOnClickListener(v -> {
            haptic(v);
            type(letter && shift != SHIFT_OFF ? ch.toUpperCase(Locale.ROOT) : ch);
            if (shift == SHIFT_ONCE && letter) {
                shift = SHIFT_OFF;
                buildKeys();
            }
        });
        return k;
    }

    private TextView modKey(String label, float weight, View.OnClickListener click, String description) {
        TextView k = baseKey(label, weight);
        k.setTextSize(label.length() > 2 ? 14 : 20);
        k.setBackground(keyBg(getColor(R.color.surface)));
        k.setContentDescription(description);
        if (click != null) {
            k.setOnTouchListener(this::pressAnimation);
            k.setOnClickListener(v -> {
                haptic(v);
                click.onClick(v);
            });
        }
        return k;
    }

    private RippleDrawable keyBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(color);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    private boolean pressAnimation(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                break;
            default:
                break;
        }
        return false;
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
        buildKeys();
    }

    private void onSpace() {
        long now = SystemClock.uptimeMillis();
        InputConnection ic = getCurrentInputConnection();
        // Double-tap space for ". ", like most phone keyboards.
        if (ic != null && now - lastSpaceTap < 450) {
            CharSequence before = ic.getTextBeforeCursor(2, 0);
            if (before != null && before.length() == 2 && before.charAt(1) == ' '
                    && Character.isLetterOrDigit(before.charAt(0))) {
                ic.deleteSurroundingText(1, 0);
                type(". ");
                lastSpaceTap = 0;
                return;
            }
        }
        lastSpaceTap = now;
        type(" ");
    }

    /** Capitalizes the next letter at the start of a sentence, when the field wants that. */
    private void updateAutoShift() {
        if (keys == null || shift == SHIFT_LOCK || symbols) return;
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
            buildKeys();
        }
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
