# NK Voice AI — Image Question Feature

This update adds a camera/gallery button to the Flutter chat screen. It uses on-device ML Kit Latin-script OCR to read printed English text from a selected image, then sends the extracted text to the existing local Gemma chat service for solving/explanation.

## Important limitations
- This is OCR-to-text, not a multimodal vision model: diagrams, graphs, and visual-only questions are not interpreted.
- The bundled ML Kit recognizer is configured for Latin script. Urdu/Arabic handwriting or printed Urdu is not supported by this OCR configuration.
- OCR and model answers depend on image quality and the capabilities of the installed model.
- Image processing is designed to work on-device; Flutter dependencies must be fetched when building.

## Build
From `Nasir-main/gemma-on-device` run:

```bash
flutter pub get
flutter analyze
flutter build apk
```

The image button lets the user take a photo or choose one from the gallery. Recognized text is placed into a prompt and submitted to the existing Gemma service.
