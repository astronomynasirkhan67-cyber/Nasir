# NK Voice AI Desktop

## Run from source
- Use Python 3.8 64-bit for the legacy Windows target.
- Install packages from `requirements-win7.txt`.
- Run `python main.py`.

## Use
1. Launch NK Voice AI.
2. Click **Choose GGUF Model**, choose a compatible instruction-tuned `.gguf` file, and click **Load Model**.
3. Ask questions. Once the model is loaded, local chat does not need the internet.
4. Optional microphone input: download a Vosk model, extract it, then click **Listen (Vosk)** and select that folder.
5. **Speak last reply** uses the Windows voices installed on your computer.

## Notes
This is a desktop companion, not a direct port of the Android runtime. Android `.litertlm` models are not compatible with llama.cpp; use a GGUF model. No large model files are included in the source ZIP.

## Optional Qwen3 semantic recall (new)
The desktop app can optionally load a **separate Qwen3-Embedding GGUF** file with the **Load Qwen3 Embeddings** button. When loaded, NK Voice AI embeds previous user prompts and retrieves up to three semantically similar turns to provide context for later answers. The small index is kept in memory for the current session and is cleared with **Clear chat**; it is not a persistent database.

Important: the ZIP supplied as `qwen3-embedding-06b-gguf-main.zip` is a code recipe/notebook, **not model weights**. It does not contain a `.gguf` file. Download a compatible Qwen3-Embedding GGUF model separately from the official model page: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF . Then select the actual `.gguf` file in the app. You still need a separate instruction-tuned **chat** GGUF model for generating replies; an embedding model cannot answer chat prompts by itself.

This is an optional desktop feature. The Windows 7 target has limited RAM and older native-library compatibility, so loading two models may be slow or fail on low-memory PCs. The GitHub workflow builds the application but does not download or bundle model weights. Test on the target Windows version.
