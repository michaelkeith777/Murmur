package com.zelvari.murmur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Texting mode, incoming side: when a message notification arrives in another language, Murmur
 * translates it into his language, posts the translation as its own notification and shows it
 * in the keyboard's top bar. It reads only message notifications, and only while texting mode
 * is on.
 */
public class MurmurNotifications extends NotificationListenerService {
    private static final String CHANNEL = "translations";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Messages already handled, so an updated notification isn't translated twice. */
    private final ArrayDeque<String> seen = new ArrayDeque<>();
    private int nextId = 1000;

    static boolean allowed(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, MurmurNotifications.class).flattenToString());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!Translate.texting(this) || sbn == null) return;
        if (getPackageName().equals(sbn.getPackageName()) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null) return;

        String from = null, text = null;
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            CharSequence t = last.getCharSequence("text");
            CharSequence s = last.getCharSequence("sender");
            Object person = last.get("sender_person");
            if (s == null && person instanceof android.app.Person) s = ((android.app.Person) person).getName();
            // A message with no sender is one he sent himself.
            if (s == null) return;
            from = s.toString();
            text = t == null ? null : t.toString();
        } else if (Notification.CATEGORY_MESSAGE.equals(n.category)) {
            CharSequence t = x.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            from = title == null ? "" : title.toString();
            text = t == null ? null : t.toString();
        }
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty() || text.length() > 1500) return;

        String mark = sbn.getPackageName() + "\n" + from + "\n" + text;
        synchronized (seen) {
            if (seen.contains(mark)) return;
            seen.addLast(mark);
            while (seen.size() > 40) seen.removeFirst();
        }

        final String key = Prefs.get(this, Prefs.API_KEY, "");
        if (key.isEmpty()) return;
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        final String fFrom = from, fText = text, app = sbn.getPackageName();
        final android.app.PendingIntent open = n.contentIntent;
        worker.execute(() -> {
            try {
                String out = AiClient.toMine(base, key, model, fText, mine);
                if (out.isEmpty()) return;
                Translate.saveIncoming(this, fFrom, out, app);
                post(fFrom, out, fText, open);
            } catch (Exception ignored) {
                // a missed translation is better than a noisy error notification
            }
        });
    }

    private void post(String from, String english, String original, android.app.PendingIntent open) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Translated messages",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Texting mode: messages you get in another language, in yours");
            nm.createNotificationChannel(ch);
        }
        String title = (from == null || from.isEmpty() ? "Message" : from) + " · translated";
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(english)
                .setStyle(new Notification.BigTextStyle().bigText(english + "\n\n“" + original + "”"))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true);
        if (open != null) nb.setContentIntent(open);
        try {
            nm.notify(nextId++, nb.build());
        } catch (SecurityException ignored) {
            // notifications are turned off for Murmur; the keyboard still shows it
        }
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
