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

        String title() {
            return name.isEmpty() ? (app.isEmpty() ? "this app" : app) : name;
        }
    }

    private static List<Chat> chats;

    static String key(String pkg, String name) {
        return (pkg == null ? "" : pkg) + "|" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    // ---- the chat open on screen, as the open-chat reader last saw it ----------------

    private static volatile String openPkg = "", openName = "";

    static void setOpen(Context c, String pkg, String name) {
        if (pkg == null) pkg = "";
        if (name == null) name = "";
        if (pkg.equals(openPkg) && name.equals(openName)) return;
        openPkg = pkg;
        openName = name;
        Prefs.sp(c).edit().putLong(Prefs.CHAT_OPEN_AT, System.currentTimeMillis()).apply();
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
        for (Chat ch : all) if (ch.key.equals(k)) return ch;
        // A chat with no history yet: a placeholder so its language and switches still work.
        Chat ch = new Chat();
        ch.key = k;
        ch.pkg = editorPkg;
        ch.name = name;
        return ch;
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
        Chat ch = null;
        for (Chat x : all) if (x.key.equals(k)) ch = x;
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
                if (!ch.key.isEmpty()) chats.add(ch);
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
