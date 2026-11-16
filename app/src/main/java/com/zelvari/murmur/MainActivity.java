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
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Setup checklist and settings. */
public class MainActivity extends Activity {
    private TextView stepMic, stepEnable, stepPick;
    private EditText apiKey, style, vocab, language, baseUrl, sttModel, llmModel;
    private Switch cleanup, autoStart, autoReturn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stepMic = findViewById(R.id.step_mic);
        stepEnable = findViewById(R.id.step_enable);
        stepPick = findViewById(R.id.step_pick);
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
        findViewById(R.id.get_key).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Open console.groq.com/keys in your browser", Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.save).setOnClickListener(v -> {
            save();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSteps();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The keyboard picker is a dialog; refresh once it closes.
        if (hasFocus) refreshSteps();
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
        vocab.setText(Prefs.get(this, Prefs.VOCAB, ""));
        language.setText(Prefs.get(this, Prefs.LANGUAGE, ""));
        baseUrl.setText(Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL));
        sttModel.setText(Prefs.get(this, Prefs.STT_MODEL, Prefs.DEF_STT_MODEL));
        llmModel.setText(Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL));
        cleanup.setChecked(Prefs.flag(this, Prefs.CLEANUP, true));
        autoStart.setChecked(Prefs.flag(this, Prefs.AUTO_START, false));
        autoReturn.setChecked(Prefs.flag(this, Prefs.AUTO_RETURN, false));
    }

    private void save() {
        Prefs.sp(this).edit()
                .putString(Prefs.API_KEY, text(apiKey))
                .putString(Prefs.STYLE, text(style))
                .putString(Prefs.VOCAB, text(vocab))
                .putString(Prefs.LANGUAGE, text(language).toLowerCase())
                .putString(Prefs.BASE_URL, text(baseUrl))
                .putString(Prefs.STT_MODEL, text(sttModel))
                .putString(Prefs.LLM_MODEL, text(llmModel))
                .putBoolean(Prefs.CLEANUP, cleanup.isChecked())
                .putBoolean(Prefs.AUTO_START, autoStart.isChecked())
                .putBoolean(Prefs.AUTO_RETURN, autoReturn.isChecked())
                .apply();
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
    }

    private void step(TextView view, boolean done, String label) {
        view.setText((done ? "✓  " : "") + label);
        view.setTextColor(getColor(done ? R.color.ok : R.color.text));
    }
}
