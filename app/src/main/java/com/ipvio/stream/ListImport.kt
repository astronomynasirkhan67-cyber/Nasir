package com.ipvio.stream

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Import of lists exported from other services (Letterboxd ZIP/CSV, MyAnimeList XML, Trakt JSON). Pure Kotlin so it can be unit tested.
 * The parsers are tolerant: unknown columns/fields are ignored, anything without a title is skipped.
 */
object ListImportLogic {
    enum class Status { WATCHED, WATCHLIST }

    data class Row(val title: String, val year: Int?, val status: Status, val series: Boolean?)

    /** Entry point: detects the format from the content (ZIP magic, leading '<' or '[' / '{', else CSV). */
    fun parse(fileName: String, bytes: ByteArray): List<Row> {
        val lname = fileName.lowercase()
        if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return parseZip(bytes)
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val t = text.trimStart()
        val defaultStatus = if (lname.contains("watchlist") || lname.contains("plan")) Status.WATCHLIST else Status.WATCHED
        return when {
            t.startsWith("<") -> parseMal(t)
            t.startsWith("[") || t.startsWith("{") -> parseTrakt(t, defaultStatus)
            else -> parseCsv(t, defaultStatus)
        }
    }

    private fun parseZip(bytes: ByteArray): List<Row> {
        val out = ArrayList<Row>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/').lowercase()
                if (e.isDirectory || !n.endsWith(".csv")) continue
                // Letterboxd: watchlist.csv = to watch; watched.csv and ratings.csv = seen; other files are ignored
                val st = when (n) { "watchlist.csv" -> Status.WATCHLIST; "watched.csv", "ratings.csv" -> Status.WATCHED; else -> null } ?: continue
                val text = String(z.readBytes(), Charsets.UTF_8).removePrefix("﻿")
                out += parseCsv(text, st)
            }
        }
        return out
    }

    /** CSV with a header row; needs a "Name" or "Title" column, optional "Year". */
    fun parseCsv(text: String, status: Status): List<Row> {
        val rows = csvRows(text)
        if (rows.isEmpty()) return emptyList()
        val head = rows[0].map { it.trim().lowercase() }
        val ti = head.indexOfFirst { it == "name" || it == "title" }
        if (ti < 0) return emptyList()
        val yi = head.indexOf("year")
        return rows.drop(1).mapNotNull { r ->
            val title = r.getOrNull(ti)?.trim().orEmpty()
            if (title.isEmpty()) null else Row(title, r.getOrNull(yi)?.trim()?.toIntOrNull(), status, null)
        }
    }

    /** Minimal CSV reader: quotes, doubled quotes, commas and line breaks inside quotes. */
    fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { cur.add(sb.toString()); sb.setLength(0) }
                '\n' -> { cur.add(sb.toString()); sb.setLength(0); rows.add(cur); cur = ArrayList() }
                '\r' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return unxml(m.groupValues[1])
    }

    private fun unxml(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) t = t.substring(9, t.length - 3)
        return t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").trim()
    }

    /** MyAnimeList export: &lt;anime&gt; blocks with series_title, series_type, my_status. */
    fun parseMal(xml: String): List<Row> =
        Regex("<anime>(.*?)</anime>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            val title = tag(b, "series_title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = tag(b, "my_status").orEmpty().lowercase()
            val st = if (status.contains("plan")) Status.WATCHLIST else Status.WATCHED
            val movie = tag(b, "series_type").orEmpty().lowercase() == "movie"
            Row(title, null, st, !movie)
        }.toList()

    /**
     * Trakt JSON, tolerant: walks the whole document and takes every object that has a "movie" or "show" child with a "title",
     * or (for flat lists) an object with "title" directly. "watchlist" in the file name or "listed_at" = watchlist.
     */
    fun parseTrakt(json: String, defaultStatus: Status): List<Row> {
        val root = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull() ?: return emptyList()
        val out = ArrayList<Row>()
        fun row(o: org.json.JSONObject, series: Boolean?, parent: org.json.JSONObject?) {
            val title = o.optString("title").trim()
            if (title.isEmpty()) return
            val listed = parent?.has("listed_at") == true && !parent.has("watched_at") && !parent.has("last_watched_at") && !parent.has("plays")
            val st = if (listed) Status.WATCHLIST else defaultStatus
            out.add(Row(title, o.optInt("year", 0).takeIf { it > 0 }, st, series))
        }
        fun walk(n: Any?) {
            when (n) {
                is org.json.JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
                is org.json.JSONObject -> {
                    val mv = n.optJSONObject("movie"); val sh = n.optJSONObject("show")
                    when {
                        mv != null -> row(mv, false, n)
                        sh != null -> row(sh, true, n)
                        n.has("title") -> row(n, null, null)
                        else -> for (k in n.keys()) walk(n.opt(k))
                    }
                }
            }
        }
        walk(root)
        return out
    }

    /** Matches rows against the library; returns pairs of (item, status). Same normalized title and (if both known) year ±1. */
    fun match(rows: List<Row>, items: List<Item>): List<Pair<Item, Status>> {
        val byTitle = HashMap<String, MutableList<Item>>()
        for (i in items) if (i.kind != Kind.LIVE) byTitle.getOrPut(MatchLogic.norm(MatchLogic.searchTitle(i.title))) { ArrayList() }.add(i)
        val out = ArrayList<Pair<Item, Status>>()
        val seen = HashSet<String>()
        for (r in rows) {
            val c = byTitle[MatchLogic.norm(r.title)] ?: continue
            val hit = c.firstOrNull { i ->
                (r.year == null || i.year == null || Math.abs(r.year - i.year) <= 1) &&
                    (r.series == null || (i.kind == Kind.SERIES) == r.series)
            } ?: continue
            if (seen.add(hit.id + r.status)) out.add(hit to r.status)
        }
        return out
    }
}
