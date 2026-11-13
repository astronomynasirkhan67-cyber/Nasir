package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

data class CatReq(
    val title: String,
    val ipvio: Map<String, List<Item>>,
    val provider: Map<String, List<Item>>,
    val sort: Int,
    val recent: List<Item> = emptyList(),
    val watch: List<Item> = emptyList(),
    /** [CatOrder.MOVIE] / [CatOrder.SERIES]: provider categories use the saved sorting and can be moved. null = only Standard/A–Z. */
    val area: String? = null
)

const val CAT_RECENT = "Zuletzt geschaut"
const val CAT_WATCH = "Meine Liste"

fun isTopEntry(name: String) = name == CAT_RECENT || name == CAT_WATCH

private fun sortFor(name: String, sort: Int) = if (isTopEntry(name)) 8 else sort

object CatPrefs {
    var listView by mutableStateOf(true)
    private var inited = false
    fun init(c: Context) {
        if (inited) return
        inited = true
        listView = Profiles.sp(c, "ipvio_cat").getBoolean("list", true)
    }
    fun set(c: Context, v: Boolean) {
        listView = v
        Profiles.sp(c, "ipvio_cat").edit().putBoolean("list", v).apply()
    }
}

private val TILE_COLORS = listOf(
    0xFF3B2A8F, 0xFF064E55, 0xFF7A2E45, 0xFF7A5516, 0xFF1F4E8C, 0xFF5B2B86
).map { Color(it) }

@Composable
fun CategoriesScreen(
    req: CatReq, tab: Int,
    onPick: (String, List<Item>, Int) -> Unit, onOpen: (Item) -> Unit,
    onBack: () -> Unit, onGoTab: (Int) -> Unit
) {
    val ctx = LocalContext.current
    CatPrefs.init(ctx)
    var mode by remember { mutableIntStateOf(if (AppSettings.ipvioFilter && req.ipvio.isNotEmpty()) 0 else 1) }
    var az by remember { mutableStateOf(false) }
    val area = req.area
    if (area != null) CatOrder.init(ctx)
    // Saved sorting (Standard / A–Z / own order) only for provider categories; auto genres keep the simple A–Z switch
    val saved = area != null && mode == 1
    val sideFocus = remember { FocusRequester() }
    val pillFocus = remember { FocusRequester() }
    val firstFocus = ContentFocus.req
    var inPills by remember { mutableStateOf(false) }
    var inSidebar by remember { mutableStateOf(false) }
    BackHandler(enabled = !inPills) { runCatching { pillFocus.requestFocus() } }
    BackHandler(enabled = inPills) { onBack() }

    val map = if (mode == 0) req.ipvio else req.provider
    val entries = remember(map, az, saved, CatOrder.version, req.recent, req.watch) {
        val l = map.entries.map { it.key to it.value }
        val base = if (saved && area != null) CatOrder.sorted(area, map.keys.toList()).mapNotNull { k -> map[k]?.let { k to it } }
            else if (az) l.sortedBy { it.first.lowercase() } else l
        val top = ArrayList<Pair<String, List<Item>>>()
        if (req.recent.isNotEmpty()) top.add(CAT_RECENT to req.recent)
        if (req.watch.isNotEmpty()) top.add(CAT_WATCH to req.watch)
        top + base
    }
    val restoring = remember { CatMemory.restore[req.title] == true }
    var bgItem by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(Unit) { if (!restoring) runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (CatPrefs.listView) {
            bgItem?.let { MetaRepo.cached(it)?.backdrop }?.let {
                AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.25f)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg.copy(alpha = .97f), Bg.copy(alpha = .6f)))))
        }
        Column(Modifier.fillMaxSize().sidebarPad().padding(horizontal = 32.dp, vertical = DS.PageY)) {
            Text(req.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.onFocusChanged { inPills = it.hasFocus }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Provider categories first; the choice is saved (same setting as "Automatic genres")
                    val modes = listOf("Anbieter-Kategorien", "Auto-Genres")
                    val sel = if (mode == 0) 1 else 0
                    DropdownPill("Filter", modes[sel], modes, sel, modifier = Modifier.focusRequester(pillFocus), icon = FIcon.Genre) {
                        mode = if (it == 1) 0 else 1
                        AppSettings.set(ctx, mode == 0)
                    }
                    if (saved && area != null) {
                        val sorts = listOf("Standard", "A–Z", "Eigene Reihenfolge")
                        val si = CatOrder.mode(area).coerceIn(0, 2)
                        DropdownPill("Sortierung", sorts[si], sorts, si, icon = FIcon.Sort) { CatOrder.setMode(ctx, area, it) }
                    } else {
                        val sorts = listOf("Standard", "A–Z")
                        DropdownPill("Sortierung", sorts[if (az) 1 else 0], sorts, if (az) 1 else 0, icon = FIcon.Sort) { az = it == 1 }
                    }
                    val views = listOf("Liste", "Kacheln")
                    DropdownPill("Ansicht", views[if (CatPrefs.listView) 0 else 1], views, if (CatPrefs.listView) 0 else 1, icon = FIcon.View) { CatPrefs.set(ctx, it == 0) }
                    if (CatPrefs.listView) {
                        val pr = listOf(0, 4, 5, 6, 7)
                        val prl = pr.map { if (it == 0) "Auto (5)" else "$it" }
                        val pi = pr.indexOf(General.perRow).let { if (it < 0) 0 else it }
                        DropdownPill("Spalten", prl[pi], prl, pi, icon = FIcon.View) { General.setPerRow(ctx, pr[it]) }
                    }
                    Text("${entries.size} Kategorien", color = Muted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (mode == 0 && req.ipvio.isEmpty()) AutoGenresOff()
            else {
                if (mode == 0 && Classifier.running) Text("Genres werden gerade im Hintergrund erkannt. Die Kategorien erscheinen nach und nach.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                // Auto genres: titles sorted by rating (best first); provider categories keep their sorting
                val catSort = if (mode == 0) 2 else req.sort
                // Menu key / long OK on a provider category: move it (switches to the own order)
                val onMove: ((String, Int) -> Unit)? = if (saved && area != null) { name, how ->
                    val names = map.keys.toList()
                    if (how == 0) CatOrder.toTop(ctx, area, names, name) else CatOrder.move(ctx, area, names, name, how)
                } else null
                if (CatPrefs.listView) ListMode(req.title, entries, catSort, firstFocus, pillFocus, onPick, onOpen, onMove) { bgItem = it }
                else TileMode(entries, catSort, firstFocus, onPick)
            }
        }
        Sidebar(tab, onGoTab, Modifier.align(SidebarPrefs.align()), sideFocus) { inSidebar = it }
    }
}

object CatMemory {
    val sel = HashMap<String, Int>()
    val item = HashMap<String, Int>()
    val restore = HashMap<String, Boolean>()

    fun reset() {
        sel.clear()
        item.clear()
        restore.clear()
    }
}

@Composable
private fun ListMode(
    title: String, entries: List<Pair<String, List<Item>>>, sort: Int, firstFocus: FocusRequester, pillFocus: FocusRequester,
    onPick: (String, List<Item>, Int) -> Unit, onOpen: (Item) -> Unit,
    onMove: ((String, Int) -> Unit)?, onFocusItem: (Item) -> Unit
) {
    val scope = rememberCoroutineScope()
    var sel by remember { mutableIntStateOf((CatMemory.sel[title] ?: 0).coerceIn(0, maxOf(entries.lastIndex, 0))) }
    var inPreview by remember { mutableStateOf(false) }
    var pendingPreview by remember { mutableStateOf(false) }
    var focusedItem by remember { mutableStateOf<Item?>(null) }
    val cur = entries.getOrNull(sel)
    val firstPreview = remember { FocusRequester() }
    val restoreReq = remember { FocusRequester() }
    val restoreIdx = remember {
        if (CatMemory.restore.remove(title) == true) CatMemory.item[title + "|" + (entries.getOrNull(sel)?.first ?: "")] else null
    }
    val catState = rememberLazyListState(maxOf(sel - 3, 0))
    val previewState = remember(cur?.first) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    // Stalker portal: titles of the category loaded on demand belong to it (see [StalkerMore])
    val more = cur?.let { StalkerMore.added[it.first] }
    val prev = remember(cur?.first, cur?.second, more) {
        val c = cur
        if (c == null) emptyList<Item>()
        else if (isTopEntry(c.first)) c.second.take(40)
        else StalkerMore.withAdded(c.first, c.second).takeLast(40).reversed()
    }
    val ctx = LocalContext.current
    // Stayed on the category briefly: fetch the next portion from the portal (not while scrolling quickly)
    LaunchedEffect(cur?.first) {
        val c = cur ?: return@LaunchedEffect
        if (isTopEntry(c.first)) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        StalkerMore.prefetch(ctx, c.first, c.second.mapTo(HashSet()) { it.kind })
    }
    SideEffect { CatMemory.sel[title] = sel }
    // Unique row keys (a provider category could be called like a top entry)
    val rowKeys = remember(entries) { val seen = HashSet<String>(); entries.mapIndexed { i, e -> if (seen.add(e.first)) e.first else e.first + "#" + i } }
    // After moving a category the selection follows it to its new place
    var movedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entries) {
        val m = movedName ?: return@LaunchedEffect
        movedName = null
        val j = entries.indexOfFirst { it.first == m }
        if (j >= 0) sel = j
    }
    BackHandler(enabled = inPreview) { runCatching { firstFocus.requestFocus() } }
    LaunchedEffect(cur?.first) {
        focusedItem = prev.firstOrNull()
        focusedItem?.let(onFocusItem)
    }
    LaunchedEffect(Unit) {
        if (restoreIdx != null && restoreIdx in prev.indices) {
            previewState.scrollToItem(restoreIdx)
            withFrameNanos { }
            withFrameNanos { }
            runCatching { (if (restoreIdx == 0) firstPreview else restoreReq).requestFocus() }
        }
    }
    LaunchedEffect(sel, pendingPreview) {
        if (pendingPreview) {
            catState.scrollToItem(maxOf(sel - 3, 0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { firstPreview.requestFocus() }
            pendingPreview = false
        }
    }
    fun focusPreview() {
        scope.launch {
            previewState.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstPreview.requestFocus() }
        }
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(250.dp).fillMaxHeight(), state = catState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(entries, key = { i, e -> rowKeys.getOrElse(i) { e.first + "#" + i } }) { i, e ->
                var moveMenu by remember { mutableStateOf(false) }
                val all = if (isTopEntry(e.first)) e.second else StalkerMore.withAdded(e.first, e.second)
                Box {
                    CatListRow(
                        e.first, StalkerMore.total(ctx, e.first, setOfNotNull(e.second.firstOrNull()?.kind)) ?: all.size, i == sel,
                        if (i == sel) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocus = { sel = i }, onRight = { focusPreview() },
                        onMenu = if (onMove != null && !isTopEntry(e.first)) ({ moveMenu = true }) else null
                    ) { onPick(e.first, all, sortFor(e.first, sort)) }
                    if (moveMenu && onMove != null) OptionPopup(listOf("Nach oben verschieben", "Nach unten verschieben", "An den Anfang verschieben"), -1, { moveMenu = false }) { k ->
                        moveMenu = false
                        movedName = e.first
                        onMove(e.first, when (k) { 0 -> -1; 1 -> 1; else -> 0 })
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp).onFocusChanged { inPreview = it.hasFocus }) {
            // Grid: 2 rows with large covers (default 5 columns = 10 titles), columns adjustable in the "View" button
            val cols = if (General.perRow > 0) General.perRow.coerceIn(3, 7) else 5
            val gap = 14.dp
            val byWidth = (maxWidth - gap * (cols - 1)) / cols
            val posterW = minOf(byWidth, ((maxHeight - gap - 48.dp) / 2f) / 1.5f)
            if (cur != null) Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols),
                    state = previewState,
                    contentPadding = PaddingValues(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(prev) { i, item ->
                        RowPosterCard(
                            item, posterW,
                            Modifier.onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && i < cols) {
                                    runCatching { pillFocus.requestFocus() }; true
                                } else false
                            }.onFocusChanged {
                                if (it.hasFocus) {
                                    focusedItem = item; onFocusItem(item)
                                    CatMemory.item[title + "|" + cur.first] = i
                                }
                            }
                                .then(if (i == restoreIdx && i != 0) Modifier.focusRequester(restoreReq) else Modifier)
                                .then(if (i == 0) Modifier.focusRequester(firstPreview) else Modifier),
                            overlay = if (i % cols >= cols / 2) 2 else 1
                        ) { CatMemory.restore[title] = true; onOpen(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatListRow(
    name: String, count: Int, selected: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onRight: () -> Unit, onMenu: (() -> Unit)? = null, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .then(if (onMenu != null) Modifier.favKeys(onMenu) else Modifier)
            .background(if (f) DS.Overlay4 else if (selected) DS.Overlay2 else Color.Transparent)
            .border(DS.Border, if (f) Accent2 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { k ->
                if (k.type == KeyEventType.KeyDown && k.key == Key.DirectionRight && k.nativeKeyEvent.repeatCount == 0) { onRight(); true } else false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = if (isTopEntry(name)) Accent2 else Color.White, fontSize = 14.sp, fontWeight = if (selected || isTopEntry(name)) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("$count", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TileMode(entries: List<Pair<String, List<Item>>>, sort: Int, firstFocus: FocusRequester, onPick: (String, List<Item>, Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(entries) { i, e ->
            CatTile(e.first, e.second, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) { onPick(e.first, e.second, sortFor(e.first, sort)) }
        }
    }
}

/**
 * Category tile: background image from the newest title, in front a small fan of up to three covers,
 * name and count on the left. Without images a calm gradient in the accent color remains.
 */
@Composable
private fun CatTile(name: String, items: List<Item>, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.04f) else 1f, label = "tile")
    val shape = RoundedCornerShape(DS.RadiusL)
    val picks = remember(items) { (if (isTopEntry(name)) items.take(3) else items.takeLast(3).reversed()) }
    val ver = Classifier.version
    val posters = remember(picks, ver) { picks.mapNotNull { MetaRepo.cached(it)?.poster?.let { p -> cardPoster(p) } } }
    val backdrop = remember(picks, ver) { picks.firstNotNullOfOrNull { MetaRepo.cached(it)?.backdrop } }
    val tint = TILE_COLORS[(name.hashCode() and 0x7fffffff) % TILE_COLORS.size]
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.fillMaxWidth().height(132.dp).focusGlow(f, shape).clip(shape)
            .background(Brush.linearGradient(listOf(tint, Surf)))
            .border(DS.FocusBorder, if (f) Accent2 else DS.Overlay1, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        if (backdrop != null) AsyncImage(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Surf.copy(alpha = .96f), Surf.copy(alpha = .55f), Color.Transparent))))
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.62f).padding(start = 20.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, color = if (isTopEntry(name)) Accent2 else Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${items.size} Titel", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (posters.isNotEmpty()) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(118.dp).height(100.dp)) {
                posters.indices.reversed().forEach { i ->
                    AsyncImage(
                        posters[i], null,
                        Modifier.align(Alignment.CenterEnd).offset(x = (-i * 26).dp).height(100.dp - (i * 10).dp).aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(DS.RadiusS)).border(1.dp, DS.Overlay3, RoundedCornerShape(DS.RadiusS)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/** Explains why the automatic genres are empty (library too large) and what you can do. */
@Composable
private fun AutoGenresOff() {
    val all = PlaylistStore.channels
    val n = remember(all) { all.count { it.kind != Kind.LIVE } }
    val nf = java.text.NumberFormat.getIntegerInstance()
    val shape = RoundedCornerShape(DS.RadiusL)
    Column(
        Modifier.widthIn(max = 720.dp).clip(shape).background(Surf).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Automatische Genres sind für diese Auswahl ausgeschaltet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (!MetaRepo.enabled()) Text("Automatische Genres brauchen einen TMDB-Schlüssel (Einstellungen > API-Schlüssel).", color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
        else Text(
            "Deine Bibliothek hat " + nf.format(n) + " Filme und Serien – die Grenze für automatische Genres liegt bei " + nf.format(Classifier.MAX_TITLES) +
                ". Blende in Einstellungen > Playlists > „Sprachen der Inhalte“ die Sprachen aus, die du nicht brauchst. Danach werden die Genres im Hintergrund erkannt.",
            color = Muted, fontSize = 15.sp, lineHeight = 21.sp
        )
    }
}
