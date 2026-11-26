package com.zelvari.murmur;

import android.content.Context;

/** Speak to translate: dictation comes out in another language. */
final class Translate {
    private Translate() {}

    /** Language name (what the AI is told) and the short code shown on the top bar button. */
    static final String[][] LANGUAGES = {
            {"Spanish", "ES"}, {"English", "EN"}, {"French", "FR"}, {"German", "DE"},
            {"Italian", "IT"}, {"Portuguese", "PT"}, {"Chinese (Simplified)", "ZH"},
            {"Japanese", "JA"}, {"Korean", "KO"}, {"Arabic", "AR"}, {"Hindi", "HI"},
            {"Russian", "RU"}, {"Vietnamese", "VI"}, {"Tagalog", "TL"}, {"Dutch", "NL"},
            {"Polish", "PL"}, {"Turkish", "TR"}, {"Ukrainian", "UK"}, {"Haitian Creole", "HT"},
    };

    /** The language dictation is translated into right now, or "" when translate is off. */
    static String target(Context c) {
        // 2.0: one switch, and nothing else decides. On: everything he sends goes out in the
        // language he picked and every reply shows in his. Off: plain typing. Up to 1.9.8 a second
        // switch (send in their language or not), the older speak-to-translate switch and each
        // chat's own setting could all change it, so messages went out one way or the other.
        return texting(c) ? theirsDefault(c) : "";
    }

    /** Languages he can send in: every one but his own. */
    static java.util.List<String[]> sendable(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String mine = mine(c);
        for (String[] l : LANGUAGES) if (!l[0].equals(mine)) out.add(l);
        return out;
    }

    /** Turns translate on or off. On picks up the last language he used (Spanish the first time). */
    static void switchOn(Context c, boolean on) {
        android.content.SharedPreferences.Editor e = Prefs.sp(c).edit().putBoolean(Prefs.TEXTING, on)
                .putBoolean(Prefs.TRANSLATE_ON, false);
        if (on) {
            String l = last(c);
            if (l.isEmpty() || l.equals(mine(c))) e.putString(Prefs.TRANSLATE_TO, "Spanish");
            // The open-chat reader translates the other person's messages already on screen once.
            e.putLong(Prefs.TRANSLATE_SINCE, System.currentTimeMillis());
        }
        e.apply();
    }

    /** Picks the language he sends in, and turns translate on. It stays until he changes it. */
    static void pick(Context c, String language) {
        if (code(language) == null) return;
        Prefs.sp(c).edit().putString(Prefs.TRANSLATE_TO, language).apply();
        if (!texting(c)) switchOn(c, true);
    }

    /**
     * True when a translation into {@code to} plainly didn't happen: it came back empty, or the
     * same as an English text. Text that was already in {@code to} counts as done.
     */
    static boolean notTranslated(String original, String out, String to) {
        if (out == null || out.trim().isEmpty()) return true;
        if (!sameText(original, out)) return false;
        return looksEnglish(original, to);
    }

    /** True when a text reads as English rather than {@code to} (used to check a translation). */
    static boolean looksEnglish(String text, String to) {
        if (text == null) return false;
        String t = text.trim();
        boolean letters = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetter(ch)) {
                letters = true;
                if (ch > 0x7F) return false;
            }
        }
        if (!letters) return false;
        if (to != null && to.startsWith("Spanish") && looksForeign(t, "English")) return false;
        // Plain letters could be English or, say, French without accents: English words decide.
        return ENGLISH_WORD.matcher(t).find();
    }

    private static final java.util.regex.Pattern ENGLISH_WORD = java.util.regex.Pattern.compile(
            "(?i)\\b(i|i'm|you|your|the|is|are|was|be|to|and|or|but|my|we|it|it's|at|of|for|with|"
            + "this|that|what|how|when|where|why|who|ok|okay|yes|not|hi|hello|hey|thanks|thank|please|sorry|"
            + "good|morning|night|see|can|will|did|don't|have|has|go|going|coming|come|call|love|miss|"
            + "tomorrow|today|tonight|later|now|soon|here|there|home|work|time|day|let|let's|lol|omg)\\b");

    /** The chat he's typing in: its own language ("" for the default) and whether it's switched off. */
    static volatile String chatLang = "";
    static volatile boolean chatOff;
    static volatile boolean chatSend;

    /** Texting mode: send in their language, read their replies in yours. */
    static boolean texting(Context c) {
        return Prefs.flag(c, Prefs.TEXTING, false);
    }

    /** Texting mode: whether what he sends goes out in their language. */
    static boolean sending(Context c) {
        return Prefs.flag(c, Prefs.SEND_LAST, false);
    }

    static void setSending(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean(Prefs.SEND_LAST, on).apply();
    }

    static void setTexting(Context c, boolean on) {
        switchOn(c, on);
    }

    /** Remembers what he just sent in their language, so it isn't mistaken for a reply. */
    static void rememberSent(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String old = Prefs.get(c, Prefs.SENT_RECENT, "");
        StringBuilder b = new StringBuilder(norm(text));
        String[] lines = old.split("\n");
        for (int i = 0; i < lines.length && i < 14; i++) if (!lines[i].isEmpty()) b.append('\n').append(lines[i]);
        Prefs.sp(c).edit().putString(Prefs.SENT_RECENT, b.toString()).apply();
    }

    /**
     * True when a message is one he just sent (it shows up in some notifications and on screen).
     * A long message cut short in a notification still counts. 1.9.6 and before also counted any
     * reply that started with a short message of his, so after he sent "Hola", a reply like
     * "Hola, bien, ¿y tú?" was taken for his own and never translated.
     */
    static boolean wasSent(Context c, String text) {
        String n = norm(text);
        if (n.isEmpty()) return false;
        for (String l : Prefs.get(c, Prefs.SENT_RECENT, "").split("\n")) {
            if (l.isEmpty()) continue;
            if (l.equals(n)) return true;
            String shorter = l.length() < n.length() ? l : n, longer = shorter == l ? n : l;
            if (shorter.length() >= 20 && longer.startsWith(shorter.replaceAll("[….]+$", ""))) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern SPANISH_WORD = java.util.regex.Pattern.compile(
            "(?iu)(^|[^\\p{L}])(que|qué|como|cómo|estás|estas|está|esta|hola|gracias|por|para|pero|porque|"
            + "bueno|bien|mañana|manana|hoy|ahora|tengo|tienes|quiero|vamos|voy|eres|soy|muy|mucho|nada|"
            + "también|tambien|donde|dónde|cuando|cuándo|sí|si|claro|amor|mi|tu|te|yo|el|la|los|las|un|una|"
            + "es|de|en|con|del|al|lo|le|me|se|no sé|vale|oye|jaja|jeje|ya|aquí|aqui|allí|nos|hacer|hace|"
            + "puedo|puedes|ver|vez|día|dia|noche|casa|trabajo|ella|él|ellos|usted|ustedes|algo|todo)(?=$|[^\\p{L}])");

    /**
     * True when a message plainly isn't in English: Spanish accents and marks, or Spanish words
     * that English doesn't have. Only used when his language is English, to catch a reply the
     * translator left as is.
     */
    static boolean looksForeign(String text, String mine) {
        if (text == null || mine == null || !mine.toLowerCase(java.util.Locale.ROOT).startsWith("english")) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        int letters = 0, odd = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (ch > 0x7F) odd++;
            }
            if (ch == '¿' || ch == '¡') return true;
        }
        if (letters == 0) return false;
        if (odd > 0) return true;
        java.util.regex.Matcher m = SPANISH_WORD.matcher(t);
        int hits = 0;
        while (m.find()) hits++;
        int words = t.split("\\s+").length;
        return hits >= 2 || (hits == 1 && words <= 3 && !t.matches("(?i).*\\b(i|you|the|is|are|and|to|ok|okay|lol)\\b.*"));
    }

    // ---- 2.1: which language a message is in ---------------------------------------------

    private static final String[][] WORDS = {
            {"Spanish", "que|qué|como|cómo|estás|estas|está|esta|hola|gracias|pero|porque|bueno|bien|mañana|hoy|ahora|tengo|tienes|quiero|vamos|voy|eres|soy|muy|mucho|nada|también|donde|dónde|cuando|claro|yo|el|los|las|una|es|de|en|con|del|al|lo|le|me|se|ya|aquí|nos|hacer|puedo|usted|todo|y|la|un|por|para|mi|tu|te|no"},
            {"Portuguese", "você|voce|obrigado|obrigada|não|nao|sim|muito|bom|boa|tudo|bem|estou|está|vou|tenho|quero|amanhã|hoje|agora|também|onde|quando|eu|ele|ela|nós|uma|um|os|as|do|da|dos|das|no|na|em|com|para|por|que|é|e|de|meu|minha|seu|sua|isso|aqui|obrigad"},
            {"French", "je|tu|vous|nous|il|elle|est|suis|es|sont|pas|merci|bonjour|salut|oui|non|très|bien|avec|pour|dans|sur|une|un|le|la|les|des|du|de|et|que|qui|ça|ca|c'est|mais|demain|aujourd'hui|maintenant|aussi|où|quand|mon|ma|mes|ton|ta|tes|ce|cette|j'ai|t'es|on|y|au|aux|moi|toi"},
            {"Italian", "ciao|grazie|sono|sei|è|non|sì|molto|bene|anche|perché|domani|oggi|adesso|ora|dove|quando|io|tu|lui|lei|noi|voi|una|uno|il|lo|la|gli|le|di|da|in|con|per|che|e|mio|mia|tuo|tua|questo|questa|ho|hai|ha|abbiamo|come|cosa|buongiorno|stai|sto"},
            {"German", "ich|du|sie|wir|ihr|ist|bin|bist|sind|nicht|danke|hallo|ja|nein|sehr|gut|mit|für|und|oder|aber|der|die|das|ein|eine|einen|zu|auf|im|in|es|wie|was|wo|wann|morgen|heute|jetzt|auch|mein|meine|dein|deine|habe|hast|hat|kann|kannst|noch|schon"},
            {"Dutch", "ik|jij|je|u|wij|we|zij|is|ben|bent|zijn|niet|dank|bedankt|hallo|hoi|ja|nee|heel|goed|met|voor|en|of|maar|de|het|een|van|op|in|wat|waar|wanneer|morgen|vandaag|nu|ook|mijn|jouw|heb|hebt|heeft|kan|kun|nog|al|dat|die|deze"},
            {"Tagalog", "ako|ikaw|ka|siya|kami|tayo|kayo|sila|ang|ng|sa|mga|na|ay|hindi|oo|salamat|po|opo|ko|mo|niya|natin|namin|ninyo|nila|ito|iyan|iyon|ano|saan|kailan|bakit|paano|mahal|kita|kumain|ba|lang|din|rin|naman|talaga|kasi"},
            {"Haitian Creole", "mwen|ou|li|nou|yo|se|pa|mèsi|mesi|bonjou|wi|non|anpil|byen|ak|pou|nan|yon|la|sa|ki|kote|kilè|kounye|demen|jodi|tou|m|w|map|ap|te|gen|vle|kapab|kijan|sak|pase|sou|dako"},
            {"Polish", "ja|ty|on|ona|my|wy|oni|jest|jestem|jesteś|są|nie|tak|dziękuję|dzięki|cześć|bardzo|dobrze|z|dla|i|lub|ale|w|na|do|co|gdzie|kiedy|jutro|dzisiaj|teraz|też|mój|moja|twój|twoja|mam|masz|ma|się|to|że"},
            {"Turkish", "ben|sen|o|biz|siz|onlar|ve|bir|bu|şu|evet|hayır|teşekkürler|teşekkür|merhaba|çok|iyi|ile|için|ama|ne|nerede|ne zaman|yarın|bugün|şimdi|da|de|benim|senin|var|yok|mı|mi|mu|mü|nasılsın|tamam"},
            {"Vietnamese", "tôi|bạn|anh|chị|em|của|và|là|không|có|được|cảm|ơn|xin|chào|rất|tốt|với|cho|nhưng|gì|đâu|khi|nào|mai|hôm|nay|bây|giờ|cũng|này|đó|người|một|những|các"},
    };
    private static final java.util.Map<String, java.util.regex.Pattern> WORD_RE = new java.util.LinkedHashMap<>();
    static {
        for (String[] w : WORDS) {
            WORD_RE.put(w[0], java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}'])(" + w[1] + ")(?![\\p{L}'])"));
        }
    }
    private static final java.util.regex.Pattern ENGLISH_COMMON = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}'])(the|you|and|is|are|to|of|it|that|this|what|was|for|with|have|i|i'm|im|my|me|your|we|be|do|don't|not|but|so|just|can|will|how|ok|okay|yes|lol|thanks|hey|hi|good|going|know|love|at|on|in|a|an|if|or|get|got|see|there|here|they|he|she|him|her)(?![\\p{L}'])");

    /**
     * The language a message is plainly written in, or "" when it isn't plain (too short, mixed,
     * or English). Writing systems decide at once (Korean, Japanese, Russian and so on); for
     * languages written in the Latin alphabet it needs at least two of that language's common
     * words and a clear lead over every other language, English included. Used only to offer a
     * switch: it never changes what he sends by itself.
     */
    static String detect(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        int letters = 0, hangul = 0, kana = 0, han = 0, cyr = 0, ukr = 0, arabic = 0, hebrew = 0, deva = 0, greek = 0, thai = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            Character.UnicodeBlock b = Character.UnicodeBlock.of(ch);
            if (b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO) hangul++;
            else if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) kana++;
            else if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) han++;
            else if (b == Character.UnicodeBlock.CYRILLIC) {
                cyr++;
                if ("іїєґІЇЄҐ".indexOf(ch) >= 0) ukr++;
            } else if (b == Character.UnicodeBlock.ARABIC) arabic++;
            else if (b == Character.UnicodeBlock.HEBREW) hebrew++;
            else if (b == Character.UnicodeBlock.DEVANAGARI) deva++;
            else if (b == Character.UnicodeBlock.GREEK) greek++;
            else if (b == Character.UnicodeBlock.THAI) thai++;
        }
        if (letters == 0) return "";
        float half = letters * 0.5f;
        if (hangul > half) return "Korean";
        if (kana > 0 && kana + han > half) return "Japanese";
        if (han > half) return "Chinese (Simplified)";
        if (cyr > half) return ukr > 0 ? "Ukrainian" : "Russian";
        if (arabic > half) return "Arabic";
        if (deva > half) return "Hindi";
        if (hebrew > half || greek > half || thai > half) return "";
        // ñ, ¿ and ¡ are Spanish's own.
        if (t.indexOf('ñ') >= 0 || t.indexOf('¿') >= 0 || t.indexOf('¡') >= 0) return "Spanish";
        String[] words = t.split("\\s+");
        if (words.length < 3) return "";
        int english = count(ENGLISH_COMMON, t);
        String best = "";
        int top = 0, second = 0;
        for (java.util.Map.Entry<String, java.util.regex.Pattern> e : WORD_RE.entrySet()) {
            int n = count(e.getValue(), t);
            if (n > top) {
                second = top;
                top = n;
                best = e.getKey();
            } else if (n > second) {
                second = n;
            }
        }
        if (top < 2 || top < second * 2 || top <= english) return "";
        return best;
    }

    private static int count(java.util.regex.Pattern p, String t) {
        java.util.regex.Matcher m = p.matcher(t);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static boolean sameText(String a, String b) {
        return norm(a).replaceAll("[^\\p{L}\\p{N} ]", "").equals(norm(b).replaceAll("[^\\p{L}\\p{N} ]", ""));
    }

    private static String norm(String t) {
        return t == null ? "" : t.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** Saves a translated incoming message for the keyboard's top bar. */
    static void saveIncoming(Context c, String from, String text, String app) {
        Prefs.sp(c).edit().putString(Prefs.IN_FROM, from).putString(Prefs.IN_TEXT, text)
                .putString(Prefs.IN_APP, app).putLong(Prefs.IN_AT, System.currentTimeMillis()).apply();
    }

    /** The language picked last time, so one tap turns it back on. */
    static String last(Context c) {
        String l = Prefs.get(c, Prefs.TRANSLATE_TO, "");
        return code(l) == null ? "" : l;
    }

    static boolean picked(Context c) {
        return !last(c).isEmpty();
    }

    static void set(Context c, String language, boolean on) {
        Prefs.sp(c).edit().putString(Prefs.TRANSLATE_TO, language).putBoolean(Prefs.TRANSLATE_ON, on).apply();
    }

    static void setOn(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean(Prefs.TRANSLATE_ON, on).apply();
    }

    /** The language Michael speaks in conversation mode (the other side is {@link #last}). */
    static String mine(Context c) {
        String l = Prefs.get(c, Prefs.CONVO_MINE, "English");
        return code(l) == null ? "English" : l;
    }

    /** The other person's language in conversation mode: the keyboard's last pick, or Spanish. */
    static String theirs(Context c) {
        return theirsDefault(c);
    }

    /** The language new chats send in (ignoring the open chat's own choice). */
    static String theirsDefault(Context c) {
        String l = last(c);
        return l.isEmpty() || l.equals(mine(c)) ? "Spanish" : l;
    }

    /** ISO 639-1 code for speech-to-text and the voice ("es"). */
    static String iso(String language) {
        String code = code(language);
        return code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
    }

    static java.util.Locale locale(String language) {
        String iso = iso(language);
        if (iso.equals("tl")) return new java.util.Locale("fil");
        if (iso.equals("zh")) return java.util.Locale.SIMPLIFIED_CHINESE;
        return new java.util.Locale(iso);
    }

    static String code(String language) {
        for (String[] l : LANGUAGES) if (l[0].equals(language)) return l[1];
        return null;
    }
}
