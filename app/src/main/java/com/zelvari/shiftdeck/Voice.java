package com.zelvari.shiftdeck;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Murmur voice typing for ShiftDeck.
 * With a Groq key: records audio, transcribes with Whisper and polishes it with the Murmur clean-up prompt.
 * Without a key: falls back to the phone's built-in speech recognizer (live words as you talk).
 * Results go back to the page through window.murmur.* callbacks.
 */
final class Voice {
    interface Js { void call(String code); }

    static final String BASE_URL = "https://api.groq.com/openai/v1";
    static final String STT_MODEL = "whisper-large-v3-turbo";
    static final String LLM_MODEL = "openai/gpt-oss-120b";
    static final int PERM_REQUEST = 8;

    private final Activity act;
    private final Js js;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private MediaRecorder recorder;
    private File audioFile;
    private SpeechRecognizer recognizer;
    private String mode = "";
    private String hint = "";
    private int session = 0;

    Voice(Activity act, Js js) {
        this.act = act;
        this.js = js;
        this.prefs = act.getSharedPreferences("voice", Context.MODE_PRIVATE);
    }

    String status() {
        try {
            return new JSONObject()
                    .put("hasKey", !key().isEmpty())
                    .put("cleanup", prefs.getBoolean("cleanup", true))
                    .put("basic", SpeechRecognizer.isRecognitionAvailable(act))
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    void config(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = prefs.edit();
            if (o.has("key")) ed.putString("key", o.optString("key", "").trim());
            if (o.has("cleanup")) ed.putBoolean("cleanup", o.optBoolean("cleanup", true));
            ed.apply();
        } catch (Exception ignored) { }
    }

    private String key() { return prefs.getString("key", "").trim(); }

    /** Returns "ai", "basic", "perm" (asking for mic permission) or "none". */
    String start(String fieldHint) {
        hint = fieldHint == null ? "" : fieldHint;
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQUEST));
            return "perm";
        }
        final int my = ++session;
        if (!key().isEmpty()) {
            mode = "ai";
            main.post(() -> startRecorder(my));
            return "ai";
        }
        if (SpeechRecognizer.isRecognitionAvailable(act)) {
            mode = "basic";
            main.post(() -> startRecognizer(my));
            return "basic";
        }
        return "none";
    }

    void onPermission(boolean granted) {
        js.call("window.murmur&&murmur.onPermission(" + granted + ")");
    }

    void stop() {
        main.post(() -> {
            if ("ai".equals(mode)) finishRecording(session);
            else if (recognizer != null) recognizer.stopListening();
        });
    }

    void cancel() {
        session++;
        main.post(this::release);
    }

    // ---------------- AI (Groq Whisper) path ----------------

    private void startRecorder(int my) {
        release();
        try {
            audioFile = new File(act.getCacheDir(), "murmur.m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(act) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(48000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            pollLevel(my);
        } catch (Exception e) {
            release();
            error("Could not start the microphone");
        }
    }

    private void pollLevel(int my) {
        main.postDelayed(() -> {
            if (my != session || recorder == null) return;
            try {
                int amp = recorder.getMaxAmplitude();
                double lv = Math.min(1.0, Math.sqrt(amp / 18000.0));
                js.call("window.murmur&&murmur.onLevel(" + String.format(java.util.Locale.US, "%.3f", lv) + ")");
            } catch (Exception ignored) { }
            pollLevel(my);
        }, 90);
    }

    private void finishRecording(int my) {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        js.call("window.murmur&&murmur.onState('thinking')");
        final File file = audioFile;
        final String k = key();
        final boolean polish = prefs.getBoolean("cleanup", true);
        final String field = hint;
        worker.execute(() -> {
            try {
                String raw = AiClient.transcribe(BASE_URL, k, STT_MODEL, file, "", "");
                String text = raw;
                if (polish && !raw.isEmpty()) {
                    try {
                        String style = "This goes into the \"" + field + "\" box of a work calendar and to-do app "
                                + "used at a print and shipping store. Keep short entries short and do not end a "
                                + "short title with a period.";
                        String cleaned = AiClient.cleanup(BASE_URL, k, LLM_MODEL, raw, style, "", "ShiftDeck");
                        if (!cleaned.isEmpty()) text = cleaned;
                    } catch (Exception ignored) {
                        // keep the raw transcript if clean-up fails
                    }
                }
                if (my == session) result(text);
            } catch (Exception e) {
                if (my == session) error(e.getMessage() == null ? "Network problem — try again" : e.getMessage());
            }
        });
    }

    // ---------------- built-in recognizer path ----------------

    private void startRecognizer(int my) {
        release();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(act);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rms) {
                    if (my != session) return;
                    double lv = Math.max(0, Math.min(1, (rms + 2) / 12.0));
                    js.call("window.murmur&&murmur.onLevel(" + String.format(java.util.Locale.US, "%.3f", lv) + ")");
                }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    if (my == session) js.call("window.murmur&&murmur.onState('thinking')");
                }
                @Override public void onError(int err) {
                    if (my != session) return;
                    String m;
                    if (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) m = "Didn't catch that — tap the mic and try again";
                    else if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) m = "Microphone permission is needed";
                    else if (err == SpeechRecognizer.ERROR_NETWORK || err == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) m = "No connection for speech — try again";
                    else m = "Speech error (" + err + ") — try again";
                    release();
                    error(m);
                }
                @Override public void onResults(Bundle results) {
                    if (my != session) return;
                    ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = r == null || r.isEmpty() ? "" : r.get(0);
                    release();
                    result(tidy(text));
                }
                @Override public void onPartialResults(Bundle partial) {
                    if (my != session) return;
                    ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) js.call("window.murmur&&murmur.onPartial(" + JSONObject.quote(r.get(0)) + ")");
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.getPackageName());
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            if (Build.VERSION.SDK_INT >= 33) i.putExtra("android.speech.extra.ENABLE_FORMATTING", "quality");
            recognizer.startListening(i);
        } catch (Exception e) {
            release();
            error("Speech recognition isn't available on this device");
        }
    }

    /** Built-in results come back lower-case and unpunctuated; capitalise the first letter at least. */
    private static String tidy(String t) {
        t = t == null ? "" : t.trim();
        if (t.isEmpty()) return t;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ---------------- shared ----------------

    private void result(String text) {
        js.call("window.murmur&&murmur.onResult(" + JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void error(String msg) {
        js.call("window.murmur&&murmur.onError(" + JSONObject.quote(msg) + ")");
    }

    private void release() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    void destroy() {
        session++;
        release();
        worker.shutdownNow();
    }
}
