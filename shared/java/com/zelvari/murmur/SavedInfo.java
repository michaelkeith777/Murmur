package com.zelvari.murmur;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * His saved info, like his address, email and phone numbers, filled in with one tap from the
 * keyboard. It stays on the phone, in the keyboard's own settings, and is never sent anywhere.
 */
final class SavedInfo {
    private SavedInfo() {}

    static final class Item {
        final String label, value;

        Item(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    static final int MAX = 30;
    static final int MAX_CHARS = 1000;

    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c, Prefs.SAVED_INFO, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String v = o.optString("v", "");
                if (!v.isEmpty()) out.add(new Item(o.optString("l", kindOf(v)), v));
            }
        } catch (JSONException ignored) {
            // a damaged list starts over
        }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item k : items) a.put(new JSONObject().put("l", k.label).put("v", k.value));
        } catch (JSONException e) {
            return;
        }
        Prefs.sp(c).edit().putString(Prefs.SAVED_INFO, a.toString()).apply();
    }

    /**
     * Saves a value with a label for what it is (Email, Phone, Address or Note, numbered when
     * there's more than one). Returns the label, or null when there's nothing to save or it's
     * already saved.
     */
    static String add(Context c, String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.length() > MAX_CHARS) v = v.substring(0, MAX_CHARS);
        List<Item> items = list(c);
        for (Item k : items) if (k.value.equals(v)) return null;
        String kind = kindOf(v);
        int same = 0;
        for (Item k : items) if (k.label.equals(kind) || k.label.startsWith(kind + " ")) same++;
        String label = same == 0 ? kind : kind + " " + (same + 1);
        items.add(new Item(label, v));
        while (items.size() > MAX) items.remove(0);
        save(c, items);
        return label;
    }

    static void remove(Context c, String value) {
        List<Item> items = list(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).value.equals(value)) items.remove(i);
        save(c, items);
    }

    private static final Pattern EMAIL = Pattern.compile("(?i)^[^\\s@]+@[^\\s@]+\\.[a-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[\\d\\s().\\-]{7,20}$");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)^\\d+\\s+\\S.*(\\b(st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|ct|court|way|"
            + "pl|place|pkwy|parkway|cir|circle|hwy|highway|ter|terrace|trl|trail|apt|suite|unit)\\b|\\b\\d{5}(-\\d{4})?\\b).*");

    /** What a saved value is, for its label. */
    static String kindOf(String v) {
        String t = v == null ? "" : v.trim();
        if (EMAIL.matcher(t).matches()) return "Email";
        if (PHONE.matcher(t).matches() && t.replaceAll("\\D", "").length() >= 7) return "Phone";
        if (ADDRESS.matcher(t.replace('\n', ' ')).matches()) return "Address";
        return "Note";
    }

    private static final Pattern WHOLE_CODE = Pattern.compile("^(?=[A-Z0-9-]*\\d)[A-Z0-9][A-Z0-9-]{3,9}$");
    private static final Pattern CODE_IN_TEXT = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    private static final Pattern CODE_WORD = Pattern.compile("(?i)code|código|codigo|otp|verif|pin|passcode|contraseña");

    /**
     * The verification code in a copied text: the whole text when it's just a code, else a 4 to
     * 8 digit number in a message about a code. Null when there's none.
     */
    static String codeIn(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 400) return null;
        if (WHOLE_CODE.matcher(t).matches() && t.replaceAll("\\D", "").length() >= 4) return t;
        Matcher m = CODE_IN_TEXT.matcher(t);
        if (!m.find()) return null;
        String first = m.group(1);
        if (m.find()) {
            // Two numbers: only when the text says which is the code, take the one after "code".
            Matcher w = CODE_WORD.matcher(t);
            if (!w.find()) return null;
            Matcher after = CODE_IN_TEXT.matcher(t);
            if (after.find(w.end())) return after.group(1);
            return null;
        }
        return CODE_WORD.matcher(t).find() || t.length() <= 12 ? first : null;
    }
}
