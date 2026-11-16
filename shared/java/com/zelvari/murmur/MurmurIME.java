package com.zelvari.murmur;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Murmur keyboard: one big mic button. Tap, talk, tap again; the recording is
 * transcribed, polished by an LLM, and typed into whatever field has focus.
 */
public class MurmurIME extends InputMethodService {
    private static final String IDLE_HINT = "Tap the mic and talk";
    private static final long MIN_RECORDING_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView cancel;
    private ImageView mic;

    private MediaRecorder recorder;
    private File audioFile;
    private long startedAt;
    private boolean recording;
    private boolean busy;
    /** Bumped whenever in-flight work should be ignored (cancel, keyboard closed). */
    private int generation;
    private int lastInsertedLength;
    /** Watch layout: the keyboard fills the whole screen. */
    private boolean fillScreen;
    /** Opened by the one-tap trigger: listen right away, then hand back to the regular keyboard. */
    private boolean oneShot;

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
            float scale = 1f + Math.min(1f, amp / 12000f) * 0.22f;
            mic.animate().scaleX(scale).scaleY(scale).setDuration(90).start();
            long sec = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            status.setText(String.format(Locale.US, "Listening %d:%02d · tap to finish", sec / 60, sec % 60));
            ui.postDelayed(this, 100);
        }
    };

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            ui.postDelayed(this, 55);
        }
    };

    @Override
    public View onCreateInputView() {
        View root = getLayoutInflater().inflate(R.layout.keyboard, null);
        status = root.findViewById(R.id.status);
        cancel = root.findViewById(R.id.cancel);
        mic = root.findViewById(R.id.mic);

        fillScreen = getResources().getBoolean(R.bool.fill_screen);
        if (fillScreen) {
            View panel = root.findViewById(R.id.panel);
            if (panel != null) {
                panel.getLayoutParams().height = getResources().getDisplayMetrics().heightPixels;
            }
        }

        mic.setOnClickListener(v -> onMicTapped());
        cancel.setOnClickListener(v -> cancelEverything("Cancelled"));

        // The watch layout leaves some of these keys out, so each one is optional.
        onClick(root, R.id.k_space, v -> type(" "));
        onClick(root, R.id.k_comma, v -> type(","));
        onClick(root, R.id.k_period, v -> type("."));
        onClick(root, R.id.k_enter, v -> {
            if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            if (fillScreen) requestHideSelf(0);
        });
        onClick(root, R.id.k_undo, v -> undoLast());

        View switcher = root.findViewById(R.id.k_switch);
        if (switcher != null) {
            switcher.setOnClickListener(v -> switchToPreviousKeyboard());
            switcher.setOnLongClickListener(v -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
                return true;
            });
        }

        View del = root.findViewById(R.id.k_del);
        if (del != null) del.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
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

        render(IDLE_HINT);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (!restarting && !recording && !busy) {
            render(IDLE_HINT);
            oneShot = takeOneShot();
            if (oneShot || Prefs.flag(this, Prefs.AUTO_START, false)) startRecording();
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
        try {
            audioFile = new File(getCacheDir(), "dictation.m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(48000);
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

        final File file = audioFile;
        final String key = Prefs.get(this, Prefs.API_KEY, "");
        final String base = stripSlash(Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL));
        final String sttModel = Prefs.get(this, Prefs.STT_MODEL, Prefs.DEF_STT_MODEL);
        final String llmModel = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final boolean polish = Prefs.flag(this, Prefs.CLEANUP, true);
        final String style = Prefs.get(this, Prefs.STYLE, "");
        final String vocab = Prefs.get(this, Prefs.VOCAB, "");
        final String language = Prefs.get(this, Prefs.LANGUAGE, "");
        EditorInfo info = getCurrentInputEditorInfo();
        final String app = info != null && info.packageName != null ? info.packageName : "";

        worker.execute(() -> {
            String text = null;
            String note = null;
            String error = null;
            try {
                String raw = AiClient.transcribe(base, key, sttModel, file, language, vocab);
                if (raw.isEmpty()) {
                    error = "Didn't catch anything. Try again";
                } else {
                    text = raw;
                    if (polish) {
                        ui.post(() -> { if (gen == generation) render("Polishing…"); });
                        try {
                            String cleaned = AiClient.cleanup(base, key, llmModel, raw, style, vocab, app);
                            if (!cleaned.isEmpty()) text = cleaned;
                        } catch (Exception e) {
                            note = "Inserted unpolished text (cleanup failed: " + e.getMessage() + ")";
                        }
                    }
                }
            } catch (Exception e) {
                String m = e.getMessage();
                error = m == null || m.isEmpty() ? "Network problem. Try again" : m;
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();

            final String fText = text;
            final String fNote = note;
            final String fError = error;
            ui.post(() -> {
                if (gen != generation) return;
                busy = false;
                if (fError != null) {
                    render(fError);
                    return;
                }
                boolean inserted = insert(fText);
                if (!inserted) {
                    render("No text field to type into");
                } else {
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

    // ---- typing ----------------------------------------------------------------

    private boolean insert(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null || text.isEmpty()) return false;
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        boolean needsSpace = before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(0))
                && !Character.isWhitespace(text.charAt(0));
        String out = needsSpace ? " " + text : text;
        ic.commitText(out, 1);
        lastInsertedLength = out.length();
        return true;
    }

    private void undoLast() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || lastInsertedLength == 0) return;
        ic.deleteSurroundingText(lastInsertedLength, 0);
        lastInsertedLength = 0;
        render("Removed");
    }

    private void type(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
        lastInsertedLength = 0;
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
        status.setText(message);
        cancel.setVisibility(recording || busy ? View.VISIBLE : View.GONE);
        if (!recording) {
            mic.animate().cancel();
            mic.setScaleX(1f);
            mic.setScaleY(1f);
        }
        if (recording) {
            mic.setBackgroundResource(R.drawable.bg_mic_rec);
            mic.setImageResource(R.drawable.ic_stop);
            mic.setAlpha(1f);
        } else if (busy) {
            mic.setBackgroundResource(R.drawable.bg_mic_busy);
            mic.setImageResource(R.drawable.ic_mic);
            mic.setAlpha(0.6f);
        } else {
            mic.setBackgroundResource(R.drawable.bg_mic_idle);
            mic.setImageResource(R.drawable.ic_mic);
            mic.setAlpha(1f);
        }
    }
}
