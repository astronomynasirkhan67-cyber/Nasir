# NK Voice AI

AI voice assistant for Android (English + Urdu).

- Speech to text: English = Vosk (offline). Urdu = the phone's own speech service (ur-PK).
- Brain: **Gemma 4 E2B** running on the phone through Google LiteRT-LM (native Kotlin, no Flutter)
- Text to speech: Android TTS (install the Urdu voice in phone settings if asked)

## First launch
The app downloads everything it needs by itself (English speech model + Gemma 4, about 2.4 GB for Gemma, use Wi-Fi).
Downloads resume if they are interrupted (tap "Download / resume"). After that chat and English voice work with no internet.
Urdu listening needs internet unless the Urdu offline pack is installed in Google voice settings.

## If the app ever closes by itself
Open it again: a box at the top shows the reason (crash text, native crash, or low memory).

## Build
Push to GitHub. The workflow in `.github/workflows/build.yml` builds the APK:
Actions tab -> latest run -> Artifacts -> `NKVoiceAI-APK`.

## Notes
- Needs Android 8+ (API 26), arm64 phone, about 3 GB free storage, 6 GB+ RAM recommended.
- Gemma 4 model terms: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

## New in this version
- **Fast replies:** answers appear word by word and are spoken sentence by sentence while the model is still writing. Replies are length-limited. A new small model, **Qwen3 Fast** (about 0.6 GB), answers in seconds. Tap the model name next to "Setup" to switch.
- **Pictures:** tap Camera or Gallery (type a question first if you want to ask something specific). Needs Gemma 4.
- **Files:** say or type e.g. "make a pdf on Newton's laws" or "LED blinking code zip". Tap Open, Download (saves to Downloads/NK Voice AI) or Share.
- **+ menu:** next to the message box: Camera, Gallery, Video, PDF, Document, Any file. Maths is shown as plain text (x², 1/2).
