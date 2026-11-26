package com.zelvari.murmur;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live Translate's history, one thread per chat (app + chat name): their messages in his
 * language with the original, and what he sent with what it said in his language. Kept in a
 * file on the phone only (backups are off), with a per-chat language and on/off switch.
 */
final class ChatLog {
    private ChatLog() {}

    static final int MAX_CHATS = 40;
    static final int MAX_MSGS = 80;
    private static final String FILE = "live_translate.json";

    static final class Msg {
        String from = "", orig = "", text = "";
        boolean mine;
        long at;
    }

    static final class Chat {
        String key = "", pkg = "", name = "", app = "";
        /** The other person's language for this chat, or "" for the default. */
        String lang = "";
        boolean off;
        /** He turned on sending in their language for this chat (off for every new chat). */
        boolean send;
        long at;
        final List<Msg> msgs = new ArrayList<>();
        /** For the keyboard's feed: the chat with the newest message in it (not saved). */
        String newestKey = "";

        String title() {
            return name.isEmpty() ? (app.isEmpty() ? "this app" : app) : name;
        }
    }

    private static List<Chat> chats;

    static String key(String pkg, String name) {
        return (pkg == null ? "" : pkg) + "|" + normName(name);
    }

    /**
     * A chat name with case, emoji and punctuation left out. 1.9.5 matched names exactly, so a
     * reply from a notification titled "Elizabeth ❤️" or "+1 (555) 010-2030" filed under another
     * chat than the one on screen, and never showed in the keyboard's bar.
     */
    static String normName(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (space && b.length() > 0) b.append(' ');
                space = false;
                b.appendCodePoint(Character.toLowerCase(cp));
            } else if (Character.isWhitespace(cp)) {
                space = true;
            }
        }
        String out = b.toString();
        // A phone number, as an unsaved contact shows: its last ten digits, however it's written.
        String digits = out.replace(" ", "");
        if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit) && digits.length() >= 7) {
            return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        }
        return out;
    }

    /**
     * True when two names are the same chat: the same once emoji and case are left out, or one is
     * the other's first words ("Elizabeth" and "Elizabeth Smith"). "Byron" and "Byron's Mom" are not.
     */
    static boolean sameName(String a, String b) {
        String x = normName(a), y = normName(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String shorter = x.length() < y.length() ? x : y, longer = shorter == x ? y : x;
        return longer.startsWith(shorter + " ");
    }

    /** The saved chat for this app and name: the same name first, else the one name that matches. */
    private static Chat findIn(List<Chat> all, String pkg, String name) {
        String k = key(pkg, name);
        for (Chat ch : all) if (ch.key.equals(k)) return ch;
        if (normName(name).isEmpty()) return null;
        Chat hit = null;
        for (Chat ch : all) {
            if (!ch.pkg.equals(pkg == null ? "" : pkg) || !sameName(ch.name, name)) continue;
            if (hit != null) return null;
            hit = ch;
        }
        return hit;
    }

    static synchronized Chat find(Context c, String pkg, String name) {
        return findIn(load(c), pkg, name);
    }

    // ---- the chat open on screen, as the open-chat reader last saw it ----------------

    private static volatile String openPkg = "", openName = "";
    /** The reader is sure of the name: from the app's own title view, or read the same twice. */
    private static volatile boolean openSure;

    static void setOpen(Context c, String pkg, String name, boolean sure) {
        if (pkg == null) pkg = "";
        if (name == null) name = "";
        sure = sure && !name.isEmpty();
        if (pkg.equals(openPkg) && name.equals(openName) && sure == openSure) return;
        openPkg = pkg;
        openName = name;
        openSure = sure;
        Prefs.sp(c).edit().putLong(Prefs.CHAT_OPEN_AT, System.currentTimeMillis()).apply();
    }

    /** True when the open-chat reader is sure this chat is the one on screen in that app. */
    static boolean namedOpen(String pkg, String name) {
        return openSure && readsOpen(pkg, name);
    }

    /** True when the reader's latest read of the screen names this chat, sure or not. */
    static boolean readsOpen(String pkg, String name) {
        return name != null && !name.isEmpty() && pkg != null && pkg.equals(openPkg)
                && (name.equals(openName) || sameName(name, openName));
    }

    /** What the reader last read in this app, for the status card: the name, a guess, or nothing. */
    static String openLabel(String pkg) {
        if (pkg == null || !pkg.equals(openPkg) || openName.isEmpty()) return "can't tell which chat";
        return openSure ? "chat with " + openName : "maybe the chat with " + openName + " (not sure yet)";
    }

    /**
     * The chat he's typing in: the open chat when the reader knows it. When it can't tell which
     * chat (or the app isn't one it reads), the app as a whole, so a switch turned on for one
     * person never carries over to another.
     */
    static synchronized Chat forEditor(Context c, String editorPkg) {
        if (editorPkg == null || editorPkg.isEmpty()) return null;
        List<Chat> all = load(c);
        String name = editorPkg.equals(openPkg) ? openName : "";
        String k = key(editorPkg, name);
        Chat found = findIn(all, editorPkg, name);
        if (found != null) return found;
        if (name.isEmpty()) {
            // The reader couldn't name the open chat (it may not be running, or the chat came from
            // a notification): the chat in this app with the latest message, as 1.8 did. 1.9
            // dropped this, so replies that arrived by notification never showed on the keyboard.
            long now = System.currentTimeMillis();
            for (Chat ch : all) if (ch.pkg.equals(editorPkg) && !ch.name.isEmpty() && now - ch.at < 3 * 3600_000L) return ch;
        }
        // A chat with no history yet: a placeholder so its language and switches still work.
        Chat ch = new Chat();
        ch.key = k;
        ch.pkg = editorPkg;
        ch.name = name;
        return ch;
    }

    /**
     * Every recent message in one app, from all its chats, oldest first, each with who wrote it.
     * The keyboard shows this so a reply always shows up in his language, whichever chat Murmur
     * thinks is open. Chats with replies turned off are left out. Null when there's nothing.
     */
    static synchronized Chat feed(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        List<Msg> out = new ArrayList<>();
        Chat newest = null;
        for (Chat ch : load(c)) {
            if (!ch.pkg.equals(pkg)) continue;
            for (Msg m : ch.msgs) {
                Msg x = new Msg();
                x.from = m.mine ? "" : m.from.isEmpty() ? ch.title() : m.from;
                x.orig = m.orig;
                x.text = m.text;
                x.mine = m.mine;
                x.at = m.at;
                out.add(x);
            }
            if (!ch.msgs.isEmpty() && (newest == null || ch.at > newest.at)) newest = ch;
        }
        if (out.isEmpty()) return null;
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        while (out.size() > MAX_MSGS) out.remove(0);
        Chat f = new Chat();
        f.key = "feed|" + pkg;
        f.pkg = pkg;
        f.app = newest.app;
        f.at = out.get(out.size() - 1).at;
        f.newestKey = newest.key;
        f.msgs.addAll(out);
        return f;
    }

    static synchronized List<Chat> all(Context c) {
        return new ArrayList<>(load(c));
    }

    static synchronized Chat get(Context c, String key) {
        for (Chat ch : load(c)) if (ch.key.equals(key)) return ch;
        return null;
    }

    /** Adds a message to its chat's thread and moves that chat to the top. */
    static synchronized Chat add(Context c, String pkg, String app, String name, String from,
                                 String orig, String text, boolean mine) {
        List<Chat> all = load(c);
        String k = key(pkg, name);
        Chat ch = findIn(all, pkg, name);
        if (ch == null) {
            ch = new Chat();
            ch.key = k;
            ch.pkg = pkg == null ? "" : pkg;
            ch.name = name == null ? "" : name.trim();
        }
        if (app != null && !app.isEmpty()) ch.app = app;
        Msg m = new Msg();
        m.from = from == null ? "" : from;
        m.orig = orig == null ? "" : orig;
        m.text = text == null ? "" : text;
        m.mine = mine;
        m.at = System.currentTimeMillis();
        ch.msgs.add(m);
        while (ch.msgs.size() > MAX_MSGS) ch.msgs.remove(0);
        ch.at = m.at;
        all.remove(ch);
        all.add(0, ch);
        while (all.size() > MAX_CHATS) all.remove(all.size() - 1);
        save(c);
        return ch;
    }

    static void settings(Context c, Chat placeholder, String lang, Boolean off) {
        settings(c, placeholder, lang, off, null);
    }

    /** Saves a chat's language or switches, making the chat if it's new. */
    static synchronized void settings(Context c, Chat placeholder, String lang, Boolean off, Boolean send) {
        List<Chat> all = load(c);
        Chat ch = null;
        for (Chat x : all) if (x.key.equals(placeholder.key)) ch = x;
        if (ch == null) {
            ch = placeholder;
            if (ch.at == 0) ch.at = System.currentTimeMillis();
            all.add(0, ch);
        }
        if (lang != null) ch.lang = lang;
        if (off != null) ch.off = off;
        if (send != null) ch.send = send;
        placeholder.lang = ch.lang;
        placeholder.off = ch.off;
        placeholder.send = ch.send;
        save(c);
    }

    static synchronized void clear(Context c, String key) {
        List<Chat> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).key.equals(key)) all.remove(i);
        save(c);
    }

    static synchronized void clearAll(Context c) {
        load(c).clear();
        save(c);
    }

    // ---- file ---------------------------------------------------------------------------

    private static List<Chat> load(Context c) {
        if (chats != null) return chats;
        chats = new ArrayList<>();
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        if (!f.exists()) return chats;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray arr = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Chat ch = new Chat();
                ch.key = o.optString("key");
                ch.pkg = o.optString("pkg");
                ch.name = o.optString("name");
                ch.app = o.optString("app");
                ch.lang = o.optString("lang");
                ch.off = o.optBoolean("off");
                ch.send = o.optBoolean("send");
                ch.at = o.optLong("at");
                JSONArray ms = o.optJSONArray("msgs");
                if (ms != null) for (int j = 0; j < ms.length(); j++) {
                    JSONObject mo = ms.getJSONObject(j);
                    Msg m = new Msg();
                    m.from = mo.optString("from");
                    m.orig = mo.optString("orig");
                    m.text = mo.optString("text");
                    m.mine = mo.optBoolean("mine");
                    m.at = mo.optLong("at");
                    ch.msgs.add(m);
                }
                if (ch.key.isEmpty()) continue;
                // Keys saved before 1.9.6 kept emoji and punctuation: file them the new way.
                if (!ch.key.startsWith("feed|")) ch.key = key(ch.pkg, ch.name);
                Chat same = null;
                for (Chat x : chats) if (x.key.equals(ch.key)) same = x;
                if (same == null) {
                    chats.add(ch);
                } else {
                    same.msgs.addAll(ch.msgs);
                    java.util.Collections.sort(same.msgs, (m1, m2) -> Long.compare(m1.at, m2.at));
                    same.at = Math.max(same.at, ch.at);
                }
            }
        } catch (Exception ignored) {
            // a damaged file just starts the history over
        }
        return chats;
    }

    private static void save(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Chat ch : chats) {
                JSONObject o = new JSONObject();
                o.put("key", ch.key).put("pkg", ch.pkg).put("name", ch.name).put("app", ch.app)
                        .put("lang", ch.lang).put("off", ch.off).put("send", ch.send).put("at", ch.at);
                JSONArray ms = new JSONArray();
                for (Msg m : ch.msgs) {
                    ms.put(new JSONObject().put("from", m.from).put("orig", m.orig).put("text", m.text)
                            .put("mine", m.mine).put("at", m.at));
                }
                o.put("msgs", ms);
                arr.put(o);
            }
            File dir = c.getApplicationContext().getFilesDir();
            File tmp = new File(dir, FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(dir, FILE));
        } catch (Exception ignored) {
        }
        // Lets the keyboard strip and the Live Translate screen redraw.
        Prefs.sp(c).edit().putLong(Prefs.IN_AT, System.currentTimeMillis()).apply();
    }
}
