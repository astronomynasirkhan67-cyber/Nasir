package com.ipvio.stream

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

data class PlayReq(
    val title: String, val url: String, val itemId: String, val live: Boolean, val startMs: Long,
    val tmdbId: Int = 0, val season: Int = 0, val episode: Int = 0,
    /** Further sources of the same movie (other quality) – to fall back on errors/stutter and in the quality menu. */
    val alts: List<Variant> = emptyList(),
    /** Short notice at start (e.g. "4K does not suit this device – playing FHD"). */
    val note: String? = null,
    /** Show from the archive (catchup) of a live channel; [itemId] is then the channel. */
    val archive: ArchiveRef? = null
)

/** Digit of a remote control key (0-9) or -1. */
private fun digitOf(k: Key): Int = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9; else -> -1
}
/** Error when a Stalker portal gives no play address (details: [StalkerApi.lastDetail]). */
private const val STALKER_NO_ADDRESS = "Das Portal hat keine Abspiel-Adresse geliefert. Später erneut versuchen."

private val SLEEP_MIN = listOf(0, 15, 30, 45, 60, 90, 120)

private enum class Panel { NONE, TRACKS, SUBSEARCH, SUBSTYLE, QUALITY, SPEED, ASPECT, MENU, STEP, EPISODES, SLEEP, ARCHIVE, CLOCK }

private class Flash(val icon: PIcon?, val text: String?)

private class MenuEntry(val id: Int, val label: String, val trailing: String?, val chevron: Boolean)

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val ASPECTS get() = General.ASPECT_NAMES
private val STEP_OPTIONS = listOf(5, 10, 15, 30, 60)

private fun applyStyle(v: PlayerView) {
    val sv = v.subtitleView ?: return
    sv.setApplyEmbeddedStyles(false)
    sv.setApplyEmbeddedFontSizes(false)
    val fg = SubStyle.COLORS[AppSettings.subColor].second.toInt()
    val bg = SubStyle.BGS[AppSettings.subBg].second.toInt()
    val edge = when (AppSettings.subEdge) {
        0 -> CaptionStyleCompat.EDGE_TYPE_NONE
        1 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    sv.setStyle(CaptionStyleCompat(fg, bg, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SubStyle.SIZES[AppSettings.subSize].second)
}

private fun findActivity(c: Context): ComponentActivity? =
    generateSequence<Context>(c) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().firstOrNull()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(req: PlayReq, zapList: List<Item>, liveAll: List<Item>, seriesItem: Item? = null, onBack: () -> Unit) {
    val ctx = LocalContext.current
    CatFavs.init(ctx)
    // Own HTTP settings: follows redirects even from http to https (many IPTV servers do that)
    // and gives up after 10 s connect time or 15 s read time instead of waiting forever.
    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(ctx, SoftAudio.factory(ctx))
            .setLoadControl(Mem.loadControl(ctx))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // "tap://" = live buffer on the device (rewind, recording), everything else as before
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ctx)
                    .setDataSourceFactory(TapAwareDataSource.Factory(HostFailover.DataSourceWrapper.Factory(DefaultDataSource.Factory(ctx, http))))
                    // Short network hiccups are retried inside the player (several times) instead of ending in an error
                    .setLoadErrorHandlingPolicy(IptvLoadErrorPolicy())
            )
            .build()
            .also {
                // Frame rate: only switch if the TV can do it seamlessly (no flashing); can be turned off under Settings > Player
                it.videoChangeFrameRateStrategy = if (AppSettings.autoFps) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            }
    }
    val h = remember { Handler(Looper.getMainLooper()) }
    // State for keeping the screen awake: applies only while really playing or buffering
    // (not when paused, ended, on error or in the background). Otherwise the screensaver starts after about 5 minutes.
    var pWhenReady by remember { mutableStateOf(true) }
    var pState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var pError by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    KeepScreenOn("player", AwakeLogic.playerNeedsScreen(pWhenReady, pState, pError, foreground))
    var cur by remember { mutableStateOf(req) }
    var zapSrc by remember { mutableStateOf(zapList) }
    var msg by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    // Live (round 14): open straight with the control bar showing channel and show instead of the short info box
    var controls by remember { mutableStateOf(true) }
    var focusSeek by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    // Technical info and subtitle search (OpenSubtitles)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var techOn by remember { mutableStateOf(false) }
    var panelParent by remember { mutableStateOf(Panel.NONE) }
    var lastPanel by remember { mutableStateOf(Panel.NONE) }
    var menuSel by remember { mutableIntStateOf(0) }
    var showChannels by remember { mutableStateOf(false) }
    // Second stream (round 22): live player with a second picture; splitMode 0 = picture-in-picture, 1 = split
    var second by remember { mutableStateOf<Item?>(null) }
    var pickSecond by remember { mutableStateOf(false) }
    // Quick access to the score overlay (switch off, corner, arrangement) without leaving the stream
    var scoreDlg by remember { mutableStateOf(false) }
    DualPrefs.init(ctx)
    val splitMode = DualPrefs.split
    val audioOnSecond = DualPrefs.audioSecond
    val dualSplit = second != null && splitMode == 1
    LaunchedEffect(second, audioOnSecond) { player.volume = if (second != null && audioOnSecond) 0f else 1f }
    LaunchedEffect(showChannels) { if (!showChannels) pickSecond = false }
    var showCats by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // Started from Live TV via "TV guide": show the guide immediately
    LaunchedEffect(Unit) { if (AppNav.openGuide) { AppNav.openGuide = false; if (req.live) { controls = false; showGuide = true } } }
    var inHeader by remember { mutableStateOf(false) }
    val headerFocus = remember { FocusRequester() }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    var tracksVer by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var digitBuf by remember { mutableStateOf("") }
    var lastChId by remember { mutableStateOf<String?>(null) }
    var behindSec by remember { mutableLongStateOf(0L) }
    var sleepIdx by remember { mutableIntStateOf(0) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableIntStateOf(General.defaultAspect) }
    var interact by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoFor by remember { mutableStateOf("") }
    var badges by remember { mutableStateOf<List<String>>(emptyList()) }
    var retries by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableLongStateOf(-1L) }
    var scrubStart by remember { mutableLongStateOf(0L) }
    var scrubStamp by remember { mutableLongStateOf(0L) }
    var lastStepT by remember { mutableLongStateOf(0L) }
    var holdStartT by remember { mutableLongStateOf(0L) }
    var lastRepeatT by remember { mutableLongStateOf(0L) }
    var turbo by remember { mutableIntStateOf(0) }
    var ctrlLabel by remember { mutableStateOf("") }
    var segs by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var banner by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    // Boomer mode: channel permanently unplayable → big friendly message, then back to the tiles
    var boomerFail by remember { mutableStateOf(false) }
    var miniBar by remember { mutableStateOf(false) }
    var miniTick by remember { mutableIntStateOf(0) }
    var nextDismissed by remember { mutableStateOf(false) }
    // Guard: handle "end" only once per episode (the player's end event and the time trigger must not both fire)
    var endGuard by remember { mutableStateOf("") }
    // Live: how often the server ended the stream (reconnect, but not endlessly in circles)
    var liveEnds by remember { mutableIntStateOf(0) }
    // Address actually played (generated by the portal for Stalker portals)
    var resolvedUrl by remember { mutableStateOf("") }
    // Audio/subtitles: chosen by hand (then no automatic selection for this title any more)
    var manualTracks by remember { mutableStateOf(false) }
    // Sources that already failed for this title; rebuffering pauses (stutter); last seek
    val triedUrls = remember { HashSet<String>() }
    var stalls by remember { mutableStateOf(emptyList<Long>()) }
    var readyOnce by remember { mutableStateOf(false) }
    var lastSeekT by remember { mutableLongStateOf(0L) }
    // Notice at the top (source switched, device/quality) for a few seconds
    var notice by remember { mutableStateOf(req.note) }
    // Started from an own channel list/favorites: show this selection in channel switching, otherwise the channel's category
    var chanCat by remember {
        mutableStateOf(ZapContext.key?.takeIf { k -> (k == FAV_CAT || ChannelLists.byKey(k) != null) && zapList.any { it.id == req.itemId } }
            ?: liveAll.firstOrNull { it.id == req.itemId }?.group ?: "ALL")
    }
    var playCat by remember { mutableStateOf(chanCat) }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val subsFocus = remember { FocusRequester() }
    val chanFocus = remember { FocusRequester() }
    val catsFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val liveFirst = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val btn = remember { Panel.entries.associateWith { FocusRequester() } }

    val curItem = remember(cur.itemId, liveAll, zapSrc) { liveAll.firstOrNull { it.id == cur.itemId } ?: zapSrc.firstOrNull { it.id == cur.itemId } }
    val curIdx = remember(cur.itemId, zapSrc) { zapSrc.indexOfFirst { it.id == cur.itemId } }
    val step = AppSettings.seekStep * 1000L
    val firstFocus = if (cur.live) liveFirst else playBtn

    // Series: episode list for previous/next episode and the episode menu
    val epList = remember(seriesItem) {
        if (seriesItem != null && seriesItem.kind == Kind.SERIES) seriesItem.episodes.sortedWith(compareBy({ it.season }, { it.num }))
        else emptyList()
    }
    val epIdx = if (cur.live) -1 else epList.indexOfFirst { it.season == cur.season && it.num == cur.episode }
    val hasPrev = epIdx > 0
    val hasNext = epIdx >= 0 && epIdx < epList.lastIndex

    // These values change only rarely although pos ticks every 500 ms – hence "derived":
    // the player is only recomposed when the result really changes.
    val hasDur by remember { derivedStateOf { dur > 0 } }
    val activeSeg by remember { derivedStateOf { val p = pos; segs.firstOrNull { p >= it.start && p < it.end - 1000 } } }
    val showNextCard by remember(hasNext) {
        derivedStateOf { hasNext && !cur.live && dur > 0 && dur - pos < 30_000 && !nextDismissed }
    }

    // Lambdas for timelines etc.: read only when drawing, not when composing.
    val posNow: () -> Long = { if (scrubTarget >= 0) scrubTarget else pos }
    val durNow: () -> Long = { dur }
    val bufNow: () -> Long = { buffered }
    val segsNow: () -> List<Seg> = { segs }

    fun ping() { miniBar = true; miniTick++ }

    fun openChannels() { focusedCh = curItem; showCats = false; chanCat = playCat; showChannels = true }

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }

    // Seeking: single press = configured jump size. Holding speeds up in levels (fast seeking).
    // Several jumps are collected and executed only after a short pause.
    // fromBar = true: control bar disappears immediately, only the seek display and a thin timeline remain.
    fun stepSeek(d: Int, amount: Long = step, fromBar: Boolean = true) {
        val dd = player.duration
        val base = if (scrubTarget >= 0) scrubTarget else player.currentPosition
        if (scrubTarget < 0) scrubStart = base
        var t = (base + d * amount).coerceAtLeast(0L)
        if (dd > 0) t = t.coerceAtMost((dd - 1000).coerceAtLeast(0L))
        scrubTarget = t
        scrubStamp = System.currentTimeMillis()
        lastStepT = scrubStamp
        if (fromBar && controls && panel == Panel.NONE) { controls = false }
        ping()
    }

    fun repeatSeek(d: Int) {
        val now = System.currentTimeMillis()
        if (now - lastRepeatT > 700) { holdStartT = now; turbo = 0 }   // new hold phase
        lastRepeatT = now
        val lvl = turboLevel(now - holdStartT)
        turbo = lvl
        val gap = if (lvl == 0) 300L else 220L
        if (now - lastStepT >= gap) stepSeek(d, turboStepMs(lvl, step, player.duration))
    }

    fun switchTo(it: Item, src: List<Item>) {
        if ((cur.live || cur.archive != null) && cur.itemId != it.id) lastChId = cur.itemId
        History.add(ctx, it.id)
        cur = PlayReq(it.title, it.url, it.id, true, 0L)
        zapSrc = src
        showChannels = false
        showCats = false
        showGuide = false
        controls = false
        bannerTick++
    }

    fun zap(d: Int) {
        if (zapSrc.size < 2) return
        val i = if (curIdx < 0) 0 else curIdx
        switchTo(zapSrc[(i + d + zapSrc.size) % zapSrc.size], zapSrc)
    }

    fun lastChannel() {
        val it = zapSrc.firstOrNull { c -> c.id == lastChId } ?: return
        switchTo(it, zapSrc)
    }

    fun commitDigits() {
        val n = digitBuf.toIntOrNull() ?: 0
        digitBuf = ""
        if (n in 1..zapSrc.size) switchTo(zapSrc[n - 1], zapSrc)
        else if (n > 0) flash = Flash(null, "Kein Kanal mit dieser Nummer")
    }

    // Menus: parent = menu that "Left" returns to (NONE = close completely)
    fun openPanel(p: Panel, parent: Panel = Panel.NONE) {
        if (parent == Panel.NONE) { lastPanel = p; focusSeek = false }
        panelParent = parent
        panel = p
    }

    fun closePanel() {
        val pp = panelParent
        panelParent = Panel.NONE
        panel = if (pp != Panel.NONE) pp else Panel.NONE
    }

    // Live: the same channel in several qualities (4K / Full HD / HD / SD as separate playlist entries), best first.
    // Computed a moment after zapping so fast channel changes cost nothing.
    val liveAlts by produceState(emptyList<Variant>(), cur.itemId, cur.live, liveAll) {
        value = emptyList()
        if (!cur.live || liveAll.isEmpty()) return@produceState
        kotlinx.coroutines.delay(800)
        val me = liveAll.firstOrNull { it.id == cur.itemId } ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val base = LiveBundleLogic.baseName(me.title)
            if (base.isEmpty()) emptyList()
            else liveAll.filter { it.lang == me.lang && LiveBundleLogic.baseName(it.title) == base }
                .sortedByDescending { LiveBundleLogic.rank(it.title, it.quality) }
                .map { v ->
                    val q = when (LiveBundleLogic.rank(v.title, v.quality)) { 8 -> "8K"; 7 -> "4K"; 5 -> "Full HD"; 4 -> "HD"; 2 -> "SD"; else -> "Standard" }
                    Variant(v.url, q + "  ·  " + v.title, v.quality ?: q, v.lang)
                }
        }
    }

    /** Further sources of the running title: for a movie from the playlist, for series the sources of the episode. */
    fun currentAlts(): List<Variant> = when {
        cur.live -> liveAlts
        cur.alts.isNotEmpty() -> cur.alts
        cur.season > 0 || cur.episode > 0 -> epList.firstOrNull { it.season == cur.season && it.num == cur.episode }?.variants.orEmpty()
        else -> emptyList()
    }

    /** Switch to another (preferably smaller) source – after an error or on stutter. true = switched. */
    fun switchSource(reason: String, sameFirst: Boolean = false): Boolean {
        if (cur.live || !PlaybackPrefs.autoFallback) return false
        val alts = currentAlts()
        if (alts.size < 2) return false
        triedUrls.add(cur.url)
        val curI = alts.indexOfFirst { it.url == cur.url }.coerceAtLeast(0)
        val tried = alts.indices.filter { alts[it].url in triedUrls }.toSet()
        val ni = QualityLogic.fallback(alts.map { it.quality }, curI, tried, sameFirst) ?: return false
        // 4K had to give way because of stutter/decoder: counts for the learned device limit (a dead link does not)
        if (!sameFirst && QualityLogic.rank(alts[curI].quality) >= 4 && QualityLogic.rank(alts[ni].quality) < 4) PlaybackPrefs.note4kTrouble(ctx)
        val at = player.currentPosition.coerceAtLeast(0L)
        val t = alts[ni]
        stalls = emptyList()
        cur = cur.copy(url = t.url, startMs = if (at > 0) at else cur.startMs, note = null)
        notice = reason + " – wechsle auf " + (t.quality ?: "andere Quelle")
        return true
    }

    /** Audio and subtitles according to settings and the remembered choice for this title. */
    fun applyTrackPrefs() {
        val mem = TrackMemory.get(ctx, cur.itemId)
        // Important: with headphones (stereo output only) the player used to exclude a 5.1 audio track (often the German one)
        // automatically and took a stereo track in another language. The language counts, the decoder downmixes.
        // The option exists only on DefaultTrackSelector (ExoPlayer's default), not on the generic parameters builder.
        (player.trackSelectionParameters as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters)?.let {
            player.trackSelectionParameters = it.buildUpon().setConstrainAudioChannelCountToDeviceCapabilities(false).build()
        }
        var p = player.trackSelectionParameters.buildUpon()
        val audios = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, mem?.first)
        p = p.setPreferredAudioLanguages(*audios.toTypedArray())
        val subOn = if (mem != null) mem.second != null else AppSettings.subMode == TrackLogic.SUB_ALWAYS
        val subLang = mem?.second ?: AppSettings.subLangEffective()
        p = p.setPreferredTextLanguages(subLang)
            .setPreferredTextRoleFlags(if (AppSettings.ccPref) C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subOn)
        player.trackSelectionParameters = p.build()
        manualTracks = mem != null
    }

    /**
     * Safety net for the audio language: if the selection changes on its own (headphones on/off, output device changed),
     * the preferred language is chosen again if the player can play it. Not while the audio menu is open.
     */
    var audioFixTries by remember { mutableIntStateOf(0) }
    fun ensureAudio() {
        if (cur.live || panel != Panel.NONE) return
        val prefs = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, TrackMemory.get(ctx, cur.itemId)?.first)
        if (prefs.isEmpty()) return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.size < 2) return
        for (lang in prefs) {
            val g = groups.firstOrNull { gr -> (0 until gr.length).any { TrackLogic.guess(gr.getTrackFormat(it).language, gr.getTrackFormat(it).label) == lang } } ?: continue
            val idx = (0 until g.length).first { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) == lang }
            if (g.isSelected && g.isTrackSelected(idx)) return
            if (g.isTrackSupported(idx)) {
                if (audioFixTries >= 3) return
                audioFixTries++
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, idx)).build()
                return
            }
            // Wanted language exists, but cannot be played by the device with this output
            if (audioFixTries < 3) { audioFixTries++; notice = "Tonspur „" + lang.uppercase() + "“ ist mit diesem Ausgang nicht abspielbar – andere Spur im Menü wählen" }
            return
        }
    }

    /**
     * Error comes from the audio decoder: choose another playable audio track (preferably the second language, otherwise any) and
     * restart. Returns true if playback continues this way.
     */
    var audioFallbacks by remember { mutableIntStateOf(0) }
    fun audioFallback(): Boolean {
        val e0 = player.playerError as? androidx.media3.exoplayer.ExoPlaybackException ?: return false
        val rf = e0.rendererFormat
        if (rf?.sampleMimeType?.startsWith("audio/") != true) return false
        if (audioFallbacks >= 3) return false
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val prefs = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, TrackMemory.get(ctx, cur.itemId)?.first)
        var pick: Pair<androidx.media3.common.Tracks.Group, Int>? = null
        for (lang in prefs.drop(1) + listOf("")) {
            for (g in groups) for (i in 0 until g.length) {
                if (!g.isTrackSupported(i) || g.getTrackFormat(i).id == rf.id) continue
                if (lang.isEmpty() || TrackLogic.guess(g.getTrackFormat(i).language, g.getTrackFormat(i).label) == lang) { pick = g to i; break }
            }
            if (pick != null) break
        }
        val p = pick ?: return false
        audioFallbacks++
        val at = player.currentPosition
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(p.first.mediaTrackGroup, p.second)).build()
        player.prepare()
        player.seekTo(at)
        player.playWhenReady = true
        pError = false
        notice = "Diese Tonspur kann hier nicht abgespielt werden – andere Spur gewählt"
        return true
    }

    /** "Only for a foreign audio track": subtitles on when the audio is in a language that is not set. */
    fun autoSubs() {
        if (manualTracks || cur.live || AppSettings.subMode != TrackLogic.SUB_FOREIGN) return
        val audio = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) } }
        val want = TrackLogic.subsWanted(AppSettings.subMode, audio, AppSettings.subLangEffective(), AppSettings.audioLang)
        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (want == off) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !want).build()
    }

    /** Key under which an online subtitle is remembered: the title, or the episode of a series. */
    fun subKey() = if (cur.season > 0 && cur.episode > 0) cur.itemId + "#S" + cur.season + "E" + cur.episode else cur.itemId

    /** Chosen by hand in the menu: remember for this title and set as default for the next episode. */
    fun rememberTracks() {
        manualTracks = true
        fun langOf(type: Int): String? = player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) } }
        val a = langOf(C.TRACK_TYPE_AUDIO)
        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val t = if (off) null else langOf(C.TRACK_TYPE_TEXT)
        if (a == null && t == null && !off) return
        TrackMemory.set(ctx, cur.itemId, a, t)
        var p = player.trackSelectionParameters.buildUpon()
        if (a != null) p = p.setPreferredAudioLanguages(*TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, a).toTypedArray())
        if (t != null) p = p.setPreferredTextLanguages(t)
        player.trackSelectionParameters = p.build()
    }

    fun saveProgress() {
        if (cur.live || cur.archive != null) return
        // Duration unknown (some provider streams report none): remember the position anyway, otherwise "Continue watching" would stay on the old episode
        val d = player.duration.coerceAtLeast(0L)
        val p = player.currentPosition
        if (p <= 0) return
        val now = System.currentTimeMillis()
        val si = seriesItem
        var next: Entry? = null
        if (si != null && (cur.season > 0 || cur.episode > 0)) {
            // Progress of this episode (bar/check mark in the episode list)
            EpProgress.put(ctx, cur.itemId, cur.season, cur.episode, p, d)
            // Compute freshly here: this function also runs on the 5-second tick with the state from player start
            val i = epList.indexOfFirst { it.season == cur.season && it.num == cur.episode }
            if (i >= 0 && i < epList.lastIndex) {
                val nx = epList[i + 1]
                val pref = VariantPref.get(ctx, si.id)
                next = Entry(cur.itemId, si.title + " S" + nx.season + "E" + nx.num, pickVariant(nx, pref.first, pref.second, pref.third, PlaybackPrefs.cap(ctx)).url,
                    0L, 0L, now, nx.season, nx.num, true)
            }
        }
        // Movie: remember progress so the detail page shows "Play again" after watching
        if (si == null && cur.season == 0 && cur.episode == 0) EpProgress.put(ctx, cur.itemId, 0, 0, p, d)
        WatchStore.save(ctx, Entry(cur.itemId, cur.title, cur.url, p, d, now, cur.season, cur.episode), next)
    }

    fun playEpisode(ep: Episode) {
        val si = seriesItem ?: return
        saveProgress()
        val saved = VariantPref.get(ctx, si.id)
        val v = pickVariant(ep, saved.first, saved.second, saved.third, PlaybackPrefs.cap(ctx))
        History.add(ctx, si.id)
        scrubTarget = -1L
        cur = PlayReq(si.title + " S" + ep.season + "E" + ep.num, v.url, si.id, false, 0L, cur.tmdbId, ep.season, ep.num)
        if (!openStream(player, v.url, 0L, SubSearch.item(ctx, v.url, subKey()))) { msg = PlayerLogic.MSG_FORMAT; buffering = false; return }
        ping()
    }

    // ---------------------------------------------------------------- Archive (catchup)
    // Schedule of the channel whose archive show is currently playing (for "Previous/Next show" and continued playback)
    var archProg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    LaunchedEffect(cur.archive?.chId) {
        archProg = emptyList()
        val ch = curItem
        if (cur.archive != null && ch != null) archProg = EpgFull.get(ch)
    }
    val archNow = System.currentTimeMillis() / 1000
    val archPrev = cur.archive?.let { a -> CatchupLogic.neighbour(archProg, a.prog.start, -1)?.takeIf { Catchup.canPlay(curItem, it, archNow) } }
    val archNext = cur.archive?.let { a -> CatchupLogic.neighbour(archProg, a.prog.start, 1)?.takeIf { Catchup.canPlay(curItem, it, archNow) } }

    /** Play a show from the channel's archive [ch] (including the running one from the beginning). */
    fun playArchive(ch: Item, e: EpgEntry) {
        val url = Catchup.urlFor(ch, e)
        if (url == null) { flash = Flash(null, "Nicht im Archiv verfügbar"); return }
        if (cur.live && cur.itemId != ch.id) lastChId = cur.itemId
        scrubTarget = -1L
        cur = PlayReq(e.title, url, ch.id, false, 0L, archive = ArchiveRef(ch.id, ch.title, e))
        showGuide = false
        showChannels = false
        showCats = false
        panel = Panel.NONE; panelParent = Panel.NONE
        controls = false
        flash = Flash(null, dayLabel(e.start) + "  " + hhmm(e.start))
    }

    /** From the archive back to the live picture of the same channel. */
    fun backToLive() {
        val ch = curItem ?: return
        switchTo(ch, zapSrc)
        flash = Flash(PIcon.Play, "LIVE")
    }

    // Called by the player when a movie/episode has ended (always with the latest state)
    val onEnded by rememberUpdatedState({
        val ch = curItem
        if (endGuard != cur.url) {
            endGuard = cur.url
            if (cur.archive != null && ch != null) {
                // Archive: continue seamlessly with the next show, otherwise back to the live picture
                val nx = archNext
                if (nx != null) playArchive(ch, nx) else backToLive()
            } else if (!cur.live && hasNext && AppSettings.autoNext && !nextDismissed) playEpisode(epList[epIdx + 1])
        }
    })

    var lastBackAt by remember { mutableLongStateOf(0L) }
    fun handleBack() {
        // Duplicate back events (key + system) must not first close the menu and right after that the stream
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 350) return
        lastBackAt = now
        when {
            scrubTarget >= 0 -> scrubTarget = -1L
            panel != Panel.NONE -> closePanel()
            // Guide: own back step (show → channel → category → close)
            showGuide -> GuideBack.handler?.invoke() ?: run { showGuide = false }
            showCats -> showCats = false
            showChannels -> showChannels = false
            controls -> controls = false
            showNextCard -> nextDismissed = true
            cur.archive != null && curItem != null -> backToLive()
            else -> onBack()
        }
    }

    BackHandler { handleBack() }

    // Phone search shows what is playing now (also after switching / next episode)
    LaunchedEffect(cur) { RemoteHub.nowTitle = cur.title; RemoteHub.nowLive = cur.live }
    DisposableEffect(Unit) {
        // If the player hangs in buffering for a long time (dead connection without an error): reconnect up to twice
        var bufSince = 0L
        var wdTries = 0
        val tick = object : Runnable {
            override fun run() {
                saveProgress()
                val nowT = System.currentTimeMillis()
                if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !pError) {
                    if (bufSince == 0L) bufSince = nowT
                    else if (nowT - bufSince > (if (cur.live) 15_000L else 30_000L) && (cur.live || wdTries < 2)) {
                        // Live: reconnect without limit (like a receiver), movie/series: twice
                        wdTries++; bufSince = nowT
                        msg = if (cur.live) "Verbindung hängt – neuer Versuch ($wdTries)" else "Verbindung hängt – neuer Versuch ($wdTries/2)"
                        if (cur.live && cur.archive == null) LiveTimeshift.reconnect(player) else player.prepare()
                    }
                } else {
                    bufSince = 0L
                    if (player.playbackState == Player.STATE_READY) wdTries = 0
                }
                h.postDelayed(this, 5000)
            }
        }
        fun showInfo(w: Int, hh: Int) {
            if (w <= 0 || hh <= 0) return
            if (cur.live) {
                // Live: remember picture size and frame rate per channel (shown in the channel list)
                val lf = player.videoFormat
                LiveQuality.put(ctx, cur.itemId, qualityName(maxOf(hh, w * 9 / 16)), if (lf != null && lf.frameRate > 0) Math.round(lf.frameRate) else 0)
                return
            }
            val q = qualityName(maxOf(hh, w * 9 / 16))
            val prev = QualityStore.get(cur.itemId)
            if (prev == null || rankQ(q) > rankQ(prev)) QualityStore.put(ctx, cur.itemId, q)
            val key = cur.url + "|" + q
            if (infoFor == key) return
            infoFor = key
            val vf = player.videoFormat
            val fps = if (vf != null && vf.frameRate > 0) " · ${Math.round(vf.frameRate)} fps" else ""
            val vc = codecName(vf?.sampleMimeType)
            val codec = if (vc.isBlank()) "" else " · $vc"
            val af = player.audioFormat
            val au = if (af != null) " · " + (codecName(af.sampleMimeType).ifBlank { "Audio" }) + (if (af.channelCount > 0) " " + channelLabel(af.channelCount) else "") else ""
            info = "${w}×${hh}  ·  $q$codec$fps$au"
            val b = ArrayList<String>()
            b.add(q)
            if (vc.isNotBlank()) b.add(vc)
            if (af != null && af.channelCount > 2) b.add(channelLabel(af.channelCount))
            badges = b
        }
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                pError = true
                val status = httpStatusOf(e)
                Diag.report("Wiedergabe: " + e.errorCodeName + (if (status > 0) " (HTTP $status)" else ""))
                val decoder = e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                // Chosen audio track cannot be decoded on this device/output: do not give up, switch the track
                // and keep playing (picture and position stay). Never discard the whole source just because the audio fails.
                if (decoder && audioFallback()) return
                val liveNow = cur.live && cur.archive == null
                if (retries < (if (liveNow) 50 else 3) && !isPermanentHttp(status) && !decoder) {
                    retries++
                    msg = if (liveNow) "Verbindung unterbrochen – neuer Versuch ($retries)" else "Verbindung unterbrochen – neuer Versuch ($retries/3)"
                    // Live: keep trying with a growing pause (at most 15 s), and back to the live picture
                    h.postDelayed({ if (liveNow) LiveTimeshift.reconnect(player) else player.prepare() }, minOf(1500L * retries, 15_000L))
                } else {
                    // Finally failed: remember the link (only if the link is the cause) and try another source
                    if (cur.archive == null && DeadLinkLogic.counts(status, decoder, DeviceCaps.online(ctx))) DeadLinks.fail(ctx, cur.url, DeadLinkLogic.permanent(status))
                    if (switchSource(if (decoder) "Format nicht abspielbar" else "Quelle gestört", sameFirst = !decoder)) { pError = false; msg = null }
                    else if (cur.archive != null) msg = "Diese Sendung ist im Archiv des Anbieters nicht abrufbar.  Zurück = Live-Sender."
                    else { msg = Lang.tr(playbackErrorText(e.errorCode, status, cur.live, TlsFallback.isHostMismatch(e))) + "  " + Lang.tr("Zurück mit der Zurück-Taste."); if (Boomer.enabled) boomerFail = true }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                pState = state
                if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) pError = false
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    msg = null; retries = 0; boomerFail = false
                    if (player.duration > 0 && player.currentPosition < player.duration - 3000) endGuard = ""
                    if (!readyOnce) { readyOnce = true; DeadLinks.ok(ctx, cur.url) }
                }
                // Stutter: rebuffering in the middle of playback, not right after seeking
                if (state == Player.STATE_BUFFERING && readyOnce && player.playWhenReady &&
                    System.currentTimeMillis() - lastSeekT > 4000
                ) {
                    val now = System.currentTimeMillis()
                    stalls = StallLogic.add(stalls, now)
                    if (StallLogic.tooMany(stalls, now)) {
                        // Automatic: enlarge the buffer for the next playback (slow internet)
                        val before = AppSettings.bufferLearned
                        AppSettings.learnBuffer(ctx)
                        if (AppSettings.bufferLearned != before) notice = "Ruckelt – Puffer wird beim nächsten Start größer"
                        if (!cur.live) switchSource("Ruckelt") else stalls = emptyList()
                    }
                }
                if (state == Player.STATE_ENDED) {
                    // Live stream ended by the server (common with IPTV): reconnect immediately instead of stalling
                    if (cur.live && cur.archive == null && liveEnds < 30) {
                        liveEnds++
                        msg = "Stream beendet – verbinde neu …"
                        h.postDelayed({ LiveTimeshift.reconnect(player) }, 800L)
                    } else onEnded()
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) lastSeekT = System.currentTimeMillis()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { pWhenReady = playWhenReady }
            override fun onTracksChanged(tracks: Tracks) {
                tracksVer++
                ensureAudio()
                autoSubs()
                val vf = player.videoFormat
                if (vf != null) showInfo(vf.width, vf.height)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { showInfo(videoSize.width, videoSize.height) }
        }
        player.addListener(l)
        applyTrackPrefs()
        h.postDelayed(tick, 5000)
        AppNav.playing = true
        RemoteHub.player = player
        onDispose {
            AppNav.playing = false
            if (RemoteHub.player === player) RemoteHub.player = null
            // Detail page remembers the last played episode (also after "Next episode")
            if (!cur.live && cur.archive == null) DetailReturn.set(cur.itemId, cur.season, cur.episode)
            saveProgress()
            h.removeCallbacksAndMessages(null)
            player.removeListener(l)
            player.release()
            // Release the buffer (a running recording keeps holding it)
            LiveTimeshift.stop()
        }
    }

    // Pauses when the app goes to the background (Home key, other dialog) and continues afterwards.
    val lifecycle = remember(ctx) { findActivity(ctx)?.lifecycle }
    DisposableEffect(lifecycle) {
        var resumeAfter = false
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfter = player.playWhenReady
                    player.pause()
                    saveProgress()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Return from standby/other app: if the player hangs after an error, reconnect
                    if (player.playerError != null && resumeAfter) { retries = 0; player.prepare() }
                    if (resumeAfter) {
                        resumeAfter = false
                        if (cur.live) player.seekToDefaultPosition()
                        player.play()
                    }
                    ScreenAwake.apply()
                }
                else -> {}
            }
        }
        lifecycle?.addObserver(obs)
        onDispose { lifecycle?.removeObserver(obs) }
    }

    // Network is back (Wi-Fi drop, router restart): if the player hangs in an error, reconnect immediately
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                h.post {
                    if (player.playerError != null) {
                        retries = 0
                        msg = "Verbindung wieder da – lade neu …"
                        if (cur.live) player.seekToDefaultPosition()
                        player.prepare()
                    }
                }
            }
        }
        val ok = cm != null && runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
        onDispose { if (ok) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    // Keeping awake not possible: notice instead of silent failure
    var awakeWarn by remember { mutableStateOf(false) }
    LaunchedEffect(ScreenAwake.unavailable) {
        if (ScreenAwake.unavailable) { awakeWarn = true; delay(8000); awakeWarn = false }
    }

    LaunchedEffect(cur.url) {
        readyOnce = false; stalls = emptyList()
        retries = 0; msg = null; boomerFail = false; buffering = true; segs = emptyList(); pos = 0; dur = 0; buffered = 0
        infoFor = ""; info = null; badges = emptyList(); nextDismissed = false
        if (cur.live) bannerTick++
        scrubTarget = -1L
        liveEnds = 0
        // Recording of another channel keeps running: this needs a second connection to the provider
        if (cur.live && Recorder.active.any { it.running && it.channelId != cur.itemId }) notice = "Aufnahme läuft weiter – dafür braucht dein Anbieter 2 gleichzeitige Verbindungen"
        val loaded = player.currentMediaItem?.localConfiguration?.uri?.toString()
        // Stalker portal: fetch the real address from the portal only now (it is valid only briefly)
        val playUrl = if (StalkerLogic.isItem(cur.url)) StalkerApi.resolve(ctx, cur.url) else cur.url
        if (playUrl == null) {
            msg = STALKER_NO_ADDRESS; buffering = false
            return@LaunchedEffect
        }
        resolvedUrl = playUrl
        if (cur.live && cur.archive == null && (LiveTimeshift.wanted(playUrl) || Recorder.isRecording(cur.itemId))) {
            // Live via the buffer on the device (rewind; with a running recording the same connection)
            if (!LiveTimeshift.start(ctx, player, playUrl)) {
                LiveTimeshift.stop()
                notice = "Zu wenig freier Speicher für den Live-Puffer"
                if (!openStream(player, playUrl, 0L)) { msg = PlayerLogic.MSG_FORMAT; buffering = false }
            }
        } else if (loaded != playUrl) {
            LiveTimeshift.stop()
            // Unknown format (e.g. without a matching module): notice instead of a crash
            val item = if (cur.live) null else SubSearch.item(ctx, playUrl, subKey())
            if (!openStream(player, playUrl, if (cur.startMs > 0) cur.startMs else 0L, item)) { msg = PlayerLogic.MSG_FORMAT; buffering = false }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!cur.live) {
                pos = player.currentPosition
                dur = player.duration.coerceAtLeast(0)
                buffered = player.bufferedPosition
                // Some servers never report the end: move to the next episode myself shortly before the end
                if (!cur.live && cur.archive == null && hasNext && AppSettings.autoNext && !nextDismissed && dur > 0 && dur - pos in 0..700L && player.isPlaying) onEnded()
            }
            delay(500)
        }
    }
    LaunchedEffect(scrubStamp) {
        if (scrubTarget >= 0) {
            delay(450)
            val t = scrubTarget
            if (t >= 0) { player.seekTo(t); pos = t }
            scrubTarget = -1L
            turbo = 0
        }
    }
    LaunchedEffect(cur.url, hasDur) {
        if (!cur.live && cur.tmdbId > 0 && hasDur && AppSettings.skipIntro) segs = IntroDb.get(cur.tmdbId, cur.season, cur.episode, dur)
    }
    // Optional: skip the intro automatically
    LaunchedEffect(activeSeg) {
        val s = activeSeg
        if (s != null && AppSettings.autoSkipIntro && s.label.startsWith("Intro")) player.seekTo(s.end)
        // Credits start: go straight to the next episode (only if the credits time is known)
        if (s != null && AppSettings.autoSkipOutro && s.label.startsWith("Abspann") && hasNext && !nextDismissed && player.isPlaying) playEpisode(epList[epIdx + 1])
    }
    // Skip button: visible for 10 s, then hidden. If the control bar is opened in that time (or afterwards while the section runs)
    // it appears again.
    var segVisible by remember { mutableStateOf(false) }
    LaunchedEffect(activeSeg) {
        segVisible = activeSeg != null
        if (activeSeg != null) { delay(10_000); segVisible = false }
    }
    // Skip: credits = straight to the next episode (hardly anyone wants to watch seconds of credits afterwards).
    // Then close the menu and restore focus, otherwise you got stuck in the menu.
    fun skipSeg(sg: Seg) {
        segVisible = false
        controls = false
        if (sg.label.startsWith("Abspann") && hasNext) playEpisode(epList[epIdx + 1]) else player.seekTo(sg.end)
        runCatching { rootFocus.requestFocus() }
    }
    // Episode title (TMDB, otherwise name from the playlist) for the overlay and control bar
    var epName by remember(cur.itemId, cur.season, cur.episode) { mutableStateOf<String?>(null) }
    var epBanner by remember { mutableStateOf(false) }
    LaunchedEffect(cur.itemId, cur.season, cur.episode) {
        if (cur.live || (cur.season <= 0 && cur.episode <= 0)) return@LaunchedEffect
        epBanner = false
        var name: String? = null
        if (cur.tmdbId > 0) name = runCatching { MetaRepo.season(cur.tmdbId, cur.season, null).firstOrNull { it.num == cur.episode }?.name }.getOrNull()
        if (name.isNullOrBlank()) {
            val t = epList.firstOrNull { it.season == cur.season && it.num == cur.episode }?.title?.trim()
            name = t?.takeIf { it.isNotBlank() && !Regex("[Ss]\\d{1,3}\\s*[Ee]\\d{1,4}").containsMatchIn(it) && !it.equals(seriesItem?.title, true) }
        }
        epName = name
        epBanner = true
        delay(5000)
        epBanner = false
    }
    LaunchedEffect(cur.url) {
        epg = emptyList()
        val it = curItem
        if (cur.live && it != null) {
            while (true) { epg = Epg.now(it); delay(60_000) }
        }
    }
    // Hide the control bar after a configurable time without a key press. Every key press restarts the timer.
    LaunchedEffect(controls, panel, showChannels, showGuide, playing) {
        if (controls && panel == Panel.NONE && !showChannels && !showGuide && (playing || cur.live)) {
            snapshotFlow { interact }.collectLatest {
                delay(AppSettings.hideSec * 1000L)
                controls = false
            }
        }
    }
    LaunchedEffect(controls) { if (!controls) { lastPanel = Panel.NONE; focusSeek = false; ctrlLabel = "" } }
    LaunchedEffect(controls, panel, showChannels, showCats, showGuide) {
        delay(40)
        // Focus is tried up to 6 times: lists sometimes need a moment until the target is drawn.
        // Without focus the keys would go nowhere and the list seems "stuck".
        repeat(12) {
            val ok = runCatching {
                when {
                    panel != Panel.NONE -> panelFocus.requestFocus()
                    showGuide -> guideFocus.requestFocus()
                    showChannels && showCats -> catsFocus.requestFocus()
                    showChannels -> chanFocus.requestFocus()
                    controls -> (if (lastPanel != Panel.NONE) btn.getValue(lastPanel) else if (focusSeek && !cur.live) seekFocus else firstFocus).requestFocus()
                    else -> rootFocus.requestFocus()
                }
            }.getOrDefault(false)
            if (ok) return@LaunchedEffect
            delay(70)
        }
    }
    // Channel list and TV guide close themselves when no key is pressed for a while.
    LaunchedEffect(showChannels) {
        if (showChannels) snapshotFlow { interact }.collectLatest { delay(15_000); showChannels = false }
    }
    LaunchedEffect(showGuide) {
        if (showGuide) snapshotFlow { interact }.collectLatest { delay(180_000); showGuide = false }
    }
    LaunchedEffect(showChannels) { if (!showChannels) inHeader = false }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (sleepAt > 0L) {
                player.pause()
                sleepAt = 0L; sleepIdx = 0
                flash = Flash(PIcon.Pause, "Sleep-Timer")
            }
        }
    }
    // Distance to the live picture: for HLS from the player, otherwise as the sum of the pause time
    LaunchedEffect(cur.url, cur.live) {
        behindSec = 0L
        while (cur.live) {
            delay(1000)
            if (LiveTimeshift.active) { behindSec = LiveTimeshift.behindMs(player) / 1000; continue }
            val off = player.currentLiveOffset
            if (off != C.TIME_UNSET) behindSec = if (off > 3000) off / 1000 else 0L
            else if (!player.playWhenReady && player.playbackState == Player.STATE_READY) behindSec++
        }
    }
    LaunchedEffect(cur.itemId, cur.url) { audioFixTries = 0 }
    LaunchedEffect(cur.itemId) {
        triedUrls.clear()
        applyTrackPrefs()
    }
    LaunchedEffect(notice) { if (notice != null) { delay(5000); notice = null } }
    LaunchedEffect(digitBuf) {
        if (digitBuf.isNotEmpty()) { delay(1800); commitDigits() }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(900); flash = null } }
    LaunchedEffect(info) { if (info != null) { delay(6000); info = null } }
    LaunchedEffect(bannerTick) { if (bannerTick > 0 && !controls) { banner = true; delay(4000); banner = false } }
    LaunchedEffect(miniTick) { if (miniTick > 0) { delay(1800); miniBar = false } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                interact = System.currentTimeMillis()
                // Handle back ourselves: otherwise Compose first only removes the focus and the menu stays open
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) handleBack()
                    true
                } else false
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val k = e.key
                if (e.nativeKeyEvent.repeatCount > 0) {
                    if ((k == Key.DirectionLeft || k == Key.DirectionRight) && !cur.live && !controls && panel == Panel.NONE && !showChannels && !showGuide) {
                        repeatSeek(if (k == Key.DirectionRight) 1 else -1)
                    }
                    return@onKeyEvent (k == Key.DirectionLeft || k == Key.DirectionRight)
                }
                // Boomer mode: only left/right (switch channel); everything else does nothing, no menu opens
                if (Boomer.enabled && cur.live && cur.archive == null) {
                    when (k) {
                        Key.DirectionLeft, Key.MediaPrevious -> zap(-1)
                        Key.DirectionRight, Key.MediaNext -> zap(1)
                        else -> {}
                    }
                    return@onKeyEvent true
                }
                when {
                    k == Key.MediaPlayPause || k == Key.Spacebar -> { togglePlay(); true }
                    k == Key.MediaPlay -> { player.play(); true }
                    k == Key.MediaPause -> { player.pause(); true }
                    k == Key.MediaFastForward && !cur.live -> { stepSeek(1); true }
                    k == Key.MediaRewind && !cur.live -> { stepSeek(-1); true }
                    // Live with buffer: seek keys jump within the buffer (not beyond the live picture)
                    k == Key.MediaRewind && cur.live && LiveTimeshift.active -> { LiveTimeshift.seekBy(player, -step); flash = Flash(PIcon.Replay, "−" + step / 1000 + " s"); true }
                    k == Key.MediaFastForward && cur.live && LiveTimeshift.active -> { LiveTimeshift.seekBy(player, step); flash = Flash(PIcon.Forward, "+" + step / 1000 + " s"); true }
                    k == Key.MediaNext && cur.live -> { zap(1); true }
                    k == Key.MediaPrevious && cur.live -> { zap(-1); true }
                    k == Key.MediaNext && cur.archive != null -> { val ch = curItem; val nx = archNext; if (ch != null && nx != null) playArchive(ch, nx) else backToLive(); true }
                    k == Key.MediaPrevious && cur.archive != null -> { val ch = curItem; val pv = archPrev; if (ch != null && pv != null) playArchive(ch, pv); true }
                    k == Key.MediaNext && !cur.live && hasNext -> { playEpisode(epList[epIdx + 1]); true }
                    k == Key.MediaPrevious && !cur.live && hasPrev -> { playEpisode(epList[epIdx - 1]); true }
                    cur.live && panel == Panel.NONE && !showChannels && !showGuide && digitOf(k) >= 0 -> {
                        val d = digitOf(k)
                        if (digitBuf.isEmpty() && d == 0) lastChannel()
                        else if (digitBuf.length < 4) { digitBuf += d }
                        true
                    }
                    !cur.live && !controls && panel == Panel.NONE && !showChannels && !showGuide && digitOf(k) >= 0 && player.duration > 0 -> {
                        // Digit key jumps to 0–90 % of the movie
                        val n = digitOf(k)
                        player.seekTo(player.duration * n / 10)
                        flash = Flash(null, (n * 10).toString() + " %")
                        ping()
                        true
                    }
                    cur.live && digitBuf.isNotEmpty() && (k == Key.DirectionCenter || k == Key.Enter) -> { commitDigits(); true }
                    controls || panel != Panel.NONE || showChannels || showGuide -> false
                    else -> when (k) {
                        Key.DirectionLeft -> { if (cur.live) openChannels() else stepSeek(-1); true }
                        Key.DirectionRight -> { if (cur.live) { controls = false; showGuide = true } else stepSeek(1); true }
                        Key.DirectionCenter, Key.Enter -> {
                            val seg = activeSeg
                            if (showNextCard) { playEpisode(epList[epIdx + 1]); true }
                            else if (seg != null && segVisible) { skipSeg(seg); true }
                            else if (cur.live) { controls = true; true }
                            // Like YouTube, Netflix & co.: during/shortly after seeking, OK confirms the target position and keeps playing
                            // instead of pausing (that happened because one wants to "confirm" the seek).
                            else if (scrubTarget >= 0) {
                                val t = scrubTarget
                                player.seekTo(t); pos = t
                                scrubTarget = -1L; turbo = 0
                                player.play()
                                true
                            }
                            else if (System.currentTimeMillis() - lastStepT < 1500L) {
                                if (!player.isPlaying) player.play()
                                true
                            }
                            else {
                                val was = player.isPlaying
                                togglePlay()
                                flash = Flash(if (was) PIcon.Pause else PIcon.Play, null)
                                if (was) { focusSeek = false; controls = true }
                                true
                            }
                        }
                        Key.DirectionUp -> { if (cur.live) zap(-1) else { focusSeek = true; controls = true }; true }
                        Key.DirectionDown -> { if (cur.live) zap(1) else { focusSeek = false; controls = true }; true }
                        Key.Menu -> { focusSeek = false; controls = true; true }
                        else -> false
                    }
                }
            }
            .focusable()
    ) {
        // ---------------------------------------------------------------- Video
        // Aspect ratio (in the shrunken guide picture always "Fit"). Zoom: picture area larger than the screen, centered;
        // 4:3: area in 4:3 aspect ratio, centered.
        val am = if (showGuide) 0 else aspect
        val videoMod = if (showGuide) Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp).size(width = 400.dp, height = 225.dp).clip(RoundedCornerShape(DS.RadiusM))
        else when (am) {
            3, 4 -> {
                val z = if (am == 3) 1.15f else 1.33f
                Modifier.fillMaxSize().layout { m, c ->
                    val w = (c.maxWidth * z).toInt(); val hh = (c.maxHeight * z).toInt()
                    val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, hh))
                    layout(c.maxWidth, c.maxHeight) { pl.place((c.maxWidth - w) / 2, (c.maxHeight - hh) / 2) }
                }
            }
            5 -> Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(4f / 3f)
            else -> Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    this.player = player
                }
            },
            update = { v ->
                // apply only if something really changed (saves work on every redraw)
                val styleKey = "$am|${AppSettings.subSize}|${AppSettings.subColor}|${AppSettings.subBg}|${AppSettings.subEdge}"
                if (v.tag != styleKey) {
                    v.tag = styleKey
                    v.resizeMode = when (am) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        2, 5 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    applyStyle(v)
                }
            },
            modifier = if (dualSplit) Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.5f) else videoMod
        )
        if (scoreDlg) ScoreOverlayDialog { scoreDlg = false }
        second?.let { s2 ->
            SecondStream(
                s2, dualSplit, audioOnSecond,
                if (dualSplit) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.5f)
                else pipModifier()
            )
        }
        // Phone/tablet (round 20): tap shows or hides the control bar, double-tap left/right seeks,
        // double-tap in the middle pauses. No effect on the TV (no touch).
        Box(
            Modifier.fillMaxSize().pointerInput(cur.live) {
                detectTapGestures(
                    onTap = {
                        interact = System.currentTimeMillis()
                        if (!Boomer.enabled && panel == Panel.NONE && !showChannels && !showGuide) controls = !controls
                    },
                    onDoubleTap = { o ->
                        interact = System.currentTimeMillis()
                        val third = size.width / 3f
                        val dir = if (o.x < third) -1 else if (o.x > third * 2) 1 else 0
                        when {
                            Boomer.enabled -> { if (dir != 0 && cur.live) zap(dir) }
                            dir == 0 -> togglePlay()
                            !cur.live -> stepSeek(dir)
                            LiveTimeshift.active -> { LiveTimeshift.seekBy(player, dir * step); flash = Flash(if (dir < 0) PIcon.Replay else PIcon.Forward, (if (dir < 0) "−" else "+") + step / 1000 + " s") }
                            else -> zap(dir)
                        }
                    }
                )
            }
        )

        // ---------------------------------------------------------------- Messages
        if (buffering && msg == null && !showGuide) {
            Spinner(Accent2, Modifier.align(Alignment.Center).size(48.dp))
        }
        // Hidden while the TV guide is open: the error box would sit on top of the guide (it comes back when the guide closes)
        msg?.takeIf { !Boomer.enabled && !showGuide }?.let { m ->
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(DS.RadiusM)).background(PT.PanelBg)
                    .padding(horizontal = 28.dp, vertical = 18.dp)
            ) {
                Column {
                    Text(m, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    // Stalker portal: what the portal answered (masked), so the cause can be found
                    val detail = if (m == STALKER_NO_ADDRESS) StalkerApi.lastDetail else ""
                    if (detail.isNotEmpty()) {
                        Text(detail, color = Color(0xCCFFFFFF), fontSize = 12.sp, raw = true, maxLines = 6,
                            modifier = Modifier.padding(top = 8.dp).widthIn(max = 720.dp))
                    }
                }
            }
        }
        flash?.let { fl ->
            if (fl.icon != null) {
                Box(
                    Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) { PlayerIcon(fl.icon, Color.White, size = 44.dp) }
            }
            if (fl.text != null) {
                Text(
                    fl.text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x99000000))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
        if (!cur.live) {
            ScrubCard({ scrubTarget }, { scrubStart }, { turbo }, Modifier.align(Alignment.Center))
        }
        if (AppSettings.showInfo && !showGuide && !cur.live && !controls) {
            info?.let {
                Text(
                    it, color = Color(0xD9FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium, style = TNUM,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = PT.MarginX, top = PT.MarginY)
                        .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x80000000)).padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Episode title when an episode starts
        if (epBanner && !controls && panel == Panel.NONE && !showGuide && !cur.live) {
            Column(
                Modifier.align(Alignment.TopStart).padding(start = PT.MarginX, top = PT.MarginY)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x80000000)).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Staffel " + cur.season + " · Folge " + cur.episode, color = Color(0xB3FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                epName?.let { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true) }
            }
        }

        // Mini timeline while seeking (when the control bar is not visible)
        if (!controls && !cur.live) {
            MiniSeekDock({ (miniBar || scrubTarget >= 0) && dur > 0 }, posNow, durNow, bufNow, segsNow)
        }

        notice?.takeIf { !Boomer.enabled }?.let { n ->
            Text(
                n, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = PT.MarginY + 48.dp)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xCC1F2937)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
        if (awakeWarn) {
            Text(
                "Bildschirm kann nicht wach gehalten werden – der Bildschirmschoner kann starten.",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = PT.MarginY)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xCC8B1A1A)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (digitBuf.isNotEmpty()) {
            Text(
                digitBuf, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = PT.MarginY, end = PT.MarginX)
                    .clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xCC000000)).padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }

        // Short channel banner when switching
        if (banner && cur.live && !Boomer.enabled && !controls && !showChannels && !showGuide) {
            LiveBanner(curItem, cur.title, if (curIdx >= 0) curIdx + 1 else 0, epg, Modifier.align(Alignment.BottomStart))
        }
        if (Boomer.enabled) BoomerPlayerOverlay(cur.title, bannerTick, boomerFail) { onBack() }

        if (panel == Panel.SUBSTYLE) {
            val fg = Color(SubStyle.COLORS[AppSettings.subColor].second.toInt())
            val bg = Color(SubStyle.BGS[AppSettings.subBg].second.toInt())
            Text(
                "So sehen deine Untertitel aus", color = fg, fontSize = (22 * SubStyle.SIZES[AppSettings.subSize].second).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp, end = 480.dp).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (AppSettings.clockOn && !controls && panel == Panel.NONE) PClock()
        if (techOn) PTechOverlay(player)

        // ---------------------------------------------------------------- Skip & next episode
        val seg0 = activeSeg?.takeIf { segVisible || controls }
        if ((seg0 != null || showNextCard) && panel == Panel.NONE && !showChannels && !showGuide) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = PT.MarginX, bottom = if (controls) 236.dp else 84.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Only ONE button: credits marker and "Next episode" show the same thing and are merged
                val outro = seg0 != null && seg0.label.startsWith("Abspann") && hasNext
                if (seg0 != null && !outro) PSkipPill(seg0.label + (if (!controls) "  (OK)" else "")) { skipSeg(seg0) }
                if (outro || (showNextCard && hasNext && !controls)) {
                    NextPill({ (dur - pos).coerceAtLeast(0L) }, AppSettings.autoNext, !controls) { playEpisode(epList[epIdx + 1]) }
                }
            }
        }

        // ---------------------------------------------------------------- Control bar
        AnimatedVisibility(
            // No control bar under the TV guide: otherwise focus could slip from the guide into this menu
            visible = controls && !showGuide,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            Box(Modifier.fillMaxSize()) {
                // Dimming at the top and bottom
                Box(
                    Modifier.fillMaxWidth().height(200.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(PT.ScrimTop, Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(320.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x85000000), 1f to PT.ScrimBottom))
                )

                // Title (top left) – for live the info block at the bottom takes over
                if (!cur.live) {
                    val baseTitle = if (cur.season > 0 && cur.episode > 0) cur.title.substringBeforeLast(" S" + cur.season + "E" + cur.episode) else cur.title
                    Column(
                        Modifier.align(Alignment.TopStart).padding(start = PT.MarginX, top = PT.MarginY, end = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(baseTitle, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = cur.archive != null)
                        cur.archive?.let { a ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PChip("Archiv", icon = AIcon.Catchup)
                                PChip(a.chTitle, raw = true)
                                PChip(dayLabel(a.prog.start) + "  " + hhmm(a.prog.start) + "–" + hhmm(a.prog.end), raw = true)
                            }
                        }
                        if (cur.season > 0 && cur.episode > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PChip("Staffel " + cur.season)
                                PChip("Folge " + cur.episode)
                            }
                            epName?.let { Text(it, color = Color(0xD9FFFFFF), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true) }
                        }
                    }
                }

                // Badges top right
                Row(
                    Modifier.align(Alignment.TopEnd).padding(end = PT.MarginX, top = PT.MarginY),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cur.live && Recorder.isRecording(cur.itemId)) PChip("● REC", raw = true)
                    if (cur.live) PLiveChip(behindSec = behindSec)
                    else badges.forEachIndexed { i, b -> PChip(b, filled = i == 0 && b == "4K") }
                }

                val setLabel: (String) -> Unit = { ctrlLabel = it }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = PT.MarginX, end = PT.MarginX, bottom = PT.MarginY)
                ) {
                    if (cur.live) {
                        LiveInfoBlock(curItem, cur.title, if (curIdx >= 0) curIdx + 1 else 0, epg)
                        Spacer(Modifier.height(20.dp))
                        // Recently watched channels (Up from the buttons): switch back with one press
                        RecentChannelsRow(liveAll, cur.itemId, setLabel) { switchTo(it, zapSrc) }
                        val fav = Library.isFav(cur.itemId)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { controls = false; true } else false
                            },
                            horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically
                        ) {
                            PRoundButton(Modifier.focusRequester(liveFirst), label = "Senderliste", onLabel = setLabel, onClick = { openChannels() }) {
                                PlayerIcon(PIcon.Channels, it)
                            }
                            PRoundButton(label = "TV-Guide", onLabel = setLabel, onClick = { controls = false; showGuide = true }) {
                                PlayerIcon(PIcon.Guide, it)
                            }
                            PRoundButton(label = "Zurück zu Live", onLabel = setLabel, onClick = {
                                if (LiveTimeshift.active) LiveTimeshift.goLive(player)
                                else if (player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
                                else { player.stop(); player.prepare() }
                                player.play()
                                behindSec = 0L
                                controls = false
                                flash = Flash(PIcon.Play, "LIVE")
                            }) {
                                PlayerIcon(PIcon.Play, it)
                            }
                            // Live buffer: 30 s back (the remote's seek keys jump by the step size)
                            if (LiveTimeshift.active) {
                                PRoundButton(label = "30 s zurück", onLabel = setLabel, onClick = {
                                    LiveTimeshift.seekBy(player, -30_000L); flash = Flash(PIcon.Replay, "−30 s")
                                }) { PlayerIcon(PIcon.Replay, it) }
                            }
                            // Recording of the running show (from the buffer, without a second connection)
                            if (cur.archive == null && TapLogic.tappable(resolvedUrl.ifEmpty { cur.url })) {
                                val recOn = Recorder.isRecording(cur.itemId)
                                PRoundButton(label = if (recOn) "Aufnahme beenden" else "Aufnehmen", onLabel = setLabel, onClick = {
                                    val ch = curItem
                                    if (ch == null) flash = Flash(null, "Sender unbekannt")
                                    else if (recOn) { Recorder.stopFor(ctx, ch.id); flash = Flash(null, "Aufnahme beendet") }
                                    else {
                                        val ok = LiveTimeshift.active || LiveTimeshift.start(ctx, player, resolvedUrl.ifEmpty { cur.url })
                                        val t = LiveTimeshift.tap
                                        val r = if (ok && t != null) Recorder.start(ctx, ch, Epg.current(epg), t) else null
                                        flash = Flash(null, if (r != null) "Aufnahme gestartet" else "Zu wenig freier Speicher")
                                    }
                                }) { c -> Box(Modifier.size(20.dp).clip(CircleShape).background(if (recOn) Color(0xFFFF3B30) else c)) }
                            }
                            if (Catchup.has(curItem)) {
                                PRoundButton(label = "Von Anfang an", onLabel = setLabel, onClick = {
                                    val ch = curItem
                                    val e = Epg.current(epg)
                                    if (ch != null && e != null) playArchive(ch, e) else flash = Flash(null, "Kein Programm bekannt")
                                }) { AppIcon(AIcon.Catchup, it, size = 26.dp) }
                                PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ARCHIVE)), label = "Archiv: verpasste Sendungen", onLabel = setLabel, onClick = { openPanel(Panel.ARCHIVE) }) {
                                    AppIcon(AIcon.History, it, size = 26.dp)
                                }
                            }
                            // With own channel lists: choose where to (favorites or an own list); otherwise toggle favorite directly
                            Box {
                                var favMenu by remember { mutableStateOf(false) }
                                PRoundButton(
                                    label = if (ChannelLists.lists.isNotEmpty()) "Favoriten & Listen" else if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen", onLabel = setLabel,
                                    onClick = { if (ChannelLists.lists.isNotEmpty() && curItem != null) favMenu = true else Library.toggleFav(ctx, cur.itemId) }
                                ) { c -> PlayerIcon(if (fav) PIcon.StarOn else PIcon.StarOff, if (fav && c == Color.White) PT.Gold else c) }
                                val ci = curItem
                                if (favMenu && ci != null) ChannelMenu(ci, null, null) { favMenu = false }
                            }
                            PRoundButton(Modifier.focusRequester(btn.getValue(Panel.TRACKS)), label = "Audio & Untertitel", onLabel = setLabel, onClick = { openPanel(Panel.TRACKS) }) {
                                PlayerIcon(PIcon.AudioSubs, it)
                            }
                            PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ASPECT)), label = "Bildformat", onLabel = setLabel, onClick = { openPanel(Panel.ASPECT) }) {
                                PlayerIcon(PIcon.Aspect, it)
                            }
                            // Second stream (round 22): without a second picture channel selection, otherwise the menu with arrangement, audio and swap
                            PRoundButton(label = "Zusatzbild", onLabel = setLabel, onClick = {
                                if (second == null) { pickSecond = true; controls = false; openChannels() } else openPanel(Panel.MENU)
                            }) {
                                AppIcon(AIcon.Layers, it, size = 26.dp)
                            }
                            // Live channel with several qualities: pick the stream (4K / Full HD / HD / SD)
                            if (cur.live && liveAlts.size > 1) PRoundButton(label = "Qualität", onLabel = setLabel, onClick = { openPanel(Panel.QUALITY) }) {
                                AppIcon(AIcon.Sliders, it, size = 26.dp)
                            }
                            if (SportAlerts.miniOn || SportAlerts.pinned.isNotEmpty()) PRoundButton(label = "Score-Overlay", onLabel = setLabel, onClick = { scoreDlg = true }) {
                                AppIcon(AIcon.Live, it, size = 26.dp)
                            }
                            PRoundButton(Modifier.focusRequester(btn.getValue(Panel.MENU)), label = "Einstellungen", onLabel = setLabel, onClick = { openPanel(Panel.MENU) }) {
                                PlayerIcon(PIcon.Settings, it)
                            }
                            PRoundButton(label = "Extern öffnen", onLabel = setLabel, onClick = { player.pause(); openExternal(ctx, cur.url, cur.title) }) {
                                PlayerIcon(PIcon.External, it)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).height(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ctrlLabel.ifEmpty { "Senderliste" }, color = Muted, fontSize = 14.sp, maxLines = 1)
                            Text("Hoch und Runter wechseln den Sender", color = Muted, fontSize = 14.sp, maxLines = 1)
                        }
                    } else {
                        val stepS = (step / 1000).toString()
                        PSeekBar(
                            Modifier.fillMaxWidth().focusRequester(seekFocus),
                            pos = posNow, dur = durNow, buffered = bufNow, segs = segsNow,
                            scrubbing = { scrubTarget >= 0 },
                            onFocus = { if (it) ctrlLabel = "Zeitleiste  ·  Links/Rechts = $stepS s springen  ·  Halten = weiter springen" },
                            onDown = { runCatching { firstFocus.requestFocus() } },
                            onSeekKey = { stepSeek(it) },
                            onRepeat = { repeatSeek(it) }
                        )
                        PTimeRow(posNow, durNow)
                        Spacer(Modifier.height(14.dp))
                        val arch = cur.archive != null
                        val prevLabel = if (arch) (archPrev?.let { "Vorherige Sendung: " + hhmm(it.start) + "  " + it.title } ?: "Vorherige Sendung")
                            else if (hasPrev) "Vorherige Folge: Staffel " + epList[epIdx - 1].season + ", Folge " + epList[epIdx - 1].num else "Vorherige Folge"
                        val nextLabel = if (arch) (archNext?.let { "Nächste Sendung: " + hhmm(it.start) + "  " + it.title } ?: "Nächste Sendung: Live")
                            else if (hasNext) "Nächste Folge: Staffel " + epList[epIdx + 1].season + ", Folge " + epList[epIdx + 1].num else "Nächste Folge"
                        Box(
                            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { seekFocus.requestFocus() }; true }
                                else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { controls = false; true }
                                else false
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(PT.BtnPrimary),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically) {
                                    PRoundButton(enabled = if (arch) archPrev != null else hasPrev, label = prevLabel, onLabel = setLabel, onClick = {
                                        val ch = curItem
                                        val pv = archPrev
                                        if (arch) { if (ch != null && pv != null) playArchive(ch, pv) }
                                        else if (hasPrev) playEpisode(epList[epIdx - 1])
                                    }) {
                                        PlayerIcon(PIcon.PrevEp, it)
                                    }
                                    PRoundButton(label = "$stepS Sekunden zurück", onLabel = setLabel, onClick = { stepSeek(-1, fromBar = false) }) { c ->
                                        Box(contentAlignment = Alignment.Center) {
                                            PlayerIcon(PIcon.Replay, c, size = 30.dp)
                                            Text(stepS, color = c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.offset(y = 2.dp))
                                        }
                                    }
                                    PRoundButton(
                                        Modifier.focusRequester(playBtn), size = PT.BtnPrimary,
                                        label = if (playing) "Pause" else "Abspielen", onLabel = setLabel, onClick = { togglePlay() }
                                    ) { PlayerIcon(if (playing) PIcon.Pause else PIcon.Play, it, size = 32.dp) }
                                    PRoundButton(label = "$stepS Sekunden vor", onLabel = setLabel, onClick = { stepSeek(1, fromBar = false) }) { c ->
                                        Box(contentAlignment = Alignment.Center) {
                                            PlayerIcon(PIcon.Forward, c, size = 30.dp)
                                            Text(stepS, color = c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.offset(y = 2.dp))
                                        }
                                    }
                                    PRoundButton(enabled = arch || hasNext, label = nextLabel, onLabel = setLabel, onClick = {
                                        val ch = curItem
                                        val nx = archNext
                                        if (arch) { if (ch != null && nx != null) playArchive(ch, nx) else backToLive() }
                                        else if (hasNext) playEpisode(epList[epIdx + 1])
                                    }) {
                                        PlayerIcon(PIcon.NextEp, it)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically) {
                                    if (arch) {
                                        PRoundButton(label = "Zurück zu Live", onLabel = setLabel, onClick = { backToLive() }) {
                                            AppIcon(AIcon.Live, it, size = 26.dp)
                                        }
                                        PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ARCHIVE)), label = "Archiv: verpasste Sendungen", onLabel = setLabel, onClick = { openPanel(Panel.ARCHIVE) }) {
                                            AppIcon(AIcon.History, it, size = 26.dp)
                                        }
                                    }
                                    PRoundButton(Modifier.focusRequester(btn.getValue(Panel.TRACKS)), label = "Audio & Untertitel", onLabel = setLabel, onClick = { openPanel(Panel.TRACKS) }) {
                                        PlayerIcon(PIcon.AudioSubs, it)
                                    }
                                    if (epList.size > 1) {
                                        PRoundButton(Modifier.focusRequester(btn.getValue(Panel.EPISODES)), label = "Folgen", onLabel = setLabel, onClick = { openPanel(Panel.EPISODES) }) {
                                            PlayerIcon(PIcon.Episodes, it)
                                        }
                                    }
                                    PRoundButton(Modifier.focusRequester(btn.getValue(Panel.MENU)), label = "Einstellungen", onLabel = setLabel, onClick = { openPanel(Panel.MENU) }) {
                                        PlayerIcon(PIcon.Settings, it)
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).height(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ctrlLabel, color = Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (AppSettings.showEndTime) PEndsAt(posNow, durNow) { SPEEDS[speedIdx] }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Channel list & TV guide
        if (showChannels) {
            ChannelOverlay(
                liveAll, cur.itemId, curItem,
                chanCat, { chanCat = it },
                showCats, { showCats = it },
                focusedCh, { focusedCh = it },
                inHeader, { inHeader = it },
                chanFocus, catsFocus, headerFocus,
                { showChannels = false }
            ) { item, list ->
                playCat = chanCat
                if (pickSecond) { pickSecond = false; second = item; showChannels = false; showCats = false }
                else switchTo(item, list)
            }
        }

        if (showGuide) {
            Guide(
                zapSrc, cur.itemId, guideFocus, onClose = { showGuide = false }, onArchive = { ch, e -> playArchive(ch, e) },
                all = liveAll.ifEmpty { zapSrc }, onPickIn = { ch, src -> switchTo(ch, src) }
            ) { switchTo(it, zapSrc) }
        }

        // ---------------------------------------------------------------- Menus
        if (panel != Panel.NONE) {
            val back = if (panelParent != Panel.NONE) "Links = zurück" else "Links schließt das Menü"
            when (panel) {
                Panel.TRACKS -> TracksPanel(
                    player, tracksVer, { tracksVer++; h.postDelayed({ rememberTracks() }, 400) }, panelFocus, subsFocus,
                    { closePanel() }, { openPanel(Panel.SUBSTYLE, Panel.TRACKS) },
                    if (cur.live) null else ({ openPanel(Panel.SUBSEARCH, Panel.TRACKS) })
                )

                Panel.SUBSEARCH -> SubSearchPanel(
                    SubSearchLogic.query(cur.title, cur.tmdbId, cur.season, cur.episode, AppSettings.subLangEffective(), Lang.code()),
                    subKey(), player, panelFocus, back, { closePanel() }
                ) { s ->
                    notice = Lang.tr("Untertitel geladen") + ": " + s.label
                    // Remember the choice like a subtitle picked by hand (on again next time)
                    h.postDelayed({ tracksVer++; rememberTracks() }, 1500)
                    panelParent = Panel.NONE; panel = Panel.NONE
                }

                Panel.SUBSTYLE -> PSimplePanel(440.dp, "Untertitel-Stil", back, { closePanel() }) {
                    Column(verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        PPanelRow("Größe", false, Modifier.focusRequester(panelFocus), trailing = SubStyle.SIZES[AppSettings.subSize].first, chevron = true) {
                            AppSettings.setSub(ctx, (AppSettings.subSize + 1) % SubStyle.SIZES.size, AppSettings.subColor, AppSettings.subBg, AppSettings.subEdge)
                        }
                        PPanelRow("Farbe", false, trailing = SubStyle.COLORS[AppSettings.subColor].first, chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, (AppSettings.subColor + 1) % SubStyle.COLORS.size, AppSettings.subBg, AppSettings.subEdge)
                        }
                        PPanelRow("Hintergrund", false, trailing = SubStyle.BGS[AppSettings.subBg].first, chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, AppSettings.subColor, (AppSettings.subBg + 1) % SubStyle.BGS.size, AppSettings.subEdge)
                        }
                        PPanelRow("Rand", false, trailing = SubStyle.EDGES[AppSettings.subEdge], chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, AppSettings.subColor, AppSettings.subBg, (AppSettings.subEdge + 1) % SubStyle.EDGES.size)
                        }
                        Text("OK = nächster Wert. Die Vorschau zeigt das Ergebnis.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Panel.QUALITY -> PSimplePanel(440.dp, "Videoqualität", back, { closePanel() }) {
                    val video = remember(tracksVer) { collectTracks(player, C.TRACK_TYPE_VIDEO) }
                    val videoAuto = player.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }
                    // Several sources of the same title (4K/FHD/HD as separate entries): switch by hand here
                    val alts = remember(cur.url, cur.itemId, cur.season, cur.episode, liveAlts) { currentAlts() }
                    val altsShown = alts.size > 1
                    if (altsShown) {
                        PColumnHeader("Quelle")
                        val curI = alts.indexOfFirst { it.url == cur.url }.coerceAtLeast(0)
                        alts.forEachIndexed { i, a ->
                            PPanelRow(
                                a.label.ifBlank { a.quality ?: "Quelle ${i + 1}" }, i == curI,
                                if (i == curI) Modifier.focusRequester(panelFocus) else Modifier,
                                trailingIcon = if (DeadLinks.isDead(a.url)) AIcon.Warning else null
                            ) {
                                if (a.url != cur.url) {
                                    val at = player.currentPosition.coerceAtLeast(0L)
                                    stalls = emptyList()
                                    cur = cur.copy(url = a.url, startMs = if (cur.live) 0L else at, note = null)
                                }
                                closePanel()
                            }
                        }
                        if (video.size >= 2) PColumnHeader("Bildspur")
                    }
                    if (altsShown && video.size < 2) {
                        // sources only – no picture tracks to choose from
                    } else if (video.size < 2) {
                        Text(
                            "Keine Auswahl verfügbar (feste Qualität).", color = Muted, fontSize = 15.sp,
                            modifier = Modifier.focusRequester(panelFocus).focusable().padding(16.dp)
                        )
                    } else {
                        val labels = listOf("Automatisch") + video.map { it.label }
                        val sel = if (videoAuto) 0 else 1 + video.indexOfFirst { it.selected }.coerceAtLeast(0)
                        val trackFocus = remember { FocusRequester() }
                        PChoiceList(labels, sel, if (altsShown) trackFocus else panelFocus) { i ->
                            player.trackSelectionParameters = if (i == 0) {
                                player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                            } else {
                                val t = video[i - 1]
                                player.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                            }
                            tracksVer++
                        }
                    }
                }

                Panel.ARCHIVE -> {
                    // Like the channel list: channels with archive | days | shows (Back closes via the player)
                    val archChannels = remember(zapSrc, liveAll, Catchup.version) {
                        zapSrc.filter { Catchup.has(it) }.ifEmpty { liveAll.filter { Catchup.has(it) } }
                    }
                    ArchivePanel(curItem, archChannels, cur.archive?.prog?.start, panelFocus, { closePanel() }) { ch, e -> playArchive(ch, e) }
                }

                Panel.SPEED -> PSimplePanel(400.dp, "Wiedergabe-Tempo", back, { closePanel() }) {
                    PChoiceList(SPEEDS.map { speedLabel(it) }, speedIdx, panelFocus) { i ->
                        speedIdx = i
                        player.setPlaybackSpeed(SPEEDS[i])
                    }
                }

                Panel.SLEEP -> PSimplePanel(400.dp, "Sleep-Timer", back, { closePanel() }) {
                    PChoiceList(SLEEP_MIN.map { if (it == 0) "Aus" else "$it min" }, sleepIdx, panelFocus) { i ->
                        sleepIdx = i
                        sleepAt = if (i == 0) 0L else System.currentTimeMillis() + SLEEP_MIN[i] * 60_000L
                    }
                }

                Panel.ASPECT -> PSimplePanel(440.dp, "Bildformat", back, { closePanel() }) {
                    PChoiceList(ASPECTS, aspect, panelFocus) { aspect = it; flash = Flash(null, ASPECTS[it]) }
                }

                Panel.CLOCK -> PSimplePanel(440.dp, "Uhrzeit im Bild", back, { closePanel() }) {
                    val poss = listOf("Oben rechts", "Oben links", "Unten rechts", "Unten links")
                    val sizes = listOf("Klein", "Mittel", "Groß")
                    val ops = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
                    Column(verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        ClockSample(Modifier.align(Alignment.CenterHorizontally))
                        PPanelRow("Anzeigen", false, Modifier.focusRequester(panelFocus), trailing = if (AppSettings.clockOn) "An" else "Aus", chevron = true) {
                            AppSettings.setClock(ctx, !AppSettings.clockOn, AppSettings.clockPos, AppSettings.clockOpacity, AppSettings.clockSize)
                        }
                        PPanelRow("Ort", false, trailing = poss[AppSettings.clockPos], chevron = true) {
                            AppSettings.setClock(ctx, AppSettings.clockOn, (AppSettings.clockPos + 1) % 4, AppSettings.clockOpacity, AppSettings.clockSize)
                        }
                        PPanelRow("Schrift: Deckkraft", false, trailing = "${AppSettings.clockOpacity} %", chevron = true) {
                            val i = ops.indexOf(AppSettings.clockOpacity).let { if (it < 0) 5 else it }
                            AppSettings.setClock(ctx, AppSettings.clockOn, AppSettings.clockPos, ops[(i + 1) % ops.size], AppSettings.clockSize)
                        }
                        PPanelRow("Größe", false, trailing = sizes[AppSettings.clockSize], chevron = true) {
                            AppSettings.setClock(ctx, AppSettings.clockOn, AppSettings.clockPos, AppSettings.clockOpacity, (AppSettings.clockSize + 1) % 3)
                        }
                        PPanelRow("Hintergrund", false, trailing = if (AppSettings.clockBg) "An" else "Aus", chevron = true) {
                            AppSettings.setClockBg(ctx, !AppSettings.clockBg, AppSettings.clockBgOpacity)
                        }
                        if (AppSettings.clockBg) PPanelRow("Hintergrund: Deckkraft", false, trailing = "${AppSettings.clockBgOpacity} %", chevron = true) {
                            val i = ops.indexOf(AppSettings.clockBgOpacity).let { if (it < 0) 1 else it }
                            AppSettings.setClockBg(ctx, true, ops[(i + 1) % ops.size])
                        }
                        Text("OK = nächster Wert. Die Vorschau zeigt das Ergebnis.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Panel.STEP -> PSimplePanel(400.dp, "Sprungweite", back, { closePanel() }) {
                    Text("Gilt für Links/Rechts, Halten und die Zeitleiste.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                    PChoiceList(STEP_OPTIONS.map { "$it s" }, STEP_OPTIONS.indexOf(AppSettings.seekStep).coerceAtLeast(0), panelFocus) { i ->
                        AppSettings.setPlayer(ctx, AppSettings.skipIntro, AppSettings.showInfo, STEP_OPTIONS[i])
                    }
                }

                Panel.MENU -> PSimplePanel(440.dp, "Einstellungen", back, { closePanel() }) {
                    val entries = buildList<MenuEntry> {
                        if (cur.live) add(MenuEntry(20, "Zusatzbild", second?.title ?: "Aus", false))
                        if (cur.live && second != null) {
                            add(MenuEntry(21, "Anordnung", if (splitMode == 1) "Geteilt" else "Bild-in-Bild", false))
                            add(MenuEntry(22, "Ton von", if (audioOnSecond) "Zusatzbild" else "Hauptstream", false))
                            if (splitMode == 0) {
                                add(MenuEntry(24, "Fenstergröße", DualPrefs.SIZE_NAMES[DualPrefs.size], false))
                                add(MenuEntry(25, "Position", DualPrefs.CORNER_NAMES[DualPrefs.corner], false))
                            }
                            add(MenuEntry(23, "Streams tauschen", null, false))
                        }
                        add(MenuEntry(0, "Sprungweite", "${step / 1000} s", true))
                        add(MenuEntry(1, "Untertitel-Stil", null, true))
                        add(MenuEntry(2, "Videoqualität", currentAlts().size.takeIf { it > 1 }?.let { "$it Quellen" }, true))
                        add(MenuEntry(3, "Wiedergabe-Tempo", speedLabel(SPEEDS[speedIdx]), true))
                        add(MenuEntry(4, "Bildformat", ASPECTS[aspect].substringBefore(" ("), true))
                        if (epList.size > 1) add(MenuEntry(5, "Nächste Folge automatisch", if (AppSettings.autoNext) "An" else "Aus", false))
                        if (epList.size > 1) add(MenuEntry(13, "Abspann überspringen", if (AppSettings.autoSkipOutro) "An" else "Aus", false))
                        add(MenuEntry(6, "Endzeit anzeigen", if (AppSettings.showEndTime) "An" else "Aus", false))
                        add(MenuEntry(10, "Uhrzeit im Bild", if (AppSettings.clockOn) "An" else "Aus", true))
                        add(MenuEntry(11, "Technik-Info", if (techOn) "An" else "Aus", false))
                                        add(MenuEntry(8, "Sleep-Timer", if (sleepIdx == 0) "Aus" else SLEEP_MIN[sleepIdx].toString() + " min", true))
                        if (cur.live && lastChId != null) add(MenuEntry(9, "Letzter Kanal", null, false))
                        add(MenuEntry(7, "Extern öffnen", null, false))
                    }
                    val focusAt = menuSel.coerceIn(0, entries.lastIndex)
                    // Starts so that the last chosen row is drawn (otherwise focus cannot find it and keys go nowhere)
                    LazyColumn(state = rememberLazyListState(maxOf(focusAt - 3, 0)), verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        itemsIndexed(entries) { i, e ->
                            PPanelRow(
                                e.label, false, if (i == focusAt) Modifier.focusRequester(panelFocus) else Modifier,
                                trailing = e.trailing, chevron = e.chevron
                            ) {
                                menuSel = i
                                when (e.id) {
                                    0 -> openPanel(Panel.STEP, Panel.MENU)
                                    1 -> openPanel(Panel.SUBSTYLE, Panel.MENU)
                                    2 -> openPanel(Panel.QUALITY, Panel.MENU)
                                    3 -> openPanel(Panel.SPEED, Panel.MENU)
                                    4 -> openPanel(Panel.ASPECT, Panel.MENU)
                                    8 -> openPanel(Panel.SLEEP, Panel.MENU)
                                    10 -> openPanel(Panel.CLOCK, Panel.MENU)
                                    11 -> techOn = !techOn
                                    13 -> AppSettings.setAutoOutro(ctx, !AppSettings.autoSkipOutro)
                                    9 -> { panel = Panel.NONE; panelParent = Panel.NONE; lastChannel() }
                                    20 -> {
                                        if (second != null) second = null
                                        else { pickSecond = true; panel = Panel.NONE; panelParent = Panel.NONE; controls = false; openChannels() }
                                    }
                                    21 -> DualPrefs.set(ctx, split = 1 - DualPrefs.split)
                                    22 -> DualPrefs.set(ctx, audio = !DualPrefs.audioSecond)
                                    24 -> DualPrefs.set(ctx, size = (DualPrefs.size + 1) % DualPrefs.SIZES.size)
                                    25 -> DualPrefs.set(ctx, corner = (DualPrefs.corner + 1) % DualPrefs.CORNER_NAMES.size)
                                    23 -> {
                                        val s2 = second; val m = curItem
                                        if (s2 != null && m != null) { second = m; panel = Panel.NONE; panelParent = Panel.NONE; switchTo(s2, zapSrc) }
                                    }
                                    5 -> AppSettings.setPlayerExtra(ctx, !AppSettings.autoNext, AppSettings.hideSec, AppSettings.showEndTime, AppSettings.autoSkipIntro)
                                    6 -> AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, !AppSettings.showEndTime, AppSettings.autoSkipIntro)
                                    else -> {
                                        player.pause()
                                        openExternal(ctx, resolvedUrl.ifEmpty { cur.url }, cur.title)
                                        panel = Panel.NONE
                                        panelParent = Panel.NONE
                                    }
                                }
                            }
                        }
                    }
                }

                Panel.EPISODES -> EpisodesPanel(epList, cur.season, cur.episode, panelFocus, { closePanel() }) { ep ->
                    panel = Panel.NONE
                    panelParent = Panel.NONE
                    controls = false
                    playEpisode(ep)
                }

                Panel.NONE -> {}
            }
        }
    }
}

/** Centered seek display: only direction and the time actually queued ("+0:30"), without a counter. */
@Composable
private fun ScrubCard(target: () -> Long, start: () -> Long, turbo: () -> Int, modifier: Modifier) {
    val t = target()
    if (t < 0) return
    val delta = t - start()
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.clip(shape).background(Color(0xE612141C)).border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        PlayerIcon(if (delta >= 0) PIcon.Forward else PIcon.Replay, Color.White, size = 32.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtDelta(delta), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, style = TNUM, maxLines = 1)
            val lv = turbo()
            if (lv > 0) Text("Schnell " + "»".repeat(lv), color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Timeline at the bottom while seeking and the big control bar is not open. */
@Composable
private fun BoxScope.MiniSeekDock(
    visible: () -> Boolean, pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>
) {
    if (!visible()) return
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, PT.ScrimBottom)))
    )
    PMiniSeek(
        pos, dur, buffered, segs,
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = PT.MarginX, vertical = PT.MarginY)
    )
}

/**
 * "Next episode" as the only pill in the style of "Skip intro". If automatic continuation is running,
 * it counts down in the last 10 seconds. OK starts the episode immediately.
 */
@Composable
private fun NextPill(remain: () -> Long, auto: Boolean, showOk: Boolean, onClick: () -> Unit) {
    val r = remain()
    val label = if (auto && r in 1..10_000L) Lang.tr("Nächste Folge") + " · " + ((r + 999) / 1000) + " s" else Lang.tr("Nächste Folge")
    PSkipPill(label + (if (showOk) "  (OK)" else ""), onClick = onClick)
}

/**
 * Open a stream. Media3 throws an IllegalStateException on setting if no source is built in for the format
 * (formerly with .mpd without the DASH module: crash on this line). false = not playable.
 */
internal fun openStream(player: androidx.media3.exoplayer.ExoPlayer, url: String, startMs: Long, item: MediaItem? = null): Boolean = try {
    player.setMediaItem(item ?: MediaItem.fromUri(url), startMs)
    player.prepare()
    player.playWhenReady = true
    true
} catch (e: IllegalStateException) {
    false
} catch (e: IllegalArgumentException) {
    false
} catch (e: Exception) {
    false
}
