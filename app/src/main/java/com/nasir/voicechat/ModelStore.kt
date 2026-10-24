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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun voskName(lang: String) =
        if (lang == "hi") "vosk-model-small-hi-0.22" else "vosk-model-small-en-us-0.15"

    fun voskUrl(lang: String) = "https://alphacephei.com/vosk/models/${voskName(lang)}.zip"

    fun voskDir(ctx: Context, lang: String) = File(File(ctx.filesDir, "models"), voskName(lang))

    fun hasVosk(ctx: Context, lang: String) = File(voskDir(ctx, lang), "am").exists()

    fun llmFile(ctx: Context) = File(ctx.filesDir, "gemma.task")

    const val QWEN_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    fun setKind(ctx: Context, kind: String) {
        ctx.getSharedPreferences("nk", 0).edit().putString("kind", kind).apply()
    }

    fun kind(ctx: Context): String =
        ctx.getSharedPreferences("nk", 0).getString("kind", "gemma") ?: "gemma"

    fun hasLlm(ctx: Context) = llmFile(ctx).let { it.exists() && it.length() > 1_000_000 }

    fun download(url: String, dest: File, token: String?, onProgress: (Int) -> Unit) {
        val rb = Request.Builder().url(url)
        if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer ${token.trim()}")
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
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
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save file")
        }
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
        download(voskUrl(lang), zip, null, onProgress)
        unzip(zip, File(ctx.filesDir, "models"))
        zip.delete()
    }
}
