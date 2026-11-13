package com.ipvio.stream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary cache of the playlist (without Android, testable).
 * Version 5: contains only the shown languages (large providers: 800,000 entries → e.g. 120,000) and in the head
 * the count and amount of data per language of the whole list as well as the languages hidden at that time.
 * The whole list is additionally stored compressed on the device (pl_*.m3u.gz); with a different language selection it is parsed again from it.
 * Version 4 (whole list, language at the front of each entry) and 3 (without language) are still read.
 */
object PlaylistCacheIO {
    const val VERSION = 5
    private const val MAX_STR = 1 shl 20      // 1 MB per text: anything larger can only be a corrupt file
    private const val MAX_COUNT = 2_000_000

    /**
     * [hidden] = languages hidden when writing (version 5 only); null = the cache contains the whole list (version 3/4).
     * [bytes] = amount of data per language of the whole list (for the memory estimate), empty for version 3/4.
     */
    class Cached(
        val items: List<Item>, val epg: List<String>, val order: Map<String, Int>, val counts: Map<String, Int>,
        val bytes: Map<String, Long> = emptyMap(), val hidden: Set<String>? = null
    )

    /**
     * Writes version 5. [list] = the shown entries; [counts]/[bytes] describe the whole list.
     * Without [counts] it is counted from [list] (then [list] contains everything, [hidden] is empty).
     */
    fun write(
        out: OutputStream, url: String, list: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>? = null, bytes: Map<String, Long> = emptyMap(), hidden: Set<String> = emptySet()
    ) {
        val o = DataOutputStream(out)
        o.writeInt(VERSION)
        o.str(url.trim())
        o.writeInt(epg.size); epg.forEach { o.str(it) }
        o.writeInt(order.size); order.forEach { (k, v) -> o.str(k); o.writeInt(v) }
        o.str(LangTagLogic.encode(hidden))
        val c = counts ?: HashMap<String, Int>().also { m -> for (i in list) m[i.lang] = (m[i.lang] ?: 0) + 1 }
        o.writeInt(c.size); c.forEach { (k, v) -> o.str(k); o.writeInt(v) }
        o.writeInt(bytes.size); bytes.forEach { (k, v) -> o.str(k); o.writeLong(v) }
        o.writeInt(list.size)
        for (i in list) o.item(i)
        o.flush()
    }

    /** Head of the cache (address checked); the entries follow afterwards in the data stream. */
    private class Head(
        val version: Int, val epg: List<String>, val order: Map<String, Int>,
        val hidden: Set<String>?, val counts: Map<String, Int>?, val bytes: Map<String, Long>
    )

    private fun DataInputStream.head(url: String): Head? {
        val version = readInt()
        if (version !in 3..5) return null
        if (str() != url.trim()) return null
        val ne = count()
        val epg = ArrayList<String>(ne)
        for (k in 0 until ne) epg.add(str() ?: "")
        val no = count()
        val order = LinkedHashMap<String, Int>()
        for (k in 0 until no) { val key = str() ?: ""; order[key] = readInt() }
        if (version < 5) return Head(version, epg, order, null, null, emptyMap())
        val stored = LangTagLogic.decode(str())
        val nc = count()
        val counts = HashMap<String, Int>()
        for (k in 0 until nc) { val key = str() ?: ""; counts[key] = readInt() }
        val nb = count()
        val bytes = HashMap<String, Long>()
        for (k in 0 until nb) { val key = str() ?: ""; bytes[key] = readLong() }
        return Head(version, epg, order, stored, counts, bytes)
    }

    /**
     * Only the head, without entries: EPG addresses, category order, counters and the languages hidden at that time.
     * [Cached.hidden] = null means: the cache contains the whole list (version 3/4). The counters are then empty.
     */
    fun readHeader(input: InputStream, url: String): Cached? {
        val h = DataInputStream(input).head(url) ?: return null
        return Cached(emptyList(), h.epg, h.order, h.counts ?: emptyMap(), h.bytes, h.hidden)
    }

    /**
     * Reads the cache; null if it belongs to a different address or has an unknown version. Damaged → exception.
     * [check] is called every 2,000 entries (memory guard, abort).
     */
    fun read(input: InputStream, url: String, hidden: Set<String>, check: (() -> Unit)? = null): Cached? {
        val i = DataInputStream(input)
        val h = i.head(url) ?: return null
        val n = i.count()
        val pool = StringPool()
        val counts = HashMap<String, Int>()
        val items = ArrayList<Item>(if (hidden.isEmpty()) n else minOf(n, 4096))
        for (k in 0 until n) {
            i.readItem(h.version, pool, hidden, counts)?.let { items.add(it) }
            if (check != null && k % 2000 == 1999) check()
        }
        items.trimToSize()
        return Cached(items, h.epg, h.order, h.counts ?: counts, h.bytes, h.hidden)
    }

    private fun DataInputStream.count(): Int {
        val n = readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("Zwischenspeicher beschädigt")
        return n
    }

    private fun DataOutputStream.str(s: String?) {
        if (s == null) writeInt(-1) else { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    }

    private fun DataInputStream.str(): String? {
        val n = readInt()
        if (n < 0) return null
        if (n > MAX_STR) throw IOException("Zwischenspeicher beschädigt")
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    /** Skips a text without creating it (for hidden entries). */
    private fun DataInputStream.skipStr() {
        val n = readInt()
        if (n < 0) return
        if (n > MAX_STR) throw IOException("Zwischenspeicher beschädigt")
        var left = n
        while (left > 0) {
            val k = skipBytes(left)
            if (k <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= k
        }
    }

    private fun DataOutputStream.item(i: Item) {
        str(i.id); writeInt(i.kind.ordinal); str(i.lang); str(i.title); writeInt(i.year ?: -1); str(i.url); str(i.logo); str(i.group)
        str(i.quality); str(i.tvgId)
        writeInt(i.episodes.size)
        for (e in i.episodes) {
            writeInt(e.season); writeInt(e.num); str(e.title); str(e.url); str(e.quality)
            writeInt(e.variants.size)
            for (v in e.variants) { str(v.url); str(v.label); str(v.quality); str(v.lang) }
        }
    }

    /** Reads an entry; hidden languages return null (they are counted anyway). */
    private fun DataInputStream.readItem(version: Int, pool: StringPool, hidden: Set<String>, counts: HashMap<String, Int>): Item? {
        val id = str() ?: ""
        val kind = Kind.entries.getOrNull(readInt()) ?: throw IOException("Zwischenspeicher beschädigt")
        var lang: String? = if (version >= 4) pool.of(str() ?: LangTagLogic.UNKNOWN) else null
        if (lang != null) {
            counts[lang] = (counts[lang] ?: 0) + 1
            if (LangTagLogic.hides(hidden, lang, kind)) { skipItemRest(); return null }
        }
        // Older caches: titles still with "&amp;" or "|EN|" – clean up while reading (fast, mostly unchanged)
        val rawTitle = str() ?: ""
        val title = if (kind == Kind.LIVE) TitleText.decode(rawTitle) else TitleText.title(rawTitle)
        val y = readInt()
        val url = str() ?: ""
        val logo = str()
        val group = pool.of(str() ?: "")
        val quality = pool.ofOrNull(str())
        val tvg = str()
        val n = count()
        val eps = ArrayList<Episode>(n)
        for (k in 0 until n) {
            val season = readInt()
            val num = readInt()
            val et = pool.of(str() ?: "")
            val eu = str() ?: ""
            val eq = pool.ofOrNull(str())
            val vc = count()
            // Older caches also contain single sources as a list: then omit as when parsing (saves memory)
            val vars = if (vc == 0) emptyList() else ArrayList<Variant>(vc).also { l ->
                for (j in 0 until vc) l.add(Variant(str() ?: "", pool.of(str() ?: ""), pool.ofOrNull(str()), pool.ofOrNull(str())))
            }
            eps.add(Episode(season, num, et, eu, if (vars.size > 1) vars else emptyList(), eq))
        }
        if (lang == null) {
            lang = pool.of(LangTagLogic.of(group, rawTitle, kind == Kind.LIVE))
            counts[lang] = (counts[lang] ?: 0) + 1
            if (LangTagLogic.hides(hidden, lang, kind)) return null
        }
        return Item(
            id = id, kind = kind, title = title, year = if (y < 0) null else y, url = url, logo = logo,
            group = group, episodes = EpisodeTable.of(eps, pool), quality = quality, tvgId = tvg, lang = lang
        )
    }

    private fun DataInputStream.skipItemRest() {
        skipStr(); readInt(); skipStr(); skipStr(); skipStr(); skipStr(); skipStr()   // title, year, URL, logo, category, quality, tvg-id
        val n = count()
        for (k in 0 until n) {
            readInt(); readInt(); skipStr(); skipStr(); skipStr()
            val vc = count()
            for (j in 0 until vc) { skipStr(); skipStr(); skipStr(); skipStr() }
        }
    }
}
