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

    fun voskName(lang: String) =
        if (lang == "hi") "vosk-model-small-hi-0.22" else "vosk-model-small-en-us-0.15"

    fun voskUrl(lang: String) = "https://alphacephei.com/vosk/models/${voskName(lang)}.zip"

    fun voskDir(ctx: Context, lang: String) = File(File(ctx.filesDir, "models"), voskName(lang))

    fun hasVosk(ctx: Context, lang: String) = File(voskDir(ctx, lang), "am").exists()

    // Gemma 4 E2B (LiteRT-LM). Public model, no Hugging Face login needed (~2.4 GB).
    const val GEMMA_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    fun llmFile(ctx: Context) = File(File(ctx.filesDir, "models"), "gemma-4-E2B-it.litertlm")

    // The file is only renamed to its final name after a complete download/copy.
    fun hasLlm(ctx: Context) = llmFile(ctx).let { it.exists() && it.length() > 100_000_000 }

    fun fetchGemma(ctx: Context, onProgress: (Int) -> Unit) {
        download(GEMMA_URL, llmFile(ctx), onProgress)
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

    fun fetchVosk(ctx: Context, lang: String, onProgress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, voskName(lang) + ".zip")
        download(voskUrl(lang), zip, onProgress)
        unzip(zip, File(ctx.filesDir, "models"))
        zip.delete()
    }
}
