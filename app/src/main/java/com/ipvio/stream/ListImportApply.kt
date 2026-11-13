package com.ipvio.stream

import android.content.Context
import android.widget.Toast

/**
 * Imported lists, kept as their own collections per service (Letterboxd, Trakt, MyAnimeList) and status (watched / watchlist).
 * They appear as categories in Movies and Series. Importing a service again replaces its previous lists.
 */
object ImportedLists {
    val SOURCES = listOf("Letterboxd", "Trakt", "MyAnimeList")
    private const val SEP = "\u001F"
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_importedlists")

    fun set(c: Context, source: String, status: ListImportLogic.Status, ids: List<String>) {
        prefs(c).edit().putString("$source|${status.name}", ids.joinToString(SEP)).apply()
    }

    private fun ids(c: Context, source: String, status: ListImportLogic.Status): List<String> =
        (prefs(c).getString("$source|${status.name}", "") ?: "").split(SEP).filter { it.isNotEmpty() }

    /** Category name shown in the lists, e.g. "Letterboxd · Gesehen". */
    fun title(source: String, status: ListImportLogic.Status) =
        source + " · " + if (status == ListImportLogic.Status.WATCHED) "Gesehen" else "Merkliste"

    /** All non-empty imported lists as categories for [kind] (movies or series), in a stable order. */
    fun groups(c: Context, kind: Kind, byId: Map<String, Item>): Map<String, List<Item>> {
        val out = LinkedHashMap<String, List<Item>>()
        for (src in SOURCES) for (st in ListImportLogic.Status.values()) {
            val l = ids(c, src, st).mapNotNull { byId[it] }.filter { it.kind == kind }
            if (l.isNotEmpty()) out[title(src, st)] = l
        }
        return out
    }

    /** Which service a file belongs to, from its content (same detection as the parser). */
    fun sourceOf(bytes: ByteArray): String {
        if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return "Letterboxd"
        val t = String(bytes, 0, minOf(bytes.size, 2000), Charsets.UTF_8).trimStart().removePrefix("\uFEFF").trimStart()
        return when {
            t.startsWith("<") -> "MyAnimeList"
            t.startsWith("[") || t.startsWith("{") -> "Trakt"
            else -> "Letterboxd"
        }
    }
}

/** Applies an imported list file: matches titles against the loaded playlist and stores them as their own lists. Runs on the main thread. */
object ListImportApply {
    fun run(ctx: Context, name: String, bytes: ByteArray) {
        val rows = runCatching { ListImportLogic.parse(name, bytes) }.getOrDefault(emptyList())
        val hits = ListImportLogic.match(rows, PlaylistStore.channels)
        val source = ImportedLists.sourceOf(bytes)
        // Own collections only: history and "My list" stay untouched
        for (st in ListImportLogic.Status.values()) {
            ImportedLists.set(ctx, source, st, hits.filter { it.second == st }.map { it.first.id }.distinct())
        }
        Toast.makeText(
            ctx,
            Lang.tr("Liste importiert") + ": " + rows.size + " " + Lang.tr("Titel gelesen") + ", " + hits.size + " " + Lang.tr("in deiner Playlist gefunden") +
                " · " + Lang.tr("Kategorien") + " > " + source,
            Toast.LENGTH_LONG
        ).show()
    }
}
