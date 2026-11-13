package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SearchState {
    var query by mutableStateOf("")
    /** Quick filter: 0 all, 1 movies, 2 series, 3 live TV, 4 sports hub. Survives switching between keyboard and results. */
    var filter by mutableIntStateOf(0)
}

/** Titles of the big picture per area (tab) with the candidate ids they were picked from. */
private object HeroCache {
    val byTab = HashMap<Int, Pair<List<String>, List<Item>>>()
}

private object HomeReturn {
    var returning = false
}

/** Last adopted genre state and trend list – kept when leaving the home screen. */
private object HomeVer {
    var value = -1
    var trend: Set<String> = emptySet()
}

private const val SORT_NEW = 0
private const val SORT_RATING = 2
private const val SORT_PLAYLIST = 8

private fun ipvioGroups(list: List<Item>): Map<String, List<Item>> {
    val map = LinkedHashMap<String, ArrayList<Item>>()
    for (item in list) {
        val m = MetaRepo.cached(item)
        val cats = if (m != null) Genres.categoriesOf(m) else emptyList()
        if (cats.isEmpty()) map.getOrPut("Sonstige") { ArrayList() }.add(item)
        else for (c in cats) map.getOrPut(c) { ArrayList() }.add(item)
    }
    val prio = listOf("Anime", "Animation")
    val sorted = map.entries.sortedWith(
        compareBy<Map.Entry<String, ArrayList<Item>>>(
            { if (it.key in prio) prio.indexOf(it.key) else if (it.key == "Sonstige") 1000 else 10 },
            { -it.value.size }
        )
    )
    return sorted.associate { it.key to it.value }
}

private fun providerGroups(list: List<Item>): Map<String, List<Item>> {
    val g = list.groupBy { it.group }
    val order = PlaylistStore.catOrder
    if (order.isEmpty()) return g
    return g.entries.sortedBy { order[it.key] ?: 1_000_000 }.associate { it.key to it.value }
}

/**
 * Everything the home screen derives from the playlist. Computed on a background thread (with 100,000+ titles
 * this takes noticeable time); the UI shows the previous state or the loading skeleton meanwhile.
 */
private class HomeData(
    val live: List<Item>, val movies: List<Item>, val series: List<Item>,
    val liveP: Map<String, List<Item>>, val movieP: Map<String, List<Item>>, val seriesP: Map<String, List<Item>>,
    val movieG: Map<String, List<Item>>, val seriesG: Map<String, List<Item>>,
    val topM: List<Item>, val topS: List<Item>, val byId: Map<String, Item>, val trendItems: List<Item>,
    val genresOk: Boolean
)

private fun buildHome(all: List<Item>, ipvioWanted: Boolean, trend: Set<String>): HomeData {
    val live = ArrayList<Item>(); val movies0 = ArrayList<Item>(); val series = ArrayList<Item>()
    for (i in all) when (i.kind) { Kind.LIVE -> live.add(i); Kind.MOVIE -> movies0.add(i); Kind.SERIES -> series.add(i) }
    // Show the same movie in several versions (HD, 4K, …) only once; the version is chosen on the detail page (source)
    val movies = PlaylistLogic.firstPerKey(movies0) { it.mk + "|" + it.lang }
    val genresOk = MetaRepo.enabled() && Classifier.supported(movies.size + series.size)
    val ipvio = ipvioWanted && genresOk
    val movieP = providerGroups(movies)
    val seriesP = providerGroups(series)
    val trendItems = if (trend.isEmpty()) emptyList() else (movies.asSequence() + series.asSequence()).filter { Trending.norm(it.title) in trend }.toList()
    return HomeData(
        live, movies, series, providerGroups(live), movieP, seriesP,
        if (ipvio) ipvioGroups(movies) else movieP, if (ipvio) ipvioGroups(series) else seriesP,
        topRated(movies), topRated(series), all.associateBy { it.id }, trendItems, genresOk
    )
}

/**
 * The home data is rebuilt when only the genre progress changed (every 45 s while genres are detected); the live channels are the
 * same then. Reusing the previous list objects lets Live TV keep its cached genres and bundles instead of recomputing them on the
 * main thread while it is open.
 */
private fun keepLiveLists(d: HomeData, prev: HomeData?): HomeData {
    if (prev == null) return d
    val live = if (sameItems(d.live, prev.live)) prev.live else d.live
    val liveP = if (d.liveP.size == prev.liveP.size && d.liveP.keys.zip(prev.liveP.keys).all { (a, b) -> a == b } &&
        d.liveP.all { (k, l) -> sameItems(l, prev.liveP[k]) }) prev.liveP else d.liveP
    if (live === d.live && liveP === d.liveP) return d
    return HomeData(live, d.movies, d.series, liveP, d.movieP, d.seriesP, d.movieG, d.seriesG, d.topM, d.topS, d.byId, d.trendItems, d.genresOk)
}

private fun sameItems(a: List<Item>, b: List<Item>?): Boolean {
    if (b == null || a.size != b.size) return false
    for (i in a.indices) if (a[i] !== b[i]) return false
    return true
}

/** Filtered lists per area, so the same list object comes back and [HomeCache] can recognize it (no full recompute on every tab switch). */
private object AreaCache {
    private val m = HashMap<String, Pair<List<Item>, List<Item>>>()
    @Synchronized fun get(all: List<Item>, tab: Int, k: Int): List<Item> {
        val key = "$tab|$k"
        m[key]?.let { if (it.first === all) return it.second }
        return AreaSource.filter(all, tab, k).also { m[key] = all to it }
    }
}

/** Last result: when returning to the home screen (e.g. from the detail page) nothing has to be recomputed. */
private object HomeCache {
    private var all: List<Item>? = null
    private var order: Map<String, Int>? = null
    private var want = false
    private var ver = -1
    private var trend: Set<String>? = null
    private var data: HomeData? = null

    @Synchronized fun get(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>): HomeData? =
        if (all === this.all && order === this.order && want == this.want && ver == this.ver && trend === this.trend) data else null

    @Synchronized fun put(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>, d: HomeData) {
        this.all = all; this.order = order; this.want = want; this.ver = ver; this.trend = trend; data = d
    }

    /** For the wait after a change: better show the previous state than an empty skeleton. */
    @Synchronized fun last(): HomeData? = data
}

private val EMPTY_HOME = HomeData(
    emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    emptyList(), emptyList(), emptyMap(), emptyList(), false
)

private fun topRated(list: List<Item>): List<Item> =
    list.mapNotNull { i ->
        val m = MetaRepo.cached(i)
        val sc = m?.score()
        if (m != null && (m.votes >= 300 || m.tmdbId <= 0) && sc != null) i to sc else null
    }.sortedByDescending { it.second }.map { it.first }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    tab: Int, onTab: (Int) -> Unit, states: List<LazyListState>,
    onOpen: (Item) -> Unit, onPlayNow: (Item) -> Unit,
    onResume: (Entry, Item) -> Unit, onBrowse: (BrowseReq) -> Unit,
    onCategories: (CatReq) -> Unit, onAdd: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allRaw = PlaylistStore.channels
    // Movies/series: playlist selection at the top (with several shown playlists) – only titles of this playlist
    val areaK = if (tab in 1..2) AreaSource.get(tab) else -1
    val all = remember(allRaw, areaK, tab) { if (areaK < 0) allRaw else AreaCache.get(allRaw, tab, areaK) }
    val order = PlaylistStore.catOrder
    // Adopt genre progress at most every 45 s: every adoption recomputes rows and top lists
    var ver by remember { mutableIntStateOf(HomeVer.value.takeIf { it >= 0 } ?: Classifier.version) }
    LaunchedEffect(Unit) { while (true) { delay(45_000); if (ver != Classifier.version) { ver = Classifier.version; HomeVer.value = ver } } }
    var trend by remember { mutableStateOf(HomeVer.trend) }
    LaunchedEffect(all.isNotEmpty()) { if (all.isNotEmpty()) { trend = Trending.titles(); HomeVer.trend = trend } }
    val wantIpvio = AppSettings.ipvioFilter
    val cached = HomeCache.get(all, order, wantIpvio, ver, trend)
    // When inputs change, the previous state stays visible until the new result (produceState keeps the value)
    val dataState = produceState(cached, all, order, wantIpvio, ver, trend) {
        value = HomeCache.get(all, order, wantIpvio, ver, trend)
            ?: withContext(Dispatchers.Default) { keepLiveLists(buildHome(all, wantIpvio, trend), HomeCache.last()) }.also { HomeCache.put(all, order, wantIpvio, ver, trend, it) }
    }
    // While recomputing: show the previous state, the loading skeleton only the very first time
    val prev = HomeCache.last()
    val data = dataState.value ?: prev?.takeIf { it.byId.isNotEmpty() && all.isNotEmpty() } ?: EMPTY_HOME
    val computing = dataState.value == null && prev == null && all.isNotEmpty()
    val live = data.live
    val movies = data.movies
    val series = data.series
    val movieP = data.movieP
    val seriesP = data.seriesP
    val liveP = data.liveP
    val movieG = data.movieG
    val seriesG = data.seriesG
    val topM = data.topM
    val topS = data.topS
    val byId = data.byId
    val cardW = AppSettings.cardW
    // derivedStateOf: the lists are rebuilt only when history / favorites / My list really change, not on every
    // recomposition; the same list object lets the rows below skip instead of recomposing all their cards
    val hist by remember(byId) { derivedStateOf { History.ids.mapNotNull { byId[it] } } }
    val watchList by remember(byId) { derivedStateOf { Library.watch.mapNotNull { byId[it] } } }
    val favLive by remember(byId) { derivedStateOf { Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE } } }
    // Row lists that were built anew inside the rows on every recomposition
    val newMovies = remember(movies) { movies.takeLast(40).reversed() }
    val newSeries = remember(series) { series.takeLast(40).reversed() }
    val histTop = remember(hist) { hist.take(30) }
    val trendItems = data.trendItems
    val trendTop = remember(trendItems) { trendItems.take(10) }
    val wlVod = remember(watchList) { watchList.filter { it.kind != Kind.LIVE } }
    // Live TV opens faster: genres and quality bundling of all channels are prepared in the background (both keep their result)
    LaunchedEffect(live) {
        if (live.isEmpty()) return@LaunchedEffect
        delay(2500)
        withContext(Dispatchers.Default) {
            runCatching { LiveGenre.split(live) }
            if (General.bundleLive) runCatching { LiveBundleLogic.bundle(live) }
            runCatching { SportWatch.prewarm(live) }
        }
    }
    // "Because you watched X": similar titles (TMDB) of the last started movie/series, matched against the own playlist
    val becauseSrc = hist.firstOrNull { it.kind != Kind.LIVE }
    val because by produceState<Pair<Item, List<Item>>?>(null, becauseSrc?.id, byId.size) {
        val src = becauseSrc ?: return@produceState
        val m = MetaRepo.extended(ctx, src) ?: return@produceState
        if (m.tmdbId <= 0) return@produceState
        val c = CreditsRepo.credits(ctx, m) ?: return@produceState
        val seen = History.ids.toSet()
        val l = withContext(Dispatchers.Default) {
            LibMatch.matches(PlaylistStore.channels, c.similar, 40).filter { it.id != src.id && it.id !in seen && it.kind != Kind.LIVE }
        }
        if (l.size >= 4) value = src to l.take(20)
    }

    // Check new episodes of favorite series (bell)
    LaunchedEffect(byId.size, Library.favs.size) {
        if (byId.isNotEmpty()) NewEpisodes.check(ctx, Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.SERIES })
    }

    SideEffect { NavState.tab = tab; ContentFocus.tab = if (tab in 0 until MenuLogic.COUNT) tab else -1 }

    // Header (search, reminders, profile) and quick access as in the design
    var picker by remember { mutableStateOf(false) }
    val ready = remember { mutableStateListOf<Item>() }
    // Also use the sizes of movies/series as a key: if the computed home screen arrives only after the list (and without trend titles,
    // i.e. the same empty trend list), the selection stayed empty and the big picture was missing.
    val candidates = remember(all, trendItems, movies.size, series.size, tab) {
        when (tab) {
            1 -> (trendItems.filter { it.kind == Kind.MOVIE }.take(8) + movies.takeLast(30).reversed())
            2 -> (trendItems.filter { it.kind == Kind.SERIES }.take(8) + series.takeLast(30).reversed())
            else -> (trendItems.take(8) + movies.takeLast(25).reversed() + series.takeLast(15).reversed())
        }.distinctBy { it.id }
    }
    // Movies/series: tabs at the top next to the search (instead of the big buttons under the picture)
    val isVod = tab == 1 || tab == 2
    val vodBase = if (tab == 1) movies else series
    val vodTop = if (tab == 1) topM else topS
    val vodNoun = if (tab == 1) "Filme" else "Serien"
    val openCats: () -> Unit = {
        scope.launch {
            val g = if (data.genresOk) withContext(Dispatchers.Default) { ipvioGroups(vodBase) } else emptyMap()
            // Own imported lists (Letterboxd, Trakt, MyAnimeList) come first, next to the genres
            val imported = ImportedLists.groups(ctx, if (tab == 1) Kind.MOVIE else Kind.SERIES, byId)
            val prov = if (tab == 1) movieP else seriesP
            onCategories(CatReq("$vodNoun · Kategorien", imported + g, imported + prov, SORT_NEW, area = if (tab == 1) CatOrder.MOVIE else CatOrder.SERIES))
        }
    }
    // One header button per imported service (Letterboxd, Trakt, MyAnimeList) that has titles in this area
    val importedTabs = if (!isVod) emptyList() else remember(tab, byId) {
        val g = ImportedLists.groups(ctx, if (tab == 1) Kind.MOVIE else Kind.SERIES, byId)
        ImportedLists.SOURCES.mapNotNull { src ->
            val l = g.filter { it.key.startsWith("$src · ") }.values.flatten().distinctBy { it.id }
            if (l.isEmpty()) null else Triple(src, l, "tb:imp$src$tab")
        }
    }
    // Asked by the View settings' live preview (consumed at once so a later visit does not open it again)
    LaunchedEffect(AppNav.openCatsSignal) { if (AppNav.openCatsSignal > 0) { AppNav.openCatsSignal = 0; if (tab == 1) openCats() } }
    LaunchedEffect(AppNav.openBrowseSignal) {
        if (AppNav.openBrowseSignal > 0) { AppNav.openBrowseSignal = 0; if (tab == 1) onBrowse(BrowseReq("Alle $vodNoun", vodBase, SORT_NEW)) }
    }
    // Asked by the View settings preview: bring the home rows into view (they are far down the list)
    LaunchedEffect(AppNav.homeScrollKey) {
        val want = AppNav.homeScrollKey
        if (want.isEmpty()) return@LaunchedEffect
        AppNav.homeScrollKey = ""
        if (tab != 0) return@LaunchedEffect
        val st = states[0]
        if (want == "top") { st.scrollToItem(0); return@LaunchedEffect }
        val keys = want.split("|")
        repeat(25) {
            val hit = st.layoutInfo.visibleItemsInfo.filter { it.key in keys }.minByOrNull { it.index }
            if (hit != null) { st.scrollToItem(hit.index); return@LaunchedEffect }
            if (!st.canScrollForward) return@LaunchedEffect
            st.animateScrollBy(700f)
        }
    }
    val navTabs = if (!isVod) emptyList() else listOf(
        HeaderTab("Kategorien", AIcon.Categories, "tb:cats$tab") { openCats() },
        HeaderTab("Top bewertet", AIcon.Star, "tb:top$tab") { onBrowse(BrowseReq("Top bewertet · $vodNoun", vodTop, SORT_RATING)) },
        HeaderTab("Neu hinzugefügt", AIcon.Sparkle, "tb:new$tab") { onBrowse(BrowseReq("Neu hinzugefügt · $vodNoun", vodBase, SORT_NEW)) },
        HeaderTab("Alle $vodNoun", AIcon.Grid, "tb:all$tab") { onBrowse(BrowseReq("Alle $vodNoun", vodBase, SORT_NEW)) }
    ) + importedTabs.map { (src, l, id) -> HeaderTab(src, AIcon.Download, id) { onBrowse(BrowseReq("$src · $vodNoun", l, SORT_NEW)) } }
    var inHeader by remember { mutableStateOf(false) }
    val header: @Composable (Modifier) -> Unit = { m ->
        HeaderBar(
            m.onFocusChanged { inHeader = it.hasFocus },
            onSearch = { onTab(4) },
            onReminder = { r -> byId[r.chId]?.let(onOpen) },
            onNewEpisode = { id -> byId[id]?.let(onOpen) },
            // Only one profile: go directly to the profile page of settings (before: general settings)
            onProfile = { if (Profiles.list.size > 1) picker = true else { SettingsNav.openPage = SettingsNav.PROFILES; onTab(MenuLogic.SETTINGS) } },
            tabs = navTabs,
            areaTab = tab,
            // Round 20: the View button is gone (now in Settings > View), only bell and profile at the top right
            showView = false,
            // Without a big picture (no title images yet): the first tab is the target from the sidebar
            firstIsContent = isVod && ready.isEmpty()
        )
    }
    // Tiles with real images from the own library (round 15): scene image of a top title per area,
    // for favorites and My list a title from it, for Live TV the logos of favorite channels.
    val nowChannels = remember(favLive, hist, live) {
        (favLive + hist.filter { it.kind == Kind.LIVE }).distinctBy { it.id }.ifEmpty { live.take(20) }
    }
    val favVod by remember(byId) { derivedStateOf { Library.favs.mapNotNull { byId[it] }.filter { it.kind != Kind.LIVE } } }
    val tileImg by produceState(emptyMap<Int, String>(), topM.size, topS.size, favVod.size, watchList.size) {
        val used = HashSet<String>()
        val out = HashMap<Int, String>()
        suspend fun pick(id: Int, list: List<Item>) {
            for (c in list.take(14)) {
                val u = MetaRepo.basic(ctx, c)?.backdrop
                if (u != null && used.add(u)) { out[id] = u; value = HashMap(out); return }
            }
        }
        pick(1, topM); pick(2, topS)
        pick(MenuLogic.FAVS, favVod.ifEmpty { topM.drop(3) })
        pick(MenuLogic.MYLIST, watchList.filter { it.kind != Kind.LIVE }.ifEmpty { topS.drop(3) })
    }
    val liveLogos = nowChannels.mapNotNull { it.logo }.take(3)
    val tiles = listOf(
        QuickTile("Live TV", "${live.size} Sender", 3, Color(0xFF6B3A0E), Color(0xFF2B1708), Color(0xFFF59E0B), logos = liveLogos) { onTab(3) },
        QuickTile("Filme", "${movies.size} Filme", 1, Color(0xFF1E3A8A), Color(0xFF0F1B45), Color(0xFF60A5FA), image = tileImg[1]) { onTab(1) },
        QuickTile("Serien", "${series.size} Serien", 2, Color(0xFF4C1D95), Color(0xFF22104A), Color(0xFFC084FC), image = tileImg[2]) { onTab(2) },
        QuickTile("Favoriten", "Deine Auswahl", 7, Color(0xFF7F1D1D), Color(0xFF3A0B0B), Color(0xFFFB7185), image = tileImg[MenuLogic.FAVS]) { onTab(MenuLogic.FAVS) },
        QuickTile("Meine Liste", "${watchList.count { it.kind != Kind.LIVE }} Titel", 8, Color(0xFF134E4A), Color(0xFF082725), Color(0xFF2DD4BF), image = tileImg[MenuLogic.MYLIST]) { onTab(MenuLogic.MYLIST) }
    )
    DisposableEffect(Unit) { onDispose { HomeReturn.returning = true } }

    LaunchedEffect(candidates) {
        // Switching between Home / Movies / Series: the big picture of this area is taken over at once instead of
        // vanishing and being looked up title by title again
        val ids = candidates.map { it.id }
        val hit = HeroCache.byTab[tab]
        if (hit != null && hit.first == ids) {
            if (ready.toList() != hit.second) { ready.clear(); ready.addAll(hit.second) }
            return@LaunchedEffect
        }
        ready.clear()
        for (c in candidates) {
            val m = MetaRepo.basic(ctx, c)
            if (m?.backdrop != null) ready.add(c)
            if (ready.size >= 6) break
        }
        HeroCache.byTab[tab] = ids to ready.toList()
    }
    // Switched here from another screen via the sidebar (e.g. search): focus the content
    LaunchedEffect(Unit) {
        if (AppNav.focusTab == tab && LastFocus.pending == null) {
            AppNav.focusTab = -1
            repeat(10) {
                delay(150)
                if (runCatching { ContentFocus.req.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(Unit) {
        if (HomeReturn.returning) {
            HomeReturn.returning = false
            delay(1200)
            if (LastFocus.pending != null) {
                LastFocus.pending = null
                val st = states[tab]
                val atTop = (tab == 0 && st.firstVisibleItemIndex == 0) || (tab in 1..2 && st.firstVisibleItemIndex <= 1)
                if (atTop) runCatching { ContentFocus.req.requestFocus() }
            }
        }
    }

    var inSidebar by remember { mutableStateOf(false) }
    val sideFocus = remember { FocusRequester() }
    // App start: focus on "Watch now" instead of the sidebar. The top picture appears only once playlist and info are loaded
    // (can take a few seconds); so it retries for up to 15 s, and the marker is set only on success.
    // After 3 s only while focus is still in the sidebar (if the user has already chosen, nothing is stolen).
    LaunchedEffect(ready.isNotEmpty(), tab) {
        if (tab != 0 || HomeFlags.heroDone || LastFocus.pending != null) return@LaunchedEffect
        var waited = 0
        while (waited < 45000) {
            delay(150); waited += 150
            if (ready.isEmpty() && waited < 6000) continue
            if (waited > 3000 && !inSidebar) return@LaunchedEffect
            if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) { HomeFlags.heroDone = true; return@LaunchedEffect }
        }
    }
    // Quit: back twice within 2.5 s. Independent of where the focus currently is (otherwise the second
    // back led to the home screen again instead of quitting).
    var lastBack by remember { mutableLongStateOf(0L) }
    fun exitApp() {
        val act = generateSequence<android.content.Context>(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        act?.finishAffinity()
    }
    BackHandler(enabled = !inSidebar && all.isNotEmpty()) {
        val now = System.currentTimeMillis()
        if (tab == 0 && General.confirmExit && now - lastBack < 2500) exitApp()
        // Round 17: Back goes up exactly one level – further down the home screen first up to "Watch now",
        // only from there into the sidebar (like in Movies/Series)
        else if (tab == 0 && states[0].firstVisibleItemIndex > 0) scope.launch {
            runCatching { states[0].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
        else runCatching { sideFocus.requestFocus() }
    }
    // Movies/series: Back from the rows leads first to the tabs at the top, from there into the sidebar
    BackHandler(enabled = !inSidebar && !inHeader && isVod && all.isNotEmpty()) {
        scope.launch {
            runCatching { states[tab].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { HeaderFocus.firstTab.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }
    BackHandler(enabled = inSidebar && tab == 0 && General.confirmExit) {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2500) exitApp()
        else { lastBack = now; android.widget.Toast.makeText(ctx, Lang.tr("Noch einmal Zurück drücken zum Beenden"), android.widget.Toast.LENGTH_SHORT).show() }
    }

    val resumeList by remember(byId) { derivedStateOf { WatchStore.entries.mapNotNull { e -> byId[e.itemId]?.let { e to it } } } }
    // Movies/series tab: the same lists filtered by kind, kept between recompositions
    val vodKind = if (tab == 1) Kind.MOVIE else Kind.SERIES
    val resumeK = remember(resumeList, vodKind) { resumeList.filter { it.second.kind == vodKind } }
    val watchK = remember(watchList, vodKind) { watchList.filter { it.kind == vodKind } }
    val histK = remember(hist, vodKind) { hist.filter { it.kind == vodKind } }
    val trendK = remember(trendItems, vodKind) { trendItems.filter { it.kind == vodKind } }
    val reload: () -> Unit = { PlaylistStore.savedUrl(ctx)?.let { u -> scope.launch { PlaylistStore.load(ctx, u, false) } } }

    // Round 17b (Cinematic Remake): background and info follow the selected title once the big picture has scrolled away
    val bbTab = tab in 0..2
    val bbState = states[tab.coerceIn(0, states.lastIndex)]
    // Round 18: even when the big picture is still partly visible (e.g. first row "Popular series"), the background shows
    // the info of the selected title – and the big picture (slideshow) pauses.
    // Info at the top only for the normal title rows (from "Top 10 today" or "Newly added"), not for tiles, Live TV, Continue watching,
    // My list and Recently watched. And only if the row is low enough not to cover the info.
    val listTop = remember { floatArrayOf(0f) }
    val noBb = remember { setOf("hero", "top", "tiles", "resume", "chnow", "hero1", "hero2", "top1", "top2", "res1", "res2", "wl1", "wl2", "hr1", "hr2", "hint1", "hint2") }
    val bbOnState = remember(tab) {
        derivedStateOf {
            if (!bbTab || !Billboard.cardFocus || Billboard.focused == null) return@derivedStateOf false
            val co = Billboard.focusCo
            if (co == null || !co.isAttached) return@derivedStateOf false
            val li = bbState.layoutInfo
            val y = co.positionInRoot().y - listTop[0]
            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y } ?: return@derivedStateOf false
            (row.key as? String) !in noBb && row.index > 0 &&
                row.offset >= (li.viewportEndOffset - li.viewportStartOffset) * 0.33f
        }
    }
    // Read only in effects and in BillboardLayer: reading it here would recompose the whole home screen
    LaunchedEffect(bbOnState) { snapshotFlow { bbOnState.value }.collect { Billboard.active = it } }
    LaunchedEffect(inSidebar) { if (inSidebar) Billboard.cardFocus = false }
    LaunchedEffect(tab) { Billboard.cardFocus = false }
    val bbItemState = remember { mutableStateOf<Item?>(Billboard.focused) }
    // 17c: switch only after a short dwell (no big images are loaded while browsing quickly)
    // snapshotFlow instead of LaunchedEffect(Billboard.focused): reading the focused title here recomposed the whole home screen
    // (all visible rows and cards) on every move of the highlight
    LaunchedEffect(Unit) {
        snapshotFlow { Billboard.focused }.collectLatest { f -> if (bbItemState.value != null) delay(450); bbItemState.value = f }
    }
    val bbAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bbOnState) {
        snapshotFlow { bbOnState.value && bbItemState.value != null }.collectLatest { on ->
            val t = if (on) 1f else 0f
            if (Look.motion == 0) bbAnim.snapTo(t) else bbAnim.animateTo(t, tween(520))
        }
    }
    // Read only in draw / layer lambdas and in BillboardLayer (no recomposition per animation frame)
    val bbA: () -> Float = remember(bbAnim) { { bbAnim.value } }
    val pivotSpec = remember(tab) { PivotSpec(0.5f) { states[tab.coerceIn(0, states.lastIndex)] } }
    Box(Modifier.fillMaxSize()) {
        if (bbTab) BillboardLayer(bbA, bbItemState, Modifier.padding(start = SideStart + dpx(56), top = dpx(64) + (if (SidebarPrefs.pos == SidebarPrefs.TOP) TopBarH else 0.dp)))
        BoxWithConstraints(Modifier.fillMaxSize().sidebarPad()) {
            // Card widths and height of the big picture from the available space (as in the design: everything at a glance)
            val grid = remember(maxWidth, Design.k, General.cardSize) { cardGrid(maxWidth - RowPadX * 2) }
            // Design: big picture 420 px high (start), 345 px (movies/series)
            val heroH = dpx(if (tab == 0) 540 else 450)
            val keepSettings = AddFlowLogic.keepsSettingsPage(tab, PlaylistStore.lists.isNotEmpty())
            when {
                // Download/parse without a shown list: loading screen with progress (and possibly language selection)
                // On the settings tab only when a language choice is waiting: a mode switch re-parses the stored copy (heavy)
                // and would otherwise unmount the settings, which then reopen on their first page
                PlaylistStore.loading && all.isEmpty() && PlaylistStore.heavy && (!keepSettings || PlaylistStore.langAsk != null) -> PlaylistLoadingOverlay()
                // The settings page stays mounted while the list is empty or being rebuilt (e.g. after a playlist mode switch):
                // replacing it would drop the focus, and the next OK press could land on "Add playlist"
                !keepSettings && ((PlaylistStore.loading && all.isEmpty()) || computing) -> SkeletonHome()
                !keepSettings && all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        BrandMark(Modifier.size(72.dp))
                        val err = PlaylistStore.error
                        // Everything hidden (language filter does not fit this playlist): be able to get it back directly
                        val allHidden = err == null && PlaylistStore.langCounts.isNotEmpty() && PlaylistStore.langCounts.keys.all { it in LangFilter.hidden }
                        Text(
                            when { err != null -> "Die Playlist konnte nicht geladen werden"; allHidden -> "Alle Sprachen dieser Playlist sind ausgeblendet"; else -> "Noch keine Sender" },
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        if (err != null) {
                            Text(err, color = Muted, fontSize = 16.sp)
                            Text("Prüfe die Internetverbindung, die Adresse und die Zugangsdaten.", color = Muted, fontSize = 16.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (allHidden) PillButton("Alle Sprachen zeigen", light = true) { LangFilter.setAndApply(ctx, emptySet()) }
                            if (err != null) PillButton("Erneut versuchen", light = true) { reload() }
                            PillButton("Playlist hinzufügen", light = err == null && !allHidden) { onAdd() }
                        }
                    }
                }
                tab == 3 -> LiveTab(live, liveP, favLive, onOpen, onPlayNow)
                tab == MenuLogic.SPORT -> SportHub(live) { ch, l -> ZapContext.list = l; ZapContext.key = null; onOpen(ch) }
                // The selected row stays in the lower half (PivotSpec); rows above are hidden so that at the top
                // title and info of the selected title are free (billboard)
                else -> CompositionLocalProvider(
                    LocalCardGrid provides grid, LocalScrollTop provides { states[tab].scrollToItem(0) },
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (bbTab) pivotSpec else SmoothSpec)
                ) { LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listTop[0] = it.positionInRoot().y }.drawWithContent {
                    // While the info is at the top (also while fading out): everything above the selected row is gone, the big picture always.
                    val ct = if (bbTab && bbA() > 0.01f) {
                        val li = states[tab].layoutInfo
                        val co = Billboard.focusCo
                        var edge = size.height * 0.5f - dpx(140).toPx()
                        if (Billboard.focused != null && co != null && co.isAttached) {
                            val y = co.positionInRoot().y - listTop[0]
                            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y }
                            if (row != null && row.index > 0) edge = row.offset.toFloat()
                        }
                        li.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { edge = maxOf(edge, (it.offset + it.size).toFloat()) }
                        edge.coerceIn(0f, size.height * 0.75f)
                    } else 0f
                    if (ct > 1f) clipRect(top = ct) { this@drawWithContent.drawContent() } else drawContent()
                }, state = states[tab], contentPadding = PaddingValues(bottom = dpx(420))) {
                    when (tab) {
                        0 -> {
                            // Order and selection of the blocks: Settings > Customize home (HomeLayout)
                            if (HomeEdit.active) item(key = "editTop") {
                                Row(Modifier.padding(start = RowPadX + dpx(2), top = dpx(12)), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PillButton("Fertig", light = true, icon = AIcon.Check) { HomeEdit.active = false }
                                    Text("Bearbeiten: Über jeder Reihe kannst du sie verschieben oder ausblenden.", color = Muted, fontSize = 15.sp)
                                }
                            }
                            if (ready.isNotEmpty() && HomeLayout.shown("hero")) item(key = "hero") { Hero(ready.toList(), heroH, true, onOpen, onPlayNow, header, { heroScroll(states[0]) }) { scope.launch { states[0].animateScrollToItem(0) } } }
                            else item(key = "top") { header(Modifier) }
                            val wl = wlVod
                            for (blockId in HomeLayout.order) {
                                if (blockId == "hero") continue
                                val editing = HomeEdit.active
                                if (editing) item(key = "eb$blockId") { HomeEditBar(ctx, blockId) }
                                if (!HomeLayout.shown(blockId)) continue
                                when (blockId) {
                                    "tiles" -> { item(key = "tiles") { QuickTiles(tiles) } }
                                    "clock" -> { item(key = "clock") { ClockWidget() } }
                                    // Continue watching directly under the tiles: whoever watches a lot wants to continue in few clicks
                                    "resume" -> { if (resumeList.isNotEmpty()) item(key = "resume") { ResumeRow(resumeList, cardW, onResume) } }
                                    "favch" -> { if (favLive.isNotEmpty()) item(key = "favch") { ItemsRow("Meine Sender", favLive, cardW, onOpen) { onTab(3) } } }
                                    "chnow" -> { if (nowChannels.isNotEmpty()) item(key = "chnow") { ChannelNowRow("Live TV", nowChannels, onOpen) { onTab(3) } } }
                                    // Top 10 always as whole covers (independent of the picture setting)
                                    "trend" -> { if (trendItems.size >= 3) item(key = "trend") {
                                        ItemsRow("Top 10 heute", trendTop, cardW, onOpen, style = CARD_TOP10) { onBrowse(BrowseReq("Im Trend", trendItems, SORT_PLAYLIST)) }
                                    } }
                                    "because" -> { because?.let { (src, l) ->
                                        item(key = "because") { ItemsRow("Weil du „" + src.title + "“ geschaut hast", l, cardW, onOpen) { onBrowse(BrowseReq("Ähnlich wie " + src.title, l, SORT_PLAYLIST)) } }
                                    } }
                                    "wl" -> { if (wl.isNotEmpty()) item(key = "wl") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onTab(MenuLogic.MYLIST) } } }
                                    "hist" -> { if (hist.isNotEmpty()) item(key = "hist") { ItemsRow("Zuletzt geschaut", histTop, cardW, onOpen) { onBrowse(BrowseReq("Zuletzt geschaut", hist, SORT_PLAYLIST)) } } }
                                    "newm" -> { if (movies.isNotEmpty()) item(key = "newm") {
                                        ItemsRow("Neu hinzugefügt · Filme", newMovies, cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · Filme", movies, SORT_NEW)) }
                                    } }
                                    "topm" -> { if (topM.size >= 8) item(key = "topm") {
                                        ItemsRow("Top bewertet · Filme", topM, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · Filme", topM, SORT_RATING)) }
                                    } }
                                    "news" -> { if (series.isNotEmpty()) item(key = "news") {
                                        ItemsRow("Neu hinzugefügt · Serien", newSeries, cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · Serien", series, SORT_NEW)) }
                                    } }
                                    "tops" -> { if (topS.size >= 8) item(key = "tops") {
                                        ItemsRow("Top bewertet · Serien", topS, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · Serien", topS, SORT_RATING)) }
                                    } }
                                    "sg" -> { seriesG.entries.take(3).forEach { (g, l) ->
                                        item(key = "s$g") { ItemsRow("Serien · $g", l, cardW, onOpen) { onBrowse(BrowseReq("Serien · $g", l, SORT_NEW)) } }
                                    } }
                                    "mg" -> { movieG.entries.take(3).forEach { (g, l) ->
                                        item(key = "m$g") { ItemsRow("Filme · $g", l, cardW, onOpen) { onBrowse(BrowseReq("Filme · $g", l, SORT_NEW)) } }
                                    } }
                                }
                            }
                            // Build own home screen: entry at the end of the page
                            item(key = "editHome") {
                                Row(Modifier.padding(start = RowPadX + dpx(2), top = dpx(24))) {
                                    SmallPill("Startseite anpassen", icon = AIcon.Sliders) { HomeEdit.active = true; scope.launch { states[0].animateScrollToItem(0) } }
                                }
                            }
                            if (movies.isEmpty() && series.isEmpty()) {
                                item { EmptyHint("Diese Playlist enthält keine Filme oder Serien. Live TV findest du links.") }
                                item { EmptyHint(CONTENT_MODE_HINT.format("movies or series", "live TV only")) }
                            }
                        }
                        1, 2 -> {
                            val isM = tab == 1
                            val base = if (isM) movies else series
                            val top = if (isM) topM else topS
                            val noun = if (isM) "Filme" else "Serien"
                            val kind = if (isM) Kind.MOVIE else Kind.SERIES
                            val groups = if (isM) movieG else seriesG
                            if (ready.isNotEmpty()) item(key = "hero$tab") { Hero(ready.toList(), heroH, false, onOpen, onPlayNow, header, { heroScroll(states[tab]) }) { scope.launch { states[tab].animateScrollToItem(0) } } }
                            else item(key = "top$tab") { header(Modifier) }
                            // Popular (trending, otherwise top rated) directly under the big picture. First row:
                            // "Show all" up = profile at the top right, left = "To list" in the picture
                            val tt = trendK
                            val pop = if (tt.size >= 5) tt else top
                            if (pop.size >= 3) item(key = "pop$tab") {
                                ItemsRow(if (isM) "Beliebte Filme" else "Beliebte Serien", pop, cardW, onOpen,
                                    upReq = HeaderFocus.profile, leftReq = if (ready.isNotEmpty()) HeroFocus.second else null) {
                                    onBrowse(BrowseReq((if (isM) "Beliebte Filme" else "Beliebte Serien"), pop, SORT_PLAYLIST))
                                }
                            }
                            if (base.isNotEmpty() && ready.isEmpty() && pop.size < 3) item(key = "hint$tab") { EmptyHint("Oben findest du alle $noun, Neuheiten, Top bewertet und Kategorien.") }
                            val rl = resumeK
                            if (rl.isNotEmpty()) item(key = "res$tab") { ResumeRow(rl, cardW, onResume) }
                            val wl = watchK
                            if (wl.isNotEmpty()) item(key = "wl$tab") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onBrowse(BrowseReq("Meine Liste · $noun", wl, SORT_PLAYLIST)) } }
                            val recent = histK
                            if (recent.isNotEmpty()) item(key = "hr$tab") { ItemsRow("Zuletzt geschaut", recent, cardW, onOpen) { onBrowse(BrowseReq("Zuletzt geschaut · $noun", recent, SORT_PLAYLIST)) } }
                            if (base.isEmpty()) {
                                item { EmptyHint("Keine $noun in dieser Playlist") }
                                item { EmptyHint(CONTENT_MODE_HINT.format(if (isM) "movies" else "series", "live TV only")) }
                            }
                            if (base.isNotEmpty()) item(key = "new$tab") { ItemsRow("Neu hinzugefügt", if (isM) newMovies else newSeries, cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · $noun", base, SORT_NEW)) } }
                            if (top.size >= 8) item(key = "best$tab") { ItemsRow("Top bewertet", top, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · $noun", top, SORT_RATING)) } }
                            groups.entries.take(12).forEach { (g, l) -> item(key = "G$tab$g") { ItemsRow(g, l, cardW, onOpen) { onBrowse(BrowseReq(g, l, SORT_NEW)) } } }
                            if (groups.size > 12) item { EmptyHint("Weitere Kategorien findest du unter „Kategorien“ oben.") }

                        }
                        MenuLogic.FAVS -> {
                            // Round 14: besides channels also movies and series (detail page > "Add to favorites")
                            val favM = Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.MOVIE }
                            val favS = Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.SERIES }
                            item(key = "favtop") { header(Modifier) }
                            if (favLive.isEmpty() && favM.isEmpty() && favS.isEmpty()) {
                                item(key = "favh") { RowTitle("Favoriten", null, null, onAll = null) }
                                item(key = "fave") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Heart, "Noch keine Favoriten", "Auf einem Sender OK gedrückt halten, bei Filmen und Serien auf der Detailseite „Zu Favoriten“ wählen.",
                                            action = "Zu Live TV", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(3) }
                                    }
                                }
                            }
                            if (favLive.isNotEmpty()) {
                                item(key = "favh") { RowTitle("Favoriten · Sender", null, null, onAll = null) }
                                item(key = "favg") { ChannelDetailRows(favLive, ContentFocus.req, onOpen, reorder = favLive) }
                            }
                            if (favM.isNotEmpty()) {
                                item(key = "favmh") { RowTitle("Favoriten · Filme", null, null, onAll = null) }
                                cardGridItems("favm", favM, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty()) ContentFocus.req else null, onOpen, reorder = favM)
                            }
                            if (favS.isNotEmpty()) {
                                item(key = "favsh") { RowTitle("Favoriten · Serien", null, null, onAll = null) }
                                cardGridItems("favs", favS, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty() && favM.isEmpty()) ContentFocus.req else null, onOpen, reorder = favS)
                            }
                            val favCats = CatFavs.list.filter { it in liveP }
                            if (favCats.isNotEmpty()) favCats.forEach { g ->
                                item(key = "fc$g") { ItemsRow(g, liveP[g].orEmpty(), cardW, onOpen) { onTab(3) } }
                            }
                        }
                        MenuLogic.MYLIST -> {
                            item(key = "mltop") { header(Modifier) }
                            val wm = watchList.filter { it.kind == Kind.MOVIE }
                            val ws = watchList.filter { it.kind == Kind.SERIES }
                            if (wm.isEmpty() && ws.isEmpty()) {
                                item(key = "mlh") { RowTitle("Meine Liste", null, null, onAll = null) }
                                item(key = "mle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Plus, "Noch nichts in deiner Liste", "Auf der Seite eines Films oder einer Serie „Meine Liste“ wählen.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (wm.isNotEmpty()) {
                                item(key = "mlmh") { RowTitle("Meine Liste · Filme", null, null, onAll = null) }
                                cardGridItems("mlm", wm, CARD_POSTER, ContentFocus.req, onOpen)
                            }
                            if (ws.isNotEmpty()) {
                                item(key = "mlsh") { RowTitle("Meine Liste · Serien", null, null, onAll = null) }
                                cardGridItems("mls", ws, CARD_POSTER, if (wm.isEmpty()) ContentFocus.req else null, onOpen)
                            }
                        }
                        MenuLogic.VERLAUF -> {
                            // Recently watched; for series only the series title (the history stores one line per series)
                            item(key = "vltop") { header(Modifier) }
                            val vm = hist.filter { it.kind == Kind.MOVIE }
                            val vs = hist.filter { it.kind == Kind.SERIES }
                            if (vm.isEmpty() && vs.isEmpty()) {
                                item(key = "vlh") { RowTitle("Verlauf", null, null, onAll = null) }
                                item(key = "vle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.History, "Noch nichts angesehen", "Hier erscheinen die zuletzt gestarteten Filme und Serien.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (vm.isNotEmpty()) {
                                item(key = "vlmh") { RowTitle("Verlauf · Filme", null, null, onAll = null) }
                                cardGridItems("vlm", vm, CARD_POSTER, ContentFocus.req, onOpen)
                            }
                            if (vs.isNotEmpty()) {
                                item(key = "vlsh") { RowTitle("Verlauf · Serien", null, null, onAll = null) }
                                cardGridItems("vls", vs, CARD_POSTER, if (vm.isEmpty()) ContentFocus.req else null, onOpen)
                            }
                        }
                        else -> {
                            item(key = "settop") { header(Modifier) }
                            item { SettingsHub(onAdd, reload) }
                        }
                    }
                } }
            }
            // Design settings: the preview stays fixed at the top right, the settings scroll away below
            if (DesignPreview.visible) DesignPreviewPanel(Modifier.align(Alignment.TopEnd).padding(top = dpx(84), end = DS.PageX))
        }
        if (picker) {
            BackHandler { picker = false }
            ProfilePicker { id -> picker = false; if (id != Profiles.active) Profiles.switchTo(ctx, id) }
        }
        Sidebar(
            tab,
            { id -> scope.launch { states.getOrNull(id)?.scrollToItem(0) }; onTab(id) },
            Modifier.align(SidebarPrefs.align()), sideFocus
        ) { inSidebar = it }
    }
}

/** Shown when the playlist has no movies/series; most likely its content mode hides them. Args: kind of content, mode name. */
private const val CONTENT_MODE_HINT = "There are no %s. If there should be some, check Settings > Playlists: the content mode of this playlist may be set to %s."

@Composable
private fun EmptyHint(text: String) {
    // Round 17: notice with icon, larger and calmer (instead of a small gray line)
    Row(Modifier.padding(horizontal = DS.PageX, vertical = dpx(20)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        AppIcon(AIcon.Info, Accent2, size = dpx(26))
        Text(text, color = Tok.TextMid, fontSize = spx(19))
    }
}

object PillMemory {
    var key: String? = null
}

@Composable
private fun ItemsRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, style: Int = -1,
    upReq: FocusRequester? = null, leftReq: FocusRequester? = null, onAll: () -> Unit
) {
    // After "Show all" and back: focus the link of this row again
    val key = NavState.tab.toString() + "|" + title
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    // Picture type from "View" (default: cover); Top 10 always cover
    val st = if (style >= 0) style else if (General.cardArt == 1) CARD_LAND else CARD_POSTER
    CardRow(title, list, st, onOpen, { PillMemory.key = key; onAll() }, RowStates.get("${NavState.tab}|$title"), headerReq, firstCard,
        upReq = upReq, leftReq = leftReq)
}

@Composable
private fun ResumeRow(list: List<Pair<Entry, Item>>, cardW: Int, onResume: (Entry, Item) -> Unit) {
    val ctx = LocalContext.current
    Column {
        val g = LocalCardGrid.current
        Row(Modifier.padding(start = dpx(29), top = dpx(26), end = dpx(28)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
            Text("Weiterschauen", color = Color.White, fontSize = spx(23), fontWeight = FontWeight.Bold)
            Text("OK lange drücken = entfernen", color = Muted, fontSize = spx(13), modifier = Modifier.padding(bottom = dpx(3)))
        }
        LazyRow(
            state = RowStates.get("${NavState.tab}|resume"),
            contentPadding = PaddingValues(start = RowPadX, end = RowPadX, top = dpx(12), bottom = dpx(10)),
            horizontalArrangement = Arrangement.spacedBy(g.gap)
        ) {
            items(list) { pair ->
                val e = pair.first
                val item = pair.second
                val se = if (e.season > 0 || e.ep > 0) e.season to e.ep else ResumeLogic.seasonEp(e.title)
                val multi = item.kind == Kind.SERIES && item.episodes.any { it.season != (se?.first ?: 1) }
                val epText = se?.let { if (multi) "S${it.first} · Folge ${it.second}" else "Folge ${it.second}" }
                val sub = when {
                    item.kind != Kind.SERIES -> "bei " + (e.pos / 60000) + " Min"
                    e.next && epText != null -> "Als Nächstes: $epText"
                    epText != null -> epText
                    else -> e.title.removePrefix(item.title).trim()
                }
                WideCard(
                    item, sub, if (e.dur > 0) (e.pos.toFloat() / e.dur).coerceIn(0f, 1f) else 0f,
                    Modifier.width(g.wide).restoreFocus(item.id).favKeys {
                        WatchStore.remove(ctx, e.itemId)
                        android.widget.Toast.makeText(ctx, Lang.tr("Aus „Weiterschauen“ entfernt"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) { onResume(e, item) }
            }
        }
    }
}

/** Wide landscape card (backdrop) with title and progress bar, like "Continue watching" in Netflix. */
@Composable
private fun WideCard(item: Item, sub: String?, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val m = rememberMeta(item)
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "wide")
    val shape = RoundedCornerShape(DS.RadiusM)
    val img = m?.backdrop ?: m?.poster ?: item.logo
    Column(modifier.graphicsLayer { scaleX = sc; scaleY = sc }.billboardFocus(item) { f = it }.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusGlow(f, shape).clip(shape)
                .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
                .border(DS.FocusBorder, if (f) Accent2 else Color.Transparent, shape)
        ) {
            if (img != null) ArtImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xDD000000))))
            AppIcon(AIcon.Play, Color.White, Modifier.align(Alignment.Center), 30.dp)
            Text(raw = true, text = item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = if (progress > 0f) 14.dp else 10.dp))
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent2))
            }
        }
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

/** Second button in the big picture ("More info" / "To list"): target for Left from "Show all" of the first row. */
object HeroFocus { val second = FocusRequester() }

/** How far the big picture is pushed up (for the parallax effect); 0 when it is no longer at the top. */
private fun heroScroll(st: LazyListState): Int = if (st.firstVisibleItemIndex == 0) st.firstVisibleItemScrollOffset else 0

@Composable
private fun Hero(
    featured: List<Item>, heroH: Dp, home: Boolean, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit,
    header: @Composable (Modifier) -> Unit, scrollY: () -> Int = { 0 }, onFocusTop: () -> Unit
) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured.size, General.heroSec) {
        while (featured.size > 1 && General.heroSec > 0) { delay(General.heroSec * 1000L); if (!Billboard.active) idx = (idx + 1) % featured.size }
    }
    val item = featured[idx.coerceIn(0, featured.lastIndex)]

    // Picture across the full width (also behind the header), faded into the background at the left and bottom.
    // Round 14: without arrows; the description has more width (up to about the middle of the picture) and is no longer cut off (max. 250 characters).
    // Round 17c: parallax while scrolling (the constant zoom was removed for performance)
    Box(Modifier.fillMaxWidth().height(heroH).background(Bg).clipToBounds().onFocusChanged { if (it.hasFocus) Billboard.cardFocus = false }) {
        Crossfade(item, animationSpec = tween(1100), label = "heroBg") { c ->
            val m = rememberMeta(c)
            // 17c: calm picture (no constant zoom any more, which made the Fire TV redraw constantly); only slight parallax while scrolling
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = if (Look.motion == 0) 0f else scrollY() * 0.4f
            }) {
                m?.backdrop?.let { ArtImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd) }
            }
        }
        // Gradients: left and bottom into the background, subtle at the top behind the header; plus a soft vignette
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.28f to Bg.copy(alpha = .9f), 0.5f to Bg.copy(alpha = .45f), 0.72f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = .55f), 0.22f to Color.Transparent, 0.62f to Color.Transparent, 0.88f to Bg.copy(alpha = .85f), 1f to Bg)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Bg.copy(alpha = .35f)), radius = 2200f)))
        header(Modifier.align(Alignment.TopStart))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = dpx(39), bottom = dpx(if (home) 34 else 26)).widthIn(max = dpx(780)),
            verticalArrangement = Arrangement.spacedBy(dpx(10))
        ) {
            Crossfade(item, animationSpec = tween(500), label = "heroText") { c ->
                // with IMDb and Rotten Tomatoes rating (fetched the first time, then remembered on the device)
                val m = rememberFullMeta(c, rememberMeta(c))
                Column(verticalArrangement = Arrangement.spacedBy(dpx(if (home) 10 else 8))) {
                    Kicker(Lang.tr(kindLabel(c.kind)) + (qualityOf(c)?.let { q -> "  ·  " + q.uppercase() } ?: ""))
                    Text(
                        raw = true, text = c.title, color = Tok.TextHi, fontSize = spx(if (home) 80 else 72), lineHeight = spx(if (home) 80 else 72),
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    HeroMetaLine(c, m, dots = !home)
                    m?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(raw = true, text = TitleText.short(it, 250), color = Tok.TextMid, fontSize = spx(19), lineHeight = spx(27), maxLines = 3)
                    }
                }
            }
            Row(
                Modifier.padding(top = dpx(if (home) 12 else 8)).onFocusChanged { if (it.hasFocus) onFocusTop() },
                horizontalArrangement = Arrangement.spacedBy(dpx(16))
            ) {
                val h = if (home) 52 else 46
                HeroButton("Jetzt ansehen", AIcon.Play, true, Modifier.focusRequester(ContentFocus.req), h) { onPlay(item) }
                if (home) HeroButton("Mehr Infos", AIcon.Info, false, Modifier.focusRequester(HeroFocus.second), h = h) { onOpen(item) }
                else {
                    val inList = Library.isWatch(item.id)
                    HeroButton(if (inList) "In Meiner Liste" else "Zur Liste", if (inList) AIcon.Check else AIcon.Plus, false, Modifier.focusRequester(HeroFocus.second), h = h) { Library.toggleWatch(ctx, item.id) }
                }
            }
        }
        if (featured.size > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = dpx(if (home) 33 else 42), bottom = dpx(if (home) 30 else 20)),
            horizontalArrangement = Arrangement.spacedBy(dpx(12)), verticalAlignment = Alignment.CenterVertically
        ) {
            // Active dot fills up until the next change (shows when the picture changes)
            val prog = remember(idx) { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(idx, General.heroSec) {
                if (General.heroSec > 0) prog.animateTo(1f, tween(General.heroSec * 1000, easing = androidx.compose.animation.core.LinearEasing))
            }
            featured.indices.forEach { i ->
                // 17c: progress only drawn (no rebuild on every frame)
                if (i == idx) Box(Modifier.width(dpx(40)).height(dpx(7)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (General.heroSec > 0) prog.value.coerceIn(0.08f, 1f) else 1f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(Accent1))
                }
                else Box(Modifier.width(dpx(7)).height(dpx(7)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .45f)))
            }
        }
    }
}

@Composable
private fun SkeletonHome() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.14f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ska")
    val col = Color.White.copy(alpha = a)
    Column(Modifier.fillMaxSize().padding(horizontal = DS.PageX).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(DS.RadiusXl)).background(col))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(5) { Box(Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(DS.RadiusM)).background(col)) }
        }
    }
}

/** Background and info of the selected title. Reads the fade value only here and in layer lambdas, so fading does not recompose the home screen. */
@Composable
private fun BillboardLayer(fade: () -> Float, item: State<Item?>, infoMod: Modifier) {
    val shown by remember { derivedStateOf { fade() > 0.01f } }
    if (!shown) return
    val bi = item.value
    BillboardBackdrop(bi, Modifier.fillMaxSize().graphicsLayer { alpha = fade() })
    bi?.let { BillboardInfo(it, infoMod.graphicsLayer { alpha = fade(); translationY = (1f - fade()) * 20.dp.toPx() }) }
}
