package com.zelvari.murmur;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Setup checklist and settings. */
public class MainActivity extends Activity {
    private TextView stepMic, stepEnable, stepPick, stepOneTap;
    private EditText apiKey, style, vocab, language, baseUrl, sttModel, llmModel;
    private Switch cleanup, autoStart, autoReturn, commands, learn, learnStyle;
    private Switch autocorrect, swipe, keyVibrate, keySound, narrowNumbers;
    private SeekBar narrowSize;
    private TextView narrowSizeLabel;
    private Spinner tone, theme;
    private Switch clipHistory;
    private EditText shortcutsField;
    private EditText learned;
    private TextView samplesInfo;
    /**
     * What the word list and learned list said when last shown. The keyboard adds to both
     * while this screen may be open, so they are only saved when the user changed them here.
     */
    private String shownVocab = "", shownLearned = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View convo = findViewById(R.id.open_conversation);
        if (convo != null) convo.setOnClickListener(v ->
                startActivity(new Intent(this, ConversationActivity.class)));
        Prefs.migrate(this);

        stepMic = findViewById(R.id.step_mic);
        stepEnable = findViewById(R.id.step_enable);
        stepPick = findViewById(R.id.step_pick);
        stepOneTap = findViewById(R.id.step_one_tap);
        apiKey = findViewById(R.id.api_key);
        style = findViewById(R.id.style);
        vocab = findViewById(R.id.vocab);
        language = findViewById(R.id.language);
        baseUrl = findViewById(R.id.base_url);
        sttModel = findViewById(R.id.stt_model);
        llmModel = findViewById(R.id.llm_model);
        cleanup = findViewById(R.id.cleanup);
        autoStart = findViewById(R.id.auto_start);
        autoReturn = findViewById(R.id.auto_return);
        commands = findViewById(R.id.commands);
        learn = findViewById(R.id.learn);
        learnStyle = findViewById(R.id.learn_style);
        autocorrect = findViewById(R.id.autocorrect);
        swipe = findViewById(R.id.swipe);
        keyVibrate = findViewById(R.id.key_vibrate);
        keySound = findViewById(R.id.key_sound);
        narrowNumbers = findViewById(R.id.narrow_numbers);
        narrowSize = findViewById(R.id.narrow_size);
        narrowSizeLabel = findViewById(R.id.narrow_size_label);
        narrowSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showNarrowSize(80 + progress);
                if (fromUser) Prefs.sp(MainActivity.this).edit().putInt(Prefs.NARROW_KEY_SIZE, 80 + progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        learned = findViewById(R.id.learned);
        samplesInfo = findViewById(R.id.samples_info);
        tone = findViewById(R.id.tone);
        theme = findViewById(R.id.theme);
        clipHistory = findViewById(R.id.clip_history);
        shortcutsField = findViewById(R.id.shortcuts);

        ArrayAdapter<String> themes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, Theme.LABELS);
        themes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        theme.setAdapter(themes);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Saved right away, like the tone: the keyboard changes the same setting.
                Theme.set(MainActivity.this, Theme.IDS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        String[] toneNames = new String[Tones.IDS.length];
        for (int i = 0; i < toneNames.length; i++) {
            toneNames[i] = Tones.EMOJI[i] + "  " + Tones.LABELS[i];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, toneNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tone.setAdapter(adapter);
        tone.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Saved right away: the keyboard's tone chip changes the same setting.
                Tones.set(MainActivity.this, Tones.IDS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.clear_learned).setOnClickListener(v -> {
            Learn.clearLearned(this);
            refreshLearned(true);
            Toast.makeText(this, "Cleared what Murmur learned. Your own word list stays.", Toast.LENGTH_LONG).show();
        });

        stepMic.setOnClickListener(v -> {
            if (!micGranted()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        });
        stepEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        stepPick.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        stepOneTap.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.get_key).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Open console.groq.com/keys in your browser", Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.save).setOnClickListener(v -> {
            save();
            int n = Clips.shortcuts(this).size();
            Toast.makeText(this, n == 0 ? "Saved" : "Saved, with " + n + " shortcut" + (n == 1 ? "" : "s"),
                    Toast.LENGTH_SHORT).show();
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSteps();
        tone.setSelection(Tones.index(Tones.current(this)));
        theme.setSelection(Theme.index(Theme.current(this)));
        clipHistory.setChecked(Prefs.flag(this, Prefs.CLIP_HISTORY, true));
        shortcutsField.setText(Prefs.get(this, Prefs.SHORTCUTS, ""));
        refreshLearned(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The keyboard picker is a dialog; refresh once it closes.
        if (hasFocus) {
            refreshSteps();
            refreshLearned(false);
        }
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshSteps();
        if (!micGranted() && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied for good: the system dialog won't show again, so go to app settings.
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    private void load() {
        apiKey.setText(Prefs.get(this, Prefs.API_KEY, ""));
        style.setText(Prefs.get(this, Prefs.STYLE, ""));
        language.setText(Prefs.get(this, Prefs.LANGUAGE, ""));
        baseUrl.setText(Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL));
        sttModel.setText(Prefs.get(this, Prefs.STT_MODEL, Prefs.DEF_STT_MODEL));
        llmModel.setText(Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL));
        cleanup.setChecked(Prefs.flag(this, Prefs.CLEANUP, true));
        autoStart.setChecked(Prefs.flag(this, Prefs.AUTO_START, false));
        autoReturn.setChecked(Prefs.flag(this, Prefs.AUTO_RETURN, false));
        commands.setChecked(Prefs.flag(this, Prefs.COMMANDS, true));
        learn.setChecked(Prefs.flag(this, Prefs.LEARN, true));
        learnStyle.setChecked(Prefs.flag(this, Prefs.LEARN_STYLE, true));
        autocorrect.setChecked(Prefs.flag(this, Prefs.AUTO_REPLACE, false));
        swipe.setChecked(Prefs.flag(this, Prefs.SWIPE, true));
        keyVibrate.setChecked(Prefs.flag(this, Prefs.KEY_VIBRATE, true));
        keySound.setChecked(Prefs.flag(this, Prefs.KEY_SOUND, false));
        narrowNumbers.setChecked(Prefs.flag(this, Prefs.NARROW_NUMBERS, true));
        int size = Math.max(80, Math.min(130, Prefs.sp(this).getInt(Prefs.NARROW_KEY_SIZE, Prefs.NARROW_KEY_SIZE_DEFAULT)));
        narrowSize.setProgress(size - 80);
        showNarrowSize(size);
        refreshLearned(true);
    }

    private void showNarrowSize(int pct) {
        narrowSizeLabel.setText("Key height on narrow screens: " + pct + "%");
    }

    /** Shows the latest word list and learned fixes, unless the user is editing them here. */
    private void refreshLearned(boolean force) {
        String v = Prefs.get(this, Prefs.VOCAB, "");
        if (force || text(vocab).equals(shownVocab)) {
            vocab.setText(v);
            shownVocab = v;
        }
        String l = Learn.display(Learn.learned(this));
        if (force || text(learned).equals(shownLearned)) {
            learned.setText(l);
            shownLearned = l;
        }
        int n = Learn.samples(this).size();
        samplesInfo.setText(n == 0 ? "No writing samples yet. They build up as you dictate."
                : "Learning from your last " + n + " dictation" + (n == 1 ? "" : "s") + ".");
    }

    private void save() {
        Prefs.sp(this).edit()
                .putString(Prefs.API_KEY, text(apiKey))
                .putString(Prefs.STYLE, text(style))
                .putString(Prefs.LANGUAGE, text(language).toLowerCase())
                .putString(Prefs.BASE_URL, text(baseUrl))
                .putString(Prefs.STT_MODEL, text(sttModel))
                .putString(Prefs.LLM_MODEL, text(llmModel))
                .putBoolean(Prefs.CLEANUP, cleanup.isChecked())
                .putBoolean(Prefs.AUTO_START, autoStart.isChecked())
                .putBoolean(Prefs.AUTO_RETURN, autoReturn.isChecked())
                .putBoolean(Prefs.COMMANDS, commands.isChecked())
                .putBoolean(Prefs.LEARN, learn.isChecked())
                .putBoolean(Prefs.LEARN_STYLE, learnStyle.isChecked())
                .putBoolean(Prefs.AUTO_REPLACE, autocorrect.isChecked())
                .putBoolean(Prefs.SWIPE, swipe.isChecked())
                .putBoolean(Prefs.KEY_VIBRATE, keyVibrate.isChecked())
                .putBoolean(Prefs.KEY_SOUND, keySound.isChecked())
                .putBoolean(Prefs.NARROW_NUMBERS, narrowNumbers.isChecked())
                .putInt(Prefs.NARROW_KEY_SIZE, 80 + narrowSize.getProgress())
                .putBoolean(Prefs.CLIP_HISTORY, clipHistory.isChecked())
                .putString(Prefs.SHORTCUTS, text(shortcutsField))
                .apply();
        if (!clipHistory.isChecked()) Prefs.sp(this).edit().remove(Prefs.CLIPS).apply();
        // Only overwrite the lists if they were edited here, so words the keyboard just
        // learned are not lost.
        if (!text(vocab).equals(shownVocab)) {
            Prefs.sp(this).edit().putString(Prefs.VOCAB, text(vocab)).apply();
            shownVocab = text(vocab);
        }
        if (!text(learned).equals(shownLearned)) {
            Learn.save(this, Learn.parse(text(learned)));
            shownLearned = text(learned);
        }
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean keyboardEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean keyboardSelected() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(getPackageName() + "/");
    }

    private void refreshSteps() {
        step(stepMic, micGranted(), "1.  Allow the microphone");
        step(stepEnable, keyboardEnabled(), "2.  Turn on the Murmur keyboard");
        step(stepPick, keyboardSelected(), "3.  Switch to Murmur (or use your keyboard's switch key)");
        step(stepOneTap, MurmurAccessService.isEnabled(this), "Optional:  turn on the one-tap dictation button");
    }

    private void step(TextView view, boolean done, String label) {
        view.setText((done ? "✓  " : "") + label);
        view.setTextColor(getColor(done ? R.color.ok : R.color.text));
    }
}
