# NK Voice AI

Android app built with Kotlin, Jetpack Compose, Vosk speech recognition, and MediaPipe LLM Inference.

## Features
- English-first interface with English/Hindi speech-language selection
- Text chat using the message box and **Send** button
- Voice input using Vosk and optional spoken replies using Android Text-to-Speech
- Lion-themed launcher icon
- GitHub Actions workflow to build a debug APK

## Build an APK on GitHub
1. Upload/push all files in this project to your GitHub repository.
2. Open **Actions** and run **Build APK** (or push to `main`/`master`).
3. Download the `NKVoiceAI-APK` artifact after the workflow succeeds.

## Models
Vosk speech models are downloaded by the app from the official Vosk model host. Gemma is not bundled in the APK because it is large and must be a MediaPipe LLM Inference-compatible model file (typically `.task`). Open **Setup** to choose a compatible file or enter a direct download URL. A random Gemma `.bin` or a standard Hugging Face repository page URL may not be compatible; use a direct download URL for the exact supported MediaPipe model.

## Troubleshooting
- If AI replies fail, open Setup and verify that the model is a compatible MediaPipe `.task` model and that the download completed fully.
- The app returns a readable error for failed inference rather than continuing with a bad response.
- The Android build itself must be verified by the GitHub Actions run; this environment does not have Gradle installed, so a local APK build was not run here.
