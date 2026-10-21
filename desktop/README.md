# NK Voice AI — Windows Desktop (Offline)

This is a desktop companion for the NK Voice AI Android project. It is a separate Python desktop app because the Android app uses Android-only Kotlin APIs and a LiteRT-LM model runtime.

## Features
- Local chat using a GGUF language model through `llama-cpp-python`.
- Optional offline speech recognition using Vosk and your PC microphone.
- Text-to-speech using the Windows speech voices via `pyttsx3`.
- No cloud/API key is used for chat inference after the model files and packages are installed.

## Requirements
- Windows 10/11, 64-bit.
- Python 3.10 or 3.11 recommended. Install from https://www.python.org/downloads/windows/ and enable **Add Python to PATH**.
- RAM depends on the model. A 2B–4B quantized model may run on a PC with 8 GB RAM; larger models need more memory.
- Free disk space for the model (often several GB).

## Setup
1. Install Python.
2. Double-click `install_windows.bat`.
3. Download a compatible **instruction-tuned GGUF** model from a trusted model publisher. This desktop app cannot load the Android `.litertlm` model directly; download a GGUF version instead. Check the model license and choose a quantization that fits your RAM.
4. Run `run_windows.bat`.
5. Click **Choose GGUF Model**, select the `.gguf` file, then click **Load Model**.
6. Type a question and click **Send**. After the model is loaded, text chat runs locally/offline.

## Optional offline voice input
1. Download a Vosk English model from https://alphacephei.com/vosk/models.
2. Extract the downloaded archive to a folder.
3. Click **Listen (Vosk)** and select the extracted model folder. Speak into your microphone. Recognized text appears in the message box; press **Send**.
4. Urdu offline speech recognition depends on finding a compatible Urdu speech model; this starter desktop build provides Vosk microphone input and does not bundle an Urdu model.

## Troubleshooting
- **`llama-cpp-python` installation fails:** available wheels depend on Python version and architecture. Try Python 3.11 64-bit. If pip cannot find a compatible wheel, follow the build instructions at https://github.com/abetlen/llama-cpp-python.
- **Model fails to load:** verify the file is a supported GGUF model, use an instruction-tuned model, and choose a quantization small enough for your RAM.
- **Slow generation:** use a smaller quantized model, close memory-heavy applications, and lower `n_ctx` in `main.py` from `4096` to `2048`.
- **No voice:** confirm Windows microphone permissions and that a speech output voice is installed.

## Important
The Android Gemma `.litertlm` model and Android Kotlin code are not a Windows desktop runtime. This folder is a separate offline desktop implementation. You need to download a compatible GGUF model and Vosk model yourself; these large model files are intentionally not included in the ZIP.
