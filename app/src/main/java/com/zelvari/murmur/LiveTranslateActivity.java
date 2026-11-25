package com.zelvari.murmur;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * Live Translate: the app side of texting mode. One switch turns it on; each chat Murmur has
 * translated gets its own thread here, in his language, with its own language and on/off
 * switch. The keyboard's strip, the floating bubble and the notifications all open here.
 * The history stays on the phone and can be cleared per chat or all at once.
 */
public class LiveTranslateActivity extends Activity {
    static final String EXTRA_CHAT = "chat";

    private ScrollView scroll;
    private LinearLayout body;
    /** The chat being shown, or null for the list of chats. */
    private String openKey;
    private int text, dim, accent, bg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = getColor(R.color.text);
        dim = getColor(R.color.dim);
        accent = getColor(R.color.accent);
        bg = getColor(R.color.bg);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(body);
        setContentView(scroll);
        openKey = getIntent().getStringExtra(EXTRA_CHAT);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openKey = intent.getStringExtra(EXTRA_CHAT);
        render();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener watch = (sp, key) -> {
        if (Prefs.IN_AT.equals(key) || Prefs.TEXTING.equals(key) || Prefs.DIAG_LAST.equals(key)
                || Prefs.DIAG_SEEN.equals(key) || Prefs.DIAG_READING.equals(key)) {
            runOnUiThread(this::render);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(watch);
        render();
    }

    @Override
    protected void onPause() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(watch);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (openKey != null) {
            openKey = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    private void render() {
        body.removeAllViews();
        ChatLog.Chat ch = openKey == null ? null : ChatLog.get(this, openKey);
        if (openKey != null && ch == null) openKey = null;
        if (ch != null) renderChat(ch);
        else renderList();
    }

    // ---- the list of chats --------------------------------------------------------------

    private void renderList() {
        body.addView(title("Live Translate"));
        body.addView(line("Their replies come back in " + Translate.mine(this) + ", on the keyboard and here, for "
                + "each chat. Tap ⇄ in a chat to send that chat in their language; other chats send as you type.",
                dim, 14));

        boolean on = Translate.texting(this);
        Switch sw = new Switch(this);
        sw.setText(on ? "Live Translate is on" : "Live Translate is off");
        sw.setTextColor(text);
        sw.setTextSize(18);
        sw.setTypeface(Typeface.DEFAULT_BOLD);
        sw.setChecked(on);
        sw.setPadding(0, dp(18), 0, dp(6));
        sw.setOnCheckedChangeListener((b, checked) -> {
            Translate.setTexting(this, checked);
            if (checked && !ready()) startSetup();
            render();
        });
        body.addView(sw);

        if (on) {
            boolean ready = ready();
            TextView setup = line(ready ? "✓ Ready: reading new replies in your open chat and in message notifications"
                    : "⚠ One more step so their replies translate by themselves · tap to finish setup",
                    ready ? 0xFF22C55E : 0xFFF59E0B, 14);
            if (!ready) setup.setOnClickListener(v -> startSetup());
            body.addView(setup);
            body.addView(status());
        }

        body.addView(row("You read in", Translate.mine(this), v -> pickLanguage("You read in", Translate.mine(this),
                name -> Prefs.sp(this).edit().putString(Prefs.CONVO_MINE, name).apply())));
        body.addView(row("⇄ translates into", Translate.theirsDefault(this),
                v -> pickLanguage("⇄ translates into", Translate.last(this), name -> Translate.set(this, name,
                        Prefs.flag(this, Prefs.TRANSLATE_ON, false)))));

        body.addView(section("CHATS"));
        List<ChatLog.Chat> chats = ChatLog.all(this);
        if (chats.isEmpty()) {
            body.addView(line("No chats yet. Turn Live Translate on, open WhatsApp or Messages and text "
                    + "someone. Each chat shows up here with its messages in " + Translate.mine(this) + ".", dim, 14));
        }
        for (ChatLog.Chat c : chats) body.addView(chatRow(c));

        if (!chats.isEmpty()) {
            TextView clear = line("Clear all history", 0xFFF87171, 14);
            clear.setPadding(0, dp(22), 0, dp(6));
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Clear all Live Translate history?")
                    .setMessage("This deletes every chat's translated messages from this phone. The chats "
                            + "themselves stay in WhatsApp and Messages.")
                    .setPositiveButton("Clear all", (d, w) -> {
                        ChatLog.clearAll(this);
                        render();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            body.addView(clear);
        }
        body.addView(line("This history stays on this phone only.", dim, 12));
    }

    private View chatRow(ChatLog.Chat c) {
        LinearLayout box = card();
        TextView name = line(c.title() + (c.off ? "  · off" : ""), text, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(0, 0, 0, dp(2));
        box.addView(name);
        String lang = c.send ? "sends in " + (c.lang.isEmpty() ? Translate.theirsDefault(this) : c.lang)
                : "sends in " + Translate.mine(this);
        String when = c.at == 0 ? "" : " · " + DateUtils.getRelativeTimeSpanString(c.at, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        box.addView(line((c.app.isEmpty() ? "" : c.app + " · ") + lang + when, dim, 12));
        if (!c.msgs.isEmpty()) {
            ChatLog.Msg m = c.msgs.get(c.msgs.size() - 1);
            TextView last = line((m.mine ? "You: " + (m.orig.isEmpty() ? m.text : m.orig) : m.text), text, 14);
            last.setMaxLines(2);
            last.setEllipsize(android.text.TextUtils.TruncateAt.END);
            last.setPadding(0, dp(4), 0, 0);
            box.addView(last);
        }
        box.setOnClickListener(v -> {
            openKey = c.key;
            render();
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        return box;
    }

    // ---- one chat -----------------------------------------------------------------------

    private void renderChat(ChatLog.Chat c) {
        TextView back = line("‹ All chats", accent, 15);
        back.setPadding(0, 0, 0, dp(8));
        back.setOnClickListener(v -> onBackPressed());
        body.addView(back);
        body.addView(title(c.title()));
        if (!c.app.isEmpty()) body.addView(line(c.app, dim, 13));

        String lang = c.lang.isEmpty() ? "Default (" + Translate.theirsDefault(this) + ")" : c.lang;
        String sendIn = c.lang.isEmpty() ? Translate.theirsDefault(this) : c.lang;
        Switch send = new Switch(this);
        send.setText("Send my messages in " + sendIn);
        send.setTextColor(text);
        send.setTextSize(16);
        send.setChecked(c.send);
        send.setPadding(0, dp(14), 0, dp(2));
        send.setOnCheckedChangeListener((b, checked) -> {
            ChatLog.settings(this, c, null, null, checked);
            render();
        });
        body.addView(send);
        body.addView(line(c.send ? "Only this chat. Your other chats send in " + Translate.mine(this) + "."
                : "Off: this chat sends what you type. ⇄ on the keyboard turns it on for this chat only.", dim, 12));
        Switch sw = new Switch(this);
        sw.setText("Show their replies in " + Translate.mine(this));
        sw.setTextColor(text);
        sw.setTextSize(16);
        sw.setChecked(!c.off);
        sw.setPadding(0, dp(10), 0, dp(4));
        sw.setOnCheckedChangeListener((b, checked) -> {
            ChatLog.settings(this, c, null, !checked);
            render();
        });
        body.addView(sw);
        body.addView(row("They write in", lang, v -> pickLanguage("They write in", c.lang,
                name -> ChatLog.settings(this, c, name, null))));

        body.addView(section("MESSAGES IN " + Translate.mine(this).toUpperCase(java.util.Locale.ROOT)));
        long lastDay = -1;
        for (ChatLog.Msg m : c.msgs) {
            long day = m.at / DateUtils.DAY_IN_MILLIS;
            if (day != lastDay) {
                lastDay = day;
                TextView d = line(DateUtils.formatDateTime(this, m.at, DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL), dim, 12);
                d.setGravity(Gravity.CENTER);
                d.setPadding(0, dp(10), 0, dp(4));
                body.addView(d);
            }
            body.addView(bubble(c, m));
        }
        if (c.msgs.isEmpty()) body.addView(line("No messages yet.", dim, 14));

        TextView clear = line("Clear this chat's history", 0xFFF87171, 14);
        clear.setPadding(0, dp(24), 0, dp(6));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear " + c.title() + "?")
                .setMessage("This deletes this chat's translated messages from this phone.")
                .setPositiveButton("Clear", (d, w) -> {
                    ChatLog.clear(this, c.key);
                    openKey = null;
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show());
        body.addView(clear);
    }

    private View bubble(ChatLog.Chat c, ChatLog.Msg m) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(m.mine ? Gravity.END : Gravity.START);
        wrap.setPadding(m.mine ? dp(40) : 0, dp(4), m.mine ? 0 : dp(40), dp(4));
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(m.mine ? 0xFF1E3A8A : 0xFF4C1D95);
        b.setBackground(g);
        String who = m.mine ? "You" : m.from.isEmpty() ? c.title() : m.from;
        String time = DateUtils.formatDateTime(this, m.at, DateUtils.FORMAT_SHOW_TIME);
        b.addView(line(who + " · " + time, 0xFFC4B5FD, 12));
        // His own message reads as he said it; theirs reads in his language.
        String main = m.mine && !m.orig.isEmpty() ? m.orig : m.text;
        String other = m.mine ? (m.orig.isEmpty() ? "" : m.text) : m.orig;
        b.addView(line(main, 0xFFFFFFFF, 16));
        if (!other.isEmpty() && !other.equals(main)) {
            TextView o = line((m.mine ? "Sent: " : "They wrote: ") + other, 0xB3FFFFFF, 12);
            o.setPadding(0, dp(4), 0, 0);
            b.addView(o);
        }
        wrap.addView(b);
        return wrap;
    }

    /** What's reading their replies right now, and the last one it caught. */
    private View status() {
        LinearLayout box = card();
        TextView h = line("Reading replies", text, 15);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(h);
        boolean readerOn = MurmurChatReader.enabled(this);
        boolean readerRunning = MurmurChatReader.running();
        box.addView(line((readerRunning ? "✓ " : "✗ ") + "Open chat: "
                + (readerRunning ? "reading the chat you have open"
                : readerOn ? "switched on, but Android hasn't started it. Turn Murmur off and on again in "
                + "Accessibility" : "off, so replies in the chat you have open aren't read"),
                readerRunning ? 0xFF22C55E : 0xFFF59E0B, 13));
        boolean notes = MurmurNotifications.allowed(this);
        box.addView(line((notes ? "✓ " : "✗ ") + "Closed chats: " + (notes ? "reading message notifications"
                : "off, so replies to chats you don't have open aren't read"), notes ? 0xFF22C55E : 0xFFF59E0B, 13));
        String reading = Prefs.get(this, Prefs.DIAG_READING, "");
        if (!reading.isEmpty()) box.addView(line("Last chat opened: " + reading, dim, 12));
        String seen = Prefs.get(this, Prefs.DIAG_SEEN, "");
        box.addView(line(seen.isEmpty() ? "No reply caught yet" : "Last reply caught: " + seen, dim, 12));
        String last = Prefs.get(this, Prefs.DIAG_LAST, "");
        if (!last.isEmpty()) box.addView(line("Last: " + last, dim, 12));
        if (!readerRunning || !notes) {
            TextView fix = line("Finish setup ›", accent, 14);
            fix.setPadding(0, dp(8), 0, 0);
            fix.setOnClickListener(v -> startSetup());
            box.addView(fix);
        }
        return box;
    }

    // ---- helpers ------------------------------------------------------------------------

    private boolean ready() {
        return MurmurNotifications.allowed(this) && MurmurChatReader.enabled(this);
    }

    private void startSetup() {
        Intent i = new Intent(this, MainActivity.class).putExtra("setup_texting", true);
        startActivity(i);
    }

    private interface Pick {
        void picked(String name);
    }

    private void pickLanguage(String title, String current, Pick pick) {
        String[] names = new String[Translate.LANGUAGES.length];
        int sel = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = Translate.LANGUAGES[i][0];
            if (names[i].equals(current)) sel = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, sel, (d, w) -> {
                    pick.picked(names[w]);
                    d.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView title(String s) {
        TextView t = line(s, text, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView section(String s) {
        TextView t = line(s, accent, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(26), 0, dp(8));
        return t;
    }

    private View row(String label, String value, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(14), 0, dp(4));
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = line(label, text, 15);
        r.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView b = line(value + "  ›", accent, 15);
        r.addView(b);
        r.setOnClickListener(l);
        return r;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(blend(bg, text, 0.08f));
        box.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView line(String s, int color, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private static int blend(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
