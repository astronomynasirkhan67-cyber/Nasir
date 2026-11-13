package com.ipvio.stream

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Layouts of the multi view: slots are laid out as fractions of the screen. */
object MultiViewLogic {
    const val GRID = 0   // two tiles of equal size side by side
    const val BIG = 1    // one large tile on the left, a smaller one on the right
    val LAYOUT_NAMES = listOf("Zwei gleich groß", "Einer groß, einer klein")

    /** Number of tiles (two for now: more streams overload most boxes and many providers allow few connections). */
    const val SLOTS = 2

    /** Start channels: the first two distinct live channels from favorites, then history, then the list. */
    fun startChannels(live: List<Item>, favs: List<String>, hist: List<String>): List<Item> {
        val byId = live.associateBy { it.id }
        val out = LinkedHashMap<String, Item>()
        for (id in favs + hist) byId[id]?.let { out.putIfAbsent(it.id, it) }
        for (i in live) { if (out.size >= SLOTS) break; out.putIfAbsent(i.id, i) }
        return out.values.take(SLOTS)
    }
}

/**
 * Multi view: two live streams at once. Only one tile has sound (highlighted border). OK on a tile = sound to this tile,
 * long OK = choose another channel for this tile. Many providers allow only one connection per account; failing tiles say so.
 * Opened as a full-screen dialog from the Live TV list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiViewScreen(live: List<Item>, onClose: () -> Unit) {
    val slots = remember { mutableStateListOf<Item>().also { it.addAll(MultiViewLogic.startChannels(live, Library.favs.toList(), History.ids.toList())) } }
    var layout by remember { mutableIntStateOf(MultiViewLogic.GRID) }
    var audio by remember { mutableIntStateOf(0) }
    var picking by remember { mutableIntStateOf(-1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mehrfachansicht", color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                SmallPill(MultiViewLogic.LAYOUT_NAMES[layout], icon = AIcon.Grid) { layout = (layout + 1) % MultiViewLogic.LAYOUT_NAMES.size }
                SmallPill("Schließen", icon = AIcon.Close) { onClose() }
                Text("OK = Ton hierher, OK lange = Sender wechseln", color = Color(0xFFA0A6B8), fontSize = 14.sp)
            }
            @Composable
            fun tile(i: Int, m: Modifier) {
                val item = slots.getOrNull(i)
                var f by remember { mutableStateOf(false) }
                Box(
                    m.onFocusChanged { f = it.isFocused }
                        .border(if (f) 4.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { audio = i }, onLongClick = { picking = i })
                ) {
                    if (item != null) androidx.compose.runtime.key(item.id) { SecondStream(item, split = false, audioOn = audio == i, modifier = Modifier.fillMaxSize(), small = true) }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tile(0, Modifier.weight(if (layout == MultiViewLogic.GRID) 1f else 2f).fillMaxSize())
                tile(1, Modifier.weight(1f).fillMaxSize())
            }
        }
        if (picking >= 0) GuideSearchDialog(live, onPick = { ch ->
            val p = picking
            if (p in 0 until slots.size) slots[p] = ch else if (p == slots.size) slots.add(ch)
            picking = -1
        }, onClose = { picking = -1 })
    }
}
