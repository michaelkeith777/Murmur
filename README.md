# Murmur

AI dictation keyboard for Android. Tap the mic, talk, tap again: your speech is
transcribed (Whisper), polished by an LLM (fillers removed, punctuation fixed,
"no wait, I meant…" corrections applied) and typed into whatever app you're in.

## Get the APK

**Without a PC (GitHub builds it):**
1. Push this folder to a GitHub repo (branch `main`).
2. The "Build APK" action runs automatically (about 3 minutes).
3. On your phone, open the repo's **Releases** page and download `murmur.apk`
   (`murmur-watch.apk` is the watch version).

**With Android Studio:** open this folder, plug in the phone, press Run.
Or from a terminal: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. Open Murmur and tap through the three setup steps (microphone, enable keyboard, switch).
2. Paste an API key. A free Groq key works: https://console.groq.com/keys
3. In any app, switch to the Murmur keyboard (the keyboard icon in the nav bar, or
   long-press space on Gboard), tap the mic and talk.

The keyboard is a full typing keyboard (number row, letters, ?123 symbols, shift with
double-tap caps lock, auto-capitals, double-space for a period) with a big mic button on
the right. ⌨ goes back to your regular keyboard (long-press for the picker), ↶ undoes the
last dictation.

## What's new in 1.8.1

- **Their replies in the open chat now come back in your language.** 1.8 started over each time
  the chat scrolled, and WhatsApp scrolls every time a reply comes in, so replies were missed.
  Now a reply counts as new when it lands at the bottom of the chat, under messages already
  seen. Your own WhatsApp messages (the ones with ticks) are never translated back.
- **Live Translate shows what's reading replies:** whether the open-chat reader is running,
  whether notifications are read for closed chats, the last chat opened and the last reply
  caught.

## What's new in 1.8

- **Live Translate, linked to every chat.** Texting mode now keeps a thread for each chat
  (WhatsApp, Samsung Messages, Google Messages and more): their messages in your language with
  what they wrote, and what you sent with what you said. It's a screen of its own in the app,
  with its own Live Translate icon, and stays on the phone only, with clear-per-chat and
  clear-all.
- **The open chat's messages stay on the keyboard.** A strip above the keys shows that chat's
  latest messages in your language. Tap it to scroll back through more, ↗ opens the whole chat
  in Live Translate, ✕ hides it until their next message.
- **Open-chat reader.** Replies in the chat you have open (which post no notification) are read
  from the screen, with a fallback for when WhatsApp renames its views.
- **A floating bubble that stays.** With the keyboard closed, a new reply shows at the top of the
  screen until you close it; ▾ More opens the chat's recent messages.
- **One notification per chat** that keeps its latest messages and stays until you tap or clear it.
- **Each chat has its own language and on/off switch.** Pick a language on the keyboard while in a
  chat and it sticks to that chat.
- **Translate is obvious when it's on.** The strip says what's being translated and has an Off
  button, so it's never left on by accident.
- Setup explains Android's "Allow restricted settings" step for apps installed from a file.

## What's new in 1.7.1

- **Replies now come back translated:** Murmur asks for permission to show notifications before anything else, so the English version can actually pop up (on Android 13 and newer that was getting lost). WhatsApp's notifications are read more reliably, and Murmur's own messages are no longer mistaken for replies.
- **Replies in the open chat:** WhatsApp sends no notification for a reply in the chat you have open, so an optional second step lets Murmur see new messages in the open chat (WhatsApp, Google Messages, Samsung Messages, Telegram) and translate them too.
- **Status and test:** settings show whether each part is working and what Murmur last did, and a test button sends you a pretend message in their language.

## What's new in 1.7

- **Texting mode:** text someone who speaks another language. Hold 文A and tap 📨 Texting mode (or use the switch in Murmur's settings). Talk or type in English and it goes out in their language; tap ⇄ to translate what you typed before you send.
- **Replies in your language:** allow Murmur once to read message notifications and every reply in their language shows up translated, as a notification and in the keyboard's top bar. Murmur walks you through it the first time you turn texting mode on. Copying a message also shows it translated.

## What's new in 1.6

- **Conversation mode:** talk back and forth with someone in another language. Hold 文A and tap 💬 Conversation (or open it from Murmur's settings). Each person taps their own button and talks; Murmur shows and reads it out in the other person's language. Their half of the screen is upside down so it reads right across a table (⇅ turns that off).
- Each turn ends by itself when the speaker pauses. **Hands-free** starts listening to the other person as soon as a translation has been read out, so you don't have to tap at all. Tap a language to change it, or ⇄ to swap sides.

## What's new in 1.5

- **Speak to translate:** tap 文A in the top bar and speak in any language; Murmur types it in the language you picked. Hold 文A to choose from 19 languages. The button shows the target (like →ES) while it's on.
- **Space bar cursor:** slide your finger along the space bar to move the cursor one letter at a time.
- **Slide to delete words:** slide left from backspace to select whole words, then lift to delete them. Slide back to cancel.
- **Suggestions, not surprises:** auto-correct no longer changes words by itself. Your word and the best fixes show above the keys; tap one to use it, and Murmur remembers what you pick. You can turn auto-correct back on in settings.

## What's new in 1.4.4

- **Edge to edge on narrow screens:** no side padding, thinner gaps between keys, bigger letters, keys 10% taller by default, and the tone chip shrinks to its emoji. One-handed mode now only applies on wide screens.

## What's new in 1.4.3

- **Fits narrow screens:** on a narrow screen like the Galaxy Z Fold cover screen, the mic and ↶ move into the top bar so the letters use the full width, with slimmer gaps and a shorter number row. It switches by itself when you fold or unfold. Wide screens, like the Fold's inner screen, keep the usual layout.
- **Narrow-screen settings:** a key height slider (80% to 130%) and a switch to hide the number row (digits stay on ?123).

## What's new in 1.4.2

- **↶ feels like a real key:** it acts the instant you touch it, with the same buzz, click and press animation as the letters. Hold it to keep removing words.
- **Sturdier deleting:** backspace and ↶ fall back to plain delete presses in apps that ignore the usual edit calls, and ↶ no longer reaches into a new text box for dictation from an old one.

## What's new in 1.4.1

- **Shortcuts work on space:** type a code like `addr` and press space, and the saved phrase replaces it. Backspace right after brings the code back. Codes can hold numbers, and lines can use `=`, `:`, `->` or just a space.
- **↶ undoes typing too:** with no dictation to take back, it removes the last typed word.

## What's new in 1.4

- **Rewrite button (✍️).** Select text in any app, or select nothing to use the whole message,
  then tap ✍️ and pick Fix grammar, Professional, Casual, Hype, Short & sweet, Kind, Flirty,
  Pirate or Shakespeare. ↶ brings back the original.
- **Clipboard history (📋).** Everything you copy is listed so you can paste it again later.
  📌 keeps a clip; Clear removes the rest. The history stays on the phone and skips passwords
  and anything an app marks as sensitive. It can be turned off in the app.
- **Shortcuts.** Add lines like `myemail = you@example.com` in the app. Type the code on the
  keyboard and the phrase shows above the keys; tap it to type it. Codes are never autocorrected.
- **Themes and one-handed mode.** 📋 → Look: Midnight, Pure black, Ocean, Forest, Sunset and
  Light, plus a one-handed layout for either thumb.

## What's new in 1.3

- **Faster keys.** Letters go in the instant your finger touches the key, not when it lifts.
  Every finger is tracked on its own, so fast two-thumb typing doesn't drop or double letters,
  and a tap that lands in the gap between keys goes to the nearest one. A bubble shows the
  letter you hit, with a light buzz (and an optional click sound).
- **Autocorrect** with a suggestion strip above the keys. Typos are fixed when you hit space
  or punctuation ("teh" → "the", "dont" → "don't", "im" → "I'm"). Backspace right after a fix
  puts back what you typed, and Murmur leaves that word alone from then on. Tap the quoted
  word in the strip to keep exactly what you typed. Your vocabulary words and learned names
  are never "fixed".
- **Swipe typing.** Slide across the letters without lifting; a trail follows your finger and
  the word goes in when you let go. Other guesses show in the strip; tap one to swap it in.
  Backspace removes a swiped word in one go.
- No typing help in password, email and web address fields.
- Settings → Typing has switches for autocorrect, swipe, vibration and key sound.

## What's new in 1.2

- **Tone chip** (top left of the keyboard): Auto, Professional, Casual, Hype, Short & sweet,
  Pirate, Shakespeare, Flirty, Kind. Tap to pick, hold to flip Professional ↔ Casual.
  The mic and enter key take the tone's colour.
- **Smart cleanup**: fillers and stutters removed, grammar fixed, written in the chosen tone.
- **Voice commands**: right after dictating, tap the mic and say "make that shorter",
  "translate that to Spanish", "make it more professional", "add some emojis" or
  "scratch that". Start with "Murmur," for anything else. Select text to edit that instead.
- **Learns you**: when you fix a name or term Murmur typed, it remembers the fix and spells
  it right next time. It also keeps a few of your recent dictations so cleanup sounds like
  you. Select a word and tap the message above the keys to add it to your word list.
  Everything learned is on the phone and can be edited or cleared in the app.
- **More accurate by default**: the full `whisper-large-v3` model (1.1 used the faster
  turbo model; 1.2 switches anyone still on it), and your phone's language instead of
  auto-detect.

## One-tap dictation (phone)

Tap one button while typing in any app: Murmur opens already listening, types what you
said, then hands back to your regular keyboard.

1. In Murmur, tap **Optional: turn on the one-tap dictation button**.
2. Under Installed apps, turn on Murmur and choose the floating Accessibility button as
   its shortcut. If the switch is greyed out: Murmur's App info → ⋮ → Allow restricted settings.
3. Tap the floating button whenever a text field is open.

Side key instead: set the side key (double press → Open app) to **Murmur Dictate**.
Murmur uses the accessibility service only to switch keyboards; it does not read the screen.

## Watch (Wear OS)

`murmur-watch.apk` is the same keyboard for a round watch: mic, undo, backspace, send.
Watches can't install an APK by tapping it, so it is pushed from a PC once:

1. Watch: Settings → About watch → Software information → tap **Software version** 7 times.
2. Watch: Settings → Developer options → turn on **ADB debugging** and **Wireless debugging**
   (watch and PC on the same Wi-Fi).
3. PC: download Android "platform-tools" from developer.android.com, unzip, open a terminal there.
4. Watch: Wireless debugging → **Pair new device**. On the PC, using the address and code shown:
   `adb pair 192.168.x.x:PAIRPORT`
5. Back on the main Wireless debugging screen, use the address shown there:
   `adb connect 192.168.x.x:PORT`
6. `adb install murmur-watch.apk`
7. Send your API key so you don't have to type it on the watch:
   `adb shell am start -n com.zelvari.murmur.wear/com.zelvari.murmur.MainActivity -e api_key gsk_YOURKEY`
8. Watch: open Murmur and tap **Allow microphone**, **Turn on keyboard**, **Use Murmur**.
9. Turn ADB debugging back off to save battery.

Other settings can be sent the same way with `-e vocab "…"`, `-e style "…"`, `-e language en`,
`-e llm_model …`, `-e stt_model …`, `-e base_url …`.

## Settings

- **Smart cleanup**: turn off to get the raw transcript (a tone other than Auto still rewrites).
- **Tone**: same setting as the keyboard's tone chip.
- **Voice commands**, **Learn from my fixes**, **Learn how I write**, **Clear what Murmur learned**.
- **Style instructions**: free text passed to the cleanup model.
- **Names and words**: custom vocabulary so product names and jargon are spelled right.
- **Start listening when the keyboard opens** + **Switch back after inserting**:
  together these make it a one-tap flow from your regular keyboard.
- **Advanced**: any OpenAI-compatible API works; change the base URL and model names.
  Defaults: `https://api.groq.com/openai/v1`, `whisper-large-v3`, `openai/gpt-oss-120b`.

## Privacy

Audio goes straight from the phone to the API you configure and is deleted from the
phone after each dictation. The API key is stored in the app's private storage.
The microphone is only open while the red stop button is showing.

## Code map

- `shared/java`: code used by both apps
  - `MurmurIME.java`: the keyboard (recording, state, inserting text)
  - `AiClient.java`: transcription + cleanup HTTP calls and the cleanup prompt
  - `Prefs.java`: settings keys and defaults
- `app/`: phone app (`MainActivity` setup and settings, `MurmurAccessService` and
  `DictateActivity` for one-tap dictation, phone keyboard layout)
- `wear/`: watch app (`MainActivity` setup, round keyboard layout)
- `murmur.jks`: signing key used for every build so updates install over the old app.
  Anyone with this file can sign an APK that updates Murmur, so only install builds from this repo.

## Credits

The English word list behind autocorrect and swipe is built from word counts in
[hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018),
licensed CC BY-SA 4.0. `tools/make_words.py` rebuilds it.
