package com.zelvari.shiftdeck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * Shows ShiftDeck notifications:
 *  - "remind": a normal reminder
 *  - "soon":   a live countdown ("due in 14:59") before something is due
 *  - "due":    a ringing alarm that keeps going until you open it or tap Stop
 * Re-arms everything after a reboot or app update.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.zelvari.shiftdeck.REMINDER";
    static final String ACTION_STOP = "com.zelvari.shiftdeck.STOP_ALARM";
    private static final String CH_REMIND = "reminders";
    private static final String CH_TIMER = "timers";
    private static final String CH_ALARM = "due_alarms";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Reminders.restore(c);
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String id = intent.getStringExtra("id");
        int nid = id == null ? (int) System.currentTimeMillis() : id.hashCode();

        if (ACTION_STOP.equals(action)) {
            nm.cancel(nid);
            if (id != null) Reminders.addAck(c, id, intent.getLongExtra("due", 0));
            return;
        }
        if (!ACTION_FIRE.equals(action)) return;

        String kind = intent.getStringExtra("kind");
        if (kind == null) kind = "remind";
        // When the app is open, its own countdown pop-up and alarm take over.
        if (MainActivity.visible && !"remind".equals(kind)) return;

        createChannels(nm);
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        long due = intent.getLongExtra("due", System.currentTimeMillis());
        if (title == null) title = "ShiftDeck";
        if (body == null) body = "";

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if ("due".equals(kind) && id != null) open.putExtra("alarm", id);
        PendingIntent tap = PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if ("soon".equals(kind)) {
            b = new Notification.Builder(c, CH_TIMER)
                    .setContentTitle("⏱ Coming up: " + title)
                    .setContentText(body)
                    .setWhen(due)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(Math.max(1000, due - System.currentTimeMillis()))
                    .setCategory(Notification.CATEGORY_REMINDER);
        } else if ("due".equals(kind)) {
            Intent stop = new Intent(c, ReminderReceiver.class).setAction(ACTION_STOP)
                    .setData(Uri.parse("shiftdeck://stop/" + Uri.encode(id == null ? "" : id)))
                    .putExtra("id", id).putExtra("due", due);
            PendingIntent stopPi = PendingIntent.getBroadcast(c, nid, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b = new Notification.Builder(c, CH_ALARM)
                    .setContentTitle("⏰ Due now: " + title)
                    .setContentText(body + " · tap to mark it done")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setFullScreenIntent(tap, true)
                    .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                    .addAction(new Notification.Action.Builder(null, "Open", tap).build());
        } else {
            b = new Notification.Builder(c, CH_REMIND)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setCategory(Notification.CATEGORY_REMINDER);
        }
        Notification n = b.setSmallIcon(R.drawable.ic_notif)
                .setColor(Color.parseColor("#FF6200"))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        if ("due".equals(kind)) n.flags |= Notification.FLAG_INSISTENT; // keep ringing until handled
        try {
            if ("due".equals(kind) && id != null) nm.cancel(("s" + id.substring(1)).hashCode());
            nm.notify(nid, n);
        } catch (SecurityException ignored) { }
    }

    private static void createChannels(NotificationManager nm) {
        NotificationChannel r = new NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("Shift, event and to-do reminders");
        r.enableVibration(true);
        nm.createNotificationChannel(r);

        NotificationChannel t = new NotificationChannel(CH_TIMER, "Countdown timers", NotificationManager.IMPORTANCE_HIGH);
        t.setDescription("Live countdown before something is due");
        t.enableVibration(true);
        nm.createNotificationChannel(t);

        NotificationChannel a = new NotificationChannel(CH_ALARM, "Due alarms", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Rings when a to-do or event is due");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        a.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        a.enableVibration(true);
        a.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        a.enableLights(true);
        a.setLightColor(Color.parseColor("#FF6200"));
        a.setBypassDnd(true);
        nm.createNotificationChannel(a);
    }
}
