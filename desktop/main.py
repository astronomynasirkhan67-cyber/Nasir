import os
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext
from pathlib import Path

APP_TITLE = "NK Voice AI"
class NKVoiceAI:
    def __init__(self, root):
        self.root = root
        root.title("NK Voice AI — Windows Desktop")
        root.geometry("900x680")
        root.minsize(700, 520)
        self.llm = None
        self.embedding_llm = None
        self.embedding_model_path = ""
        self.embedding_memory = []
        self.model_path = ""
        self.busy = False
        self.last_reply = ""
        self.history = []

        top = tk.Frame(root, padx=12, pady=10); top.pack(fill="x")
        tk.Label(top, text="NK Voice AI", font=("Segoe UI", 18, "bold")).pack(side="left")
        self.model_label = tk.Label(top, text="No GGUF model loaded", fg="#a33")
        self.model_label.pack(side="left", padx=18)
        tk.Button(top, text="Choose GGUF Model", command=self.choose_model).pack(side="right")
        tk.Button(top, text="Load Model", command=self.load_model).pack(side="right", padx=8)
        tk.Button(top, text="Load Qwen3 Embeddings", command=self.load_embedding_model).pack(side="right", padx=8)

        self.chat = scrolledtext.ScrolledText(root, wrap="word", state="disabled",
                                              font=("Segoe UI", 11), padx=12, pady=12)
        self.chat.pack(fill="both", expand=True, padx=12, pady=(0, 10))
        self.chat.tag_config("you", foreground="#1769aa", font=("Segoe UI", 10, "bold"))
        self.chat.tag_config("ai", foreground="#16803c", font=("Segoe UI", 10, "bold"))
        self.chat.tag_config("system", foreground="#777777", font=("Segoe UI", 9, "italic"))

        controls = tk.Frame(root, padx=12, pady=8); controls.pack(fill="x")
        self.entry = tk.Text(controls, height=3, wrap="word", font=("Segoe UI", 11))
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Control-Return>", lambda event: self.send_message())
        buttons = tk.Frame(controls); buttons.pack(side="left", padx=(8, 0), fill="y")
        self.send_btn = tk.Button(buttons, text="Send", width=13, command=self.send_message)
        self.send_btn.pack(fill="x", pady=2)
        tk.Button(buttons, text="Listen (Vosk)", width=13, command=self.listen).pack(fill="x", pady=2)
        tk.Button(buttons, text="Speak last reply", width=13, command=self.speak_last).pack(fill="x", pady=2)
        tk.Button(buttons, text="Clear chat", width=13, command=self.clear_chat).pack(fill="x", pady=2)
        self.status = tk.StringVar(value="Install/download a local model, then load it. Chat inference works offline.")
        tk.Label(root, textvariable=self.status, anchor="w", padx=12, pady=6).pack(fill="x")
        self.add_line("System", "Select an instruction-tuned GGUF chat model. Optional: load a separate Qwen3-Embedding GGUF model for local semantic recall. Model weights are not bundled.", "system")

    def add_line(self, who, text, tag=None):
        self.chat.configure(state="normal")
        self.chat.insert("end", who + ": ", tag or ("you" if who == "You" else "ai" if who == "NK Voice AI" else "system"))
        self.chat.insert("end", text + "\n\n")
        self.chat.configure(state="disabled"); self.chat.see("end")

    def choose_model(self):
        path = filedialog.askopenfilename(title="Select local GGUF model", filetypes=[("GGUF model", "*.gguf"), ("All files", "*.*")])
        if path:
            self.model_path = path
            self.model_label.config(text=Path(path).name, fg="#1769aa")
            self.status.set("Model selected. Click Load Model.")

    def load_embedding_model(self):
        path = filedialog.askopenfilename(
            title="Select Qwen3-Embedding GGUF model",
            filetypes=[("GGUF model", "*.gguf"), ("All files", "*.*")])
        if not path:
            return
        if self.busy:
            self.status.set("Please wait for the current task before loading a model.")
            return
        self.embedding_model_path = path
        self.busy = True
        self.status.set("Loading Qwen3 embedding model; this may use extra RAM.")
        threading.Thread(target=self._load_embedding_worker, daemon=True).start()

    def _load_embedding_worker(self):
        try:
            from llama_cpp import Llama
            embedder = Llama(model_path=self.embedding_model_path, embedding=True,
                             n_ctx=512, n_threads=max(2, (os.cpu_count() or 4)-1),
                             verbose=False)
            self.embedding_llm = embedder
            self.root.after(0, lambda: self.status.set(
                "Qwen3 embeddings loaded. Past chat turns can be recalled by meaning."))
            self.root.after(0, lambda: self.add_line(
                "System", "Qwen3 embedding model loaded. Semantic recall is enabled; this is not the chat model.", "system"))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror(
                "Embedding model load failed", str(exc)))
            self.root.after(0, lambda: self.status.set("Could not load embedding model."))
        finally:
            self.busy = False

    def _make_embedding(self, text):
        result = self.embedding_llm.create_embedding(text)
        return result["data"][0]["embedding"]

    @staticmethod
    def _cosine(a, b):
        import math
        denom = math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(x*x for x in b))
        if not denom:
            return 0.0
        return sum(x*y for x, y in zip(a, b)) / denom

    def _remember_turn(self, prompt, answer):
        if self.embedding_llm is None:
            return
        try:
            vector = self._make_embedding(prompt)
            self.embedding_memory.append({"text": "User previously asked: " + prompt + "\nNK Voice AI replied: " + answer,
                                          "vector": vector})
            self.embedding_memory = self.embedding_memory[-100:]
        except Exception as exc:
            self.root.after(0, lambda: self.status.set("Reply ready; semantic memory could not index this turn."))

    def _relevant_memory(self, prompt):
        if self.embedding_llm is None or not self.embedding_memory:
            return ""
        query_vector = self._make_embedding(prompt)
        ranked = sorted(self.embedding_memory,
                        key=lambda item: self._cosine(query_vector, item["vector"]), reverse=True)
        relevant = [item["text"] for item in ranked[:3]]
        return "\n\nRelevant previous conversation (use only if helpful):\n" + "\n---\n".join(relevant)

    def load_model(self):
        if not self.model_path:
            self.choose_model()
            if not self.model_path: return
        if self.busy: return
        self.busy = True; self.status.set("Loading local model; this may take a while.")
        threading.Thread(target=self._load_worker, daemon=True).start()

    def _load_worker(self):
        try:
            from llama_cpp import Llama
            self.llm = Llama(model_path=self.model_path, n_ctx=2048,
                             n_threads=max(2, (os.cpu_count() or 4)-1), verbose=False)
            self.root.after(0, lambda: self.status.set("Model loaded — chat is offline."))
            self.root.after(0, lambda: self.add_line("System", "Local AI model loaded successfully.", "system"))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Model load failed", str(exc)))
            self.root.after(0, lambda: self.status.set("Could not load model."))
        finally: self.busy = False

    def send_message(self):
        prompt = self.entry.get("1.0", "end").strip()
        if not prompt: return
        if self.busy: self.status.set("Please wait for the current task."); return
        if self.llm is None:
            messagebox.showinfo("Load a model", "Choose a .gguf model and click Load Model first."); return
        self.entry.delete("1.0", "end"); self.add_line("You", prompt, "you")
        self.history.append({"role":"user","content":prompt})
        self.busy = True; self.send_btn.config(state="disabled"); self.status.set("Thinking locally…")
        threading.Thread(target=self._generate_worker, daemon=True).start()

    def _generate_worker(self):
        try:
            prompt = self.history[-1]["content"] if self.history else ""
            try:
                memory = self._relevant_memory(prompt)
            except Exception:
                memory = ""
            msgs = [{"role":"system","content":"You are NK Voice AI, a helpful assistant. Reply in the user's language." + memory}] + self.history[-10:]
            result = self.llm.create_chat_completion(messages=msgs, max_tokens=384, temperature=0.7)
            answer = result["choices"][0]["message"]["content"].strip()
            self.history.append({"role":"assistant","content":answer}); self.last_reply = answer
            if prompt:
                self._remember_turn(prompt, answer)
            self.root.after(0, lambda: self.add_line("NK Voice AI", answer, "ai"))
            self.root.after(0, lambda: self.status.set("Ready — running locally without internet."))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("AI error", str(exc)))
        finally:
            self.busy = False
            self.root.after(0, lambda: self.send_btn.config(state="normal"))

    def listen(self):
        folder = filedialog.askdirectory(title="Select extracted Vosk model folder")
        if not folder: return
        if self.busy: return
        self.busy = True; self.status.set("Listening for up to 10 seconds…")
        threading.Thread(target=self._listen_worker, args=(folder,), daemon=True).start()

    def _listen_worker(self, folder):
        try:
            import json, queue, time
            import sounddevice as sd
            from vosk import Model, KaldiRecognizer
            q = queue.Queue(); model = Model(folder); rec = KaldiRecognizer(model, 16000)
            def callback(indata, frames, time_info, status): q.put(bytes(indata))
            texts = []; started = time.time()
            with sd.RawInputStream(samplerate=16000, blocksize=8000, dtype="int16", channels=1, callback=callback):
                while time.time() - started < 10:
                    data = q.get()
                    if rec.AcceptWaveform(data):
                        t = json.loads(rec.Result()).get("text", "")
                        if t: texts.append(t)
                t = json.loads(rec.FinalResult()).get("text", "")
                if t: texts.append(t)
            result = " ".join(texts).strip()
            def finish():
                if result:
                    self.entry.insert("end", result + " "); self.status.set("Speech recognized; press Send.")
                else: self.status.set("No speech detected; try again.")
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Voice input error", str(exc)))
            finish = lambda: self.status.set("Offline speech recognition unavailable.")
        self.root.after(0, finish); self.busy = False

    def speak_last(self):
        if not self.last_reply:
            messagebox.showinfo("Nothing to speak", "Ask a question first."); return
        threading.Thread(target=self._speak_worker, args=(self.last_reply,), daemon=True).start()

    def _speak_worker(self, text):
        try:
            import pyttsx3
            engine = pyttsx3.init(); engine.say(text); engine.runAndWait()
            self.root.after(0, lambda: self.status.set("Ready."))
        except Exception as exc:
            self.root.after(0, lambda: messagebox.showerror("Speech output error", str(exc)))

    def clear_chat(self):
        self.history.clear(); self.embedding_memory.clear(); self.last_reply = ""
        self.chat.configure(state="normal"); self.chat.delete("1.0", "end"); self.chat.configure(state="disabled")
        self.add_line("System", "Chat cleared. The model remains loaded.", "system")

if __name__ == "__main__":
    root = tk.Tk()
    NKVoiceAI(root)
    root.mainloop()
