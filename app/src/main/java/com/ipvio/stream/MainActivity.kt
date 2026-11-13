package com.ipvio.stream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load settings early so colors and size are right in the very first frame.
        Profiles.init(this)
        Profiles.prewarm(this)
        val pick = Profiles.needsPicker(this)
        if (!pick) AppSettings.init(this)
        General.init(this)
        Boomer.init(this)
        HomeLayout.init(this); BoomerLayout.init(this)
        SidebarPrefs.init(this)
        Config.init(this)
        Config.initSubs(this)
        LiveSettings.init(this)
        Recorder.attach(this)
        MobilePrefs.apply(this)
        // Fresh start (not just a rotated/recreated screen): "Resume last watched at start" may apply again.
        // The process often lives on after quitting; the marker flag therefore stayed set from the first start and nothing was opened any more.
        if (savedInstanceState == null) General.startupHandled = false
        // WorkManager sets up its database on first use: off the main thread, so the first screen is not delayed
        val app = applicationContext
        Thread({ runCatching { Updater.apply(app) } }, "updater-apply").start()
        CrashLog.install(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(Mem.imageCachePercent(Mem.tier(this))).build() }
                .allowRgb565(true)
                .bitmapFactoryMaxParallelism(Mem.imageDecoders(Mem.tier(this)))
                .crossfade(false)
                .build()
        )
        ScreenAwake.attach(this)
        intent?.getStringExtra(Reminders.EXTRA_CH)?.let { AppNav.openChannel = it }
        setContent { App(pick) }
    }

    /** Reminder tapped while the app is already running: open the channel. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Reminders.EXTRA_CH)?.let { AppNav.openChannel = it }
    }

    override fun onStart() {
        super.onStart()
        Reminders.foreground = true
    }

    override fun onStop() {
        Reminders.foreground = false
        super.onStop()
    }

    /** Home key (or app switch) by the user – not on the screensaver and not on trailer/external player. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppNav.leftByUser = true
    }

    /** Back in the app after the Home key: to the home screen (see AppContent). */
    override fun onRestart() {
        super.onRestart()
        if (AppNav.leftByUser) { AppNav.leftByUser = false; AppNav.homeSignal++ }
    }

    override fun onResume() {
        super.onResume()
        // After standby or app switch set the window flag again in case the system dropped it
        ScreenAwake.apply()
    }

    override fun onDestroy() {
        ScreenAwake.detach(this)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val low = TRIM_MEMORY_RUNNING_LOW
        if (level >= low) Mem.trim(this)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        Mem.trim(this)
    }
}

@OptIn(ExperimentalFoundationApi::class)
val SmoothSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec = tween<Float>(280, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = 90f
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f && trailing > containerSize -> 0f
            leading < 0f -> leading - peek
            else -> trailing - containerSize + peek
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(pick: Boolean = false) {
    // Display size: the design is for 960 dp width. On devices with more dp (e.g. Fire TV) it is scaled up automatically.
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val factor = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
    val tf = General.textFactor()
    val scaled = remember(base.density, base.fontScale, factor, tf) { Density(base.density * factor, base.fontScale * tf) }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalBringIntoViewSpec provides SmoothSpec,
        LocalOverscrollFactory provides null
    ) {
        val c = LocalContext.current
        var picked by remember { mutableStateOf(!pick) }
        if (!picked) ProfilePicker { id -> Profiles.select(c, id); AppSettings.init(c); Parental.init(c); LangFilter.init(c); Boomer.init(c); HomeLayout.init(c); BoomerLayout.init(c); picked = true }
        else {
            LaunchedEffect(Unit) { delay(10_000); runCatching { SportAlerts.run(c.applicationContext) } }   // after the start: the playlist and the home screen come first
            Box(Modifier.fillMaxSize()) { AppContent(); SportAlertHost() }
        }
        // New version available: dialog with one button (above every screen)
        UpdatePromptHost()
        // First start without a playlist: one non-blocking card that checks for updates (T-014)
        if (picked) UpdateCardHost()
    }
}

/** "Adjust screen": margin all around (in percent of width/height) in case the TV cuts off edges. */
fun Modifier.fitScreenInset(on: Boolean): Modifier = if (!on || (General.fitX == 0 && General.fitY == 0)) this else this.layout { m, c ->
    val dx = (c.maxWidth * General.fitX / 100f).toInt()
    val dy = (c.maxHeight * General.fitY / 100f).toInt()
    val inner = c.copy(maxWidth = (c.maxWidth - 2 * dx).coerceAtLeast(0), maxHeight = (c.maxHeight - 2 * dy).coerceAtLeast(0), minWidth = 0, minHeight = 0)
    val pl = m.measure(inner)
    layout(c.maxWidth, c.maxHeight) { pl.place(dx, dy) }
}

private val SE_RE = Regex("""S(\d+)E(\d+)""")

@Composable
private fun AppContent() {
    val ctx = LocalContext.current
    remember {
        AppSettings.init(ctx); WatchStore.init(ctx); History.init(ctx); Library.init(ctx); QualityStore.init(ctx)
        PlaybackPrefs.init(ctx); DeadLinks.init(ctx); ChannelLists.init(ctx); Reminders.init(ctx); Reminders.schedule(ctx); 0
    }
    // Interrupted in the middle of adding last time (crash, power loss): the add screen automatically continues loading
    val resumeAdd = remember { PlaylistStore.pending(ctx)?.autoResume == true }
    var adding by remember { mutableStateOf(PlaylistStore.savedUrl(ctx) == null || resumeAdd) }
    // Top detail page (Item or PersonRef) plus back stack, so Back retraces Movie → Person → Movie
    var top by remember { mutableStateOf<Any?>(null) }
    val trail = remember { mutableStateListOf<Any>() }
    val detail = top as? Item
    val personTop = top as? PersonRef
    fun pushTop(x: Any) { top?.let { trail.add(it) }; top = x }
    fun popTop() { top = trail.removeLastOrNull() }
    var browse by remember { mutableStateOf<BrowseState?>(null) }
    // Stalker portal: load the opened category on demand, stop when leaving
    LaunchedEffect(browse) { browse?.let { StalkerMore.attach(ctx, it) } ?: StalkerMore.detach() }
    var cats by remember { mutableStateOf<CatReq?>(null) }
    var playing by remember { mutableStateOf<PlayReq?>(null) }
    var tab by remember { mutableStateOf(SettingsLogic.resolveStartTab(General.startTab, AppSettings.menuOrder.toList(), AppSettings.menuHidden.toList())) }
    val gridState = remember(browse) { LazyGridState() }
    val states = listOf(
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState()
    )

    // New app version on GitHub? Check silently once a day; a find appears in the bell at the top right.
    LaunchedEffect(Unit) {
        delay(3_000)   // only after the start, the playlist takes priority
        runCatching { AppUpdate.startCheck(ctx) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { General.cleanupLegacy(ctx) }
        if (resumeAdd) return@LaunchedEffect
        PlaylistStore.savedUrl(ctx)?.let { u ->
            val skip = !SettingsLogic.refreshDue(General.plan(), General.lastRefresh, System.currentTimeMillis(), General.zone())
            val ok = PlaylistStore.load(ctx, u, false, skip)
            if (ok && !skip) General.markRefreshed(ctx)
        }
    }

    // Back in the app after the Home key: to the home screen (a running playback and adding stay as they are)
    LaunchedEffect(AppNav.homeSignal) {
        if (AppNav.homeSignal > 0 && playing == null && !adding) {
            val changed = top != null || browse != null || cats != null || tab != 0
            top = null; trail.clear(); browse = null; cats = null
            if (changed) AppNav.focusTab = 0
            if (tab != 0) { tab = 0; CatMemory.reset() }
        }
    }

    // Left the add screen without anything loaded (e.g. resuming cancelled): load the saved playlist.
    // Not when a list was already shown and is only empty because of the playlist mode (see AddFlowLogic).
    var wasAdding by remember { mutableStateOf(adding) }
    LaunchedEffect(adding) {
        if (AddFlowLogic.shouldReloadAfterAddClosed(wasAdding, adding, PlaylistStore.channels.isEmpty(), PlaylistStore.loading, PlaylistStore.shownUrl != null)) {
            PlaylistStore.savedUrl(ctx)?.let { PlaylistStore.load(ctx, it, false, true) }
        }
        wasAdding = adding
    }

    val all = PlaylistStore.channels
    val liveAll = remember(all) { all.filter { it.kind == Kind.LIVE } }
    // Do not fall into the screensaver while downloading and parsing the playlist
    KeepScreenOn("playlist", PlaylistStore.loading)
    // Genre detection in the background; restarts cleanly with every new list (reload, language filter, parental control)
    LaunchedEffect(all) { kotlinx.coroutines.delay(8_000); if (!DemoMode.active) Classifier.restart(ctx, all) }   // after the first screen is up, and debounced while the list is still changing

    fun start(item: Item, title: String, url: String, startMs: Long = 0L) {
        if (DemoMode.active) return   // sample entries have no stream address
        History.add(ctx, item.id)
        val sm = SE_RE.find(title)
        val season = sm?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ep = sm?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val tmdb = MetaRepo.cached(item)?.tmdbId?.takeIf { it > 0 } ?: 0
        var playUrl = url
        var alts = emptyList<Variant>()
        var note: String? = null
        if (item.kind == Kind.MOVIE && !item.id.startsWith("rec:")) {
            // Same movie in other qualities (own entries of the provider): choose to suit the device, the rest as fallbacks
            val list = Sources.alternatives(item, all)
            if (list.size > 1) {
                alts = Sources.asVariants(list)
                val chosen = alts.indexOfFirst { it.url == url }.coerceAtLeast(0)
                val cap = PlaybackPrefs.cap(ctx)
                val best = QualityLogic.order(alts.map { it.quality }, cap).first()
                val rc = QualityLogic.rank(alts[chosen].quality)
                // Quality chosen explicitly on the detail page takes precedence over the automatic mode
                val vp = VariantPref.get(ctx, item.id)
                // Explicitly chosen source (detail page, round 14) before explicitly chosen quality before automatic
                val fixedSrc = vp.third?.let { l -> alts.indexOfFirst { it.label == l } }?.takeIf { it >= 0 }
                val fixed = vp.first?.let { q -> alts.indexOfFirst { it.quality == q } }?.takeIf { it >= 0 }
                if (fixedSrc != null && !DeadLinks.isDead(alts[fixedSrc].url)) {
                    playUrl = alts[fixedSrc].url
                } else if (fixed != null) {
                    playUrl = alts[fixed].url
                } else if (PlaybackPrefs.quality == QualityLogic.AUTO && rc > cap && QualityLogic.rank(alts[best].quality) <= cap) {
                    playUrl = alts[best].url
                    note = (alts[chosen].quality ?: "Diese Qualität") + " passt nicht zu diesem Gerät – spiele " + (alts[best].quality ?: "SD")
                } else if (DeadLinks.isDead(url)) {
                    // chosen source is known to be broken: take another one right away
                    alts.indices.firstOrNull { it != chosen && !DeadLinks.isDead(alts[it].url) }?.let { playUrl = alts[it].url; note = "Quelle gestört – spiele eine andere" }
                }
            }
        }
        playing = PlayReq(title, playUrl, item.id, item.kind == Kind.LIVE, startMs, tmdb, season, ep, alts, note)
    }

    /** EPG (sidebar/tile): start the last watched channel (otherwise the first) and show the TV guide right away. */
    fun openEpg() {
        val ch = History.ids.firstNotNullOfOrNull { id -> liveAll.firstOrNull { it.id == id } } ?: liveAll.firstOrNull() ?: return
        AppNav.focusTab = -1
        ZapContext.list = liveAll; ZapContext.key = "ALL"
        AppNav.openGuide = true
        start(ch, ch.title, ch.url)
    }

    /** Switch area; "EPG" is not a screen of its own but opens the TV guide in the player. */
    // Area switched (tile, "Show all", sidebar): put the focus into the new content.
    // Otherwise it falls back to the first element, which is the sidebar – it expands by itself (round 15).
    var jumpTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(jumpTick) {
        if (jumpTick == 0) return@LaunchedEffect
        val id = tab
        delay(150)
        repeat(20) {
            if (ContentFocus.tab == id && runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(120)
        }
    }
    fun goTab(id: Int) {
        if (id == MenuLogic.EPG) { openEpg(); return }
        tab = id; CatMemory.reset()
        jumpTick++
    }

    val open: (Item) -> Unit = { i ->
        if (i.kind == Kind.SERIES) NewEpisodes.dismiss(ctx, i.id)
        LastFocus.pending = i.id
        if (i.kind == Kind.LIVE) start(i, i.title, i.url) else pushTop(i)
    }

    // Reminder notification tapped: open the channel as soon as the list is loaded
    LaunchedEffect(AppNav.openChannel, liveAll) {
        val id = AppNav.openChannel ?: return@LaunchedEffect
        val ch = liveAll.firstOrNull { it.id == id } ?: return@LaunchedEffect
        AppNav.openChannel = null
        General.startupHandled = true
        top = null; browse = null; cats = null; adding = false
        start(ch, ch.title, ch.url)
    }
    // Phone search: title the phone wants to start (movie, episode, channel), as soon as the playlist is loaded
    LaunchedEffect(RemoteHub.playReq, all) {
        val r = RemoteHub.playReq ?: return@LaunchedEffect
        val it = all.firstOrNull { x -> x.id == r.id } ?: return@LaunchedEffect
        RemoteHub.playReq = null
        RemoteUi.show = false
        General.startupHandled = true
        top = null; trail.clear(); browse = null; cats = null; adding = false
        when (it.kind) {
            Kind.LIVE -> { ZapContext.list = emptyList(); ZapContext.key = null; start(it, it.title, it.url) }
            Kind.MOVIE -> {
                // Already started? Then continue where you left off (as on the TV)
                val we = WatchStore.entries.firstOrNull { w -> w.itemId == it.id && !w.next }
                start(it, it.title, it.url, we?.pos ?: 0L)
            }
            Kind.SERIES -> {
                val ep = LazySeries.full(it).episodes.firstOrNull { e -> e.season == r.season && e.num == r.ep }
                if (ep != null) {
                    // Same episode already started: continue at the remembered position
                    val we = WatchStore.entries.firstOrNull { w -> w.itemId == it.id && !w.next && w.season == ep.season && w.ep == ep.num }
                    start(it, it.title + " S" + ep.season + "E" + ep.num, pickVariant(ep, null, null, null, PlaybackPrefs.cap(ctx)).url, we?.pos ?: 0L)
                }
            }
        }
    }
    // "Stop" on the phone: close the player
    LaunchedEffect(RemoteHub.closeTick) { if (RemoteHub.closeTick > 0 && playing != null) playing = null }
    // Reminders while the app is open: card with "Switch" (instead of a notification)
    var remindNow by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (remindNow == null) Reminders.pending(System.currentTimeMillis() / 1000).firstOrNull()?.let { r ->
                Reminders.markDone(ctx, r); remindNow = r
            }
            delay(15_000)
        }
    }

    // Settings "Play last channel / last movie or series at start" (once per app start).
    // What counts is what ran last: a channel or a movie / series.
    LaunchedEffect(all.isNotEmpty()) {
        if (all.isNotEmpty() && !General.startupHandled) {
            General.startupHandled = true
            val lastId = History.ids.firstOrNull()
            val last = if (lastId == null || playing != null) null else all.firstOrNull { it.id == lastId }
            if (last != null && last.kind == Kind.LIVE && General.resumeLive) start(last, last.title, last.url)
            else if (last != null && last.kind != Kind.LIVE && General.resumeVod) {
                // only with a remembered position (fully watched movies do not start from the beginning; for series possibly the next episode)
                val e = WatchStore.entries.firstOrNull { it.itemId == last.id }
                if (e != null) {
                    pushTop(last)
                    val t = WatchStore.target(ctx, last, e)
                    start(last, t.first, t.second, e.pos)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).fitScreenInset(playing == null)) {
        // Scale of the design from the available width (see Design)
        val dk = maxWidth.value / Design.WIDTH
        SideEffect { if (Design.k != dk) Design.k = dk }
        val p = playing
        val d = detail
        val b = browse
        val c = cats
        when {
            p != null -> {
                val zap = if (p.live || p.archive != null) {
                    val ctxList = ZapContext.list
                    if (ctxList.any { it.id == p.itemId }) ctxList
                    else {
                        val g = liveAll.firstOrNull { it.id == p.itemId }?.group
                        liveAll.filter { it.group == g }
                    }
                } else emptyList()
                val seriesBase = remember(p.itemId, all) {
                    if (p.live || p.archive != null) null else all.firstOrNull { it.id == p.itemId }?.let { LazySeries.full(it) }
                }
                // Series with a placeholder (e.g. Resume from the home screen): fetch episodes in the background, the player uses them right after
                val seriesItem by produceState(seriesBase, seriesBase) {
                    value = seriesBase
                    val b = seriesBase
                    if (b != null && LazySeries.isStub(b)) LazySeries.load(ctx, b)?.let { value = it }
                }
                // New start from outside (e.g. reminder "Switch") during playback: rebuild the player
                key(p) {
                    PlayerScreen(p, zap, liveAll, seriesItem) {
                        // Live: back in Live TV on the last watched channel (also after switching in the player)
                        if (p.live || p.archive != null) LastFocus.pending = History.ids.firstOrNull { id -> liveAll.any { it.id == id } }
                        playing = null
                    }
                }
            }
            // Round 17: every screen fades in softly (screenEnter); the player deliberately without an effect
            personTop != null -> key(personTop.id) { Box(Modifier.fillMaxSize().screenEnter()) { PersonScreen(personTop, all, onOpen = { pushTop(it) }, onBack = { popTop() }) } }
            d != null -> key(d.id) { Box(Modifier.fillMaxSize().screenEnter()) {
                DetailScreen(
                    d, all, onPlay = { url, t, pos -> start(d, t, url, pos) },
                    onPerson = { pushTop(it) }, onOpenItem = { pushTop(it) }, onBack = { popTop() }
                )
            } }
            b != null -> Box(Modifier.fillMaxSize().screenEnter()) { BrowseScreen(
                b, gridState, tab, onOpen = open,
                onBack = { browse = null },
                onGoTab = { if (it != MenuLogic.EPG) { browse = null; cats = null }; goTab(it) },
                // Started from the cinema view: put the title's detail page underneath so "Back" lands there (not in the cinema view again)
                all = all, onPlay = { i, t, url, pos -> if (i.kind != Kind.LIVE) pushTop(i); start(i, t, url, pos) }, onPerson = { pushTop(it) }
            ) }
            c != null -> Box(Modifier.fillMaxSize().screenEnter()) { CategoriesScreen(
                c, tab,
                onPick = { name, list, sort -> browse = BrowseState(BrowseReq(name, list, sort)) },
                onOpen = open,
                onBack = { cats = null },
                onGoTab = { if (it != MenuLogic.EPG) cats = null; goTab(it) }
            ) }
            adding -> Box(Modifier.fillMaxSize().screenEnter()) { AddPlaylistScreen { adding = false } }
            // Boomer mode: very simple home screen with big channel tiles instead of the normal UI
            Boomer.enabled -> BoomerHome(
                all, PlaylistStore.loading,
                onPlay = { ch, list ->
                    ZapContext.list = list; ZapContext.key = null
                    start(ch, ch.title, ch.url)
                },
                // Movie: starts immediately (Back leads to the detail page underneath); series: episode list
                onVod = { i ->
                    LastFocus.pending = i.id
                    if (i.kind == Kind.SERIES) pushTop(i) else { pushTop(i); start(i, i.title, i.url) }
                },
                onResume = { e, i ->
                    LastFocus.pending = i.id
                    pushTop(i)
                    val t = WatchStore.target(ctx, i, e)
                    start(i, t.first, t.second, e.pos)
                }
            )
            tab == 4 && all.isNotEmpty() -> Box(Modifier.fillMaxSize().screenEnter()) { SearchScreen(
                tab = tab, state = states[4],
                onOpen = open,
                onBrowse = { browse = BrowseState(it) },
                onTab = { goTab(it) }
            ) }
            else -> Box(Modifier.fillMaxSize().screenEnter(tab)) { HomeScreen(
                tab = tab, onTab = { goTab(it) }, states = states,
                onOpen = open,
                onPlayNow = { i -> LastFocus.pending = i.id; if (i.kind == Kind.SERIES) pushTop(i) else { if (i.kind != Kind.LIVE && !i.id.startsWith("rec:")) pushTop(i); start(i, i.title, i.url) } },
                onResume = { e, i ->
                    LastFocus.pending = i.id
                    if (i.kind != Kind.LIVE) pushTop(i)
                    // current address from the loaded playlist instead of the stored one (providers change addresses)
                    val t = WatchStore.target(ctx, i, e)
                    start(i, t.first, t.second, e.pos)
                },
                onBrowse = { browse = BrowseState(it) },
                onCategories = { cats = it },
                onAdd = { DemoMode.exit(); adding = true }
            ) }
        }
        if (RemoteUi.show) RemoteOverlay(Modifier.align(Alignment.Center))
        // Live preview of the View settings: bar at the bottom, the real app stays visible
        if (ViewLive.active) ViewLivePanel(ctx, goTab = { goTab(it) }, closeCats = { cats = null; browse = null }) {
            ViewLive.active = false
            SettingsNav.openPage = 2
            goTab(MenuLogic.SETTINGS)
        }
        // Genre detection in the background: unobtrusive at the top right, never in the player
        if (p == null && !adding) MetaBadge(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 22.dp))
        remindNow?.let { r ->
            ReminderCard(r, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), onClose = { remindNow = null }) {
                remindNow = null
                val ch = liveAll.firstOrNull { it.id == r.chId }
                if (ch != null) { top = null; browse = null; cats = null; start(ch, ch.title, ch.url) }
            }
        }
    }
}
