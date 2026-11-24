package com.zelvari.murmur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Texting mode, incoming side: when a message notification arrives in another language, Murmur
 * translates it into his language, posts the translation as its own notification and shows it
 * in the keyboard's top bar. It looks only at message notifications, and only while texting
 * mode is on. What it last did is kept in {@link Prefs#DIAG_LAST} so settings can show it.
 */
public class MurmurNotifications extends NotificationListenerService {
    static final String CHANNEL = "translations";
    /** Set on Murmur's own test message so the reader handles it like a real one. */
    static final String TEST_EXTRA = "murmur_test";
    static final String TEST_CHANNEL = "texting_test";

    /** Chat apps whose notifications count as messages even without the message category. */
    private static final String[] CHAT_APPS = {
            "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms",
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
            "com.instagram.android", "com.snapchat.android", "com.discord", "com.viber.voip",
            "jp.naver.line.android", "com.tencent.mm", "org.thoughtcrime.securesms", "com.skype.raider",
            "com.microsoft.teams", "com.Slack", "com.google.android.apps.dynamite", "com.textra",
            "com.p1.chompsms", "com.verizon.messaging.vzmsgs", "com.google.android.apps.googlevoice",
    };

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Messages already handled, so an updated notification isn't translated twice. */
    private static final ArrayDeque<String> seen = new ArrayDeque<>();

    /** True the first time a message is seen, from a notification or the open chat; false after. */
    static boolean claim(String text) {
        String mark = text.trim().replaceAll("\\s+", " ");
        synchronized (seen) {
            if (seen.contains(mark)) return false;
            seen.addLast(mark);
            while (seen.size() > 80) seen.removeFirst();
            return true;
        }
    }

    static boolean allowed(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, MurmurNotifications.class).flattenToString());
    }

    /** Asks Android to reconnect the reader (after an update, or if the phone put it to sleep). */
    static void rebind(Context c) {
        try {
            requestRebind(new ComponentName(c, MurmurNotifications.class));
        } catch (RuntimeException ignored) {
        }
    }

    static void note(Context c, String what) {
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        Prefs.sp(c).edit().putString(Prefs.DIAG_LAST, time + " · " + what).apply();
    }

    /** A diagnostic line with the time, for the Live Translate screen. */
    static void diag(Context c, String key, String what) {
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        Prefs.sp(c).edit().putString(key, time + " · " + what).apply();
    }

    @Override
    public void onListenerConnected() {
        Prefs.sp(this).edit().putLong(Prefs.DIAG_CONNECTED, System.currentTimeMillis()).apply();
    }

    @Override
    public void onListenerDisconnected() {
        Prefs.sp(this).edit().putLong(Prefs.DIAG_CONNECTED, 0).apply();
        rebind(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !Translate.texting(this)) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle x = n.extras;
        boolean test = x.getBoolean(TEST_EXTRA, false);
        String pkg = sbn.getPackageName();
        if (!test && (getPackageName().equals(pkg) || sbn.isOngoing())) return;

        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        // WhatsApp's "3 messages from 2 chats" summary carries no message of its own.
        if (!test && messages == null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        boolean chat = test || messages != null || Notification.CATEGORY_MESSAGE.equals(n.category) || isChatApp(pkg);
        if (!chat) return;

        String from = null, text = null;
        CharSequence convo = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = convo != null ? convo : x.getCharSequence(Notification.EXTRA_TITLE);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            CharSequence t = last.getCharSequence("text");
            CharSequence s = last.getCharSequence("sender");
            Object person = last.get("sender_person");
            if (s == null && android.os.Build.VERSION.SDK_INT >= 28 && person instanceof Person) {
                s = ((Person) person).getName();
            }
            if (t != null) text = t.toString();
            from = s != null ? s.toString() : title != null ? title.toString() : "";
        }
        if (text == null) {
            CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            CharSequence t = lines != null && lines.length > 0 ? lines[lines.length - 1] : null;
            if (t == null) t = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (t == null) t = x.getCharSequence(Notification.EXTRA_TEXT);
            if (t != null) text = t.toString();
            if (from == null) from = title == null ? "" : title.toString();
        }
        if (text == null || text.trim().isEmpty()) {
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) note(this, appName(pkg) + ": a message with no text to read");
            return;
        }
        text = text.trim();
        if (text.length() > 1500) text = text.substring(0, 1500);
        // His own message (what Murmur just sent in their language) shows up in some chat notifications too.
        if (!test && Translate.wasSent(this, text)) {
            note(this, appName(pkg) + ": skipped your own message");
            return;
        }

        if (!claim(text)) return;

        final String key = Prefs.get(this, Prefs.API_KEY, "");
        if (key.isEmpty()) {
            note(this, "no API key, so nothing was translated");
            return;
        }
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        // The chat: a group's name, or the person for a one-to-one chat.
        String chatName = convo != null ? convo.toString() : title != null ? title.toString() : from;
        ChatLog.Chat ch = ChatLog.get(this, ChatLog.key(pkg, chatName));
        if (ch != null && ch.off) {
            note(this, appName(pkg) + ": Live Translate is off for " + ch.title());
            return;
        }
        final String fFrom = from, fText = text, app = appName(pkg), fChat = chatName, fPkg = test ? getPackageName() : pkg;
        final PendingIntent open = test ? null : n.contentIntent;
        final Context ctx = getApplicationContext();
        note(this, app + ": translating a message from " + (from.isEmpty() ? "someone" : from) + "…");
        worker.execute(() -> handle(ctx, base, key, model, mine, fPkg, fChat, fFrom, fText, app, open, false));
    }

    /** Translates one message and shows it; shared with the settings screen's test button. */
    static void handle(Context ctx, String base, String key, String model, String mine,
                       String from, String text, String app, PendingIntent open) {
        handle(ctx, base, key, model, mine, ctx.getPackageName(), from, from, text, app, open, false);
    }

    /**
     * Translates one message into his language and adds it to that chat's Live Translate thread.
     * {@code onScreen}: the reply is in the chat he has open, so the keyboard strip or the
     * floating bubble shows it; otherwise the chat's notification does.
     */
    static void handle(Context ctx, String base, String key, String model, String mine, String pkg,
                       String chatName, String from, String text, String app, PendingIntent open,
                       boolean onScreen) {
        try {
            String out = AiClient.toMine(base, key, model, text, mine);
            if (out.isEmpty()) {
                note(ctx, app + ": already in " + mine + ", left as is");
                return;
            }
            Translate.saveIncoming(ctx, from, out, app);
            ChatLog.Chat ch = ChatLog.add(ctx, pkg, app, chatName, from, text, out, false);
            if (onScreen) {
                MurmurChatReader.bubble(ch.key);
                note(ctx, app + ": translated a new message in the open chat");
                return;
            }
            boolean shown = post(ctx, ch, open);
            note(ctx, app + ": translated a message from " + (from.isEmpty() ? "someone" : from)
                    + (shown ? "" : " (Murmur's notifications are off, so it's in Live Translate only)"));
        } catch (Exception e) {
            String m = e.getMessage();
            note(ctx, app + ": couldn't translate (" + (m == null ? "network problem" : m) + ")");
        }
    }

    /** Opens Live Translate on one chat. */
    static PendingIntent openChat(Context c, String key) {
        Intent i = new Intent(c, LiveTranslateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LiveTranslateActivity.EXTRA_CHAT, key);
        return PendingIntent.getActivity(c, key.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * One notification per chat that keeps the chat's latest messages in his language. It stays
     * until he taps or clears it, and each new message updates it.
     */
    static boolean post(Context c, ChatLog.Chat ch, PendingIntent open) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel nc = new NotificationChannel(CHANNEL, "Translated messages",
                    NotificationManager.IMPORTANCE_HIGH);
            nc.setDescription("Live Translate: messages you get in another language, in yours");
            nm.createNotificationChannel(nc);
        }
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel nc = nm.getNotificationChannel(CHANNEL);
        if (nc != null && nc.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        PendingIntent live = openChat(c, ch.key);
        if (open == null) open = live;
        ChatLog.Msg last = ch.msgs.get(ch.msgs.size() - 1);
        String title = ch.title() + " · in " + Translate.mine(c);
        Notification.InboxStyle inbox = new Notification.InboxStyle().setBigContentTitle(title);
        int from = Math.max(0, ch.msgs.size() - 6);
        for (int i = from; i < ch.msgs.size(); i++) {
            ChatLog.Msg m = ch.msgs.get(i);
            String who = m.mine ? "You" : m.from.isEmpty() ? ch.title() : m.from;
            inbox.addLine(who + ": " + (m.mine && !m.orig.isEmpty() ? m.orig : m.text));
        }
        Notification.Builder nb = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText((last.from.isEmpty() ? "" : last.from + ": ") + last.text)
                .setStyle(inbox)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Live Translate", live).build())
                .setAutoCancel(true);
        try {
            nm.notify(2000 + (ch.key.hashCode() & 0xFFFF), nb.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean isChatApp(String pkg) {
        for (String a : CHAT_APPS) if (a.equals(pkg)) return true;
        return false;
    }

    private String appName(String pkg) {
        if (getPackageName().equals(pkg)) return "Test";
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
