package com.zelvari.murmur;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
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
    private final Set<String> onScreen = new java.util.LinkedHashSet<>();

    /** True while Android has the reader running (it's on and connected). */
    static boolean running() {
        return instance != null;
    }

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(c, MurmurChatReader.class).flattenToString());
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private String pendingPkg;
    private final Runnable scan = this::scan;
    /** The chat whose bubbles are in {@link #onScreen}. */
    private String chatKey = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null || !Translate.texting(this)) return;
        pendingPkg = e.getPackageName().toString();
        // Wait for the burst of changes to settle, then look once.
        ui.removeCallbacks(scan);
        ui.postDelayed(scan, 350);
    }

    /** One message bubble on screen, and whether it's one he sent. */
    private static final class Bubble {
        final String text;
        final boolean mine;

        Bubble(String text, boolean mine) {
            this.text = text;
            this.mine = mine;
        }
    }

    /**
     * Finds new replies in the open chat. A reply is new when it's at the bottom of the chat and
     * wasn't on screen before, while a message above it was: that's how a chat grows. Opening a
     * chat or scrolling up only shows messages already there, so those are left alone. (1.8 also
     * started over whenever the chat scrolled, and WhatsApp scrolls each time a reply comes in,
     * so replies were missed.)
     */
    private void scan() {
        String pkg = pendingPkg;
        if (pkg == null) return;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return;
        }
        if (root == null || root.getPackageName() == null || !pkg.equals(root.getPackageName().toString())) return;
        List<Bubble> bubbles = new ArrayList<>();
        try {
            for (String id : BUBBLE_IDS) {
                if (!id.startsWith(pkg + ":")) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    if (t != null && t.toString().trim().length() > 0) {
                        bubbles.add(new Bubble(t.toString().trim(), sentByHim(n, pkg)));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return;
        }
        // Only a chat screen (one with a box to type in) counts, not the list of chats.
        boolean chatScreen = !bubbles.isEmpty() || hasTypingBox(root, 0);
        if (bubbles.isEmpty() && chatScreen) {
            List<String> texts = new ArrayList<>();
            collectFallback(root, texts, 0);
            for (String t : texts) bubbles.add(new Bubble(t, false));
        }
        // Tells the keyboard which chat is open, so its strip shows that chat's thread.
        final String from = chatScreen ? chatName(root, pkg) : "";
        ChatLog.setOpen(this, pkg, from);
        if (!chatScreen) return;
        String key = ChatLog.key(pkg, from);
        if (!key.equals(chatKey)) {
            // Another chat: what's showing now is already read.
            chatKey = key;
            onScreen.clear();
            for (Bubble b : bubbles) onScreen.add(b.text);
            MurmurNotifications.diag(this, Prefs.DIAG_READING, label(pkg) + " · " + (from.isEmpty() ? "a chat" : from)
                    + " (" + bubbles.size() + " messages on screen)");
            return;
        }
        if (bubbles.isEmpty()) return;

        // The new messages at the bottom, if a message above them was already on screen.
        int first = bubbles.size();
        while (first > 0 && !onScreen.contains(bubbles.get(first - 1).text)) first--;
        boolean anchored = first > 0;
        List<String> fresh = new ArrayList<>();
        if (anchored) for (int i = first; i < bubbles.size(); i++) {
            Bubble b = bubbles.get(i);
            if (!b.mine && !Translate.wasSent(this, b.text) && b.text.length() <= 1500) fresh.add(b.text);
        }
        for (Bubble b : bubbles) onScreen.add(b.text);
        if (onScreen.size() > 600) {
            onScreen.clear();
            for (Bubble b : bubbles) onScreen.add(b.text);
        }
        if (fresh.isEmpty()) return;
        // A burst of several replies at once: the last three are plenty.
        while (fresh.size() > 3) fresh.remove(0);

        final String apiKey = Prefs.get(this, Prefs.API_KEY, "");
        if (apiKey.isEmpty()) {
            MurmurNotifications.note(this, "no API key, so nothing was translated");
            return;
        }
        ChatLog.Chat ch = ChatLog.get(this, key);
        if (ch != null && ch.off) {
            MurmurNotifications.note(this, "Live Translate is off for " + ch.title());
            return;
        }
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        final String app = label(pkg);
        final Context ctx = getApplicationContext();
        for (String text : fresh) {
            if (!MurmurNotifications.claim(text)) continue;
            MurmurNotifications.diag(this, Prefs.DIAG_SEEN, app + " · " + (from.isEmpty() ? "someone" : from) + ": "
                    + (text.length() > 60 ? text.substring(0, 60) + "…" : text));
            MurmurNotifications.note(this, app + " (open chat): translating a new message…");
            worker.execute(() -> MurmurNotifications.handle(ctx, base, apiKey, model, mine, pkg, from, from, text, app, null, true));
        }
    }

    /**
     * WhatsApp marks his own messages with delivery ticks; theirs have none. Looks for the ticks
     * in the bubble's row, without leaving the row.
     */
    private static boolean sentByHim(AccessibilityNodeInfo n, String pkg) {
        if (!pkg.startsWith("com.whatsapp")) return false;
        try {
            AccessibilityNodeInfo row = n;
            for (int i = 0; i < 5; i++) {
                AccessibilityNodeInfo p = row.getParent();
                if (p == null) break;
                CharSequence cls = p.getClassName();
                if (cls != null && (cls.toString().contains("RecyclerView") || cls.toString().contains("ListView"))) break;
                row = p;
            }
            if (row == n) return false;
            List<AccessibilityNodeInfo> ticks = row.findAccessibilityNodeInfosByViewId(pkg + ":id/status");
            return ticks != null && !ticks.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final java.util.regex.Pattern NOT_A_MESSAGE = java.util.regex.Pattern.compile(
            "(?i)^(\\d{1,2}:\\d{2}( ?[ap]\\.? ?m\\.?)?|today|yesterday|hoy|ayer|\\d+ unread messages?|message|mensaje|"
            + "edited|editado|online|en l[ií]nea|typing…|escribiendo…|\\d+:\\d+)$");

    /**
     * When an app update renames its bubble views, fall back to the text inside the chat's
     * scrolling list, skipping times, dates, buttons and the box he types in.
     */
    private void collectFallback(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (n.isEditable() || cls.endsWith("EditText") || cls.endsWith("Button")) return;
        boolean inList = depth > 0 && (cls.contains("RecyclerView") || cls.contains("ListView"));
        if (inList) {
            collectTexts(n, out, 0);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                collectFallback(n.getChild(i), out, depth + 1);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void collectTexts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 30) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (n.isEditable() || cls.endsWith("Button") || cls.endsWith("ImageView")) return;
        CharSequence t = n.getText();
        if (t != null && cls.endsWith("TextView")) {
            String s = t.toString().trim();
            if (s.length() > 1 && !NOT_A_MESSAGE.matcher(s).matches()) out.add(s);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                collectTexts(n.getChild(i), out, depth + 1);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean hasTypingBox(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return false;
        if (n.isEditable()) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                if (hasTypingBox(n.getChild(i), depth + 1)) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** The open chat's name from the app's title bar, if it can be found. */
    private String chatName(AccessibilityNodeInfo root, String pkg) {
        String[] ids = {pkg + ":id/conversation_contact_name", pkg + ":id/conversation_title",
                pkg + ":id/toolbar_title", pkg + ":id/title"};
        for (String id : ids) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty() && nodes.get(0).getText() != null) {
                    return nodes.get(0).getText().toString().trim();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    // ---- floating bubble -------------------------------------------------------------

    private static MurmurChatReader instance;
    private android.view.View bubble;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /**
     * Shows the chat's newest message in his language at the top of the screen while the keyboard
     * is closed (the keyboard's strip shows it when it's open). It stays until he closes it; a tap
     * opens the chat's recent messages, and the next message updates it.
     */
    static void bubble(String chatKey) {
        MurmurChatReader s = instance;
        if (s == null || MurmurIME.inputShown) return;
        s.ui.post(() -> s.showBubble(chatKey, s.bubbleKey != null && s.bubbleKey.equals(chatKey) && s.bubbleOpen));
    }

    private String bubbleKey;
    private boolean bubbleOpen;

    private void showBubble(String chatKey, boolean expanded) {
        ChatLog.Chat ch = ChatLog.get(this, chatKey);
        if (ch == null || ch.msgs.isEmpty()) return;
        removeBubble();
        android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
        if (wm == null) return;
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * d);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF22E1065);
        bg.setCornerRadius(pad * 1.6f);
        box.setBackground(bg);

        android.widget.TextView t = new android.widget.TextView(this);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        t.setLineSpacing(2 * d, 1f);
        StringBuilder b = new StringBuilder();
        int count = expanded ? 12 : 1;
        for (int i = Math.max(0, ch.msgs.size() - count); i < ch.msgs.size(); i++) {
            ChatLog.Msg m = ch.msgs.get(i);
            if (b.length() > 0) b.append("\n");
            String who = m.mine ? "You" : m.from.isEmpty() ? ch.title() : m.from;
            b.append(who).append(": ").append(m.mine && !m.orig.isEmpty() ? m.orig : m.text);
        }
        t.setText("💬 " + b);
        if (!expanded) t.setMaxLines(4);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(t);
        box.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                expanded ? Math.round(240 * d) : android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        if (expanded) scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setGravity(android.view.Gravity.END);
        row.addView(bubbleButton(expanded ? "▴ Less" : "▾ More", v -> {
            bubbleOpen = !expanded;
            showBubble(chatKey, !expanded);
        }));
        row.addView(bubbleButton("Open in Murmur", v -> {
            removeBubble();
            try {
                MurmurNotifications.openChat(this, chatKey).send();
            } catch (Exception ignored) {
            }
        }));
        row.addView(bubbleButton("✕ Close", v -> removeBubble()));
        box.addView(row);
        t.setOnClickListener(v -> {
            bubbleOpen = !expanded;
            showBubble(chatKey, !expanded);
        });

        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP;
        lp.y = Math.round(86 * d);
        lp.horizontalMargin = 0.03f;
        try {
            wm.addView(box, lp);
            bubble = box;
            bubbleKey = chatKey;
            bubbleOpen = expanded;
        } catch (RuntimeException ignored) {
        }
    }

    private android.widget.TextView bubbleButton(String label, android.view.View.OnClickListener l) {
        android.widget.TextView b = new android.widget.TextView(this);
        b.setText(label);
        b.setTextColor(0xFFDDD6FE);
        b.setTextSize(14);
        int p = Math.round(10 * getResources().getDisplayMetrics().density);
        b.setPadding(p, p, p, p / 2);
        b.setOnClickListener(l);
        return b;
    }

    private void removeBubble() {
        if (bubble == null) return;
        try {
            getSystemService(android.view.WindowManager.class).removeView(bubble);
        } catch (RuntimeException ignored) {
        }
        bubble = null;
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
        removeBubble();
        instance = null;
        worker.shutdownNow();
        super.onDestroy();
    }
}
