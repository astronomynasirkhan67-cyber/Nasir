package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.net.URLEncoder

fun openTrailer(ctx: Context, key: String?, title: String) {
    val uri = if (key != null) "https://www.youtube.com/watch?v=$key"
    else "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$title Trailer", "UTF-8")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("Keine App zum Öffnen des Trailers gefunden"), Toast.LENGTH_LONG).show()
    }
}

fun openExternal(ctx: Context, url: String, title: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
    try {
        ctx.startActivity(Intent.createChooser(i, "Externer Player").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("Kein externer Player installiert (z. B. VLC)"), Toast.LENGTH_LONG).show()
    }
}

private val QORDER = listOf("4K", "FHD", "HD")

/** Position of the first episode in the detail page list (0 = header, 1 = seasons/sections). */
/** Position of the episodes area (seasons left, episodes right) in the page list: 0 = header, 1 = episodes */
private const val EP_PANE = 1

/** Up to this length the description is always shown completely (no "Read more"). */
private const val FULL_TEXT_CHARS = 800

/**
 * Detail page. Series with a placeholder (episodes are only loaded on opening, see [LazySeries]) fetch their episodes first;
 * while that runs, the page shows a spinner, on an error "Try again".
 */
@Composable
fun DetailScreen(
    item: Item, all: List<Item>, onPlay: (String, String, Long) -> Unit,
    onPerson: (PersonRef) -> Unit, onOpenItem: (Item) -> Unit, onBack: () -> Unit,
    embedded: Boolean = false, autoFocus: Boolean = true, onTopUp: (() -> Unit)? = null,
    showActions: Boolean = true
) {
    val ctx = LocalContext.current
    var tryNo by remember(item.id) { mutableIntStateOf(0) }
    // Cinema preview without buttons (info only) does not need the episodes
    val need = LazySeries.isStub(item) && showActions
    var full by remember(item.id) { mutableStateOf(LazySeries.full(item).takeIf { it !== item }) }
    var failed by remember(item.id) { mutableStateOf(false) }
    if (need && full == null) {
        LaunchedEffect(item.id, tryNo) {
            failed = false
            val r = LazySeries.load(ctx, item)
            if (r != null) full = r else failed = true
        }
        BackHandler(enabled = !embedded, onBack = onBack)
        val retry = remember { FocusRequester() }
        LaunchedEffect(failed) { if (failed && !embedded) { delay(120); runCatching { retry.requestFocus() } } }
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!failed) {
                    Spinner(Accent2, Modifier.size(48.dp))
                    Text("Folgen werden geladen …", color = Muted, fontSize = 18.sp)
                } else {
                    Text(item.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Die Folgen konnten nicht geladen werden. Verbindung oder Zugang prüfen.", color = Muted, fontSize = 17.sp)
                    // Stalker portal: what the portal answered (masked), so the cause can be found
                    if (StalkerLogic.isLazy(item) && StalkerApi.lastSeriesDetail.isNotEmpty()) {
                        Text(StalkerApi.lastSeriesDetail, color = Muted, fontSize = 12.sp, raw = true, maxLines = 6,
                            modifier = Modifier.widthIn(max = 760.dp))
                    }
                    PillButton("Erneut versuchen", Modifier.focusRequester(retry)) { tryNo++ }
                    if (!embedded) PillButton("Zurück", light = true) { onBack() }
                }
            }
        }
        return
    }
    DetailScreenFull(
        if (need) full ?: item else item, all, onPlay, onPerson, onOpenItem, onBack,
        embedded, autoFocus, onTopUp, showActions
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DetailScreenFull(
    item: Item, all: List<Item>, onPlay: (String, String, Long) -> Unit,
    onPerson: (PersonRef) -> Unit, onOpenItem: (Item) -> Unit, onBack: () -> Unit,
    // Cinema view: embedded below the title row (no own back button, no automatic focus, Up at the header passes on)
    embedded: Boolean = false, autoFocus: Boolean = true, onTopUp: (() -> Unit)? = null,
    // false = info only (cinema preview): no buttons, no season bar
    showActions: Boolean = true
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroFocused) {
        if (heroFocused) repeat(4) { delay(70); listState.scrollToItem(0) }
    }
    BackHandler(enabled = !embedded, onBack = onBack)
    var meta by remember(item.id) { mutableStateOf(MetaRepo.cached(item)) }
    var metaDone by remember(item.id) { mutableStateOf(!MetaRepo.usable(item) || item.kind == Kind.LIVE) }
    LaunchedEffect(item.id) {
        MetaRepo.extended(ctx, item)?.let { meta = it }
        metaDone = true
    }
    val credits by produceState<Credits?>(null, meta?.tmdbId) {
        val m = meta
        value = if (m != null && m.tmdbId > 0) CreditsRepo.credits(ctx, m) else null
    }
    // Match against the whole playlist in the background (an index is built the first time)
    val similar by produceState(emptyList<Item>(), credits, all) {
        val c = credits ?: return@produceState
        value = withContext(Dispatchers.Default) { LibMatch.matches(all, c.similar, 30).filter { s -> s.id != item.id } }
    }

    val seasons = remember(item) { item.episodes.map { it.season }.distinct().sorted() }
    // Resume point: the page opens at this season and the section with this episode
    val resume = WatchStore.entries.firstOrNull { it.itemId == item.id }
    val resumeSE = remember(resume) { resume?.let { r -> if (r.season > 0 || r.ep > 0) r.season to r.ep else ResumeLogic.seasonEp(r.title) } }
    // Back from the player: open at exactly the episode that played last (once), otherwise at the resume point
    val retSE = remember(item.id) { DetailReturn.take(item.id)?.takeIf { it.first > 0 || it.second > 0 } }
    val startSE = retSE ?: resumeSE
    var season by remember(item.id) { mutableIntStateOf(startSE?.first?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
    val eps = remember(item, season) { item.episodes.filter { it.season == season } }
    // Counter: raised after marking by hand so that checkmarks and buttons redraw
    var progVer by remember(item.id) { mutableIntStateOf(0) }
    val progress = remember(item.id, progVer) { EpProgress.of(ctx, item.id).toMap() }

    val saved = remember(item.id) { VariantPref.get(ctx, item.id) }
    var prefQ by remember(item.id) { mutableStateOf(saved.first) }
    var prefLang by remember(item.id) { mutableStateOf(saved.second) }
    var prefLabel by remember(item.id) { mutableStateOf(saved.third) }
    val allVars = remember(item) { item.episodes.flatMap { it.variants } }
    val qualities = remember(item) { QORDER.filter { q -> allVars.any { it.quality == q } } }
    val langs = remember(item) { allVars.mapNotNull { it.lang }.distinct().sorted() }
    val labels = remember(item) { allVars.map { it.label }.filter { it.isNotBlank() }.distinct() }
    val multi = item.kind == Kind.SERIES && item.episodes.any { it.variants.size > 1 }
    // Movies: the same movie in other qualities (own entries of the provider)
    val movieAlts by produceState(listOf(item), item, all) {
        if (item.kind == Kind.MOVIE) value = withContext(Dispatchers.Default) { Sources.alternatives(item, all) }
    }
    val movieQs = remember(movieAlts) { QORDER.filter { q -> movieAlts.any { it.quality == q } } }
    // Round 14: choose the source of a movie explicitly (same movie several times in the playlist, e.g. HD and 4K)
    val movieVars: List<Variant> = remember(movieAlts) { Sources.asVariants(movieAlts) }

    fun save() = VariantPref.set(ctx, item.id, prefQ, prefLang, prefLabel)
    val qCap = remember { PlaybackPrefs.cap(ctx) }
    fun pick(ep: Episode) = pickVariant(ep, prefQ, prefLang, prefLabel, qCap)
    val playUrl = if (item.kind == Kind.SERIES) eps.firstOrNull()?.let { pick(it).url }
        else movieVars.firstOrNull { it.label == prefLabel }?.url ?: item.url

    val tmdbId = meta?.tmdbId
    val imdbId = meta?.imdbId
    val isTv = meta?.isTv == true
    // Long seasons (One Piece & co.): fetch TMDB seasons to use them as sections
    val needTmdbSeasons = remember(item) {
        item.kind == Kind.SERIES && item.episodes.groupBy { it.season }.values.any { it.size > EpisodeLogic.LONG || it.first().num > 1 }
    }
    val tmdbSeasons by produceState(emptyList<EpisodeLogic.TmdbSeason>(), tmdbId, isTv) {
        val id = tmdbId
        if (needTmdbSeasons && isTv && id != null && id > 0) value = MetaRepo.tvSeasons(ctx, id)
    }
    val groups = remember(eps, season, tmdbSeasons) { EpisodeLogic.groups(eps, season, seasons.size, tmdbSeasons) }
    // On season change to the left: land in the last section; "Go to episode" in another season: jump there
    val enterAtEnd = remember { booleanArrayOf(false) }
    val pendingNum = remember { intArrayOf(-1) }
    // Season change with Right: start at episode 1 of the new season (not at the last watched)
    val startFirst = remember { booleanArrayOf(false) }
    // Position in the season where the list stands – the shown section follows from it
    var anchor by remember(item.id, season) {
        mutableIntStateOf(
            when {
                pendingNum[0] > 0 -> EpisodeLogic.indexOfNum(eps, pendingNum[0]).also { pendingNum[0] = -1 }
                enterAtEnd[0] -> (groups.lastOrNull()?.from ?: 0).also { enterAtEnd[0] = false }
                startFirst[0] -> 0.also { startFirst[0] = false }
                startSE != null && startSE.first == season -> EpisodeLogic.indexOfNum(eps, startSE.second)
                else -> 0
            }.coerceAtLeast(0)
        )
    }
    val gi = EpisodeLogic.groupOf(groups, anchor)
    val group = groups.getOrNull(gi)
    val shown = remember(eps, group) { if (group == null) emptyList() else eps.subList(group.from, group.to + 1) }
    // Info from the matching TMDB season (with continuous numbering not season 1 of the playlist)
    val metaSeason = group?.tmdbSeason ?: season
    // New with a different TMDB match (the detail page can correct a wrongly stored match on opening)
    var epCache by remember(item.id, tmdbId) { mutableStateOf<Map<Int, List<EpMeta>>>(emptyMap()) }
    var epLoading by remember(item.id, tmdbId) { mutableStateOf(false) }
    LaunchedEffect(tmdbId, imdbId, metaSeason) {
        if (item.kind == Kind.SERIES && isTv && tmdbId != null && tmdbId > 0 && metaSeason >= 0 && epCache[metaSeason] == null) {
            epLoading = true
            // IMDb ratings per episode only with the same count (IMDb splits long anime differently)
            val sameCount = group == null || (group.offset == 0 && metaSeason == season)
            val res = MetaRepo.season(tmdbId, metaSeason, if (sameCount) imdbId else null)
            epCache = epCache + (metaSeason to res)
            epLoading = false
        }
    }
    val epMetas = epCache[metaSeason] ?: emptyList()
    fun metaOf(ep: Episode): EpMeta? {
        val g = group ?: return null
        if (g.tmdbSeason < 0) return null
        val n = EpisodeLogic.tmdbNum(g, ep.num)
        return epMetas.firstOrNull { it.num == n } ?: if (g.offset > 0) epMetas.firstOrNull { it.num == ep.num } else null
    }

    val playFocus = remember { FocusRequester() }
    // Back from a person or a similar title: go back there, not to "Resume"
    var topMoreFocused by remember { mutableStateOf(false) }
    var topRowFocused by remember { mutableStateOf(false) }
    val memKey = remember(item.id) { DetailFocus.last.remove(item.id) }
    var restored by remember(item.id) { mutableStateOf(memKey == null) }
    val restoreReq = remember { FocusRequester() }
    val castState = rememberLazyListState()
    val simState = rememberLazyListState()
    LaunchedEffect(autoFocus) { if (autoFocus && memKey == null && retSE == null) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(memKey, similar, credits) {
        if (memKey == null || restored) return@LaunchedEffect
        val isSim = memKey.startsWith("s:")
        val id = memKey.drop(2)
        val idx = if (isSim) similar.indexOfFirst { it.id == id } else credits?.cast?.indexOfFirst { it.id.toString() == id } ?: -1
        if (idx >= 0) {
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            repeat(3) { withFrameNanos { } }
            (if (isSim) simState else castState).scrollToItem(idx)
            repeat(3) { withFrameNanos { } }
            runCatching { restoreReq.requestFocus() }
            restored = true
        }
    }
    // If the target is not found (list changed), go to "Play" normally after a short time
    LaunchedEffect(memKey) {
        if (memKey == null) return@LaunchedEffect
        delay(5000)
        if (!restored) { restored = true; if (autoFocus) runCatching { playFocus.requestFocus() } }
    }

    // Description: 4 lines by default, expandable with "Read more"
    var descExpanded by remember(item.id) { mutableStateOf(false) }
    var descOverflow by remember(item.id) { mutableStateOf(false) }

    // Season, section and episode focus (index = position in the season)
    val seasonReq = remember { FocusRequester() }
    val seasonRowState = rememberLazyListState()
    val groupReq = remember { FocusRequester() }
    val groupRowState = rememberLazyListState()
    val epReqs = remember(item.id) { HashMap<Int, FocusRequester>() }
    val epFocusIdx = remember(item.id) { intArrayOf(0) }
    // Target for the next focus change (-1 = first episode of the section). Separate from epFocusIdx because on
    // season change the old row disappears and Compose briefly puts focus on some new row.
    val targetIdx = remember(item.id) { intArrayOf(-1) }
    var focusedIdx by remember(item.id) { mutableIntStateOf(-1) }
    var refocusEp by remember { mutableStateOf(false) }
    // Episode list (right in the episodes area); the area itself is as tall as the picture, the episodes scroll inside
    val epListState = rememberLazyListState()
    val paneH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 72).dp.coerceAtLeast(360.dp)
    var focusTick by remember { mutableIntStateOf(0) }
    var jumpOpen by remember(item.id) { mutableStateOf(false) }
    var jumpText by remember(item.id) { mutableStateOf("") }
    val jumpReq = remember { FocusRequester() }

    fun epReq(i: Int): FocusRequester = epReqs.getOrPut(i) { FocusRequester() }

    fun switchSeason(delta: Int) {
        val ni = seasons.indexOf(season) + delta
        if (ni in seasons.indices) {
            enterAtEnd[0] = delta < 0
            startFirst[0] = delta > 0
            season = seasons[ni]
            // always land on the first episode (of the section), not on the old position
            targetIdx[0] = -1
            refocusEp = true
        }
    }

    /** Left/Right in the episode list: next section (at its first episode); at the end of the season on to the next season. */
    fun switchGroup(delta: Int) {
        val ni = gi + delta
        if (ni in groups.indices) {
            val g = groups[ni]
            targetIdx[0] = g.from
            anchor = g.from
            refocusEp = true
        } else switchSeason(delta)
    }

    fun focusActiveSeason() {
        scope.launch {
            seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { seasonReq.requestFocus() }
        }
    }

    fun focusActiveGroup() {
        scope.launch {
            groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { groupReq.requestFocus() }
        }
    }

    /** "Go to episode": switch section and focus the episode (also in another season). */
    fun doJump() {
        val n0 = jumpText.trim().toIntOrNull() ?: return
        jumpOpen = false
        jumpText = ""
        // Number in the season, in another season or as total number across all seasons (e.g. 100)
        val tgt = EpisodeLogic.locate(item.episodes, season, n0)
        if (tgt == null) {
            Toast.makeText(ctx, Lang.tr("Es gibt nur ${item.episodes.size} Folgen"), Toast.LENGTH_SHORT).show()
            return
        }
        val (tSeason, n) = tgt
        if (tSeason != season) {
            pendingNum[0] = n
            targetIdx[0] = item.episodes.filter { it.season == tSeason }.sortedBy { it.num }.indexOfFirst { it.num == n }.coerceAtLeast(0)
            season = tSeason
            refocusEp = true
            focusTick++
            return
        }
        val idx = EpisodeLogic.indexOfNum(eps, n)
        if (idx < 0) return
        anchor = idx
        targetIdx[0] = idx
        refocusEp = true
        focusTick++
    }

    LaunchedEffect(jumpOpen) { if (jumpOpen) { delay(80); runCatching { jumpReq.requestFocus() } } }

    // Back from the player: highlight the episode that played last (list and section already stand there)
    LaunchedEffect(retSE) {
        if (retSE == null) return@LaunchedEffect
        delay(120)
        val idx = EpisodeLogic.indexOfNum(eps, retSE.second)
        if (idx >= 0 && eps.isNotEmpty()) { targetIdx[0] = idx; refocusEp = true; focusTick++ }
        else runCatching { playFocus.requestFocus() }
    }

    LaunchedEffect(season, gi, focusTick) {
        seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
        epListState.scrollToItem(0)
        if (groups.size > 1) groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
        if (refocusEp) {
            refocusEp = false
            val g = group ?: return@LaunchedEffect
            val t = targetIdx[0]
            targetIdx[0] = -1
            val idx = (if (t < 0) g.from else t).coerceIn(g.from, g.to)
            epFocusIdx[0] = idx
            val row = idx - g.from
            withFrameNanos { }
            // Put the episodes area at the end of the page and make the target episode visible in the episode list
            listState.scrollToItem(EP_PANE)
            epListState.scrollToItem((row - 1).coerceAtLeast(0))
            // Try several times: the new row may only be there after a frame or two
            focusedIdx = -1
            for (k in 0 until 10) {
                withFrameNanos { }
                if (focusedIdx == idx) break
                runCatching { epReq(idx).requestFocus() }
                delay(30)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Round 15: layout like the big picture of the home screen (image on the right, faded into the background at the left and bottom)
        // 17c: calm background image (the constant zoom cost too much computing time on the Fire TV)
        meta?.backdrop?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = .92f), 0.55f to Bg.copy(alpha = .5f), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = .5f), 0.2f to Color.Transparent, 0.55f to Bg.copy(alpha = .78f), 0.8f to Bg)))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 36.dp)) {
            item {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = dpx(if (embedded) 400 else 560))
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                if (onTopUp != null) {
                                    // Topmost control in the header ("Read more", otherwise the first button row): push the title row
                                    // down again. This is decided here itself, not via the focus search (which found
                                    // invisible targets in the shifted title row).
                                    val moreShown = showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)
                                    if (topMoreFocused || (!moreShown && topRowFocused)) { onTopUp(); return@onPreviewKeyEvent true }
                                    return@onPreviewKeyEvent false
                                }
                                scope.launch { listState.scrollToItem(0) }
                            }
                            false
                        }
                        .onFocusChanged { heroFocused = it.hasFocus }
                ) {
                    Column(Modifier.align(if (embedded && !showActions) Alignment.TopStart else Alignment.BottomStart).widthIn(max = dpx(980)).padding(bottom = dpx(18)), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        // Round 17b: overline with glow bar, huge title (design "Cinematic Remake", picture 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
                            Kicker(kindLabel(item.kind), raw = false)
                            qualityOf(item)?.let { PreviewQualityBadge(it) }
                        }
                        Text(raw = true, text = item.title, color = Tok.TextHi, fontSize = spx(84), lineHeight = spx(84), fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        HeroMetaLine(item, meta, dots = false)
                        // Movie started: small progress bar with remaining time
                        if (item.kind != Kind.SERIES && resume != null && resume.dur > 0 && !ResumeLogic.finished(resume.pos, resume.dur)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                                Box(Modifier.width(dpx(180)).height(dpx(4)).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
                                    Box(Modifier.fillMaxWidth((resume.pos.toFloat() / resume.dur).coerceIn(0f, 1f)).fillMaxHeight().background(Accent2))
                                }
                                Text("noch " + ((resume.dur - resume.pos) / 60000).coerceAtLeast(1) + " Min", color = Muted, fontSize = spx(14))
                            }
                        }
                        if (meta?.overview == null && !metaDone) {
                            // Loading skeleton instead of briefly "No description found."
                            val sk = shimmerColor()
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SkeletonBox(Modifier.fillMaxWidth(0.92f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.86f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(16.dp), sk)
                            }
                        } else Text(
                            raw = meta?.overview != null,
                            text = meta?.overview ?: if (MetaRepo.usable(item)) "Keine Beschreibung gefunden." else "Tipp: Unter Einstellungen > API-Schlüssel einen eigenen TMDB-Schlüssel eintragen, dann erscheinen hier Beschreibung, Cover und Bewertungen.",
                            color = Tok.TextMid, fontSize = spx(17), lineHeight = spx(25),
                            // Short and medium descriptions (up to about 800 characters) are shown completely, without "Read more".
                            // In the cinema preview (without buttons) it is at most 6 lines.
                            maxLines = if (descExpanded || (showActions && (meta?.overview?.length ?: 0) <= FULL_TEXT_CHARS)) Int.MAX_VALUE
                                else if (!showActions) 8 else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!descExpanded) descOverflow = it.hasVisualOverflow }
                        )
                        if (showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)) {
                            DetailPill(if (descExpanded) "Weniger" else "Mehr lesen", Modifier.onFocusChanged { topMoreFocused = it.isFocused }, trailing = if (descExpanded) AIcon.Up else AIcon.Down) { descExpanded = !descExpanded }
                        }
                        if (showActions) {
                        Spacer(Modifier.height(4.dp))
                        // Buttons in the style of the big picture, smaller (round 15). Padding: the enlarged button would otherwise be cut off.
                        // Two rows: Play/Resume/From start on top, below List, Favorite, Trailer, External. So nothing is
                        // cut off at the right edge any more. Padding: the enlarged button would otherwise be cropped.
                        Column(
                            Modifier.offset(x = (-8).dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(dpx(12))
                        ) {
                            val bh = 44
                            Row(Modifier.onFocusChanged { topRowFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                if (resume != null) {
                                    val oneSeason = seasons.size <= 1
                                    val label = when {
                                        item.kind != Kind.SERIES -> "Fortsetzen · " + (resume.pos / 60000) + " Min"
                                        resumeSE == null -> "Fortsetzen"
                                        resume.next -> "Weiter mit " + (if (oneSeason) "Folge ${resumeSE.second}" else "S${resumeSE.first} · Folge ${resumeSE.second}")
                                        else -> "Fortsetzen · " + (if (oneSeason) "Folge ${resumeSE.second}" else "S${resumeSE.first} · Folge ${resumeSE.second}")
                                    }
                                    HeroButton(label, AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        // current address from the playlist (the stored one can be outdated after a refresh)
                                        val t = WatchStore.target(ctx, item, resume)
                                        onPlay(t.second, t.first, resume.pos)
                                    }
                                    HeroButton("Von vorn", AIcon.Rewind, false, Modifier, bh) { playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) } }
                                } else {
                                    // Watched movie: "Play again" instead of "Play"
                                    val seenMovie = item.kind == Kind.MOVIE && progress[ResumeLogic.key(0, 0)]?.done == true
                                    HeroButton(if (seenMovie) "Erneut abspielen" else "Abspielen", if (seenMovie) AIcon.Rewind else AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                val inList = Library.isWatch(item.id)
                                HeroButton(if (inList) "In Meiner Liste" else "Meine Liste", if (inList) AIcon.Check else AIcon.Plus, false, Modifier, bh) { Library.toggleWatch(ctx, item.id) }
                                // Round 14: movies and series as favorites (appear under "Favorites")
                                val fav = Library.isFav(item.id)
                                HeroButton(if (fav) "Favorit" else "Zu Favoriten", if (fav) AIcon.HeartFilled else AIcon.Heart, false, Modifier, bh) { Library.toggleFav(ctx, item.id) }
                                HeroButton("Trailer", AIcon.Play, false, Modifier, bh) { openTrailer(ctx, meta?.trailerKey, item.title) }
                                HeroButton("Extern", AIcon.External, false, Modifier, bh) { playUrl?.let { openExternal(ctx, it, item.title) } }
                            }
                            // Watched by hand: movie, season, whole series (also reachable per episode: long OK / menu key on the row)
                            Row(horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                // A finished resume point would otherwise keep offering "Continue"
                                fun dropResume(keys: Collection<Long>) {
                                    if (resume == null) return
                                    val rk = resumeSE?.let { ResumeLogic.key(it.first, it.second) }
                                    if (item.kind != Kind.SERIES || rk in keys) WatchStore.remove(ctx, item.id)
                                }
                                if (item.kind == Kind.SERIES) {
                                    val seasonKeys = eps.map { ResumeLogic.key(it.season, it.num) }
                                    val seasonSeen = seasonKeys.isNotEmpty() && seasonKeys.all { progress[it]?.done == true }
                                    HeroButton(if (seasonSeen) "Staffel zurücksetzen" else "Staffel gesehen", if (seasonSeen) AIcon.Rewind else AIcon.Check, false, Modifier, bh) {
                                        EpProgress.mark(ctx, item.id, seasonKeys, !seasonSeen)
                                        if (!seasonSeen) dropResume(seasonKeys)
                                        progVer++
                                    }
                                    if (seasons.size > 1) {
                                        val allKeys = item.episodes.map { ResumeLogic.key(it.season, it.num) }
                                        val allSeen = allKeys.isNotEmpty() && allKeys.all { progress[it]?.done == true }
                                        HeroButton(if (allSeen) "Serie zurücksetzen" else "Serie gesehen", if (allSeen) AIcon.Rewind else AIcon.Check, false, Modifier, bh) {
                                            EpProgress.mark(ctx, item.id, allKeys, !allSeen)
                                            if (!allSeen) dropResume(allKeys)
                                            progVer++
                                        }
                                    }
                                } else if (item.kind == Kind.MOVIE) {
                                    val k = ResumeLogic.key(0, 0)
                                    val seen = progress[k]?.done == true
                                    HeroButton(if (seen) "Als ungesehen markieren" else "Als gesehen markieren", if (seen) AIcon.Rewind else AIcon.Check, false, Modifier, bh) {
                                        EpProgress.mark(ctx, item.id, listOf(k), !seen)
                                        if (!seen) dropResume(listOf(k))
                                        progVer++
                                    }
                                }
                                if (item.id in History.ids) {
                                    HeroButton("Aus „Zuletzt geschaut“ entfernen", AIcon.Close, false, Modifier, bh) { History.remove(ctx, item.id) }
                                }
                            }
                        }
                        if (item.kind == Kind.MOVIE && movieAlts.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Choose the source explicitly (takes precedence over quality) or automatically to suit the device
                            val so = listOf("Automatisch") + movieVars.map { it.label }
                            DropdownPill("Quelle", prefLabel ?: "Automatisch", so, so.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                prefLabel = if (it == 0) null else so[it]; save()
                            }
                            if (movieQs.size > 1 && prefLabel == null) {
                                val qo = listOf("Automatisch (passend zum Gerät)") + movieQs
                                DropdownPill("Qualität", prefQ ?: "Automatisch", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            Text("${movieAlts.size} Quellen · bei Störung wird automatisch gewechselt", color = Muted, fontSize = 13.sp)
                        }
                        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) IconLabel(
                            AIcon.Warning,
                            if (movieAlts.size > 1) "Diese Quelle war zuletzt nicht erreichbar – es wird eine andere genommen."
                            else "Diese Quelle war zuletzt nicht erreichbar.",
                            DS.Gold, 14.sp, iconSize = 16.dp, maxLines = 3
                        )
                        if (multi) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (qualities.size > 1) {
                                val qo = listOf("Automatisch (passend zum Gerät)") + qualities
                                DropdownPill("Qualität", prefQ ?: "Automatisch", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            if (langs.size > 1) {
                                val lo = listOf("Automatisch") + langs
                                DropdownPill("Sprache", prefLang ?: "Automatisch", lo, lo.indexOf(prefLang).coerceAtLeast(0), active = prefLang != null) {
                                    prefLang = if (it == 0) null else lo[it]; save()
                                }
                            }
                            if (labels.size > 1) {
                                val vo = listOf("Automatisch") + labels
                                DropdownPill("Quelle", prefLabel ?: "Automatisch", vo, vo.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                    prefLabel = if (it == 0) null else vo[it]; save()
                                }
                            }
                            Text("gilt für alle Folgen", color = Muted, fontSize = 13.sp)
                        }
                        credits?.directors?.takeIf { it.isNotEmpty() }?.let { ds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Regie", color = Muted, fontSize = 14.sp)
                                ds.take(3).forEach { d -> DetailPill(d.name) { onPerson(PersonRef(d.id, d.name, d.photo, item.id)) } }
                            }
                        }
                        }
                    }
                }
            }
            if (item.kind == Kind.SERIES) item(key = "eppane") {
                // Episodes area (round 16): seasons on the left with the shown season marked, episodes on the right.
                // Right in the episode list = next season, Left = to the season selection.
                Row(
                    Modifier.fillMaxWidth().height(paneH).padding(top = dpx(12))
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(EP_PANE) } }
                        .focusProperties { enterTo { seasonReq } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(dpx(28))
                ) {
                    LazyColumn(
                        Modifier.width(dpx(250)).fillMaxHeight(),
                        state = seasonRowState,
                        verticalArrangement = Arrangement.spacedBy(dpx(6)),
                        contentPadding = PaddingValues(vertical = dpx(8))
                    ) {
                        items(seasons, key = { it }) { sn ->
                            val cnt = remember(item.id, sn) { item.episodes.count { it.season == sn } }
                            SeasonEntry(
                                sn, cnt, sn == season,
                                if (sn == season) Modifier.focusRequester(seasonReq) else Modifier,
                                onRight = {
                                    // Right or OK: show the season and jump to the first episode (same season: to the last position)
                                    if (sn != season) { enterAtEnd[0] = false; startFirst[0] = true; season = sn; targetIdx[0] = -1 }
                                    else targetIdx[0] = epFocusIdx[0]
                                    refocusEp = true
                                    focusTick++
                                }
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.padding(bottom = dpx(6)),
                            horizontalArrangement = Arrangement.spacedBy(dpx(16)), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (epLoading) "lade Infos …" else "Rechts = nächste Staffel  ·  Links = Staffeln",
                                color = Muted, fontSize = spx(14)
                            )
                            if (item.episodes.size > EpisodeLogic.LONG) DetailPill("Zu Folge springen", icon = AIcon.Down) { jumpOpen = !jumpOpen }
                        }
                        // Long season: sections (TMDB seasons or blocks of 50 episodes) instead of an endless list
                        if (groups.size > 1) {
                            LazyRow(
                                Modifier.offset(x = (-10).dp),
                                state = groupRowState,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(groups) { k, g ->
                                    DetailPill(
                                        EpisodeLogic.label(g),
                                        if (k == gi) Modifier.focusRequester(groupReq) else Modifier,
                                        light = k == gi
                                    ) { anchor = g.from; epFocusIdx[0] = g.from }
                                }
                            }
                        }
                        if (jumpOpen) Row(
                            Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Field(
                                jumpText, { v -> jumpText = v.filter { it.isDigit() }.take(4) }, "Folgennummer",
                                Modifier.width(280.dp), inner = Modifier.focusRequester(jumpReq), number = true, onDone = { doJump() }
                            )
                            DetailPill("Springen", light = true) { doJump() }
                            DetailPill("Abbrechen") { jumpOpen = false; jumpText = "" }
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = epListState, contentPadding = PaddingValues(bottom = dpx(40))) {
                            itemsIndexed(shown, key = { j0, ep -> "e${ep.season}_${ep.num}_$j0" }) { j, ep ->
                                val i = (group?.from ?: 0) + j
                                val em = metaOf(ep)
                                val v = pick(ep)
                                EpisodeRow(
                                    ep, em, v, progress[ResumeLogic.key(ep.season, ep.num)],
                                    Modifier
                                        .focusRequester(epReq(i))
                                        .onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) false
                                            else when (ev.key) {
                                                // Left: to the season selection; Right: next section, at the end of the season the next season
                                                Key.DirectionLeft -> { if (ev.nativeKeyEvent.repeatCount == 0) { if (groups.size > 1 && gi > 0) switchGroup(-1) else focusActiveSeason() }; true }
                                                Key.DirectionRight -> { if (ev.nativeKeyEvent.repeatCount == 0) switchGroup(1); true }
                                                Key.DirectionUp -> if (j == 0 && groups.size > 1) { focusActiveGroup(); true } else false
                                                else -> false
                                            }
                                        },
                                    onFocus = { epFocusIdx[0] = i; focusedIdx = i },
                                    onToggleSeen = {
                                        val k = ResumeLogic.key(ep.season, ep.num)
                                        val seen = progress[k]?.done == true
                                        EpProgress.mark(ctx, item.id, listOf(k), !seen)
                                        if (!seen && resumeSE?.let { ResumeLogic.key(it.first, it.second) } == k) WatchStore.remove(ctx, item.id)
                                        progVer++
                                    }
                                ) { onPlay(v.url, item.title + " S" + ep.season + "E" + ep.num, 0L) }
                            }
                        }
                    }
                }
            }
            credits?.let { c ->
                if (c.cast.isNotEmpty()) item(key = "cast") { CastRow(c.cast, castState, if (memKey?.startsWith("c:") == true) memKey.drop(2).toIntOrNull() else null, restoreReq) { p -> DetailFocus.last[item.id] = "c:" + p.id; onPerson(PersonRef(p.id, p.name, p.photo, item.id)) } }
            }
            if (credits == null && (meta?.tmdbId ?: 0) > 0 && CreditsRepo.lastError != null) item(key = "cerr") {
                Text("Besetzung nicht verfügbar: ${CreditsRepo.lastError}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
            }
            if (similar.isNotEmpty()) item(key = "similar") { SimilarRow("Ähnliche Titel in deiner Playlist", similar, simState, if (memKey?.startsWith("s:") == true) memKey.drop(2) else null, restoreReq) { i -> DetailFocus.last[item.id] = "s:" + i.id; onOpenItem(i) } }
            if (MetaRepo.enabled()) item {
                Text("Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, em: EpMeta?, v: Variant, prog: ResumeLogic.EpPos?, modifier: Modifier, onFocus: () -> Unit, onToggleSeen: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(16))
    // Round 17b: the selected episode floats forward (glass, shadow, slightly larger) instead of a white frame; the others stay calm
    val sc by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.02f) else 1f, label = "ep")
    Row(
        modifier.fillMaxWidth().padding(vertical = dpx(6))
            .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .then(if (f) Modifier.shadow(dpx(24), shape, ambientColor = Tok.Lift, spotColor = Tok.Lift) else Modifier)
            .clip(shape).background(if (f) Color(0x1FFFFFFF) else Color(0x08FFFFFF))
            .border(1.dp, if (f) Color(0x33FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .favKeys(onToggleSeen)
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        horizontalArrangement = Arrangement.spacedBy(dpx(22)), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(dpx(300)).height(dpx(169)).clip(RoundedCornerShape(dpx(12)))
                .background(Brush.linearGradient(listOf(lerp(Surf2, Accent1, 0.22f), Surf2))),
            contentAlignment = Alignment.Center
        ) {
            if (em?.still != null) AsyncImage(em.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("E${ep.num}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            // Selected: round play button in the middle
            if (f) Box(Modifier.align(Alignment.Center).size(dpx(58)).clip(RoundedCornerShape(50)).background(Color(0xEBF4F6FB)), contentAlignment = Alignment.Center) {
                AppIcon(AIcon.Play, Color(0xFF05070D), size = dpx(26))
            }
            // Progress: bar at the bottom, watched = check mark top right
            if (prog != null && prog.done) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Badge("Gesehen", DS.Scrim, Accent2, AIcon.Check) }
            else if (prog != null && prog.fraction > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.fraction).fillMaxHeight().background(Accent2))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(raw = true, text = "${ep.num}. ${em?.name ?: ep.title}", color = if (f) Tok.TextHi else Tok.TextMid, fontSize = spx(if (f) 26 else 24), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                v.quality?.let { QualityBadge(it) }
                em?.imdb?.let { Badge("IMDb $it", Color(0xFFF5C518), Color.Black) }
                if (em?.imdb == null) em?.rating?.let { RatingBadge(it, Color(0x26FFFFFF)) }
                em?.runtime?.let { Badge("$it Min") }
            }
            em?.air?.let { Text(raw = true, text = it, color = Muted, fontSize = spx(16)) }
            em?.overview?.let { Text(raw = true, text = it, color = if (f) Tok.TextMid else Tok.TextLow, fontSize = spx(18), lineHeight = spx(25), maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (f) Text(if (prog?.done == true) "OK lange drücken = als ungesehen markieren" else "OK lange drücken = als gesehen markieren", color = Muted, fontSize = spx(14))
        }
    }
}

/** Season list entry: bar at the left and episode count for the shown season, light frame on focus. */
@Composable
private fun SeasonEntry(season: Int, count: Int, selected: Boolean, modifier: Modifier, onRight: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Row(
        modifier.fillMaxWidth().clip(shape)
            // Round 17b: shown season with a green shimmer, selected one as light glass (no white frame)
            .background(
                if (f) Brush.horizontalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))
                else if (selected) Brush.horizontalGradient(listOf(Accent1.copy(alpha = 0.18f), Color.Transparent))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { if (ev.nativeKeyEvent.repeatCount == 0) onRight(); true } else false
            }
            .clickable(onClick = onRight)
            .padding(horizontal = dpx(14), vertical = dpx(12)),
        horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(dpx(5)).height(dpx(if (selected) 46 else 24))
                .then(if (selected) Modifier.shadow(dpx(10), RoundedCornerShape(50), ambientColor = Accent1, spotColor = Accent1) else Modifier)
                .clip(RoundedCornerShape(50)).background(if (selected) Accent1 else Color.Transparent)
        )
        Column {
            Text("Staffel $season", color = if (selected || f) Tok.TextHi else Tok.TextLow, fontSize = spx(26), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            if (selected) Text(text = if (count == 1) "1 Folge" else "$count Folgen", color = Muted, fontSize = spx(15))
        }
    }
}
