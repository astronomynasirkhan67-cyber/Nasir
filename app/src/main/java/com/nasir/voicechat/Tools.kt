package com.nasir.voicechat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Extracted(val isImage: Boolean, val text: String)

object Extractor {
    const val MAX_CHARS = 1500

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
            }
        } catch (_: Throwable) {}
        return "file"
    }

    fun fromFile(ctx: Context, uri: Uri, devanagari: Boolean): Extracted {
        val name = displayName(ctx, uri).lowercase()
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return when {
            mime.startsWith("image/") -> Extracted(true, fromImage(loadBitmap(ctx, uri), devanagari))
            mime == "application/pdf" || name.endsWith(".pdf") -> Extracted(false, fromPdf(ctx, uri, devanagari))
            name.endsWith(".docx") -> Extracted(false, fromDocx(ctx, uri))
            else -> Extracted(false, fromText(ctx, uri))
        }
    }

    private fun fromText(ctx: Context, uri: Uri): String {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        return input.bufferedReader().use { r ->
            val cb = CharArray(20000)
            val n = r.read(cb)
            if (n > 0) String(cb, 0, n) else ""
        }
    }

    private fun fromDocx(ctx: Context, uri: Uri): String {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    return xml.replace("</w:p>", "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"")
                }
                e = z.nextEntry
            }
        }
        throw IOException("Could not read this Word file")
    }

    private fun fromPdf(ctx: Context, uri: Uri, devanagari: Boolean): String {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open PDF")
        val sb = StringBuilder()
        val renderer = PdfRenderer(pfd)
        try {
            val pages = minOf(renderer.pageCount, 3)
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                try {
                    val w = 1240
                    val h = (w.toFloat() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    sb.append(ocr(bmp, devanagari)).append("\n")
                    bmp.recycle()
                } finally {
                    page.close()
                }
                if (sb.length >= MAX_CHARS) break
            }
        } finally {
            renderer.close()
            pfd.close()
        }
        return sb.toString()
    }

    private fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Cannot open image")
        val orient = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun ocr(bmp: Bitmap, devanagari: Boolean): String {
        val img = InputImage.fromBitmap(bmp, 0)
        val rec = if (devanagari)
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(rec.process(img)).text.trim()
        } finally {
            rec.close()
        }
    }

    private fun fromImage(bmp: Bitmap, devanagari: Boolean): String {
        val text = try { ocr(bmp, devanagari) } catch (_: Throwable) { "" }
        var labels = ""
        try {
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            labels = Tasks.await(labeler.process(InputImage.fromBitmap(bmp, 0)))
                .filter { it.confidence > 0.6f }.take(6).joinToString(", ") { it.text }
            labeler.close()
        } catch (_: Throwable) {}
        val sb = StringBuilder()
        if (labels.isNotEmpty()) sb.append("Objects seen: ").append(labels).append("\n")
        if (text.isNotEmpty()) sb.append("Text in the picture: ").append(text)
        return sb.toString()
    }
}

object ZipMaker {
    data class CodeFile(val path: String, val content: String)

    private fun safePath(p: String): String {
        val cleaned = p.replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        return if (cleaned.isEmpty()) "file.txt" else cleaned
    }

    fun parse(reply: String): List<CodeFile> {
        val out = mutableListOf<CodeFile>()
        val parts = reply.split(Regex("(?m)^\\s*#{1,4}\\s*FILE:\\s*"))
        for (p in parts.drop(1)) {
            val nl = p.indexOf('\n')
            if (nl < 0) continue
            val path = safePath(p.substring(0, nl).trim().trim('`', '*', ' '))
            var body = p.substring(nl + 1)
            body = body.replace(Regex("^\\s*```[a-zA-Z0-9_+.-]*\\r?\\n"), "")
            val end = body.lastIndexOf("```")
            if (end >= 0) body = body.substring(0, end)
            if (body.isNotBlank()) out.add(CodeFile(path, body.trimEnd() + "\n"))
        }
        if (out.isEmpty() && reply.isNotBlank()) {
            var body = reply.replace(Regex("^\\s*```[a-zA-Z0-9_+.-]*\\r?\\n"), "")
            val end = body.lastIndexOf("```")
            if (end >= 0) body = body.substring(0, end)
            out.add(CodeFile("code.txt", body.trimEnd() + "\n"))
        }
        return out
    }

    fun save(ctx: Context, name: String, files: List<CodeFile>, request: String): String {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.path))
                z.write(f.content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("README.txt"))
            z.write(("Made by NK Voice AI\nRequest: $request\n").toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        val bytes = baos.toByteArray()
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NKVoiceAI")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Cannot save ZIP")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            return "Downloads/NKVoiceAI/$name"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "NKVoiceAI")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        return dir.path + "/" + name
    }
}
