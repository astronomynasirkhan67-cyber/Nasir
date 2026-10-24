package com.nasir.voicechat

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds PDF / document / ZIP files on the phone and saves them in Downloads/NK Voice AI. */
object FileMaker {

    private val ASK = Regex("make|create|generate|give|write|prepare|need|want|provide|send|بنا|دو|چاہیے|बना|दे|चाहिए|चाहिये")

    // Returns "led", "pdf", "doc", "zip" or null (normal chat).
    fun detect(text: String): String? {
        val s = text.lowercase()
        val led = Regex("\\bleds?\\b|एलईडी|ایل ای ڈی").containsMatchIn(s) &&
            Regex("blink|flash|chaser|ब्लिंक|بلنک").containsMatchIn(s)
        if (led) return "led"
        val ask = ASK.containsMatchIn(s)
        if (ask && Regex("pdf|पीडीएफ|پی ڈی ایف").containsMatchIn(s)) return "pdf"
        if (ask && Regex("\\bdocument\\b|\\bdoc\\b|docx|word file|डॉक्यूमेंट|دستاویز").containsMatchIn(s)) return "doc"
        if (ask && Regex("\\bzip\\b|ज़िप|जिप|زپ").containsMatchIn(s)) return "zip"
        return null
    }

    private val FILLER = setOf(
        "please", "make", "create", "generate", "give", "write", "prepare", "provide", "send", "me", "a", "an", "the",
        "pdf", "document", "doc", "docx", "file", "on", "about", "of", "topic", "for", "my", "subject", "in", "as",
        "i", "need", "want", "can", "you", "to", "word", "with", "notes", "and", "short"
    )

    fun topicOf(request: String): String {
        val words = request.trim().split(Regex("\\s+")).filter { it.lowercase().trim(',', '.', '?', '!') !in FILLER }
        val t = words.joinToString(" ").trim()
        return if (t.isBlank()) request.trim() else t.take(120)
    }

    fun titleOf(topic: String) = topic.trim().replaceFirstChar { it.uppercase() }

    fun safeName(topic: String): String {
        val n = topic.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(30)
        return n.ifEmpty { "document" }
    }

    fun stamp(): String = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------ save

    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): android.net.Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NK Voice AI")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException("Could not create file in Downloads")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("Could not write file")
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(null), "files").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    }

    // ------------------------------------------------------------ ZIP

    fun zip(files: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // Picks a file name from the request and removes ``` fences the model may add.
    fun codeFile(request: String, raw: String): Pair<String, String> {
        val s = request.lowercase()
        val name = when {
            "arduino" in s || "esp" in s -> "main.ino"
            "python" in s || "raspberry" in s -> "main.py"
            "c++" in s || "cpp" in s -> "main.cpp"
            "java" in s && "javascript" !in s -> "Main.java"
            "javascript" in s -> "main.js"
            "html" in s -> "index.html"
            "kotlin" in s -> "main.kt"
            Regex("\\bc\\b").containsMatchIn(s) -> "main.c"
            else -> "code.txt"
        }
        val fence = Regex("(?s)```[a-zA-Z+]*\\n(.*?)```").find(raw)
        val code = fence?.groupValues?.get(1) ?: raw.replace("```", "")
        return name to code.trim() + "\n"
    }

    // ------------------------------------------------------------ PDF

    fun pdf(title: String, body: String): ByteArray {
        val W = 595
        val H = 842
        val M = 48f
        val doc = PdfDocument()
        val normal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
        val h1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val h2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = M

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun block(text: String, paint: TextPaint, indent: Float, gapAfter: Float) {
            if (text.isEmpty()) return
            val width = (W - 2 * M - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setLineSpacing(3f, 1f).build()
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i)
                val lh = (layout.getLineBottom(i) - top).toFloat()
                if (y + lh > H - M) newPage()
                val base = y + (layout.getLineBaseline(i) - top)
                page.canvas.drawText(text, layout.getLineStart(i), layout.getLineEnd(i), M + indent, base, paint)
                y += lh
            }
            y += gapAfter
        }

        block(title, h1, 0f, 14f)
        for (raw in body.lines()) {
            val line = raw.trim().replace("**", "")
            when {
                line.isEmpty() -> y += 2f
                line.startsWith("## ") -> { y += 6f; block(line.removePrefix("## "), h2, 0f, 4f) }
                line.startsWith("# ") -> { y += 6f; block(line.removePrefix("# "), h2, 0f, 4f) }
                line.startsWith("- ") || line.startsWith("* ") -> block("• " + line.substring(2), normal, 12f, 3f)
                else -> block(line, normal, 0f, 5f)
            }
        }
        doc.finishPage(page)
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos)
        doc.close()
        return bos.toByteArray()
    }

    // ------------------------------------------------------------ Document (.doc, opens in Word / WPS)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun doc(title: String, body: String): ByteArray {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title></head>")
        sb.append("<body style=\"font-family:Calibri,Arial;font-size:12pt\"><h1>").append(esc(title)).append("</h1>")
        var inList = false
        fun closeList() { if (inList) { sb.append("</ul>"); inList = false } }
        for (raw in body.lines()) {
            val line = raw.trim().replace("**", "")
            when {
                line.isEmpty() -> {}
                line.startsWith("## ") || line.startsWith("# ") -> {
                    closeList(); sb.append("<h2>").append(esc(line.trimStart('#', ' '))).append("</h2>")
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) { sb.append("<ul>"); inList = true }
                    sb.append("<li>").append(esc(line.substring(2))).append("</li>")
                }
                else -> { closeList(); sb.append("<p>").append(esc(line)).append("</p>") }
            }
        }
        closeList()
        sb.append("</body></html>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ LED blinking code pack (works offline, no AI needed)

    fun ledZip(): ByteArray = zip(listOf(
        "README.txt" to """
LED BLINKING CODE PACK  (made by NK Voice AI)

What you need: 1 LED, 1 resistor (220 to 330 ohm), jumper wires, a board.
IMPORTANT: always use a resistor in series with the LED, or the LED / board can burn.
LED long leg (+) goes toward the board pin through the resistor. Short leg (-) goes to GND.

1) arduino_uno_blink/arduino_uno_blink.ino   Arduino Uno / Nano   pin 13  (board LED also blinks)
2) arduino_3_led_chaser/arduino_3_led_chaser.ino   3 LEDs on pins 8, 9, 10 running one after another
3) esp32_blink/esp32_blink.ino   ESP32   GPIO 2 (onboard LED on many boards)
4) raspberry_pi_blink.py   Raspberry Pi   GPIO 17 (physical pin 11).  Run: python3 raspberry_pi_blink.py
5) micropython_pico_blink.py   Raspberry Pi Pico / Pico W with MicroPython

Arduino / ESP32: open the .ino file in Arduino IDE, choose your board and port, press Upload.
Change delay(500) to a smaller number for faster blinking (value is in milliseconds).
""".trimIndent() + "\n",
        "arduino_uno_blink/arduino_uno_blink.ino" to """
// LED blinking - Arduino Uno / Nano
// Wiring: pin 13 -> 220 ohm resistor -> LED long leg (+), LED short leg (-) -> GND
const int LED_PIN = 13;

void setup() {
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_PIN, HIGH);  // LED on
  delay(500);                   // wait 0.5 second
  digitalWrite(LED_PIN, LOW);   // LED off
  delay(500);
}
""".trimIndent() + "\n",
        "arduino_3_led_chaser/arduino_3_led_chaser.ino" to """
// 3 LED chaser - Arduino Uno / Nano
// Wiring: pins 8, 9, 10 -> each through its own 220 ohm resistor -> LED (+); all LED (-) -> GND
const int LEDS[] = {8, 9, 10};
const int COUNT = 3;

void setup() {
  for (int i = 0; i < COUNT; i++) pinMode(LEDS[i], OUTPUT);
}

void loop() {
  for (int i = 0; i < COUNT; i++) {
    digitalWrite(LEDS[i], HIGH);
    delay(200);
    digitalWrite(LEDS[i], LOW);
  }
}
""".trimIndent() + "\n",
        "esp32_blink/esp32_blink.ino" to """
// LED blinking - ESP32
// GPIO 2 is the onboard LED on many ESP32 boards. For an outside LED use a resistor too.
const int LED_PIN = 2;

void setup() {
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_PIN, HIGH);
  delay(500);
  digitalWrite(LED_PIN, LOW);
  delay(500);
}
""".trimIndent() + "\n",
        "raspberry_pi_blink.py" to """
# LED blinking - Raspberry Pi (GPIO 17 = physical pin 11)
# Wiring: GPIO17 -> 330 ohm resistor -> LED long leg (+), LED short leg (-) -> GND (pin 9)
from gpiozero import LED
from time import sleep

led = LED(17)

while True:
    led.on()
    sleep(0.5)
    led.off()
    sleep(0.5)
""".trimIndent() + "\n",
        "micropython_pico_blink.py" to """
# LED blinking - Raspberry Pi Pico / Pico W (MicroPython)
from machine import Pin
import time

try:
    led = Pin("LED", Pin.OUT)   # onboard LED (Pico W, newer firmware)
except Exception:
    led = Pin(25, Pin.OUT)      # onboard LED (original Pico)

while True:
    led.toggle()
    time.sleep(0.5)
""".trimIndent() + "\n"
    ))
}
