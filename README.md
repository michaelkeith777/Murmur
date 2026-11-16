# Murmur

AI dictation keyboard for Android. Tap the mic, talk, tap again: your speech is
transcribed (Whisper), polished by an LLM (fillers removed, punctuation fixed,
"no wait, I meant…" corrections applied) and typed into whatever app you're in.

## Get the APK

**Without a PC (GitHub builds it):**
1. Push this folder to a GitHub repo (branch `main`).
2. The "Build APK" action runs automatically (about 3 minutes).
3. On your phone, open the repo's **Releases** page and download `murmur.apk`.

**With Android Studio:** open this folder, plug in the phone, press Run.
Or from a terminal: `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. Open Murmur and tap through the three setup steps (microphone, enable keyboard, switch).
2. Paste an API key. A free Groq key works: https://console.groq.com/keys
3. In any app, switch to the Murmur keyboard (the keyboard icon in the nav bar, or
   long-press space on Gboard), tap the mic and talk.

Keys on the keyboard: ⌨ back to your regular keyboard (long-press for the picker),
↶ undo the last dictation, plus comma, space, period, backspace and enter.

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

- `MurmurIME.java`: the keyboard (recording, state, inserting text)
- `AiClient.java`: transcription + cleanup HTTP calls and the cleanup prompt
- `MainActivity.java`: setup checklist and settings
- `Prefs.java`: settings keys and defaults
