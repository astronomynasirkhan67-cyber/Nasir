package com.ipvio.stream

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Table of a football league (ESPN, unofficial). Columns: rank, team, played, W, D, L, goal difference, points. */
object SportTable {
    class Row(val rank: Int, val name: String, val logo: String?, val played: String, val w: String, val d: String, val l: String, val diff: String, val pts: String)

    private val cache = HashMap<String, Pair<Long, List<Row>>>()

    fun hasTable(l: SportData.League) = l.path.startsWith("soccer/")

    private fun collect(o: JSONObject, out: MutableList<JSONObject>) {
        o.optJSONObject("standings")?.optJSONArray("entries")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) } }
        o.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { collect(it, out) } }
    }

    private fun stat(stats: JSONArray?, vararg names: String): String {
        if (stats == null) return "-"
        for (n in names) for (i in 0 until stats.length()) {
            val s = stats.optJSONObject(i) ?: continue
            if (s.optString("name") == n || s.optString("type") == n) {
                val dv = s.optString("displayValue", "")
                if (dv.isNotBlank()) return dv
                if (s.has("value")) return s.optDouble("value").let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
            }
        }
        return "-"
    }

    fun parse(json: String): List<Row> {
        val entries = ArrayList<JSONObject>()
        collect(JSONObject(json), entries)
        val out = ArrayList<Row>()
        entries.forEachIndexed { idx, e ->
            try {
                val t = e.optJSONObject("team") ?: return@forEachIndexed
                val st = e.optJSONArray("stats")
                val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
                val rank = stat(st, "rank").toIntOrNull() ?: (idx + 1)
                out.add(Row(rank, t.optString("displayName"), logo, stat(st, "gamesPlayed"), stat(st, "wins"), stat(st, "ties", "draws"), stat(st, "losses"),
                    stat(st, "pointDifferential", "pointsDifferential"), stat(st, "points")))
            } catch (_: Exception) { }
        }
        return out.sortedBy { it.rank }
    }

    suspend fun fetch(l: SportData.League): List<Row> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[l.path] }
        if (hit != null && now - hit.first < 300_000) return@withContext hit.second
        try {
            val req = Request.Builder().url("https://site.api.espn.com/apis/v2/sports/${l.path}/standings").header("User-Agent", "IPVIO-Stream").build()
            Net.withTimeouts(8, 10).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext hit?.second ?: emptyList()
                val rows = parse(r.body?.string().orEmpty())
                synchronized(cache) { cache[l.path] = now to rows }
                rows
            }
        } catch (_: Exception) { hit?.second ?: emptyList() }
    }
}

@Composable
fun SportTableDialog(l: SportData.League, onClose: () -> Unit) {
    val rows by produceState<List<SportTable.Row>?>(null, l.path) { value = SportTable.fetch(l) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusM)).padding(dpx(16)),
            verticalArrangement = Arrangement.spacedBy(dpx(8))
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(l.name + " – Tabelle", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold, raw = true)
                TableClose(Modifier.focusRequester(first), onClose)
            }
            val r = rows
            if (r == null) Text("Lade…", color = Muted, fontSize = spx(16))
            else if (r.isEmpty()) Text("Keine Tabelle verfügbar.", color = Muted, fontSize = spx(16))
            else {
                TRow("#", null, "Team", "Sp", "S", "U", "N", "TD", "Pkt", head = true)
                LazyColumn(Modifier.height(dpx(300)).width(dpx(560))) {
                    itemsIndexed(r, key = { i, x -> x.name + "#" + i }) { _, x -> TRow(x.rank.toString(), x.logo, x.name, x.played, x.w, x.d, x.l, x.diff, x.pts, head = false) }
                }
            }
        }
    }
}

@Composable
private fun TableClose(modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Box(
        modifier.clip(shape).background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF)).border(if (f) 2.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(horizontal = dpx(14), vertical = dpx(8))
    ) { Text("Schließen", color = Color.White, fontSize = spx(15)) }
}

@Composable
private fun TRow(rank: String, logo: String?, name: String, p: String, w: String, d: String, l: String, diff: String, pts: String, head: Boolean) {
    val col = if (head) Muted else Color.White
    val fw = if (head) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = dpx(4)), verticalAlignment = Alignment.CenterVertically) {
        Text(rank, color = col, fontSize = spx(15), fontWeight = fw, modifier = Modifier.width(dpx(34)), raw = true)
        Box(Modifier.width(dpx(30))) { if (logo != null) AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(dpx(22))) }
        Text(name, color = col, fontSize = spx(15), fontWeight = fw, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), raw = !head)
        listOf(p, w, d, l, diff).forEach { Text(it, color = col, fontSize = spx(15), fontWeight = fw, textAlign = TextAlign.End, modifier = Modifier.width(dpx(42)), raw = true) }
        Text(pts, color = if (head) Muted else Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(48)), raw = true)
    }
}
