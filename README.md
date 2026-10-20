# NK Voice AI + Gemma 4 On-Device

This repository contains two Android app projects:

1. **NK Voice AI (native Kotlin)** — the existing Jetpack Compose app with Vosk speech recognition and Android text-to-speech. Its existing MediaPipe integration accepts compatible `.task` models.
2. **Gemma 4 On-Device (Flutter)** — the supplied Gemma 4 E2B integration using `flutter_gemma` / LiteRT-LM and a public `.litertlm` model download. The model is downloaded on first launch and is not committed to GitHub.

## Important compatibility note

Gemma 4's `.litertlm` model is **not compatible** with the native app's current MediaPipe `.task` runtime. The two runtimes cannot be mixed just by copying the model file. To avoid breaking the existing NK Voice AI Android build, the Gemma 4 implementation is included as a separate Flutter app in `gemma-on-device/`. It is part of this repository, but it does not yet run inside the native Kotlin app or its APK.

## Build the existing NK Voice AI APK

Use the repository's existing GitHub Actions workflow (`.github/workflows/build.yml`). The native app still requires a MediaPipe-compatible `.task` file if you want its current LLM feature enabled.

## Build the Gemma 4 app

Requirements: Flutter 3.41+, Dart 3.11+, Android API 26+, and preferably a physical Android device with 6 GB or more RAM and around 3 GB free storage.

```bash
cd gemma-on-device
flutter pub get
flutter run
```

The Gemma app downloads the model at first setup. Internet is needed for the initial download; inference runs on-device afterward. The model itself is not included in this ZIP because it is about 2.4 GB and is downloaded by the app.

## Push to GitHub

Extract this ZIP, upload the contents of `Nasir-main/` to your repository (including the hidden `.github` folder), then commit and push. Keep the `gemma-on-device/` directory intact so its Flutter project files remain together.

## Licenses and model terms

The included Gemma sample project declares MIT licensing. Review the Gemma model's own license and terms from its official publisher before distributing an app or model weights.
