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
        setupTexting();
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
        refreshTexting();
        if (textingStatus != null) textingStatus.post(this::checkBlocked);
        if (getIntent() != null && getIntent().getBooleanExtra("setup_texting", false)) {
            getIntent().removeExtra("setup_texting");
            if (textingAccess != null) {
                View sv = findViewById(R.id.texting);
                if (sv != null) sv.getParent().requestChildFocus(sv, sv);
                nextSetupStep();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private Switch texting;
    private TextView textingHint, textingAccess, textingOpenChat, textingStatus;
    private boolean openSettingsAfterAsk;
    /** Which access screen he was sent to, so coming back without it on can explain why. */
    private String sentTo;

    /**
     * Android blocks notification and accessibility access for apps installed from a file until
     * he allows "restricted settings" in the app's info page. Coming back without access usually
     * means that block, so say so and offer the page.
     */
    private void checkBlocked() {
        String step = sentTo;
        sentTo = null;
        if (step == null) return;
        boolean ok = "notes".equals(step) ? MurmurNotifications.allowed(this) : MurmurChatReader.enabled(this);
        if (ok) {
            if ("notes".equals(step) && !MurmurChatReader.enabled(this)) textingOpenChat.performClick();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Did Android say \"App was denied access\"?")
                .setMessage("Android blocks this for apps installed from a file, until you allow it once:\n\n"
                        + "1. Tap Open App info below.\n"
                        + "2. Tap ⋮ in the top corner, then Allow restricted settings, and confirm.\n"
                        + "3. Come back here and tap Step " + ("notes".equals(step) ? "1" : "2") + " again.\n\n"
                        + "If you don't see Allow restricted settings, try turning the switch on once first so "
                        + "Android shows the warning, then look again.")
                .setPositiveButton("Open App info", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void setupTexting() {
        texting = findViewById(R.id.texting);
        textingHint = findViewById(R.id.texting_hint);
        textingAccess = findViewById(R.id.texting_access);
        textingOpenChat = findViewById(R.id.texting_open_chat);
        textingStatus = findViewById(R.id.texting_status);
        if (texting == null) return;
        texting.setOnCheckedChangeListener((b, on) -> {
            if (on == Translate.texting(this)) return;
            Translate.setTexting(this, on);
            refreshTexting();
            // Turning it on the first time walks straight into the one-time setup.
            if (on) nextSetupStep();
        });
        textingAccess.setOnClickListener(v -> {
            if (MurmurNotifications.allowed(this)) {
                openListenerSettings();
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Step 1 of 2: replies from notifications")
                    .setMessage("To show their replies in " + Translate.mine(this) + ", Murmur reads your message "
                            + "notifications. While texting mode is on, it reads only messages, sends the text to your "
                            + "AI provider to translate (the same one your dictation uses), and shows the translation "
                            + "as a notification and on the keyboard.\n\nOn the next screen, turn on Murmur texting "
                            + "mode. If the switch is greyed out, open Murmur's App info, tap ⋮ and choose Allow "
                            + "restricted settings, then try again.")
                    .setPositiveButton("Continue", (d, w) -> openListenerSettings())
                    .setNegativeButton("Not now", null)
                    .show();
        });
        textingOpenChat.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Step 2 of 2: the chat you have open")
                .setMessage("WhatsApp and most chat apps don't send a notification when a reply arrives in the chat "
                        + "you have open, so step 1 can't see it. This lets Murmur see new message bubbles in the open "
                        + "chat in WhatsApp, Google Messages, Samsung Messages and Telegram, only while texting mode "
                        + "is on, and show them in " + Translate.mine(this) + ".\n\nOn the next screen, open "
                        + "Installed apps, then Murmur texting mode: open chats, and turn it on. If it's greyed out, "
                        + "allow restricted settings in Murmur's App info first.")
                .setPositiveButton("Continue", (d, w) -> {
                    try {
                        sentTo = MurmurChatReader.enabled(this) ? null : "chats";
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton("Not now", null)
                .show());
        findViewById(R.id.texting_test).setOnClickListener(v -> sendTest());
    }

    /** Walks through whichever setup step is still missing. */
    private void nextSetupStep() {
        if (!MurmurNotifications.allowed(this)) textingAccess.performClick();
        else if (!MurmurChatReader.enabled(this)) textingOpenChat.performClick();
    }

    private boolean canNotify() {
        return android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void openListenerSettings() {
        if (!canNotify()) {
            // Ask for notifications first; the access screen opens once he answers.
            openSettingsAfterAsk = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
            return;
        }
        try {
            sentTo = MurmurNotifications.allowed(this) ? null : "notes";
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Open Settings › Notifications › Device and app notifications", Toast.LENGTH_LONG).show();
        }
    }

    /** Texting mode's notification permission answers. */
    private void onTextingPermission(int code) {
        if (code == 41 && openSettingsAfterAsk) {
            openSettingsAfterAsk = false;
            openListenerSettings();
        } else if (code == 42) {
            sendTest();
        }
    }

    /** Posts a pretend message in their language, the way a chat app would, and lets Murmur handle it. */
    private void sendTest() {
        if (!Translate.texting(this)) {
            Translate.setTexting(this, true);
            refreshTexting();
        }
        if (Prefs.get(this, Prefs.API_KEY, "").isEmpty()) {
            Toast.makeText(this, "Add your API key first", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canNotify()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
            return;
        }
        String sample = testMessage(Translate.theirs(this));
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 28 && MurmurNotifications.allowed(this) && nm != null
                && nm.areNotificationsEnabled()) {
            if (nm.getNotificationChannel(MurmurNotifications.TEST_CHANNEL) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(MurmurNotifications.TEST_CHANNEL,
                        "Texting mode test", android.app.NotificationManager.IMPORTANCE_LOW));
            }
            android.app.Person ana = new android.app.Person.Builder().setName("Test").build();
            android.app.Person me = new android.app.Person.Builder().setName("You").build();
            android.app.Notification.Builder nb = new android.app.Notification.Builder(this, MurmurNotifications.TEST_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                    .setStyle(new android.app.Notification.MessagingStyle(me).addMessage(sample, System.currentTimeMillis(), ana))
                    .setTimeoutAfter(15000)
                    .setAutoCancel(true);
            android.os.Bundle extra = new android.os.Bundle();
            extra.putBoolean(MurmurNotifications.TEST_EXTRA, true);
            nb.addExtras(extra);
            MurmurNotifications.note(this, "test message sent, waiting for the notification reader…");
            nm.notify(999, nb.build());
            Toast.makeText(this, "Sent “" + sample + "”. The English should pop up in a few seconds.", Toast.LENGTH_LONG).show();
        } else {
            // No notification access yet: still check that translating works.
            String key = Prefs.get(this, Prefs.API_KEY, "");
            String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
            String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
            String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
            String mine = Translate.mine(this);
            android.content.Context ctx = getApplicationContext();
            MurmurNotifications.note(this, "testing the translation only (step 1 isn't on)…");
            new Thread(() -> MurmurNotifications.handle(ctx, base, key, model, mine, "Test", sample, "Test", null)).start();
        }
        textingStatus.postDelayed(this::refreshTexting, 6000);
        textingStatus.postDelayed(this::refreshTexting, 12000);
    }

    private static String testMessage(String language) {
        switch (language) {
            case "French": return "Salut ! On se retrouve à 18 h ?";
            case "German": return "Hallo! Treffen wir uns um 18 Uhr?";
            case "Italian": return "Ciao! Ci vediamo alle 18?";
            case "Portuguese": return "Oi! A gente se vê às 18h?";
            case "Chinese (Simplified)": return "你好！我们六点见面吗？";
            case "Japanese": return "こんにちは！6時に会いましょうか？";
            case "Korean": return "안녕! 6시에 만날까?";
            case "Russian": return "Привет! Встретимся в 6?";
            case "Vietnamese": return "Chào bạn! 6 giờ gặp nhau nhé?";
            case "English": return "Hi! Shall we meet at 6?";
            default: return "¡Hola! ¿Nos vemos a las 6? Ya voy saliendo.";
        }
    }

    private void refreshTexting() {
        if (texting == null) return;
        boolean on = Translate.texting(this);
        texting.setChecked(on);
        boolean notes = MurmurNotifications.allowed(this);
        boolean chats = MurmurChatReader.enabled(this);
        if (on && notes) MurmurNotifications.rebind(this);
        textingAccess.setText(notes ? "✓ Step 1: replies from notifications" : "Step 1: translate replies from notifications");
        textingOpenChat.setText(chats ? "✓ Step 2: replies in the chat you have open"
                : "Step 2: translate replies in the chat you have open");
        textingHint.setText("Text someone who speaks another language. Talk or type in " + Translate.mine(this)
                + "; Murmur sends it in " + Translate.theirs(this) + " (tap ⇄ on the keyboard to translate what you "
                + "typed). Their replies show in " + Translate.mine(this) + " as a notification and in the keyboard's "
                + "top bar. Hold 文A on the keyboard to pick their language.");
        long connected = Prefs.sp(this).getLong(Prefs.DIAG_CONNECTED, 0);
        String last = Prefs.get(this, Prefs.DIAG_LAST, "nothing yet");
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        boolean pops = canNotify() && nm != null && nm.areNotificationsEnabled();
        textingStatus.setText("Status: texting mode " + (on ? "on" : "off")
                + " · notification reader " + (!notes ? "not allowed" : connected > 0 ? "connected" : "allowed, not connected yet")
                + " · open-chat reader " + (chats ? "on" : "off")
                + " · Murmur notifications " + (pops ? "on" : "off (translations show only on the keyboard)")
                + "\nLast: " + last);
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
        if (requestCode == 41 || requestCode == 42) {
            onTextingPermission(requestCode);
            refreshTexting();
            return;
        }
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
