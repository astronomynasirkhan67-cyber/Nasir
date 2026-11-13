package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val S_SORT_AZ = 6
private const val S_SORT_PLAYLIST = 8

/** Index of the current list – is kept when leaving and coming back. */
internal object SearchCache {
    private var idx: SearchIndex? = null
    @Synchronized fun get(all: List<Item>): SearchIndex? = idx?.takeIf { it.all === all }
    @Synchronized fun build(all: List<Item>): SearchIndex = get(all) ?: SearchIndex(all).also { idx = it }

    private var sug: Pair<List<Item>, WordSuggest>? = null
    @Synchronized fun suggest(all: List<Item>): WordSuggest = sug?.takeIf { it.first === all }?.second ?: WordSuggest(all).also { sug = all to it }
}

/** Search (tab 4): "Recently watched" separated into movies, series and Live TV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    tab: Int, state: LazyListState,
    onOpen: (Item) -> Unit, onBrowse: (BrowseReq) -> Unit, onTab: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = PlaylistStore.channels
    // Build the index in the background; searches also run in the background and only after a short typing pause
    // With very large playlists memory can run out while the index is built: then free the image cache and try once more,
    // and if it still does not fit, the search stays empty instead of the app crashing.
    val index by produceState(SearchCache.get(all), all) {
        value = withContext(Dispatchers.Default) {
            if (Mem.heapTight(0.6)) Mem.trim(ctx)
            try { SearchCache.build(all) } catch (e: OutOfMemoryError) {
                Mem.trim(ctx)
                try { SearchCache.build(all) } catch (e2: OutOfMemoryError) { null }
            }
        }
    }
    // Word suggestions are optional: built only after the index, skipped when memory is tight
    val suggestIdx by produceState<WordSuggest?>(null, all, index != null) {
        if (index == null) return@produceState
        value = withContext(Dispatchers.Default) {
            if (Mem.heapTight(0.7)) null else try { SearchCache.suggest(all) } catch (e: OutOfMemoryError) { null }
        }
    }
    val firstSugg = remember { FocusRequester() }
    // Recently watched: resolved in one pass over the playlist in the background (order of the history is kept)
    val hist by produceState(emptyList<Item>(), all, History.ids.size) {
        val want = History.ids.toList()
        value = withContext(Dispatchers.Default) {
            val pos = HashMap<String, Int>(want.size * 2)
            want.forEachIndexed { i, id -> pos.putIfAbsent(id, i) }
            val out = arrayOfNulls<Item>(want.size)
            for (x in all) { val p = pos[x.id] ?: continue; if (out[p] == null) out[p] = x }
            out.filterNotNull()
        }
    }
    // Smaller cards than elsewhere so more hits are visible next to the keyboard
    val cardW = (AppSettings.cardW * 0.8f).toInt().coerceAtLeast(110)
    val firstRes = remember { FocusRequester() }
    val firstHistory = remember { FocusRequester() }
    val sideFocus = remember { FocusRequester() }
    var inSidebar by remember { mutableStateOf(false) }

    SideEffect { NavState.tab = tab; ContentFocus.tab = tab }
    var canVoice by remember { mutableStateOf(VoiceSearch.available(ctx)) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            SearchState.query = it
            AppSettings.addSearch(ctx, it)
        }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    var focusItem by remember { mutableStateOf<Item?>(null) }
    // When opening the search go straight to the search field (not when returning from a detail page: then to the chosen title).
    // Only stop when the field REALLY has the focus – the sidebar still holds it briefly when switching.
    LaunchedEffect(Unit) {
        if (LastFocus.pending != null) return@LaunchedEffect
        AppNav.focusTab = -1
        repeat(20) {
            delay(100)
            if (fieldFocused) return@LaunchedEffect
            runCatching { ContentFocus.req.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }

    val q = SearchState.query.trim()
    val none = Triple(emptyList<Item>(), emptyList<Item>(), emptyList<Item>())
    // A result always belongs to exactly one input: while still searching it says "Searching …" instead of "No hits"
    val found by produceState("" to none, q, index) {
        val idx = index ?: return@produceState
        if (q.length < 2) { value = q to none; return@produceState }
        delay(250)
        // Also the de-duplication of movie versions runs here in the background (for short queries it can be tens of
        // thousands of hits, before it ran on the main thread while typing)
        value = q to withContext(Dispatchers.Default) {
            idx.find(q).let { (m, s, l) -> Triple(PlaylistLogic.firstPerKey(m) { it.mk + "|" + it.lang }, s, l) }
        }
    }
    val searching = q.length >= 2 && found.first != q
    val (rm0, rs0, rl0) = if (q.length < 2 || searching) none else found.second
    val fl = SearchState.filter
    // Word suggestions for the word being typed
    val sugg = remember(SearchState.query, suggestIdx) { suggestIdx?.suggest(WordSuggest.lastWord(SearchState.query)).orEmpty() }
    // Sports hub filter: live channels that look like sports channels (the hub itself opens with the button)
    val rlSport = remember(rl0, fl == 4) { if (fl == 4) rl0.filter { SearchSport.isSport(it) } else emptyList() }
    val rl = when (fl) { 0, 3 -> rl0; 4 -> rlSport; else -> emptyList() }
    val rs = if (fl == 0 || fl == 2) rs0 else emptyList()
    // The same movie in several versions only once (the source is chosen on the detail page)
    val rmAll: List<Item> = rm0   // already one per movie (see the search above)
    val rm = if (fl == 0 || fl == 1) rmAll else emptyList()
    val firstKey = when { rm.isNotEmpty() -> "rm"; rs.isNotEmpty() -> "rs"; rl.isNotEmpty() -> "rl"; else -> "" }
    // Back: from a hit (or any other element) first to the input (field or app keyboard); from the input to the sidebar
    BackHandler(enabled = !inSidebar) {
        // requestFocus returns false (no exception) when the target is not on screen: then go on to the sidebar instead of doing nothing
        val toInput = !fieldFocused && runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)
        if (!toInput) runCatching { sideFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().sidebarPad()) {
            // Left: input, quick filters, suggestions and last searches above the keyboard, the keyboard at the bottom
            Column(
                Modifier.width(430.dp).fillMaxHeight().padding(start = DS.PageX, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.weight(1f))
                // Everything sits directly above the keyboard and is fully visible (wraps into more rows instead of scrolling away)
                val recent = AppSettings.searchHistory.toList().take(6)
                if (recent.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEachIndexed { i, term ->
                        // OK searches again, long OK / menu key removes just this entry
                        MiniChip(term, false, (if (i == 0) Modifier.focusRequester(firstHistory) else Modifier)
                            .menuKeys(onMenu = { AppSettings.removeSearch(ctx, term) }, onLong = { AppSettings.removeSearch(ctx, term) }), raw = true) { SearchState.query = term }
                    }
                    MiniChip("Verlauf löschen", false) { AppSettings.clearSearch(ctx) }
                }
                if (sugg.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEachIndexed { i, w ->
                        MiniChip(w.replaceFirstChar { it.uppercase() }, false, if (i == 0) Modifier.focusRequester(firstSugg) else Modifier, raw = true) {
                            SearchState.query = WordSuggest.apply(SearchState.query, w)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SEARCH_FILTERS.forEachIndexed { i, name -> MiniChip(name, SearchState.filter == i) { SearchState.filter = i } }
                }
                if (AppSettings.systemKeyboard) {
                    // The device's own keyboard (e.g. Gboard) in a normal text field: OK or a tap opens it
                    Field(
                        SearchState.query, { SearchState.query = it.take(60) }, "Filme, Serien oder Sender suchen…",
                        Modifier.fillMaxWidth(),
                        inner = Modifier.focusRequester(ContentFocus.req).onFocusChanged { fieldFocused = it.isFocused || it.hasFocus },
                        onDone = { SearchState.query.trim().takeIf { it.length >= 2 }?.let { AppSettings.addSearch(ctx, it) } }
                    )
                } else {
                // Tappable: OK or a tap opens the device keyboard for typing (the app keyboard below stays available).
                // Deliberately not wired to fieldFocused: that flag belongs to the BackHandler and tracks the app keyboard only.
                Field(
                    SearchState.query, { SearchState.query = it.take(60) }, "Filme, Serien oder Sender suchen…",
                    Modifier.fillMaxWidth(),
                    onDone = { SearchState.query.trim().takeIf { it.length >= 2 }?.let { AppSettings.addSearch(ctx, it) } }
                )
                // Own on-screen keyboard (instead of the TV's keyboard): hits appear while typing
                SearchKeyboard(
                    Modifier.fillMaxWidth(),
                    onKey = { SearchState.query = (SearchState.query + it).take(60) },
                    onSpace = { if (SearchState.query.isNotEmpty() && !SearchState.query.endsWith(" ")) SearchState.query += " " },
                    onDelete = { SearchState.query = SearchState.query.dropLast(1) },
                    onClear = { SearchState.query = "" },
                    onPhone = { RemoteUi.show = true },
                    onVoice = if (canVoice) ({ runCatching { voice.launch(VoiceSearch.intent()) }.onFailure { canVoice = false } }) else null,
                    firstKey = ContentFocus.req,
                    onFocusChange = { fieldFocused = it },
                    onRight = {
                        val target = if (firstKey.isNotEmpty()) firstRes else null
                        target != null && runCatching { target.requestFocus() }.isSuccess
                    }
                )
                }
            }
            // Right: info panel of the highlighted title on top, the hits below
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val preview = focusItem ?: (rm.firstOrNull() ?: rs.firstOrNull() ?: rl.firstOrNull() ?: hist.firstOrNull())
                SearchInfoPanel(preview, Modifier.fillMaxWidth().height(190.dp).padding(start = 24.dp, end = DS.PageX, top = 16.dp))
                CompositionLocalProvider(LocalSearchFocus provides { focusItem = it }) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(bottom = 48.dp)) {
                if (fl == 4) item(key = "hubbtn") {
                    Row(Modifier.padding(horizontal = DS.PageX, vertical = 6.dp)) {
                        PillButton("Sports Hub öffnen", icon = AIcon.Live) { onTab(MenuLogic.SPORT) }
                    }
                }
                if (q.length < 2) {
                    val hm = hist.filter { it.kind == Kind.MOVIE }
                    val hs = hist.filter { it.kind == Kind.SERIES }
                    val hl = hist.filter { it.kind == Kind.LIVE }
                    if (hm.isNotEmpty()) item(key = "hm") {
                        SearchRow("Zuletzt geschaut · Filme", hm.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Filme", hm, S_SORT_PLAYLIST))
                        }
                    }
                    if (hs.isNotEmpty()) item(key = "hs") {
                        SearchRow("Zuletzt geschaut · Serien", hs.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Serien", hs, S_SORT_PLAYLIST))
                        }
                    }
                    if (hl.isNotEmpty()) item(key = "hl") {
                        SearchRow("Zuletzt geschaut · Live TV", hl.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Live TV", hl, S_SORT_PLAYLIST))
                        }
                    }
                } else {
                    val openS: (Item) -> Unit = { i -> AppSettings.addSearch(ctx, q); onOpen(i) }
                    if (searching) item {
                        Text("Suche …", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = DS.PageX, vertical = 16.dp))
                    } else if (rm.isEmpty() && rs.isEmpty() && rl.isEmpty()) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EmptyState(AIcon.Info, "Keine Treffer", "Prüfe die Schreibweise oder suche nach einem Teil des Titels. Kleine Tippfehler findet die Suche trotzdem.")
                        }
                    }
                    if (rm.isNotEmpty()) item(key = "rm") {
                        SearchRow("Filme", rm, cardW, openS, if (firstKey == "rm") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Filme", rm, S_SORT_AZ))
                        }
                    }
                    if (rs.isNotEmpty()) item(key = "rs") {
                        SearchRow("Serien", rs, cardW, openS, if (firstKey == "rs") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Serien", rs, S_SORT_AZ))
                        }
                    }
                    if (rl.isNotEmpty()) item(key = "rl") {
                        SearchRow("Live TV", rl, cardW, openS, if (firstKey == "rl") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Live TV", rl, S_SORT_AZ))
                        }
                    }
                }
                    }
                }
            }
        }
        Sidebar(
            tab,
            { id -> if (id == tab) scope.launch { state.scrollToItem(0) }; onTab(id) },
            Modifier.align(SidebarPrefs.align()), sideFocus
        ) { inSidebar = it }
    }
}

@Composable
private fun SearchRowHeader(title: String, count: Int, headerReq: FocusRequester, firstCard: FocusRequester, rowState: androidx.compose.foundation.lazy.LazyListState, onAll: () -> Unit) {
    val sc = rememberCoroutineScope()
    val key = NavState.tab.toString() + "|" + title
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    Row(Modifier.padding(start = DS.PageX, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallPill("Alle $count", Modifier.focusRequester(headerReq).onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                // First card not in view (row shifted sideways) or not yet composed: first to the start, then highlight.
                // Before, the focus got stuck here because the failure was swallowed.
                if (runCatching { firstCard.requestFocus() }.isFailure) sc.launch {
                    rowState.scrollToItem(0)
                    repeat(3) { withFrameNanos { } }
                    runCatching { firstCard.requestFocus() }
                }
                true
            } else false
        }, trailing = AIcon.Right) { PillMemory.key = key; onAll() }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Accent1))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchAllTile(live: Boolean, w: Dp, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "searchalltile")
    if (live) {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape).background(Surf)
                .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape)
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                IconLabel(null, "Alle anzeigen", Color.White, 17.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(" ", fontSize = 15.sp, maxLines = 1)
                Text(" ", fontSize = 13.sp, maxLines = 1)
            }
        }
    } else {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusGlow(f, shape).clip(shape).background(Surf)
                    .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape),
                contentAlignment = Alignment.Center
            ) {
                IconLabel(null, "Alle anzeigen", Color.White, 18.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Text(" ", fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(" ", fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SearchRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, onAll: () -> Unit
) {
    val rowState = RowStates.get("${NavState.tab}|$title")
    val liveRow = list.firstOrNull()?.kind == Kind.LIVE
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    val report = LocalSearchFocus.current
    Column {
        SearchRowHeader(title, list.size, headerReq, firstCard, rowState, onAll)
        Box(
            Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && e.nativeKeyEvent.repeatCount == 0) {
                    runCatching { headerReq.requestFocus() }.isSuccess
                } else false
            }
        ) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(list.take(60)) { i, x ->
                    val fr = (if (i == 0) Modifier.focusRequester(firstCard) else Modifier).onFocusChanged { if (it.hasFocus) report(x) }
                    if (x.kind == Kind.LIVE) LiveCard(x, { onOpen(x) }, Modifier.width((cardW * 4 / 3).dp).then(fr))
                    else PosterCard(x, { onOpen(x) }, Modifier.width(cardW.dp).restoreFocus(x.id).then(fr))
                }
                item { SearchAllTile(liveRow, if (liveRow) (cardW * 4 / 3).dp else cardW.dp, onAll) }
            }
        }
    }
}


private val SEARCH_FILTERS = listOf("Alle", "Filme", "Serien", "Live TV", "Sport")

/** Heuristic for the sports hub filter: channel name or category looks like sports. */
internal object SearchSport {
    private val WORDS = listOf("sport", "dazn", "espn", "eurosport", "bein", "arena", "sky sport", "nba", "nfl", "nhl", "ufc", "f1", "fußball", "fussball", "football", "soccer", "tennis", "golf")
    fun isSport(i: Item): Boolean {
        val t = (i.title + " " + i.group).lowercase()
        return WORDS.any { it in t }
    }
}

private val KB_DIGITS = "1234567890"
private val KB_LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val KB_SYMBOLS = listOf("-/:;()&@\"", ".,?!'+=_%", "#*<>[]{}~")
// Special letters for other languages (German, French, Spanish, Turkish, Nordic, Slavic ...)
private val KB_SPECIAL = listOf("äöüßéèêàâç", "ñışğčćšžđå", "øæœíóúáãõ")

/**
 * QWERTY on-screen keyboard: a number row on top (no extra layer needed), three key rows, then layer switch (symbols / special letters),
 * backspace, wide space bar and clear.
 */
@Composable
internal fun SearchKeyboard(
    modifier: Modifier, onKey: (String) -> Unit, onSpace: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit,
    onPhone: (() -> Unit)?, onVoice: (() -> Unit)?,
    firstKey: FocusRequester, onFocusChange: (Boolean) -> Unit, onRight: () -> Boolean
) {
    var symbols by remember { mutableStateOf(false) }
    var special by remember { mutableStateOf(false) }
    val rows = if (symbols) KB_SYMBOLS else if (special) KB_SPECIAL else KB_LETTERS
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Number row (always visible)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KB_DIGITS.forEachIndexed { c, k ->
                KbKey(k.toString(), Modifier.weight(1f).then(if (c == 0) Modifier.focusRequester(firstKey) else Modifier), onFocusChange, if (c == KB_DIGITS.lastIndex) onRight else null) { onKey(k.toString()) }
            }
        }
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                // Row 2 is indented half a key like on a real keyboard
                if (r == 1 && !symbols) Spacer(Modifier.weight(0.5f))
                if (r == 2) KbKey(if (symbols) "ABC" else "#+=", Modifier.weight(1.5f), onFocusChange, null) { symbols = !symbols; special = false }
                row.forEachIndexed { c, k ->
                    KbKey(k.toString(), Modifier.weight(1f), onFocusChange, if (r < 2 && c == row.lastIndex) onRight else null) { onKey(k.toString()) }
                }
                if (r == 1 && !symbols) Spacer(Modifier.weight(0.5f))
                if (r == 2) KbKey("⌫", Modifier.weight(1.5f), onFocusChange, onRight) { onDelete() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KbKey("Leer", Modifier.weight(if (onPhone == null) (if (onVoice != null) 3.9f else 4.5f) else if (onVoice != null) 2f else 2.6f), onFocusChange, null) { onSpace() }
            if (onPhone != null) KbKey("Handy", Modifier.weight(1.9f), onFocusChange, null, icon = AIcon.Phone) { onPhone() }
            if (onVoice != null) KbKey("", Modifier.weight(0.9f), onFocusChange, null, icon = AIcon.Mic) { onVoice() }
            KbKey(if (special) "abc" else "äöü", Modifier.weight(1.5f), onFocusChange, null) { special = !special; symbols = false }
            KbKey("Alles", Modifier.weight(1.8f), onFocusChange, onRight) { onClear() }
        }
    }
}

@Composable
private fun KbKey(label: String, modifier: Modifier, onFocusChange: (Boolean) -> Unit, onRight: (() -> Boolean)?, icon: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "kbkey")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(42.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) DS.FocusFill else DS.Overlay2)
            .onPreviewKeyEvent { e ->
                if (onRight != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) onRight() else false
            }
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocusChange(it.isFocused) } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (icon != null) AppIcon(icon, if (f) DS.FocusInk else Color.White, size = 18.dp)
            if (label.isNotEmpty()) Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = if (label.length > 1) 15.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private val LocalSearchFocus = compositionLocalOf<(Item) -> Unit> { {} }

/** Info panel above the hits (like the cinema view): picture, title, format, year, rating, genres and description of the highlighted title. */
@Composable
private fun SearchInfoPanel(item: Item?, modifier: Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(DS.RadiusM)
    Box(modifier.clip(shape).background(Surf).border(DS.Border, Surf2, shape)) {
        if (item == null) {
            Text("Suche nach einem Titel: hier erscheinen Beschreibung, Bewertung und Format des markierten Treffers.", color = Muted, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp))
            return@Box
        }
        // Wait a moment so quickly moving across the hits does not load anything
        val meta by produceState<Meta?>(null, item.id) {
            value = null
            if (item.kind == Kind.LIVE) return@produceState
            delay(200)
            value = MetaRepo.basic(ctx, item) ?: MetaRepo.cached(item)
        }
        val epg by produceState(emptyList<EpgEntry>(), item.id) {
            value = emptyList()
            if (item.kind != Kind.LIVE) return@produceState
            delay(200)
            value = kotlinx.coroutines.withContext(Dispatchers.Default) { Epg.now(item) }
        }
        val m = meta
        val nowE = Epg.current(epg)
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val img = m?.poster ?: item.logo
            Box(Modifier.fillMaxHeight().aspectRatio(if (item.kind == Kind.LIVE) 1f else 2f / 3f).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)) {
                if (img != null) coil.compose.AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = if (item.kind == Kind.LIVE) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(raw = true, text = item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val parts = ArrayList<String>()
                parts.add(when (item.kind) { Kind.MOVIE -> "Film"; Kind.SERIES -> "Serie"; else -> "Live" })
                (item.year?.toString() ?: m?.year)?.let { parts.add(it) }
                m?.runtime?.takeIf { it > 0 }?.let { parts.add("$it Min") }
                (item.quality)?.let { parts.add(it) }
                m?.genres?.take(3)?.let { parts.addAll(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    m?.score()?.let { sc ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(AIcon.Star, DS.Gold, size = 16.dp)
                            Text(raw = true, text = String.format(java.util.Locale.US, "%.1f", sc), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(raw = true, text = parts.joinToString("  ·  "), color = Muted, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                val body = when {
                    item.kind == Kind.LIVE && nowE != null -> hhmm(nowE.start) + " – " + hhmm(nowE.end) + "  " + nowE.title
                    item.kind == Kind.LIVE -> item.group
                    else -> m?.overview.orEmpty()
                }
                if (body.isNotBlank()) Text(raw = true, text = body, color = Color(0xFFD5D8E2), fontSize = 15.sp, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compact chip for the quick filters, suggestions and last searches above the keyboard (no check mark). */
@Composable
private fun MiniChip(label: String, selected: Boolean, modifier: Modifier = Modifier, raw: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(32.dp).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) DS.Overlay3 else Surf)
            .border(DS.Border, if (f) DS.FocusFill else if (selected) Color(0x99FFFFFF) else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, raw = raw)
    }
}
