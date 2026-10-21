# NK Voice AI

Offline AI voice assistant for Android (English + Hindi).

- Speech to text: Vosk
- Brain: **Gemma 4 E2B** running on the phone through Google LiteRT-LM (native Kotlin, no Flutter)
- Text to speech: Android TTS

## First launch
The app downloads everything it needs by itself (speech models + Gemma 4, about 2.4 GB for Gemma, use Wi-Fi).
Downloads resume if they are interrupted (tap "Download / resume"). After that it works with no internet.

## Build
Push to GitHub. The workflow in `.github/workflows/build.yml` builds the APK:
Actions tab -> latest run -> Artifacts -> `NKVoiceAI-APK`.

## Notes
- Needs Android 8+ (API 26), arm64 phone, about 3 GB free storage, 6 GB+ RAM recommended.
- Gemma 4 model terms: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
