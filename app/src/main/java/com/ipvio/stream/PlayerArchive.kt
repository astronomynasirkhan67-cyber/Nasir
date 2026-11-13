package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Archive in the player, built like the channel list: on the left the channels with archive, in the middle the days of the chosen channel,
 * on the right the shows of that day. At the top it always shows which channel and which day is displayed.
 * Left in the left column or Back closes.
 */
@Composable
fun BoxScope.ArchivePanel(
    current: Item?, channels: List<Item>, playingStart: Long?, focus: FocusRequester,
    onClose: () -> Unit, onPlay: (Item, EpgEntry) -> Unit
) {
    val list = remember(channels, current?.id) {
        val c = current?.takeIf { Catchup.has(it) }
        if (c != null && channels.none { it.id == c.id }) listOf(c) + channels else channels
    }
    var selCh by remember { mutableStateOf(current?.takeIf { Catchup.has(it) } ?: list.firstOrNull()) }
    val ch = selCh
    val progs by produceState<List<EpgEntry>?>(null, ch?.id) { value = null; value = if (ch != null) EpgFull.archive(ch) else emptyList() }
    val nowSec = System.currentTimeMillis() / 1000
    val today = dayLabel(nowSec)
    val yesterday = dayLabel(nowSec - 86400)
    // Days (newest first) and shows per day (sorted by time)
    val days = remember(progs) { progs.orEmpty().map { dayLabel(it.start) }.distinct() }
    var selDay by remember(ch?.id) { mutableStateOf<String?>(null) }
    val day = selDay?.takeIf { it in days } ?: (playingStart?.takeIf { ch?.id == current?.id }?.let { dayLabel(it) }?.takeIf { it in days }) ?: days.firstOrNull()
    val dayProgs = remember(progs, day) { progs.orEmpty().filter { dayLabel(it.start) == day }.sortedBy { it.start } }
    fun dayName(d: String) = when (d) { today -> Lang.tr("Heute") + "  ·  " + d; yesterday -> Lang.tr("Gestern") + "  ·  " + d; else -> d }

    val chSel = remember { FocusRequester() }
    val daySel = remember { FocusRequester() }
    val progFirst = remember { FocusRequester() }
    // On opening: jump to the running archive show or to the newest show of today (only once)
    var autoDone by remember { mutableStateOf(false) }
    LaunchedEffect(dayProgs) {
        if (!autoDone && dayProgs.isNotEmpty() && ch?.id == current?.id) {
            autoDone = true
            delay(80)
            runCatching { progFirst.requestFocus() }
        }
    }

    val maxH = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(260).dp
    val w = (LocalConfiguration.current.screenWidthDp - 64).coerceIn(600, 1120).dp
    val shape = RoundedCornerShape(PT.PanelRadius)
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = PT.MarginY, end = PT.MarginY).width(w).heightIn(max = maxH)
            .clip(shape).background(PT.PanelBg).border(1.dp, PT.PanelBorder, shape).padding(22.dp)
    ) {
        // Header: which channel, which day
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
                val lg = ch?.logo
                if (lg != null) AsyncImage(lg, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                else AppIcon(AIcon.Catchup, Accent2, size = 24.dp)
            }
            Column(Modifier.weight(1f)) {
                Text("Archiv", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(
                    raw = true,
                    text = listOfNotNull(ch?.title, ch?.let { Lang.tr("${Catchup.of(it)?.days ?: 0} Tage") }, day?.let { dayName(it) }).joinToString("  ·  "),
                    color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("Links/Rechts = Spalte  ·  Zurück schließt", color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1) Channels with archive
            ArchiveColumn("Sender", Modifier.width(290.dp)) {
                LazyColumn(
                    state = rememberLazyListState(maxOf(list.indexOfFirst { it.id == ch?.id } - 2, 0)),
                    contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    itemsIndexed(list) { _, c ->
                        val sel = c.id == ch?.id
                        ArchiveRow(
                            sel,
                            (if (sel) Modifier.focusRequester(chSel).focusRequester(focus) else Modifier)
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) false
                                    else when (e.key) {
                                        Key.DirectionLeft -> { onClose(); true }
                                        Key.DirectionRight -> { runCatching { daySel.requestFocus() }; true }
                                        else -> false
                                    }
                                },
                            onFocus = { if (selCh?.id != c.id) { selCh = c; autoDone = true } },
                            onClick = { runCatching { daySel.requestFocus() } }
                        ) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(DS.Overlay1), contentAlignment = Alignment.Center) {
                                if (c.logo != null) AsyncImage(c.logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(raw = true, text = c.title, color = Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Catchup.of(c)?.days ?: 0} Tage", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            // 2) Days of the channel
            ArchiveColumn("Tag", Modifier.width(210.dp)) {
                if (progs == null) Text("lädt …", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                else if (days.isEmpty()) Text("Keine vergangenen Sendungen im Programm.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp).focusRequester(daySel).focusable())
                else LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(days) { _, d ->
                        val sel = d == day
                        ArchiveRow(
                            sel,
                            (if (sel) Modifier.focusRequester(daySel) else Modifier).onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) false
                                else when (e.key) {
                                    Key.DirectionLeft -> { runCatching { chSel.requestFocus() }; true }
                                    Key.DirectionRight -> { runCatching { progFirst.requestFocus() }; true }
                                    else -> false
                                }
                            },
                            onFocus = { selDay = d },
                            onClick = { runCatching { progFirst.requestFocus() } }
                        ) {
                            Text(raw = true, text = dayName(d), color = Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
            // 3) Programs of the day
            ArchiveColumn("Sendungen", Modifier.weight(1f)) {
                if (dayProgs.isEmpty()) Text(if (progs == null) "lädt …" else "", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                else {
                    // Focus on opening: running archive show, otherwise the newest of the day
                    val target = dayProgs.indexOfFirst { it.start == playingStart }.takeIf { it >= 0 } ?: dayProgs.lastIndex
                    LazyColumn(
                        state = rememberLazyListState(maxOf(target - 3, 0)),
                        contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        itemsIndexed(dayProgs) { i, e ->
                            val playing = e.start == playingStart && ch?.id == current?.id
                            ArchiveRow(
                                playing,
                                (if (i == target) Modifier.focusRequester(progFirst) else Modifier).onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { runCatching { daySel.requestFocus() }; true } else false
                                },
                                onFocus = {},
                                onClick = { if (ch != null) onPlay(ch, e) }
                            ) {
                                Text(raw = true, text = hhmm(e.start), color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = TNUM, modifier = Modifier.width(56.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(raw = true, text = e.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (e.desc.isNotBlank()) Text(raw = true, text = e.desc, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${((e.end - e.start) / 60).coerceAtLeast(1)} Min", color = Muted, fontSize = 13.sp)
                                if (playing) AppIcon(AIcon.Play, Accent2, size = 16.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveColumn(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(DS.RadiusL)).background(Color(0x0DFFFFFF)).padding(6.dp)) {
        Text(title, color = PT.T2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp))
        content()
    }
}

@Composable
private fun ArchiveRow(
    selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(shape)
            .background(if (f) RowFocus else if (selected) Accent1.copy(alpha = .18f) else Color.Transparent)
            .border(if (f) DS.Border else 1.dp, if (f) Accent2 else if (selected) Accent1.copy(alpha = .5f) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}
