import os
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext
from pathlib import Path

APP_TITLE = "NK Voice AI — Desktop (Offline)"
MODEL_DIR = Path(__file__).parent / "models"
VOSK_DIR = Path(__file__).parent / "vosk-model"

class NKVoiceAI:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("900x680")
        self.root.minsize(700, 520)
        self.llm = None
        self.model_path = ""
        self.busy = False
        self.stop_listening = False
        self.vosk_model = None
        self.tts_engine = None

        top = tk.Frame(root, padx=12, pady=10)
        top.pack(fill="x")
        tk.Label(top, text="NK Voice AI", font=("Segoe UI", 18, "bold")).pack(side="left")
        self.model_label = tk.Label(top, text="No GGUF model loaded", fg="#a33")
        self.model_label.pack(side="left", padx=18)
        tk.Button(top, text="Choose GGUF Model", command=self.choose_model).pack(side="right")
        tk.Button(top, text="Load Model", command=self.load_model).pack(side="right", padx=8)

        self.chat = scrolledtext.ScrolledText(root, wrap="word", state="disabled",
                                               font=("Segoe UI", 11), padx=12, pady=12)
        self.chat.pack(fill="both", expand=True, padx=12, pady=(0, 10))
        self.chat.tag_config("you", foreground="#1769aa", font=("Segoe UI", 10, "bold"))
        self.chat.tag_config("ai", foreground="#16803c", font=("Segoe UI", 10, "bold"))
        self.chat.tag_config("system", foreground="#777777", font=("Segoe UI", 9, "italic"))

        controls = tk.Frame(root, padx=12, pady=8)
        controls.pack(fill="x")
        self.entry = tk.Text(controls, height=3, wrap="word", font=("Segoe UI", 11))
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Control-Return>", lambda event: self.send_message())
        buttons = tk.Frame(controls)
        buttons.pack(side="left", padx=(8, 0), fill="y")
        self.send_btn = tk.Button(buttons, text="Send", width=12, command=self.send_message)
        self.send_btn.pack(fill="x", pady=2)
        tk.Button(buttons, text="🎙 Listen (Vosk)", width=12, command=self.listen).pack(fill="x", pady=2)
        tk.Button(buttons, text="Speak last reply", width=12, command=self.speak_last).pack(fill="x", pady=2)
        tk.Button(buttons, text="Clear chat", width=12, command=self.clear_chat).pack(fill="x", pady=2)

        self.status = tk.StringVar(value="Ready. Load a local GGUF model to begin.")
        tk.Label(root, textvariable=self.status, anchor="w", padx=12, pady=6).pack(fill="x")
        self.last_reply = ""
        self.history = []
        self.add_line("System", "For offline use, download a compatible GGUF model first and load it. The model must be stored on this PC.", "system")

    def add_line(self, who, text, tag=None):
        self.chat.configure(state="normal")
        self.chat.insert("end", f"{who}: ", tag or ("you" if who == "You" else "ai" if who == "NK Voice AI" else "system"))
        self.chat.insert("end", text + "\n\n")
        self.chat.configure(state="disabled")
        self.chat.see("end")

    def choose_model(self):
        path = filedialog.askopenfilename(title="Select a GGUF language model",
                                          filetypes=[("GGUF model", "*.gguf"), ("All files", "*.*")])
        if path:
            self.model_path = path
            self.model_label.config(text=Path(path).name, fg="#1769aa")
            self.status.set("Model selected. Click Load Model.")

    def load_model(self):
        if not self.model_path:
            self.choose_model()
            if not self.model_path:
                return
        if self.busy:
            return
        self.busy = True
        self.status.set("Loading model… large models can take several minutes.")
        threading.Thread(target=self._load_model_worker, daemon=True).start()

    def _load_model_worker(self):
        try:
            from llama_cpp import Llama
            # n_ctx can be lowered if the PC has limited RAM.
            llm = Llama(model_path=self.model_path, n_ctx=4096, n_threads=max(2, (os.cpu_count() or 4) - 1), verbose=False)
            self.llm = llm
            self.root.after(0, lambda: self.status.set("Model loaded. Chat now works without internet."))
            self.root.after(0, lambda: self.add_line("System", "Local model loaded successfully. Internet is not needed for inference.", "system"))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Could not load model", f"{exc}\n\nCheck that this is a compatible GGUF file and that llama-cpp-python is installed."))
            self.root.after(0, lambda: self.status.set("Model loading failed."))
        finally:
            self.busy = False

    def send_message(self):
        prompt = self.entry.get("1.0", "end").strip()
        if not prompt:
            return
        if self.busy:
            self.status.set("Please wait for the current task to finish.")
            return
        if self.llm is None:
            messagebox.showinfo("Load a model first", "Choose a compatible .gguf model and click Load Model.")
            return
        self.entry.delete("1.0", "end")
        self.add_line("You", prompt, "you")
        self.history.append({"role": "user", "content": prompt})
        self.busy = True
        self.send_btn.config(state="disabled")
        self.status.set("Thinking locally…")
        threading.Thread(target=self._generate_worker, daemon=True).start()

    def _generate_worker(self):
        try:
            # Chat-completion format works with many instruction-tuned GGUF models.
            messages = [{"role": "system", "content": "You are NK Voice AI, a helpful assistant. Answer clearly. Reply in the language used by the user."}]
            messages.extend(self.history[-10:])
            result = self.llm.create_chat_completion(messages=messages, max_tokens=512, temperature=0.7)
            answer = result["choices"][0]["message"]["content"].strip()
            self.history.append({"role": "assistant", "content": answer})
            self.last_reply = answer
            self.root.after(0, lambda: self.add_line("NK Voice AI", answer, "ai"))
            self.root.after(0, lambda: self.status.set("Ready — running locally."))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Generation error", str(exc)))
            self.root.after(0, lambda: self.status.set("Generation failed."))
        finally:
            self.busy = False
            self.root.after(0, lambda: self.send_btn.config(state="normal"))

    def listen(self):
        if self.busy:
            return
        model_dir = filedialog.askdirectory(title="Select extracted Vosk speech model folder")
        if not model_dir:
            return
        self.busy = True
        self.status.set("Listening for up to 10 seconds… speak clearly.")
        threading.Thread(target=self._listen_worker, args=(model_dir,), daemon=True).start()

    def _listen_worker(self, model_dir):
        try:
            import json, queue
            import sounddevice as sd
            from vosk import Model, KaldiRecognizer
            q = queue.Queue()
            model = Model(model_dir)
            rec = KaldiRecognizer(model, 16000)
            def callback(indata, frames, time_info, status):
                q.put(bytes(indata))
            chunks = []
            with sd.RawInputStream(samplerate=16000, blocksize=8000, dtype="int16",
                                   channels=1, callback=callback):
                import time
                start = time.time()
                while time.time() - start < 10:
                    data = q.get()
                    if rec.AcceptWaveform(data):
                        text = json.loads(rec.Result()).get("text", "")
                        if text:
                            chunks.append(text)
                final = json.loads(rec.FinalResult()).get("text", "")
                if final:
                    chunks.append(final)
            recognized = " ".join(chunks).strip()
            def finish():
                if recognized:
                    self.entry.insert("end", recognized + " ")
                    self.status.set("Speech recognized. Press Send to ask NK Voice AI.")
                else:
                    self.status.set("No speech detected. Try again.")
            self.root.after(0, finish)
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Offline microphone error", f"{exc}\n\nInstall the requirements and select an extracted Vosk model folder."))
            self.root.after(0, lambda: self.status.set("Speech recognition unavailable."))
        finally:
            self.busy = False

    def speak_last(self):
        if not self.last_reply:
            messagebox.showinfo("Nothing to speak", "Ask a question first.")
            return
        threading.Thread(target=self._speak_worker, args=(self.last_reply,), daemon=True).start()

    def _speak_worker(self, text):
        try:
            import pyttsx3
            engine = pyttsx3.init()
            engine.say(text)
            engine.runAndWait()
            self.root.after(0, lambda: self.status.set("Ready."))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Text-to-speech error", str(exc)))

    def clear_chat(self):
        self.history.clear()
        self.last_reply = ""
        self.chat.configure(state="normal")
        self.chat.delete("1.0", "end")
        self.chat.configure(state="disabled")
        self.add_line("System", "Chat cleared. The local model remains loaded.", "system")

if __name__ == "__main__":
    root = tk.Tk()
    app = NKVoiceAI(root)
    root.mainloop()
