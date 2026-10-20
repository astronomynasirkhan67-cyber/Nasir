# Offline Voice Chat

An Android voice assistant built with Kotlin and Jetpack Compose.

## Features
- English app interface.
- English and Hindi speech-recognition language switch.
- Vosk speech-to-text, on-device Gemma responses through MediaPipe LLM Inference, and Android text-to-speech.
- Download Vosk models from the app's Setup screen.
- A Gemma 3 1B Android `.task` model URL is prefilled in Setup. The model is approximately 555 MB and is downloaded separately; it is not included in the APK because it is too large.
- Once both models are downloaded, core recognition and response generation can work offline.

## Build APK using GitHub Actions
1. Upload/push the contents of this repository to a GitHub repository on the `main` or `master` branch.
2. Open the **Actions** tab and run **Build APK**, or push a commit to trigger it.
3. Download the `NKVoiceAI-APK` artifact from the completed workflow.

## Model notes
The prefilled Gemma URL points to a public Hugging Face mirror of a MediaPipe-compatible Gemma 3 1B `.task` model. Downloading may take time and requires several hundred MB of free storage. Gemma is subject to Google's Gemma Terms of Use: https://ai.google.dev/gemma/terms. Review and accept the applicable terms before using the model. The model is not embedded in this source ZIP or APK.

If the prefilled model host changes or becomes unavailable, replace the URL with a valid direct download URL for an Android MediaPipe LLM Inference `.task` model. A regular Transformers checkpoint or GGUF file will not work with this app's current MediaPipe API.
