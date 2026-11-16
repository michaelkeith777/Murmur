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

Keys on the keyboard: ⌨ back to your regular keyboard (long-press for the picker),
↶ undo the last dictation, plus comma, space, period, backspace and enter.

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

- **Polish my speech**: turn off to get the raw transcript.
- **Style instructions**: free text passed to the cleanup model.
- **Names and words**: custom vocabulary so product names and jargon are spelled right.
- **Start listening when the keyboard opens** + **Switch back after inserting**:
  together these make it a one-tap flow from your regular keyboard.
- **Advanced**: any OpenAI-compatible API works; change the base URL and model names.
  Defaults: `https://api.groq.com/openai/v1`, `whisper-large-v3-turbo`, `openai/gpt-oss-120b`.

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
