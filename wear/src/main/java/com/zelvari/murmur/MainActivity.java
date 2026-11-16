package com.zelvari.murmur;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Watch setup. Typing an API key on a watch is miserable, so settings can also be sent
 * from a PC over adb as intent extras named after the keys in {@link Prefs}:
 * adb shell am start -n com.zelvari.murmur.wear/com.zelvari.murmur.MainActivity -e api_key gsk_...
 */
public class MainActivity extends Activity {
    private static final String[] TEXT_SETTINGS = {
            Prefs.API_KEY, Prefs.BASE_URL, Prefs.STT_MODEL, Prefs.LLM_MODEL,
            Prefs.STYLE, Prefs.VOCAB, Prefs.LANGUAGE,
    };

    private TextView keyStatus, stepMic, stepEnable, stepPick;
    private EditText apiKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        keyStatus = findViewById(R.id.key_status);
        stepMic = findViewById(R.id.step_mic);
        stepEnable = findViewById(R.id.step_enable);
        stepPick = findViewById(R.id.step_pick);
        apiKey = findViewById(R.id.api_key);

        stepMic.setOnClickListener(v -> {
            if (!micGranted()) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        });
        stepEnable.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Watch Settings > General > Input > Keyboard list", Toast.LENGTH_LONG).show();
            }
        });
        stepPick.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        findViewById(R.id.save).setOnClickListener(v -> {
            String typed = apiKey.getText().toString().trim();
            if (typed.isEmpty()) return;
            Prefs.sp(this).edit().putString(Prefs.API_KEY, typed).apply();
            apiKey.setText("");
            refresh();
        });

        applyExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyExtras(intent);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    /** Saves any settings passed as intent extras (sent from a PC with adb). */
    private void applyExtras(Intent intent) {
        if (intent == null) return;
        SharedPreferences.Editor edit = Prefs.sp(this).edit();
        boolean changed = false;
        for (String name : TEXT_SETTINGS) {
            String value = intent.getStringExtra(name);
            if (value != null) {
                edit.putString(name, value.trim());
                changed = true;
            }
        }
        if (changed) {
            edit.apply();
            Toast.makeText(this, "Settings received", Toast.LENGTH_SHORT).show();
        }
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

    private void refresh() {
        boolean hasKey = !Prefs.get(this, Prefs.API_KEY, "").isEmpty();
        keyStatus.setText(hasKey ? "✓ API key saved" : "No API key yet");
        keyStatus.setTextColor(getColor(hasKey ? R.color.ok : R.color.dim));
        step(stepMic, micGranted(), "Allow microphone");
        step(stepEnable, keyboardEnabled(), "Turn on keyboard");
        step(stepPick, keyboardSelected(), "Use Murmur");
    }

    private void step(TextView view, boolean done, String label) {
        view.setText((done ? "✓ " : "") + label);
        view.setTextColor(getColor(done ? R.color.ok : R.color.text));
    }
}
