package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Search inside the TV guide: channel names plus show titles from the loaded XMLTV data (no network). */
object GuideSearchLogic {
    /** [now]: for a channel hit, the show running on that channel right now (null if the guide has none). */
    class Hit(val channel: Item, val entry: EpgEntry?, val now: EpgEntry? = null)

    fun search(channels: List<Item>, query: String, nowSec: Long, limit: Int = 60, maxChannels: Int = 150): List<Hit> {
        val q = ChannelSearch.norm(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<Hit>()
        for (c in ChannelSearch.filter(channels, query).take(maxChannels)) {
            out.add(Hit(c, null, Xmltv.forChannel(c.tvgId).firstOrNull { it.start <= nowSec && nowSec < it.end }))
        }
        val shows = ArrayList<Hit>()
        for (c in channels) {
            for (e in Xmltv.forChannel(c.tvgId)) {
                if (e.end <= nowSec - 3600) continue
                val t = ChannelSearch.norm(e.title)
                if (words.all { it in t }) shows.add(Hit(c, e))
            }
            if (shows.size > 600) break
        }
        shows.sortBy { it.entry!!.start }
        out.addAll(shows.take(limit))
        return out
    }
}

@Composable
fun GuideSearchDialog(channels: List<Item>, onPick: (Item) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val kbFirst = remember { FocusRequester() }
    // With the device keyboard setting the text field gets the focus (OK opens Gboard & co.), otherwise the app keyboard
    LaunchedEffect(Unit) { delay(150); runCatching { (if (AppSettings.systemKeyboard) fr else kbFirst).requestFocus() } }
    val hits by produceState(emptyList<GuideSearchLogic.Hit>(), q) {
        if (q.isBlank()) { value = emptyList(); return@produceState }
        delay(250)
        value = withContext(Dispatchers.Default) { GuideSearchLogic.search(channels, q, System.currentTimeMillis() / 1000) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(1180.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Tok.GlassStrong).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Im TV-Guide suchen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!AppSettings.systemKeyboard) SearchKeyboard(
                Modifier.width(430.dp),
                onKey = { q = (q + it).take(40) },
                onSpace = { if (q.isNotEmpty() && !q.endsWith(" ")) q += " " },
                onDelete = { q = q.dropLast(1) },
                onClear = { q = "" },
                onPhone = null, onVoice = null,
                firstKey = kbFirst, onFocusChange = {}, onRight = { false }
            )
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(q, { q = it.take(40) }, "Sender oder Sendung", inner = Modifier.focusRequester(fr), autoOpen = false)
            if (q.isNotBlank() && hits.isEmpty()) Text("Keine Treffer", color = Muted, fontSize = 15.sp)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(hits) { _, h ->
                    var f by remember { mutableStateOf(false) }
                    val e = h.entry
                    // Channel hit: what runs right now. XMLTV first (h.now), otherwise the cached/Xtream lookup (only for rows that are on screen)
                    val nowShow by produceState(h.now, h.channel.id) {
                        if (e == null && value == null) value = runCatching { Epg.current(Epg.now(h.channel)) }.getOrNull()
                    }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM))
                            .background(if (f) Color(0x33FFFFFF) else Color(0x0FFFFFFF))
                            .onFocusChanged { f = it.isFocused }
                            .clickable { onPick(h.channel) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(raw = true, text = e?.title ?: h.channel.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            raw = true,
                            text = if (e == null) h.channel.group + (nowShow?.let { "  ·  " + Lang.tr("Jetzt") + ": " + it.title + "  " + hhmm(it.start) + " - " + hhmm(it.end) } ?: "") else h.channel.title + "  ·  " + dayLabel(e.start) + " " + hhmm(e.start) + " - " + hhmm(e.end),
                            color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            }
            }
        }
    }
}

private fun Modifier.fillMaxWidth(): Modifier = this.then(Modifier.width(720.dp))
