package com.zelvari.murmur;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Texting mode, open chat: WhatsApp and most chat apps post no notification for a reply in the
 * chat that's open on screen, so the notification reader never sees it. This service reads only
 * the message bubbles of the open chat in a few chat apps, and only while texting mode is on.
 * The messages already on screen when a chat opens are left alone; only new ones are translated.
 */
public class MurmurChatReader extends AccessibilityService {
    /** Message bubble text views in the chat apps it reads. */
    private static final String[] BUBBLE_IDS = {
            "com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text",
            "com.google.android.apps.messaging:id/message_text",
            "com.samsung.android.messaging:id/content_text_view",
            "com.samsung.android.messaging:id/message_text",
            "org.telegram.messenger:id/message_text",
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> onScreen = new HashSet<>();
    private String chatPkg = "";
    private long quietUntil;

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(c, MurmurChatReader.class).flattenToString());
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private String pendingPkg;
    private boolean pendingReset;
    private final Runnable scan = this::scan;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null || !Translate.texting(this)) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();
        long now = System.currentTimeMillis();
        // A new screen or a scroll: what's showing then counts as already read.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || !pkg.equals(chatPkg)) {
            pendingReset = true;
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) quietUntil = now + 1200;
        }
        pendingPkg = pkg;
        // Wait for the burst of changes to settle, then look once.
        ui.removeCallbacks(scan);
        ui.postDelayed(scan, 350);
    }

    private void scan() {
        String pkg = pendingPkg;
        if (pkg == null) return;
        boolean reset = pendingReset || System.currentTimeMillis() < quietUntil;
        pendingReset = false;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return;
        }
        if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;
        List<String> bubbles = new ArrayList<>();
        try {
            for (String id : BUBBLE_IDS) {
                if (!id.startsWith(pkg + ":")) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    if (t != null && t.toString().trim().length() > 0) bubbles.add(t.toString().trim());
                }
            }
        } catch (RuntimeException ignored) {
            return;
        }
        if (!pkg.equals(chatPkg)) onScreen.clear();
        chatPkg = pkg;
        if (bubbles.isEmpty()) return;
        if (reset || onScreen.isEmpty()) {
            if (reset) onScreen.clear();
            onScreen.addAll(bubbles);
            return;
        }
        // Only the newest bubble at the bottom counts as a new reply.
        String newest = bubbles.get(bubbles.size() - 1);
        boolean fresh = !onScreen.contains(newest);
        onScreen.addAll(bubbles);
        if (onScreen.size() > 400) {
            onScreen.clear();
            onScreen.addAll(bubbles);
        }
        if (!fresh || Translate.wasSent(this, newest) || newest.length() > 1500) return;
        if (!MurmurNotifications.claim(newest)) return;

        final String apiKey = Prefs.get(this, Prefs.API_KEY, "");
        if (apiKey.isEmpty()) {
            MurmurNotifications.note(this, "no API key, so nothing was translated");
            return;
        }
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        final String app = label(pkg.toString());
        final Context ctx = getApplicationContext();
        MurmurNotifications.note(this, app + " (open chat): translating a new message…");
        worker.execute(() -> MurmurNotifications.handle(ctx, base, apiKey, model, mine, "", newest, app, null));
    }

    private String label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
