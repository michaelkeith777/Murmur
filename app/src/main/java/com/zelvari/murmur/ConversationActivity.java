package com.zelvari.murmur;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-way conversation: each person taps their own button and talks, and Murmur shows and
 * says it in the other person's language. The other person's half is upside down by default
 * so it reads right from across a table.
 */
public class ConversationActivity extends Activity {
    private static final int ME = 0, THEM = 1;
    private static final int REQ_MIC = 7;
    /** Loudness that counts as talking, how long a pause ends a turn, and the longest turn. */
    private static final int SPEECH_LEVEL = 2200;
    private static final long PAUSE_MS = 1400, NO_SPEECH_MS = 8000, MAX_MS = 60000, MIN_MS = 600;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Side[] sides = new Side[2];
    private final String[] lang = new String[2];

    private MediaRecorder recorder;
    private File audio;
    private int talking = -1;
    private boolean busy, heardSpeech, first = true;
    private long startedAt, lastLoud;
    private int generation;
    private int speakingFor = -1;

    private TextToSpeech tts;
    private boolean ttsReady;
    private TextView speakBtn, handsFreeBtn, flipBtn;

    /** One person's half: their language, what they should read, and their talk button. */
    private static final class Side {
        LinearLayout box;
        TextView language, heard, said, status, talk;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lang[ME] = Translate.mine(this);
        lang[THEM] = Translate.theirs(this);
        if (lang[THEM].equals(lang[ME])) lang[THEM] = lang[ME].equals("Spanish") ? "English" : "Spanish";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1020);

        sides[THEM] = buildSide(THEM, 0xFF7C3AED);
        sides[ME] = buildSide(ME, 0xFF0EA5E9);
        root.addView(sides[THEM].box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildBar());
        root.addView(sides[ME].box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        applyFace();
        paintToggles();
        paintLanguages();

        sides[ME].heard.setText("Tap your button and talk. They'll see and hear it in " + lang[THEM] + ".");
        sides[THEM].heard.setText(greeting(lang[THEM]));

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { ui.post(() -> afterSpeaking(id)); }
                @Override public void onError(String id) { ui.post(() -> afterSpeaking(id)); }
            });
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private Side buildSide(int who, int color) {
        Side s = new Side();
        s.box = new LinearLayout(this);
        s.box.setOrientation(LinearLayout.VERTICAL);
        s.box.setPadding(dp(18), dp(14), dp(18), dp(14));

        s.language = text(15, 0xFFCBD5E1, true);
        s.language.setBackground(pill(0xFF1E293B));
        s.language.setPadding(dp(14), dp(6), dp(14), dp(6));
        s.language.setOnClickListener(v -> pickLanguage(who));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        s.box.addView(s.language, lp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        s.heard = text(26, Color.WHITE, true);
        s.heard.setPadding(0, dp(12), 0, dp(6));
        s.heard.setTextIsSelectable(true);
        s.said = text(15, 0xFF94A3B8, false);
        words.addView(s.heard);
        words.addView(s.said);
        scroll.addView(words);
        s.box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        s.status = text(14, 0xFF94A3B8, false);
        s.status.setGravity(Gravity.CENTER);
        s.status.setPadding(0, dp(4), 0, dp(6));
        s.box.addView(s.status);

        s.talk = text(20, Color.WHITE, true);
        s.talk.setGravity(Gravity.CENTER);
        s.talk.setBackground(pill(color));
        s.talk.setTag(color);
        s.talk.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onTalk(who);
        });
        s.box.addView(s.talk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return s;
    }

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(0xFF111827);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        speakBtn = barButton(bar, v -> {
            toggle(Prefs.CONVO_SPEAK, true);
            if (!Prefs.flag(this, Prefs.CONVO_SPEAK, true) && tts != null) tts.stop();
        });
        handsFreeBtn = barButton(bar, v -> toggle(Prefs.CONVO_HANDS_FREE, false));
        flipBtn = barButton(bar, v -> {
            toggle(Prefs.CONVO_FACE, true);
            applyFace();
        });
        TextView swap = barButton(bar, v -> {
            if (talking >= 0 || busy) return;
            String t = lang[ME];
            lang[ME] = lang[THEM];
            lang[THEM] = t;
            saveLanguages();
            paintLanguages();
        });
        swap.setText("⇄ Swap");
        TextView close = barButton(bar, v -> finish());
        close.setText("✕");
        return bar;
    }

    private TextView barButton(LinearLayout bar, View.OnClickListener click) {
        TextView b = text(13, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pill(0xFF1F2937));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(b, lp);
        return b;
    }

    private void toggle(String key, boolean def) {
        Prefs.sp(this).edit().putBoolean(key, !Prefs.flag(this, key, def)).apply();
        paintToggles();
    }

    private void paintToggles() {
        boolean speak = Prefs.flag(this, Prefs.CONVO_SPEAK, true);
        speakBtn.setText(speak ? "🔊 Read aloud" : "🔇 Silent");
        boolean hands = Prefs.flag(this, Prefs.CONVO_HANDS_FREE, false);
        handsFreeBtn.setText(hands ? "🔁 Hands-free on" : "🔁 Hands-free");
        handsFreeBtn.setBackground(pill(hands ? 0xFF0EA5E9 : 0xFF1F2937));
        flipBtn.setText(Prefs.flag(this, Prefs.CONVO_FACE, true) ? "⇅ Face to face" : "⇅ Same side");
    }

    /** Face to face turns the other person's half upside down, so it reads right across a table. */
    private void applyFace() {
        sides[THEM].box.setRotation(Prefs.flag(this, Prefs.CONVO_FACE, true) ? 180f : 0f);
    }

    private void paintLanguages() {
        sides[ME].language.setText("You · " + lang[ME] + " ▾");
        sides[THEM].language.setText(lang[THEM] + " ▾");
        paintTalk(ME);
        paintTalk(THEM);
    }

    private void paintTalk(int who) {
        Side s = sides[who];
        boolean live = talking == who;
        s.talk.setText(live ? "■  Done talking" : "🎙  " + talkLabel(who));
        s.talk.setBackground(pill(live ? 0xFFDC2626 : (Integer) s.talk.getTag()));
        s.talk.setAlpha(busy || (talking >= 0 && !live) ? 0.45f : 1f);
    }

    private String talkLabel(int who) {
        if (who == ME) return "Talk in " + lang[ME];
        switch (lang[THEM]) {
            case "Spanish": return "Hablar";
            case "French": return "Parler";
            case "German": return "Sprechen";
            case "Italian": return "Parlare";
            case "Portuguese": return "Falar";
            case "Chinese (Simplified)": return "说话";
            case "Japanese": return "話す";
            case "Korean": return "말하기";
            case "Arabic": return "تحدث";
            case "Hindi": return "बोलें";
            case "Russian": return "Говорить";
            case "Vietnamese": return "Nói";
            case "Tagalog": return "Magsalita";
            case "Dutch": return "Spreken";
            case "Polish": return "Mów";
            case "Turkish": return "Konuş";
            case "Ukrainian": return "Говорити";
            case "Haitian Creole": return "Pale";
            default: return "Talk";
        }
    }

    private static String greeting(String language) {
        switch (language) {
            case "Spanish": return "Toque su botón y hable. Lo traduciré.";
            case "French": return "Touchez votre bouton et parlez. Je traduirai.";
            case "German": return "Tippen Sie auf Ihre Taste und sprechen Sie. Ich übersetze.";
            case "Italian": return "Tocchi il suo pulsante e parli. Tradurrò io.";
            case "Portuguese": return "Toque no seu botão e fale. Eu traduzo.";
            case "Chinese (Simplified)": return "点您的按钮说话，我来翻译。";
            case "Japanese": return "ボタンを押して話してください。翻訳します。";
            case "Korean": return "버튼을 누르고 말씀하세요. 번역해 드릴게요.";
            case "Russian": return "Нажмите свою кнопку и говорите. Я переведу.";
            case "Vietnamese": return "Chạm vào nút của bạn và nói. Tôi sẽ dịch.";
            case "Haitian Creole": return "Peze bouton ou epi pale. M ap tradui.";
            default: return "Tap your button and talk.";
        }
    }

    private void pickLanguage(int who) {
        if (talking >= 0 || busy) return;
        String[] names = new String[Translate.LANGUAGES.length];
        for (int i = 0; i < names.length; i++) names[i] = Translate.LANGUAGES[i][0];
        new AlertDialog.Builder(this)
                .setTitle(who == ME ? "You speak" : "They speak")
                .setItems(names, (d, i) -> {
                    lang[who] = names[i];
                    saveLanguages();
                    paintLanguages();
                })
                .show();
    }

    private void saveLanguages() {
        Prefs.sp(this).edit().putString(Prefs.CONVO_MINE, lang[ME])
                .putString(Prefs.TRANSLATE_TO, lang[THEM]).apply();
    }

    // ---- talking ---------------------------------------------------------------

    private void onTalk(int who) {
        if (busy) return;
        if (talking == who) {
            finishTurn();
            return;
        }
        if (talking >= 0) return;
        startTurn(who);
    }

    private void startTurn(int who) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (Prefs.get(this, Prefs.API_KEY, "").isEmpty()) {
            sides[ME].status.setText("Add your API key in Murmur's settings first");
            return;
        }
        speakingFor = -1;
        if (tts != null) tts.stop();
        try {
            audio = new File(getCacheDir(), "conversation.m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(audio.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            release();
            sides[who].status.setText("Couldn't start the microphone");
            return;
        }
        talking = who;
        heardSpeech = false;
        startedAt = lastLoud = SystemClock.elapsedRealtime();
        sides[who].status.setText(who == ME ? "Listening… pause when you're done" : "…");
        paintTalk(ME);
        paintTalk(THEM);
        ui.postDelayed(meter, 100);
    }

    /** Ends the turn on its own after a pause, so the conversation can flow. */
    private final Runnable meter = new Runnable() {
        @Override public void run() {
            if (talking < 0 || recorder == null) return;
            long now = SystemClock.elapsedRealtime();
            int level;
            try {
                level = recorder.getMaxAmplitude();
            } catch (RuntimeException e) {
                level = 0;
            }
            if (level > SPEECH_LEVEL) {
                heardSpeech = true;
                lastLoud = now;
            }
            if ((heardSpeech && now - lastLoud > PAUSE_MS) || now - startedAt > MAX_MS) {
                finishTurn();
            } else if (!heardSpeech && now - startedAt > NO_SPEECH_MS) {
                int who = talking;
                stopRecorder();
                talking = -1;
                sides[who].status.setText(who == ME ? "Didn't hear anything" : "…?");
                paintTalk(ME);
                paintTalk(THEM);
            } else {
                ui.postDelayed(this, 100);
            }
        }
    };

    private void finishTurn() {
        final int who = talking;
        if (who < 0) return;
        ui.removeCallbacks(meter);
        long length = SystemClock.elapsedRealtime() - startedAt;
        boolean ok = stopRecorder();
        talking = -1;
        if (!ok || length < MIN_MS) {
            sides[who].status.setText(who == ME ? "Too short. Tap and talk" : "…");
            paintTalk(ME);
            paintTalk(THEM);
            return;
        }
        final int listener = 1 - who;
        final String from = lang[who], to = lang[listener];
        final String key = Prefs.get(this, Prefs.API_KEY, "");
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String stt = Prefs.get(this, Prefs.STT_MODEL, Prefs.DEF_STT_MODEL);
        final String llm = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String vocab = Prefs.get(this, Prefs.VOCAB, "");
        final File file = audio;
        final int gen = ++generation;
        busy = true;
        sides[who].status.setText(who == ME ? "Translating to " + to + "…" : "…");
        sides[listener].status.setText(listener == ME ? "Translating from " + from + "…" : "…");
        paintTalk(ME);
        paintTalk(THEM);

        worker.execute(() -> {
            String said = null, out = null, error = null;
            try {
                said = AiClient.transcribe(base, key, stt, file, Translate.iso(from), who == ME ? vocab : "");
                if (said.isEmpty()) {
                    error = "Didn't catch that. Try again";
                } else {
                    out = AiClient.interpret(base, key, llm, said, from, to, vocab);
                    if (out.isEmpty()) error = "The translation came back empty. Try again";
                }
            } catch (Exception e) {
                String m = e.getMessage();
                error = m == null || m.isEmpty() ? "Network problem. Try again" : m;
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            final String fSaid = said, fOut = out, fError = error;
            ui.post(() -> {
                if (gen != generation || isFinishing()) return;
                busy = false;
                sides[ME].status.setText("");
                sides[THEM].status.setText("");
                paintTalk(ME);
                paintTalk(THEM);
                if (fError != null) {
                    sides[ME].status.setText(fError);
                    return;
                }
                if (first) {
                    // The how-to lines have done their job.
                    first = false;
                    sides[ME].heard.setText("");
                    sides[THEM].heard.setText("");
                }
                sides[listener].heard.setText(fOut);
                sides[who].said.setText((who == ME ? "You said: " : "") + fSaid);
                speak(fOut, to, listener);
            });
        });
    }

    private void speak(String text, String language, int listener) {
        final int turn = generation;
        speakingFor = listener;
        String id = "turn-" + turn;
        if (Prefs.flag(this, Prefs.CONVO_SPEAK, true) && ttsReady) {
            java.util.Locale loc = Translate.locale(language);
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(loc);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                return;
            }
            sides[ME].status.setText("Your phone has no " + language + " voice, so it's on screen only");
        }
        afterSpeaking(id);
    }

    /** Hands-free: once a translation has been read out, listen to the person who just heard it. */
    private void afterSpeaking(String id) {
        if (id == null || !id.equals("turn-" + generation) || speakingFor < 0) return;
        final int next = speakingFor;
        speakingFor = -1;
        if (!Prefs.flag(this, Prefs.CONVO_HANDS_FREE, false) || busy || talking >= 0 || isFinishing()) return;
        ui.postDelayed(() -> {
            if (!busy && talking < 0 && !isFinishing()) startTurn(next);
        }, 350);
    }

    private boolean stopRecorder() {
        if (recorder == null) return false;
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false;
        }
        release();
        return ok;
    }

    private void release() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (talking >= 0) {
            ui.removeCallbacks(meter);
            stopRecorder();
            talking = -1;
            paintTalk(ME);
            paintTalk(THEM);
        }
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        release();
        if (tts != null) tts.shutdown();
        worker.shutdownNow();
    }

    // ---- small helpers -----------------------------------------------------------

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
