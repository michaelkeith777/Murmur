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
    /**
     * Messages handled in the last few minutes, per app, so the same message from a notification
     * and the open chat (or an updated notification) is translated once. 1.9 kept the last 80
     * texts with no time limit, so a reply he'd had before ("ok", "sí", "jaja") was never
     * translated again.
     */
    private static final java.util.LinkedHashMap<String, Long> seen = new java.util.LinkedHashMap<>();
    static final long SEEN_FOR = 3 * 60_000L;

    /** True the first time a message is seen in this app in the last few minutes; false after. */
    static boolean claim(String pkg, String text) {
        String mark = (pkg == null ? "" : pkg) + "|" + text.trim().replaceAll("\\s+", " ");
        long now = System.currentTimeMillis();
        synchronized (seen) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > SEEN_FOR) it.remove();
            if (seen.containsKey(mark)) return false;
            seen.put(mark, now);
            return true;
        }
    }

    /** One message read from a notification. */
    static final class In {
        final String from, text, chat;
        final long time;

        In(String from, String text, String chat, long time) {
            this.from = from;
            this.text = text;
            this.chat = chat;
            this.time = time;
        }
    }

    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile(
            "\\s*\\(\\d+\\s+(new\\s+)?(messages?|mensajes?(\\s+nuevos?)?)\\)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The chat's name as its title bar shows it, so a reply files under the same chat the screen
     * reader sees: some apps add "(2 messages)" to the notification's title.
     */
    static String chatTitle(CharSequence title) {
        if (title == null) return "";
        return COUNT.matcher(title.toString()).replaceAll("").trim();
    }

    private static final java.util.regex.Pattern SUMMARY_LINE = java.util.regex.Pattern.compile("(?s)^([^:\\n]{1,40}):\\s+(.+)$");
    private static final java.util.regex.Pattern COUNT_TITLE = java.util.regex.Pattern.compile(
            "(?i)^\\d+\\s+(new\\s+)?(messages?|mensajes?|conversations?|chats?)\\b|^(messages|mensajes)$");

    /** The newest message time handled per conversation, so older ones in an update are skipped. */
    private static final java.util.HashMap<String, Long> lastTime = new java.util.HashMap<>();

    /**
     * The messages in a notification that are new and from someone else. A messaging notification
     * carries the conversation's recent messages: 1.9 read only the last one, so when two replies
     * came in one update, the first was never translated. It also took his own replies (no sender)
     * for theirs. Older messages, already handled, are left out by their time.
     */
    static java.util.List<In> incoming(Bundle x, String pkg) {
        java.util.List<In> out = new java.util.ArrayList<>();
        CharSequence convo = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = convo != null ? convo : x.getCharSequence(Notification.EXTRA_TITLE);
        String chat = chatTitle(title);
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        boolean selfKnown = x.containsKey("android.messagingUser") || x.containsKey(Notification.EXTRA_SELF_DISPLAY_NAME);
        CharSequence selfName = x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME);
        if (messages != null && messages.length > 0) {
            String conv = pkg + "|" + chat;
            long since;
            synchronized (lastTime) {
                Long t = lastTime.get(conv);
                since = t == null ? -1 : t;
            }
            long newest = since;
            int start = Math.max(0, messages.length - 5);
            for (int i = start; i < messages.length; i++) {
                if (!(messages[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) messages[i];
                CharSequence t = m.getCharSequence("text");
                if (t == null || t.toString().trim().isEmpty()) continue;
                long time = m.getLong("time", 0);
                // The first time a chat is seen, only its newest message; then whatever's newer.
                if (since < 0 ? i != messages.length - 1 : time != 0 && time <= since) continue;
                if (time > newest) newest = time;
                CharSequence s = m.getCharSequence("sender");
                Object person = m.get("sender_person");
                if (s == null && person instanceof Person && android.os.Build.VERSION.SDK_INT >= 28) {
                    s = ((Person) person).getName();
                }
                boolean self = (s == null && selfKnown) || (s != null && selfName != null && s.toString().equals(selfName.toString()));
                if (self) continue;
                out.add(new In(s != null ? s.toString() : chat, t.toString().trim(), chat, time));
            }
            if (newest > since) synchronized (lastTime) {
                lastTime.put(conv, newest);
            }
            return out;
        }
        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        CharSequence t = lines != null && lines.length > 0 ? lines[lines.length - 1] : null;
        if (t == null) t = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (t == null) t = x.getCharSequence(Notification.EXTRA_TEXT);
        if (t != null && !t.toString().trim().isEmpty()) {
            String line = t.toString().trim();
            String from = chat;
            // A summary like "2 new messages" with lines "Elizabeth: ¿Vienes?": the name is the chat.
            java.util.regex.Matcher m = SUMMARY_LINE.matcher(line);
            if (m.matches() && (chat.isEmpty() || COUNT_TITLE.matcher(chat).find())) {
                from = m.group(1).trim();
                line = m.group(2).trim();
                chat = from;
            }
            out.add(new In(from, line, chat, 0));
        }
        return out;
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

        java.util.List<In> ins = incoming(x, pkg);
        if (ins.isEmpty()) {
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) == 0 && messages == null) {
                note(this, appName(pkg) + ": a message with no text to read");
            }
            return;
        }
        final String key = Prefs.get(this, Prefs.API_KEY, "");
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        final String app = appName(pkg);
        final String fPkg = test ? getPackageName() : pkg;
        final PendingIntent open = test ? null : n.contentIntent;
        final Context ctx = getApplicationContext();
        for (In in : ins) {
            String text = in.text.length() > 1500 ? in.text.substring(0, 1500) : in.text;
            // His own message (what Murmur just sent in their language) shows up in some chat notifications too.
            if (!test && Translate.wasSent(this, text)) {
                note(this, app + ": skipped your own message");
                diag(this, Prefs.DIAG_REPLY, app + " · " + in.chat + ": skipped, it matches a message you just sent");
                continue;
            }
            if (!claim(pkg, text)) continue;
            if (key.isEmpty()) {
                note(this, "no API key, so nothing was translated");
                return;
            }
            final String fFrom = in.from, fChat = in.chat, fText = text;
            note(this, app + ": translating a message from " + (fFrom.isEmpty() ? "someone" : fFrom) + "…");
            worker.execute(() -> handle(ctx, base, key, model, mine, fPkg, fChat, fFrom, fText, app, open, false));
        }
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
            // 2.0: every reply is translated, with no "already in English" way out for the
            // translator, which used to leave some Spanish replies as they were. One more try
            // when it still comes back the same and plainly isn't English.
            String out = AiClient.toMineForced(base, key, model, text, mine);
            if ((out.isEmpty() || Translate.sameText(out, text)) && Translate.looksForeign(text, mine)) {
                out = AiClient.toMineForced(base, key, model, text, mine);
            }
            boolean asIs = out.isEmpty() || Translate.sameText(out, text);
            String who = from == null || from.isEmpty() ? (chatName == null || chatName.isEmpty() ? "someone" : chatName) : from;
            // Already in his language: it still goes in the chat's thread, so the bar shows every reply.
            if (asIs) out = text;
            Translate.saveIncoming(ctx, from, out, app);
            ChatLog.Chat ch = ChatLog.add(ctx, pkg, app, chatName, from, text, out, false);
            diag(ctx, Prefs.DIAG_REPLY, app + " · " + who + ": " + (asIs ? "already in " + mine + ", shown as is" : "translated to " + mine));
            if (onScreen) {
                MurmurChatReader.bubble(ch.key);
                note(ctx, app + (asIs ? ": a new message already in " + mine : ": translated a new message in the open chat"));
                return;
            }
            if (asIs) {
                note(ctx, app + ": already in " + mine + ", left as is");
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
