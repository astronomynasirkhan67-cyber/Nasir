package com.nasir.voicechat

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ModelStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Offline English speech model (Vosk). Urdu listening uses the phone's own speech service.
    const val VOSK_NAME = "vosk-model-small-en-us-0.15"
    const val VOSK_URL = "https://alphacephei.com/vosk/models/$VOSK_NAME.zip"

    fun voskDir(ctx: Context) = File(File(ctx.filesDir, "models"), VOSK_NAME)

    fun hasVosk(ctx: Context) = File(voskDir(ctx), "am").exists()

    // Frees the space of the old Hindi speech model (Hindi was removed).
    fun removeOldHindi(ctx: Context) {
        try { File(File(ctx.filesDir, "models"), "vosk-model-small-hi-0.22").deleteRecursively() } catch (_: Throwable) {}
    }

    // ---- Chat models (LiteRT-LM). The user picks one in Setup. ----
    data class LlmModel(val id: String, val label: String, val file: String, val url: String, val size: String)

    val MODELS = listOf(
        LlmModel(
            "gemma4", "Gemma 4", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "about 2.4 GB"
        ),
        LlmModel(
            "qwen3", "Qwen3", "Qwen3_1.7B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
            "about 2.1 GB"
        )
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nk", Context.MODE_PRIVATE)

    fun selected(ctx: Context): LlmModel {
        val id = prefs(ctx).getString("model", "gemma4")
        return MODELS.firstOrNull { it.id == id } ?: MODELS[0]
    }

    fun select(ctx: Context, id: String) { prefs(ctx).edit().putString("model", id).apply() }

    fun fileOf(ctx: Context, m: LlmModel) = File(File(ctx.filesDir, "models"), m.file)

    fun llmFile(ctx: Context) = fileOf(ctx, selected(ctx))

    // The file is only renamed to its final name after a complete download/copy.
    fun hasModel(ctx: Context, m: LlmModel) = fileOf(ctx, m).let { it.exists() && it.length() > 100_000_000 }

    fun hasLlm(ctx: Context) = hasModel(ctx, selected(ctx))

    fun fetchLlm(ctx: Context, onProgress: (Int) -> Unit) {
        val m = selected(ctx)
        download(m.url, fileOf(ctx, m), onProgress)
    }

    // Resumable download: keeps a .part file and continues from it after a failure.
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val have = if (tmp.exists()) tmp.length() else 0L
        val rb = Request.Builder().url(url)
        if (have > 0) rb.header("Range", "bytes=$have-")
        http.newCall(rb.build()).execute().use { r ->
            if (r.code == 416 && tmp.exists()) {
                // .part file is already the complete file
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Could not save file")
                return
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            val resumed = r.code == 206 && have > 0
            val start = if (resumed) have else 0L
            val len = body.contentLength()
            val total = if (len > 0) len + start else -1L
            java.io.FileOutputStream(tmp, resumed).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = start
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not save file")
    }

    fun unzip(zip: File, outDir: File) {
        outDir.mkdirs()
        val base = outDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(outDir, e.name)
                if (!f.canonicalPath.startsWith(base)) throw IOException("Bad zip")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    fun fetchVosk(ctx: Context, onProgress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, "$VOSK_NAME.zip")
        download(VOSK_URL, zip, onProgress)
        unzip(zip, File(ctx.filesDir, "models"))
        zip.delete()
    }
}
