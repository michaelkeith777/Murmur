package com.zelvari.shiftdeck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

/** Shows reminder notifications, and re-arms them after reboot / update. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.zelvari.shiftdeck.REMINDER";
    private static final String CHANNEL = "reminders";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminders.restore(c);
            return;
        }
        if (!ACTION_FIRE.equals(action)) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Shift, event and to-do reminders");
        ch.enableVibration(true);
        ch.enableLights(true);
        ch.setLightColor(Color.parseColor("#FF6200"));
        nm.createNotificationChannel(ch);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String id = intent.getStringExtra("id");
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(Color.parseColor("#FF6200"))
                .setContentTitle(title == null ? "ShiftDeck" : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        try {
            nm.notify(id == null ? (int) System.currentTimeMillis() : id.hashCode(), n);
        } catch (SecurityException ignored) { }
    }
}
