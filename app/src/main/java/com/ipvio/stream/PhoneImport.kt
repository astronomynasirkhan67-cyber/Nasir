package com.ipvio.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** A playlist sent from the phone: either an M3U link or Xtream login data. */
class PhoneInput(val url: String, val host: String, val user: String, val pass: String) {
    val isXtream get() = url.isBlank()
}

/** Pure helper functions (testable, without Android). */
object PhoneImportLogic {
    /** Splits "a=1&b=x%20y" into a map. */
    fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    /** Validates input; null = invalid. */
    fun toInput(f: Map<String, String>): PhoneInput? {
        val url = f["url"].orEmpty().trim()
        if (url.isNotEmpty()) return if (url.startsWith("http://") || url.startsWith("https://")) PhoneInput(url, "", "", "") else null
        val host = f["host"].orEmpty().trim()
        val user = f["user"].orEmpty().trim()
        val pass = f["pass"].orEmpty().trim()
        return if (host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) PhoneInput("", host, user, pass) else null
    }

    fun newCode(rnd: java.util.Random): String = (1000 + rnd.nextInt(9000)).toString()

    /** Language of the phone page from "Accept-Language": the first of the app languages (de, en, tr, bs, hr, sr, es, fr, it), otherwise English. */
    fun langIndex(accept: String): Int {
        for (part in accept.split(',')) {
            val i = I18nCore.CODES.indexOf(part.substringBefore(';').trim().substringBefore('-').lowercase())
            if (i >= 0) return i
        }
        return 1
    }

    /** German text, otherwise translation from the app's dictionaries; if missing, the English text. */
    private fun tr(li: Int, de: String, en: String): String = when (li) {
        0 -> de
        1 -> en
        else -> I18nCore.translate(de, li).takeIf { it != de } ?: en
    }

    fun page(de: Boolean): String = page(if (de) 0 else 1)
    fun message(de: Boolean, kind: String): String = message(if (de) 0 else 1, kind)

    /** What the page additionally offers (round 20). */
    class Features(val upload: Boolean = false, val transfer: Boolean = false, val crash: Boolean = false, val epg: Boolean = false, val list: Boolean = false)

    private const val CSS = """body{font-family:system-ui,sans-serif;background:#0B0F19;color:#fff;margin:0;padding:20px;max-width:560px}
h1{font-size:22px}h2{font-size:16px;color:#9aa;margin-top:28px}a{color:#86EFAC}pre{white-space:pre-wrap;background:#171a26;padding:12px;border-radius:10px;font-size:13px}
input,textarea{width:100%;box-sizing:border-box;padding:14px;margin:6px 0;border-radius:10px;border:1px solid #333;background:#171a26;color:#fff;font-size:16px}
input[type=checkbox]{width:auto}label{display:block;margin:8px 0}
button{width:100%;padding:14px;margin-top:10px;border:0;border-radius:10px;background:#4ADE80;color:#0B0F19;font-size:17px;font-weight:700}"""

    /** Small script: send a file as the body and show the response. */
    private fun sendFileJs(fn: String, path: String, codeId: String, fileId: String, statusId: String): String =
        """<script>function $fn(){var f=document.getElementById('$fileId').files[0];var c=document.getElementById('$codeId').value;if(!f){return}
document.getElementById('$statusId').textContent='…';fetch('$path?code='+encodeURIComponent(c)+'&name='+encodeURIComponent(f.name),{method:'POST',body:f})
.then(function(r){return r.text()}).then(function(t){document.open();document.write(t);document.close()}).catch(function(){document.getElementById('$statusId').textContent='Error'})}</script>"""

    fun page(li: Int): String = page(li, Features())

    fun page(li: Int, feats: Features): String {
        fun t(a: String, b: String) = tr(li, a, b)
        return """<!doctype html><html lang="${I18nCore.CODES.getOrElse(li) { "en" }}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>IPVIO Stream</title><style>
body{font-family:system-ui,sans-serif;background:#0B0F19;color:#fff;margin:0;padding:20px;max-width:560px}
h1{font-size:22px}h2{font-size:16px;color:#9aa;margin-top:28px}
input,textarea{width:100%;box-sizing:border-box;padding:14px;margin:6px 0;border-radius:10px;border:1px solid #333;background:#171a26;color:#fff;font-size:16px}
button{width:100%;padding:14px;margin-top:10px;border:0;border-radius:10px;background:#4ADE80;color:#0B0F19;font-size:17px;font-weight:700}
</style></head><body><h1>IPVIO Stream</h1>
<p>${t("Sende deine Playlist an den Fernseher. Den Code siehst du auf dem Bildschirm.", "Send your playlist to the TV. You can see the code on the screen.")}</p>
<form method="post" action="/send"><h2>${t("M3U-Link", "M3U link")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<textarea name="url" rows="3" placeholder="http://…/playlist.m3u"></textarea>
<button type="submit">${t("An den Fernseher senden", "Send to TV")}</button></form>
<form method="post" action="/send"><h2>${t("oder Xtream-Login", "or Xtream login")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<input name="host" placeholder="${t("Server (z. B. http://server.tld:8080)", "Server (e.g. http://server.tld:8080)")}">
<input name="user" placeholder="${t("Benutzername", "Username")}" autocapitalize="off">
<input name="pass" placeholder="${t("Passwort", "Password")}" autocapitalize="off">
<button type="submit">${t("An den Fernseher senden", "Send to TV")}</button></form>""" +
            (if (feats.upload) """<h2>${t("oder Playlist-Datei vom Handy", "or a playlist file from your phone")}</h2>
<input id="uc" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}">
<input id="uf" type="file" accept=".m3u,.m3u8,.txt,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain">
<button onclick="up()">${t("Datei hochladen", "Upload file")}</button><p id="us"></p>""" + sendFileJs("up", "/upload", "uc", "uf", "us") else "") +
            (if (feats.epg) """<form method="post" action="/epg"><h2>${t("TV-Guide-Adresse (EPG, XMLTV)", "TV guide address (EPG, XMLTV)")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<textarea name="epg" rows="3" placeholder="http://…/xmltv.php?username=…&amp;password=…"></textarea>
<button type="submit">${t("An den Fernseher senden", "Send to TV")}</button></form>""" else "") +
            (if (feats.list) """<h2>${t("Listen importieren (Letterboxd, MyAnimeList, Trakt)", "Import lists (Letterboxd, MyAnimeList, Trakt)")}</h2>
<p>${t("Exportdatei wählen: Letterboxd ZIP oder CSV, MyAnimeList XML, Trakt JSON. Titel, die in deiner Playlist vorkommen, kommen in Merkliste bzw. Verlauf.", "Choose the export file: Letterboxd ZIP or CSV, MyAnimeList XML, Trakt JSON. Titles found in your playlist go to the watchlist or history.")}</p>
<input id="lc" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}">
<input id="lf" type="file">
<button onclick="lu()">${t("Liste hochladen", "Upload list")}</button><p id="ls"></p>""" + sendFileJs("lu", "/listimport", "lc", "lf", "ls") else "") +
            (if (feats.transfer) """<h2><a href="/transfer">${t("Einstellungen auf ein anderes Gerät übertragen", "Transfer settings to another device")}</a></h2>""" else "") +
            (if (feats.crash) """<h2><a href="/crash">${t("Letzten Absturzbericht anzeigen", "Show the last crash report")}</a></h2>""" else "") +
            "</body></html>"
    }

    /** Transfer: download a backup (optionally with playlists) or restore a backup. */
    fun transferPage(li: Int): String {
        fun t(a: String, b: String) = tr(li, a, b)
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>IPVIO Stream</title><style>$CSS</style></head><body>
<h1>${t("Auf ein anderes Gerät übertragen", "Transfer to another device")}</h1>
<p>${t("1. Auf dem alten Gerät die Sicherung herunterladen. 2. Auf dem neuen Gerät diese Seite öffnen und die Datei einspielen.", "1. Download the backup on the old device. 2. Open this page on the new device and upload the file.")}</p>
<form method="get" action="/backup"><h2>${t("Sicherung herunterladen", "Download backup")}</h2>
<input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<label><input type="checkbox" name="pl" value="1"> ${t("Playlists mit Zugangsdaten mitnehmen", "Include playlists with logins")}</label>
<button type="submit">${t("Herunterladen", "Download")}</button></form>
<h2>${t("Sicherung einspielen", "Upload backup")}</h2>
<input id="rc" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}">
<input id="rf" type="file" accept=".json,application/json">
<button onclick="rs()">${t("Einspielen", "Restore")}</button><p id="rst"></p>""" + sendFileJs("rs", "/restore", "rc", "rf", "rst") +
            """<p>${t("Die Datei mit Playlists enthält deine Zugangsdaten. Gib sie nicht weiter.", "The file with playlists contains your logins. Do not share it.")}</p></body></html>"""
    }

    /** Crash report: first ask for the code, then show the text (to copy on the phone). */
    fun crashPage(li: Int, text: String?): String {
        fun t(a: String, b: String) = tr(li, a, b)
        val esc = text?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace(">", "&gt;")
        val body = when {
            text == null -> """<form method="get" action="/crash"><input name="code" inputmode="numeric" maxlength="4" placeholder="${t("Code vom Fernseher", "Code from the TV")}" required>
<button type="submit">${t("Anzeigen", "Show")}</button></form>"""
            text.isBlank() -> "<p>" + t("Kein Absturz gespeichert.", "No crash saved.") + "</p>"
            else -> "<p>" + t("Text markieren und kopieren, z. B. für einen Fehlerbericht auf GitHub.", "Select and copy the text, e.g. for a bug report on GitHub.") + "</p><pre>$esc</pre>"
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>IPVIO Stream</title><style>$CSS</style></head><body>
<h1>${t("Absturzbericht", "Crash report")}</h1>$body</body></html>"""
    }

    fun message(li: Int, kind: String): String {
        val txt = when (kind) {
            "ok" -> tr(li, "Gesendet. Schau jetzt auf den Fernseher.", "Sent. Now look at the TV.")
            "code" -> tr(li, "Falscher Code. Bitte zurück und erneut versuchen.", "Wrong code. Go back and try again.")
            "bad" -> tr(li, "Eingabe unvollständig oder ungültig.", "Input incomplete or invalid.")
            "file" -> tr(li, "Die Datei konnte nicht verwendet werden.", "The file could not be used.")
            "restored" -> tr(li, "Eingespielt. Die App startet auf dem Fernseher neu.", "Restored. The app restarts on the TV.")
            else -> tr(li, "Zu viele Fehlversuche. Auf dem Fernseher neu starten.", "Too many attempts. Restart on the TV.")
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="font-family:system-ui,sans-serif;background:#0B0F19;color:#fff;padding:24px"><h2>$txt</h2></body></html>"""
    }
}

/**
 * Small server in the local network: the phone opens the displayed address and sends the playlist,
 * so nothing has to be typed on the TV. Runs only while the screen for it is open.
 * Protected by a four-digit code and a lock after 5 failed attempts.
 *
 * Round 20: additionally upload a playlist file ([saveUpload]), download/restore a backup ([backup]/[restore],
 * for transfer to another device) and read the last crash report ([crash]). What is not set
 * does not appear on the page.
 */
class PhoneImport(private val onReceive: (PhoneInput) -> Unit) {
    @Volatile private var server: ServerSocket? = null
    private val fails = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lockedAt = 0L
    val code: String = PhoneImportLogic.newCode(SecureRandom())
    @Volatile var port: Int = 0
        private set

    /** Store an uploaded playlist file: (file name, content) → address "file://…" or null. */
    var saveUpload: ((String, java.io.File) -> String?)? = null
    /** Folder for uploads (temporary), set together with [saveUpload]. */
    var uploadDir: java.io.File? = null
    /** Create the backup as text; parameter = with playlists. */
    var backup: ((Boolean) -> String?)? = null
    /** Restore a backup; true = success. */
    var restore: ((String) -> Boolean)? = null
    /** Receives a TV guide (XMLTV) address typed on the phone; set = the field appears on the page. */
    var onEpg: ((String) -> Unit)? = null
    /** Last crash report as text, null = none. */
    var crash: (() -> String?)? = null
    /** Phone search: answer a request under /api/<name> (JSON) or null = unknown. Set = the /r page is active. */
    var api: ((String, Map<String, String>) -> String?)? = null
    /** Import of an exported list file (file name, content); runs on the server thread, so the callback must post to the main thread itself. */
    var onList: ((String, ByteArray) -> Unit)? = null

    fun start(): Boolean {
        if (server != null) return true
        val ss = (8765..8775).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return false
        server = ss
        port = ss.localPort
        thread(isDaemon = true, name = "phone-import") {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(s) }; runCatching { s.close() } }
            }
        }
        return true
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private class Head(val method: String, val path: String, val query: Map<String, String>, val len: Long, val lang: String)

    private fun readHead(ins: InputStream): Head? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < 8192) {
            val b = ins.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val text = head.toString("UTF-8")
        val lines = text.split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val lang = lines.firstOrNull { it.startsWith("Accept-Language:", true) }?.substringAfter(':')?.trim().orEmpty()
        val target = first[1]
        val q = if (target.contains('?')) runCatching { PhoneImportLogic.parseForm(target.substringAfter('?')) }.getOrDefault(emptyMap()) else emptyMap()
        return Head(first[0], target.substringBefore('?'), q, len, lang)
    }

    private fun readSmall(ins: InputStream, len: Long): String? {
        if (len > 8192) return null
        val body = ByteArray(len.toInt())
        var n = 0
        while (n < len) { val r = ins.read(body, n, len.toInt() - n); if (r < 0) break; n += r }
        return String(body, 0, n, Charsets.UTF_8)
    }

    /** Body directly into a file (large playlists never sit completely in memory). */
    private fun readToFile(ins: InputStream, len: Long, f: java.io.File): Boolean {
        java.io.FileOutputStream(f).use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) return false
                out.write(buf, 0, r); left -= r
            }
        }
        return true
    }

    /**
     * Reads up to [len] bytes into memory without reserving [len] bytes up front: memory grows only with the data
     * that actually arrives. If the sender stops early, the bytes received so far are returned.
     */
    private fun readBody(ins: InputStream, len: Long): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (total < len) {
            val chunk = ByteArray(minOf(64L * 1024, len - total).toInt())
            var n = 0
            while (n < chunk.size) { val k = ins.read(chunk, n, chunk.size - n); if (k < 0) break; n += k }
            if (n == 0) break
            chunks.add(if (n == chunk.size) chunk else chunk.copyOf(n))
            total += n
            if (n < chunk.size) break
        }
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) { c.copyInto(out, pos); pos += c.size }
        return out
    }

    private fun codeOk(given: String?): Int = when {
        fails.get() >= 5 && System.currentTimeMillis() - lockedAt < 60_000 -> 429
        given.orEmpty().trim() != code -> {
            // thread-safe counter; the lock ends after one minute without new attempts
            if (fails.get() >= 5) fails.set(0)
            if (fails.incrementAndGet() >= 5) lockedAt = System.currentTimeMillis()
            403
        }
        else -> { fails.set(0); 200 }
    }

    private class Reply(val status: Int, val body: ByteArray, val type: String = "text/html; charset=utf-8", val extra: String = "")

    private fun html(status: Int, s: String) = Reply(status, s.toByteArray(Charsets.UTF_8))

    private fun handle(s: Socket) {
        s.soTimeout = 15000
        val ins = s.getInputStream()
        val h = readHead(ins) ?: return
        val li = PhoneImportLogic.langIndex(h.lang)
        val feats = PhoneImportLogic.Features(saveUpload != null, backup != null, crash != null, onEpg != null, onList != null)
        val r: Reply = when (h.method + " " + h.path) {
            "GET /" -> html(200, PhoneImportLogic.page(li, feats))
            "POST /send" -> {
                val f = PhoneImportLogic.parseForm(readSmall(ins, h.len) ?: "")
                when (val c = codeOk(f["code"])) {
                    200 -> {
                        val input = PhoneImportLogic.toInput(f)
                        if (input == null) html(400, PhoneImportLogic.message(li, "bad"))
                        else { onReceive(input); html(200, PhoneImportLogic.message(li, "ok")) }
                    }
                    403 -> html(403, PhoneImportLogic.message(li, "code"))
                    else -> html(c, PhoneImportLogic.message(li, "lock"))
                }
            }
            // TV guide (XMLTV) address typed on the phone
            "POST /epg" -> {
                val f = PhoneImportLogic.parseForm(readSmall(ins, h.len) ?: "")
                val cb = onEpg
                when (val c = codeOk(f["code"])) {
                    200 -> {
                        val u = f["epg"].orEmpty().trim()
                        if (cb == null || !(u.startsWith("http://") || u.startsWith("https://"))) html(400, PhoneImportLogic.message(li, "bad"))
                        else { cb(u); html(200, PhoneImportLogic.message(li, "ok")) }
                    }
                    403 -> html(403, PhoneImportLogic.message(li, "code"))
                    else -> html(c, PhoneImportLogic.message(li, "lock"))
                }
            }
            // Exported lists from other services (parsed on the TV)
            "POST /listimport" -> {
                val cb = onList
                val c = codeOk(h.query["code"])
                if (cb == null) html(404, PhoneImportLogic.message(li, "bad"))
                else if (c != 200) html(c, PhoneImportLogic.message(li, if (c == 403) "code" else "lock"))
                else if (h.len <= 0) html(400, PhoneImportLogic.message(li, "bad"))
                else if (h.len > MAX_LIST_UPLOAD) html(413, PhoneImportLogic.message(li, "file"))
                else {
                    cb(h.query["name"].orEmpty(), readBody(ins, h.len))
                    html(200, PhoneImportLogic.message(li, "ok"))
                }
            }
            // Playlist file: the browser sends the file as the body (small script on the page)
            "POST /upload" -> {
                val save = saveUpload; val dir = uploadDir
                val c = codeOk(h.query["code"])
                if (save == null || dir == null) html(404, PhoneImportLogic.message(li, "bad"))
                else if (c != 200) html(c, PhoneImportLogic.message(li, if (c == 403) "code" else "lock"))
                else if (h.len <= 0 || h.len > MAX_UPLOAD) html(400, PhoneImportLogic.message(li, "bad"))
                else {
                    dir.mkdirs()
                    val tmp = java.io.File(dir, "upload_" + System.currentTimeMillis() + ".part")
                    val ok = runCatching { readToFile(ins, h.len, tmp) }.getOrDefault(false)
                    val url = if (ok) save(h.query["name"].orEmpty(), tmp) else null
                    tmp.delete()
                    if (url == null) html(400, PhoneImportLogic.message(li, "file"))
                    else { onReceive(PhoneInput(url, "", "", "")); html(200, PhoneImportLogic.message(li, "ok")) }
                }
            }
            "GET /transfer" -> if (backup != null) html(200, PhoneImportLogic.transferPage(li)) else html(404, PhoneImportLogic.message(li, "bad"))
            "GET /backup" -> {
                val b = backup
                val c = codeOk(h.query["code"])
                if (b == null) html(404, PhoneImportLogic.message(li, "bad"))
                else if (c != 200) html(c, PhoneImportLogic.message(li, if (c == 403) "code" else "lock"))
                else {
                    val t = b(h.query["pl"] == "1")
                    if (t == null) html(500, PhoneImportLogic.message(li, "bad"))
                    else Reply(200, t.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", "Content-Disposition: attachment; filename=\"ipvio_backup.json\"\r\n")
                }
            }
            "POST /restore" -> {
                val rs = restore
                val c = codeOk(h.query["code"])
                if (rs == null) html(404, PhoneImportLogic.message(li, "bad"))
                else if (c != 200) html(c, PhoneImportLogic.message(li, if (c == 403) "code" else "lock"))
                else if (h.len <= 0 || h.len > 4L * 1024 * 1024) html(400, PhoneImportLogic.message(li, "bad"))
                else {
                    val bytes = ByteArray(h.len.toInt())
                    var n = 0
                    while (n < bytes.size) { val k = ins.read(bytes, n, bytes.size - n); if (k < 0) break; n += k }
                    if (rs(String(bytes, 0, n, Charsets.UTF_8))) html(200, PhoneImportLogic.message(li, "restored"))
                    else html(400, PhoneImportLogic.message(li, "file"))
                }
            }
            "GET /crash" -> {
                val cr = crash
                val given = h.query["code"]
                if (cr == null) html(404, PhoneImportLogic.message(li, "bad"))
                else if (given == null) html(200, PhoneImportLogic.crashPage(li, null))
                else when (val c = codeOk(given)) {
                    200 -> html(200, PhoneImportLogic.crashPage(li, cr() ?: ""))
                    403 -> html(403, PhoneImportLogic.message(li, "code"))
                    else -> html(c, PhoneImportLogic.message(li, "lock"))
                }
            }
            "GET /r" -> if (api != null) html(200, RemoteLogic.page(li)) else html(404, PhoneImportLogic.message(li, "bad"))
            else -> {
                val a = api
                if (h.method == "GET" && a != null && h.path.startsWith("/api/")) {
                    val c = codeOk(h.query["code"])
                    if (c != 200) Reply(c, "{}".toByteArray(), "application/json; charset=utf-8")
                    else {
                        val j = a(h.path.removePrefix("/api/"), h.query)
                        if (j == null) Reply(404, "{}".toByteArray(), "application/json; charset=utf-8")
                        else Reply(200, j.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
                    }
                } else html(404, PhoneImportLogic.message(li, "bad"))
            }
        }
        val out = s.getOutputStream()
        val reason = when (r.status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write(("HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; img-src data:; frame-ancestors 'none'\r\n" +
            r.extra + "Content-Length: ${r.body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(r.body)
        out.flush()
    }

    companion object {
        /** Largest uploadable playlist file. */
        const val MAX_UPLOAD = 400L * 1024 * 1024

        /** Largest uploadable list export (Letterboxd, MyAnimeList, Trakt); same limit as the file picker on the TV (ListImportUi). */
        const val MAX_LIST_UPLOAD = 16L * 1024 * 1024

        /** Local IPv4 address of the device in the Wi-Fi/LAN (or null). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

