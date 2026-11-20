package com.zelvari.shiftdeck;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps phone alarms in step with the reminder list the app sends over. */
final class Reminders {
    private static final String PREFS = "reminders";
    private static final String KEY = "list";

    private Reminders() { }

    /** Replace all scheduled reminders with the given JSON list [{id,t,title,body}]. */
    static synchronized void sync(Context c, String json) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancelAll(c, p.getString(KEY, "[]"));
        p.edit().putString(KEY, json == null ? "[]" : json).apply();
        scheduleAll(c, json);
    }

    /** Re-arm after a reboot or app update. */
    static synchronized void restore(Context c) {
        String json = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        scheduleAll(c, json);
    }

    private static final String ACKS = "acks";

    /** Remember that the user stopped an alarm from the notification, so the app won't ring it again. */
    static synchronized void addAck(Context c, String id, long due) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString(ACKS, "[]"));
            a.put(new JSONObject().put("id", id).put("due", due));
            p.edit().putString(ACKS, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static synchronized String takeAcks(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(ACKS, "[]");
        p.edit().putString(ACKS, "[]").apply();
        return v;
    }

    private static PendingIntent pending(Context c, String id, String title, String body) {
        return pending(c, id, title, body, "remind", 0);
    }

    private static PendingIntent pending(Context c, String id, String title, String body, String kind, long due) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_FIRE);
        i.setData(Uri.parse("shiftdeck://reminder/" + Uri.encode(id)));
        if (title != null) i.putExtra("title", title);
        if (body != null) i.putExtra("body", body);
        i.putExtra("id", id);
        i.putExtra("kind", kind);
        i.putExtra("due", due);
        return PendingIntent.getBroadcast(c, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int k = 0; k < a.length(); k++) {
                String id = a.getJSONObject(k).optString("id");
                if (!id.isEmpty()) am.cancel(pending(c, id, null, null));
            }
        } catch (Exception ignored) { }
    }

    private static void scheduleAll(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || json == null) return;
        long now = System.currentTimeMillis();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            JSONArray a = new JSONArray(json);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                long t = o.optLong("t");
                String id = o.optString("id");
                if (t <= now || id.isEmpty()) continue;
                PendingIntent pi = pending(c, id, o.optString("title", "ShiftDeck"), o.optString("body", ""),
                        o.optString("kind", "remind"), o.optLong("due", t));
                try {
                    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
                } catch (SecurityException se) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
                }
            }
        } catch (Exception ignored) { }
    }
}
