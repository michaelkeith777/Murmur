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
    private final NewReplies replies = new NewReplies();

    /** True while Android has the reader running (it's on and connected). */
    static boolean running() {
        return instance != null;
    }

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(new ComponentName(c, MurmurChatReader.class).flattenToString());
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable scan = this::scan;
    /** The chat whose bubbles {@link #replies} follows. */
    private String chatKey = "";

    /** The chat apps whose open chat it reads (events come only from these, see chat_reader.xml). */
    private static final String[] APPS = {
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.android.mms", "org.telegram.messenger", "com.facebook.orca",
    };

    static boolean reads(String pkg) {
        for (String a : APPS) if (a.equals(pkg)) return true;
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || !Translate.texting(this)) return;
        // Wait for the burst of changes to settle, then look once.
        ui.removeCallbacks(scan);
        ui.postDelayed(scan, 350);
    }

    /** One message bubble on screen, and whether it's one he sent. */
    private static final class Bubble {
        final String text;
        final boolean mine;
        final int top;

        Bubble(String text, boolean mine, int top) {
            this.text = text;
            this.mine = mine;
            this.top = top;
        }
    }

    /** While the keyboard is up in a chat, it looks again every few seconds, events or not. */
    static final long POLL_MS = 2500;

    /**
     * Finds the other person's replies in the open chat and translates the ones not yet in the
     * chat's thread. Two ways, so a reply is never missed: a new message that appears at the
     * bottom (as before), and the other person's messages below his last one, which are checked
     * against the thread. 1.9.6 had only the first, and on his phone a WhatsApp reply that came
     * in while the chat was open was never caught.
     */
    private void scan() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return;
        }
        if (root == null || root.getPackageName() == null) return;
        String pkg = root.getPackageName().toString();
        // Murmur's own keyboard or the status bar on top isn't another chat: keep the one open.
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") || !reads(pkg)) return;
        if (!Translate.texting(this)) return;
        ui.removeCallbacks(scan);
        if (MurmurIME.inputShown) ui.postDelayed(scan, POLL_MS);
        int sw = screenW();
        List<Bubble> bubbles = new ArrayList<>();
        boolean byId = false;
        try {
            for (String id : BUBBLE_IDS) {
                if (!id.startsWith(pkg + ":")) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    if (t != null && t.toString().trim().length() > 0) {
                        android.graphics.Rect r = new android.graphics.Rect();
                        n.getBoundsInScreen(r);
                        bubbles.add(new Bubble(t.toString().trim(), sentByHim(n, pkg, sw), r.top));
                        byId = true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return;
        }
        // Only a chat screen (one with a box to type in) counts, not the list of chats.
        boolean chatScreen = !bubbles.isEmpty() || hasTypingBox(root, 0);
        if (bubbles.isEmpty() && chatScreen) collectFallback(root, bubbles, 0);
        // Top to bottom, whatever order the app lists them in.
        java.util.Collections.sort(bubbles, (x, y) -> Integer.compare(x.top, y.top));
        // Tells the keyboard which chat is open, so its strip shows that chat's thread.
        final String from = chatScreen ? steadyName(root, pkg) : "";
        if (!chatScreen) nameWin = "";
        ChatLog.setOpen(this, pkg, from, chatScreen && nameSure);
        if (!chatScreen) return;
        String key = ChatLog.key(pkg, from);
        if (awayFrom != null && awayFrom.equals(key) && bubble == null && !MurmurIME.inputShown) {
            awayFrom = null;
            showBubble(key, bubbleOpen);
        }
        List<String> texts = new ArrayList<>();
        for (Bubble bb : bubbles) texts.add(bb.text);
        boolean newChat = !key.equals(chatKey);
        chatKey = key;
        readingDiag(pkg, from, bubbles, byId);
        if (bubbles.isEmpty()) return;
        // Lines the screen up with the bottom of the chat as last seen. A different chat name
        // with the same messages on screen is the same chat read differently, so nothing is lost.
        List<Integer> idx = replies.isEmpty() ? null : replies.update(texts, newChat);
        if (idx == null) {
            replies.reset(texts);
            idx = new ArrayList<>();
        }
        List<String> fresh = new ArrayList<>();
        for (int i : idx) {
            Bubble bb = bubbles.get(i);
            if (bb.text.length() > 1500) continue;
            if (bb.mine) continue;
            if (Translate.wasSent(this, bb.text)) {
                MurmurNotifications.diag(this, Prefs.DIAG_REPLY, label(pkg) + " · " + (from.isEmpty() ? "a chat" : from)
                        + ": skipped, it's the same as a message you just sent");
                continue;
            }
            fresh.add(bb.text);
        }
        ChatLog.Chat ch = ChatLog.find(this, pkg, from);
        for (String t : lastTheirs(bubbles, ch == null ? null : ch.msgs)) {
            if (!fresh.contains(t) && !Translate.wasSent(this, t)) fresh.add(t);
        }
        // A burst of several replies at once: the last three are plenty.
        while (fresh.size() > 3) fresh.remove(0);
        // Just turned on (or a chat opened since): the other person's earlier messages on screen
        // that were never translated, so a message from before he turned it on shows in English too.
        String once = key + "@" + Prefs.sp(this).getLong(Prefs.TRANSLATE_SINCE, 0);
        if (nameSure && !from.isEmpty() && !once.equals(caughtUp) && !caughtUpKeys.contains(once)) {
            caughtUpKeys.add(once);
            caughtUp = once;
            List<String> earlier = new ArrayList<>();
            for (String t : earlierTheirs(bubbles, ch == null ? null : ch.msgs)) {
                if (!fresh.contains(t) && !Translate.wasSent(this, t)) earlier.add(t);
            }
            fresh.addAll(0, earlier);
        }
        if (fresh.isEmpty()) return;

        final String apiKey = Prefs.get(this, Prefs.API_KEY, "");
        if (apiKey.isEmpty()) {
            MurmurNotifications.note(this, "no API key, so nothing was translated");
            return;
        }
        String b = Prefs.get(this, Prefs.BASE_URL, Prefs.DEF_BASE_URL);
        final String base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        final String model = Prefs.get(this, Prefs.LLM_MODEL, Prefs.DEF_LLM_MODEL);
        final String mine = Translate.mine(this);
        final String app = label(pkg);
        final Context ctx = getApplicationContext();
        for (String text : fresh) {
            if (!MurmurNotifications.claim(pkg, text)) continue;
            MurmurNotifications.diag(this, Prefs.DIAG_SEEN, app + " · " + (from.isEmpty() ? "someone" : from) + ": "
                    + (text.length() > 60 ? text.substring(0, 60) + "…" : text));
            MurmurNotifications.note(this, app + " (open chat): translating a new message…");
            worker.execute(() -> MurmurNotifications.handle(ctx, base, apiKey, model, mine, pkg, from, from, text, app, null, true));
        }
    }

    /**
     * The other person's messages at the bottom of the chat, below his last one (at most three),
     * that the chat's thread doesn't have yet among their replies since his last message there.
     */
    static List<String> lastTheirs(List<Bubble> bubbles, List<ChatLog.Msg> log) {
        List<String> out = new ArrayList<>();
        for (int i = bubbles.size() - 1; i >= 0 && out.size() < 3; i--) {
            Bubble bb = bubbles.get(i);
            if (bb.mine) break;
            if (bb.text.length() <= 1500 && !NOT_A_MESSAGE.matcher(bb.text).matches()) out.add(0, bb.text);
        }
        if (out.isEmpty() || log == null) return out;
        // Their replies already in the thread, back to his last message there (or the last 12).
        java.util.Set<String> have = new java.util.HashSet<>();
        for (int i = log.size() - 1, n = 0; i >= 0 && n < 12; i--, n++) {
            ChatLog.Msg m = log.get(i);
            if (m.mine) break;
            have.add(m.orig.trim().replaceAll("\\s+", " "));
        }
        List<String> keep = new ArrayList<>();
        for (String t : out) if (!have.contains(t.trim().replaceAll("\\s+", " "))) keep.add(t);
        return keep;
    }

    private String caughtUp = "";
    private final java.util.Set<String> caughtUpKeys = new java.util.HashSet<>();

    /** How many of the other person's earlier messages on screen are translated when it turns on. */
    static final int CATCH_UP = 8;

    /**
     * The other person's messages anywhere on screen (the newest {@link #CATCH_UP}) that the
     * chat's thread doesn't have yet, oldest first.
     */
    static List<String> earlierTheirs(List<Bubble> bubbles, List<ChatLog.Msg> log) {
        java.util.Set<String> have = new java.util.HashSet<>();
        if (log != null) for (ChatLog.Msg m : log) if (!m.mine) have.add(m.orig.trim().replaceAll("\\s+", " "));
        List<String> out = new ArrayList<>();
        for (int i = bubbles.size() - 1; i >= 0 && out.size() < CATCH_UP; i--) {
            Bubble bb = bubbles.get(i);
            if (bb.mine || bb.text.length() > 1500 || NOT_A_MESSAGE.matcher(bb.text).matches()) continue;
            String n = bb.text.trim().replaceAll("\\s+", " ");
            if (have.contains(n) || out.contains(bb.text)) continue;
            out.add(0, bb.text);
        }
        return out;
    }

    /** For the tests: {@link #earlierTheirs} on plain lists, his bubbles marked with "me:". */
    static List<String> earlierTheirsOf(List<String> screen, List<ChatLog.Msg> log) {
        List<Bubble> bs = new ArrayList<>();
        for (int i = 0; i < screen.size(); i++) {
            String s = screen.get(i);
            boolean me = s.startsWith("me:");
            bs.add(new Bubble(me ? s.substring(3) : s, me, i));
        }
        return earlierTheirs(bs, log);
    }

    /** For the tests: the same check on plain lists, his bubbles marked with a leading "me:". */
    static List<String> lastTheirsOf(List<String> screen, List<ChatLog.Msg> log) {
        List<Bubble> bs = new ArrayList<>();
        for (int i = 0; i < screen.size(); i++) {
            String s = screen.get(i);
            boolean me = s.startsWith("me:");
            bs.add(new Bubble(me ? s.substring(3) : s, me, i));
        }
        return lastTheirs(bs, log);
    }

    private String lastReading = "";

    /** What it reads in the open chat, for the Live Translate screen, when that changes. */
    private void readingDiag(String pkg, String from, List<Bubble> bubbles, boolean byId) {
        int theirs = 0;
        String newest = "";
        for (Bubble bb : bubbles) if (!bb.mine) {
            theirs++;
            newest = bb.text;
        }
        String line = label(pkg) + " · " + (from.isEmpty() ? "a chat" : from) + (nameSure ? "" : " (name not sure)")
                + ": " + bubbles.size() + " on screen, " + theirs + " theirs, " + (bubbles.size() - theirs) + " yours"
                + (byId ? "" : " (read the whole chat)")
                + (newest.isEmpty() ? "" : " · their newest: " + (newest.length() > 40 ? newest.substring(0, 40) + "…" : newest));
        if (line.equals(lastReading)) return;
        lastReading = line;
        MurmurNotifications.diag(this, Prefs.DIAG_READING, line);
    }

    private int screenW() {
        try {
            return getResources().getDisplayMetrics().widthPixels;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Whether a bubble is one he sent. Every chat app puts his messages on the right and theirs on
     * the left, so that decides it in any app. 1.9.5 looked only for WhatsApp's delivery ticks
     * near the bubble, and when its search took in a wider part of the screen, a reply from the
     * other person could be taken for his and never translated. The ticks still decide a bubble
     * as wide as the chat, where the side can't tell.
     */
    static boolean sentByHim(AccessibilityNodeInfo n, String pkg, int screenW) {
        try {
            Boolean side = sideOf(n, screenW);
            if (side != null) return side;
            if (!pkg.startsWith("com.whatsapp")) return false;
            AccessibilityNodeInfo row = n;
            for (int i = 0; i < 8; i++) {
                AccessibilityNodeInfo p = row.getParent();
                if (p == null) return false;
                CharSequence cls = p.getClassName();
                if (cls != null && (cls.toString().contains("RecyclerView") || cls.toString().contains("ListView"))) {
                    // Only the bubble's own row, the one right under the chat's list.
                    List<AccessibilityNodeInfo> ticks = row.findAccessibilityNodeInfosByViewId(pkg + ":id/status");
                    return ticks != null && !ticks.isEmpty();
                }
                row = p;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True for a bubble on the right of the chat, false on the left, null when it can't tell. */
    static Boolean sideOf(AccessibilityNodeInfo n, int screenW) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        int left = 0, right = screenW;
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 12; i++) {
            CharSequence cls = p.getClassName();
            if (cls != null && (cls.toString().contains("RecyclerView") || cls.toString().contains("ListView"))) {
                android.graphics.Rect lr = new android.graphics.Rect();
                p.getBoundsInScreen(lr);
                if (lr.width() > 0) {
                    left = lr.left;
                    right = lr.right;
                }
                break;
            }
            p = p.getParent();
        }
        return side(r.left, r.right, left, right);
    }

    /** The side rule on its own, for the tests: a clear gap on one side only. */
    static Boolean side(int bubbleLeft, int bubbleRight, int listLeft, int listRight) {
        int w = listRight - listLeft;
        if (w <= 0 || bubbleRight <= bubbleLeft) return null;
        int gapL = bubbleLeft - listLeft, gapR = listRight - bubbleRight;
        int margin = w / 10;
        if (gapL - gapR > margin) return Boolean.TRUE;
        if (gapR - gapL > margin) return Boolean.FALSE;
        return null;
    }

    private static final java.util.regex.Pattern NOT_A_MESSAGE = java.util.regex.Pattern.compile(
            "(?i)^(\\d{1,2}:\\d{2}( ?[ap]\\.? ?m\\.?)?|today|yesterday|hoy|ayer|\\d+ unread messages?|message|mensaje|"
            + "edited|editado|online|en l[ií]nea|typing…|escribiendo…|\\d+:\\d+)$");

    /**
     * When an app update renames its bubble views, fall back to the text inside the chat's
     * scrolling list, skipping times, dates, buttons and the box he types in.
     */
    private void collectFallback(AccessibilityNodeInfo n, List<Bubble> out, int depth) {
        if (n == null || depth > 40) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (n.isEditable() || cls.endsWith("EditText") || cls.endsWith("Button")) return;
        boolean inList = depth > 0 && (cls.contains("RecyclerView") || cls.contains("ListView"));
        if (inList) {
            android.graphics.Rect lr = new android.graphics.Rect();
            n.getBoundsInScreen(lr);
            collectTexts(n, out, 0, lr);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                collectFallback(n.getChild(i), out, depth + 1);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void collectTexts(AccessibilityNodeInfo n, List<Bubble> out, int depth, android.graphics.Rect list) {
        if (n == null || depth > 30) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (n.isEditable() || cls.endsWith("Button") || cls.endsWith("ImageView")) return;
        CharSequence t = n.getText();
        if (t != null && cls.endsWith("TextView")) {
            String s = t.toString().trim();
            if (s.length() > 1 && !NOT_A_MESSAGE.matcher(s).matches()) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                Boolean mine = side(r.left, r.right, list.left, list.right);
                out.add(new Bubble(s, Boolean.TRUE.equals(mine), r.top));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                collectTexts(n.getChild(i), out, depth + 1, list);
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

    private String nameWin = "", lastName = "", pendingName = "";
    private boolean nameById, nameSure;

    /**
     * The open chat's name, held steady. 1.9 read it fresh on every change, and in apps without a
     * known title view a status line or a blank read could rename the chat for a moment, which
     * switched the keyboard to a chat with sending off. Now a blank read keeps the name, and a
     * different guess from the top of the screen counts only once it reads the same twice.
     */
    private String steadyName(AccessibilityNodeInfo root, String pkg) {
        String n = chatName(root, pkg);
        String win = pkg + "#" + root.getWindowId();
        if (!win.equals(nameWin)) {
            nameWin = win;
            lastName = n;
            pendingName = "";
            nameSure = nameById;
            return n;
        }
        if (n.equals(lastName)) {
            pendingName = "";
            nameSure = !n.isEmpty();
            return n;
        }
        if (n.isEmpty()) return lastName;
        if (nameById || n.equals(pendingName)) {
            lastName = n;
            pendingName = "";
            nameSure = true;
            return n;
        }
        pendingName = n;
        return lastName;
    }

    /** The open chat's name from the app's title bar, if it can be found. */
    private String chatName(AccessibilityNodeInfo root, String pkg) {
        nameById = true;
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
        // Other apps: the name at the top of the chat, so each person still gets their own chat.
        nameById = false;
        try {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            String[] best = {""};
            int[] bestTop = {Integer.MAX_VALUE};
            topText(root, 0, screenH / 6, best, bestTop);
            return best[0];
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void topText(AccessibilityNodeInfo n, int depth, int limit, String[] best, int[] bestTop) {
        if (n == null || depth > 25) return;
        CharSequence cls = n.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (n.isEditable() || c.contains("RecyclerView") || c.contains("ListView")) return;
        CharSequence t = n.getText();
        if (t != null && c.endsWith("TextView")) {
            String s = t.toString().trim();
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            if (s.length() > 0 && s.length() <= 50 && r.top >= 0 && r.top < limit && r.top < bestTop[0]
                    && !NOT_A_MESSAGE.matcher(s).matches()) {
                best[0] = s;
                bestTop[0] = r.top;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            try {
                topText(n.getChild(i), depth + 1, limit, best, bestTop);
            } catch (RuntimeException ignored) {
            }
        }
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
        row.addView(bubbleButton("✕ Close", v -> {
            awayFrom = null;
            removeBubble();
        }));
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
            awayFrom = null;
            ui.removeCallbacks(watchAway);
            ui.postDelayed(watchAway, 1500);
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

    /**
     * The bubble belongs to the chat app. It floats over every app, so once he leaves for another
     * app (YouTube, say) it steps aside, or it would sit over that app's buttons, and it comes
     * back when he returns to that chat.
     */
    private String awayFrom;
    private int awayChecks;
    private final Runnable watchAway = this::checkAway;

    private void checkAway() {
        if (bubble == null) return;
        String p = null;
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) p = r.getPackageName().toString();
        } catch (RuntimeException ignored) {
        }
        boolean here = p != null && (isChatApp(p) || p.equals(getPackageName()) || p.equals("com.android.systemui"));
        awayChecks = here ? 0 : awayChecks + 1;
        if (awayChecks >= 2) {
            awayFrom = bubbleKey;
            awayChecks = 0;
            removeBubble();
            return;
        }
        ui.postDelayed(watchAway, 1500);
    }

    private static boolean isChatApp(String pkg) {
        for (String id : BUBBLE_IDS) if (id.startsWith(pkg + ":")) return true;
        return false;
    }

    private void removeBubble() {
        ui.removeCallbacks(watchAway);
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
