package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A block in the block builder (row or widget). [fixed] = always stays in first place, can only be switched on and off. */
class LayoutBlock(val id: String, val title: String, val sub: String, val fixed: Boolean = false)

/**
 * Block builder for the home screen and Boomer mode: every row and every widget is a block that can be switched on and off and moved to a
 * different place. Stored per profile under [prefName].
 */
open class BlockLayout(private val prefName: String, val blocks: List<LayoutBlock>, private val defaultHidden: Set<String>) {
    private val fixedId: String? = blocks.firstOrNull { it.fixed }?.id

    var order by mutableStateOf(blocks.map { it.id })
        private set
    var hidden by mutableStateOf(defaultHidden)
        private set

    private fun prefs(c: Context) = Profiles.sp(c, prefName)

    fun block(id: String): LayoutBlock? = blocks.firstOrNull { it.id == id }
    fun shown(id: String): Boolean = id !in hidden

    /** Remove unknown entries, append missing ones (new blocks) at the end, the fixed block always first. */
    private fun normalize(l: List<String>): List<String> {
        val out = ArrayList<String>()
        for (id in l) if (id != fixedId && block(id) != null && id !in out) out.add(id)
        for (b in blocks) if (b.id != fixedId && b.id !in out) out.add(b.id)
        if (fixedId != null) out.add(0, fixedId)
        return out
    }

    fun init(c: Context) {
        val p = prefs(c)
        order = normalize((p.getString("order", "") ?: "").split(",").filter { it.isNotBlank() })
        hidden = if (p.contains("hidden")) (p.getString("hidden", "") ?: "").split(",").filter { it.isNotBlank() }.toSet() else defaultHidden
        // The fixed block (the big slideshow) can never be hidden, even if an older setting says so
        if (fixedId != null) hidden = hidden - fixedId
    }

    private fun save(c: Context) {
        prefs(c).edit().putString("order", order.joinToString(",")).putString("hidden", hidden.joinToString(",")).apply()
    }

    fun setShown(c: Context, id: String, on: Boolean) { if (id == fixedId) return; hidden = if (on) hidden - id else hidden + id; save(c) }

    fun move(c: Context, id: String, delta: Int) {
        val i = order.indexOf(id)
        val j = i + delta
        val min = if (fixedId != null) 1 else 0
        if (i < min || j < min || j >= order.size) return
        val l = order.toMutableList()
        l.removeAt(i); l.add(j, id)
        order = l; save(c)
    }

    fun reset(c: Context) { order = blocks.map { it.id }; hidden = defaultHidden; save(c) }
}

private val HOME_BLOCKS = listOf(
    LayoutBlock("hero", "Große Slideshow", "Großes Bild oben mit aktuellen Filmen und Serien", fixed = true),
    LayoutBlock("tiles", "Kacheln", "Live TV, Filme, Serien, Favoriten und Meine Liste"),
    LayoutBlock("resume", "Weiterschauen", "Dort weitermachen, wo du aufgehört hast"),
    LayoutBlock("favch", "Meine Sender", "Deine Live-Favoriten als Reihe"),
    LayoutBlock("chnow", "Live TV gerade", "Was jetzt auf deinen Sendern läuft"),
    LayoutBlock("trend", "Top 10 heute", "Die beliebtesten Titel des Tages"),
    LayoutBlock("because", "Weil du etwas geschaut hast", "Ähnliche Titel zu deinem letzten Film oder deiner letzten Serie"),
    LayoutBlock("wl", "Meine Liste", "Titel, die du dir gemerkt hast"),
    LayoutBlock("hist", "Zuletzt geschaut", "Alles, was du zuletzt gestartet hast"),
    LayoutBlock("newm", "Neue Filme", "Zuletzt hinzugefügte Filme"),
    LayoutBlock("topm", "Beste Filme", "Die am besten bewerteten Filme"),
    LayoutBlock("news", "Neue Serien", "Zuletzt hinzugefügte Serien"),
    LayoutBlock("tops", "Beste Serien", "Die am besten bewerteten Serien"),
    LayoutBlock("sg", "Serien nach Genre", "Reihen für die häufigsten Serien-Genres"),
    LayoutBlock("mg", "Filme nach Genre", "Reihen für die häufigsten Film-Genres")
)

/** Home screen: new blocks are hidden by default so it looks as before until someone switches them on. */
object HomeLayout : BlockLayout("ipvio_homelayout", HOME_BLOCKS, setOf("favch", "hist"))

private val BOOMER_BLOCKS = listOf(
    LayoutBlock("clock", "Große Uhr", "Uhrzeit und Datum, gut lesbar"),
    LayoutBlock("ch", "Meine Sender", "Die Sender, die du ausgewählt hast (sonst die Live-Favoriten)"),
    LayoutBlock("resume", "Weiterschauen", "Filme und Serien dort fortsetzen, wo es aufgehört hat"),
    LayoutBlock("vod", "Meine Filme und Serien", "Die Filme und Serien, die du ausgewählt hast"),
    LayoutBlock("wl", "Meine Liste", "Titel aus der Merkliste"),
    LayoutBlock("hist", "Zuletzt geschaut", "Was zuletzt gestartet wurde"),
    LayoutBlock("newm", "Neue Filme", "Zuletzt hinzugefügte Filme"),
    LayoutBlock("news", "Neue Serien", "Zuletzt hinzugefügte Serien")
)

/** Boomer mode: which rows the simple home screen shows and in which order. */
object BoomerLayout : BlockLayout("ipvio_boomerlayout", BOOMER_BLOCKS, setOf("wl", "hist", "newm", "news"))

/** Settings page for a block builder: switch each block on/off and move with "Up"/"Down". */
@Composable
internal fun BlockLayoutPage(ctx: Context, layout: BlockLayout, intro: String) {
    Hint(intro)
    // Live overview: the rows exactly as they will appear, top to bottom (hidden ones are left out)
    val shownNow = layout.order.filter { layout.shown(it) }.mapNotNull { layout.block(it)?.title }
    Column(
        Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("So sieht die Reihenfolge aus", color = Muted, fontSize = 13.sp)
        if (shownNow.isEmpty()) Text("Nichts gezeigt", color = Muted, fontSize = 15.sp)
        shownNow.forEachIndexed { i, t -> Text(raw = true, text = "${i + 1}.  " + Lang.tr(t), color = Color.White, fontSize = 15.sp) }
    }
    layout.order.forEach { id ->
        val b = layout.block(id) ?: return@forEach
        key(id) {
            val on = layout.shown(id)
            Row(
                Modifier.fillMaxWidth().widthIn(max = 900.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(b.title, color = if (on) Color.White else Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(b.sub, color = Muted, fontSize = 13.sp)
                }
                if (b.fixed) Text("Immer oben", color = Muted, fontSize = 13.sp)
                else SmallPill(if (on) "Gezeigt" else "Versteckt", icon = if (on) AIcon.Eye else AIcon.EyeOff) { layout.setShown(ctx, id, !on) }
                if (!b.fixed) {
                    SmallPill("Hoch", icon = AIcon.Up) { layout.move(ctx, id, -1) }
                    SmallPill("Runter", icon = AIcon.Down) { layout.move(ctx, id, 1) }
                }
            }
        }
    }
    ConfirmPill("Auf Standard zurücksetzen") { layout.reset(ctx) }
}

/** Settings page „Startseite anpassen“. */
@Composable
internal fun HomeLayoutPage(ctx: Context) {
    PillButton("Direkt auf der Startseite bearbeiten", light = true, icon = AIcon.Sliders) { HomeEdit.active = true; AppNav.homeSignal++ }
    Hint("Öffnet die echte Startseite mit deinen Titeln. Über jeder Reihe erscheint eine Leiste zum Verschieben und Ein- oder Ausblenden.")
    BlockLayoutPage(
        ctx, HomeLayout,
        "Oder hier in der Liste: Schalte Reihen und Widgets ein oder aus und schiebe sie mit „Hoch“ und „Runter“ an die Stelle, die du möchtest. Die Änderung gilt sofort."
    )
}

/** "Clock and date" widget for the home screen. */
@Composable
fun ClockWidget(
    modifier: Modifier = Modifier.fillMaxWidth().padding(start = RowPadX + dpx(2), end = RowPadX, top = dpx(18)),
    timeSp: Int = 64, dateSp: Int = 24
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L - now % 60_000L + 50L)
        }
    }
    val h24 = when (General.timeFmt) { 1 -> true; 2 -> false; else -> General.sys24 }
    val tf = remember(h24) {
        java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", java.util.Locale.getDefault()).also { it.timeZone = General.zone() }
    }
    val df = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, java.util.Locale.getDefault()).also { it.timeZone = General.zone() } }
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(24))
    ) {
        Text(tf.format(java.util.Date(now)), color = Color.White, fontSize = spx(timeSp), fontWeight = FontWeight.ExtraBold, maxLines = 1, raw = true)
        Text(df.format(java.util.Date(now)), color = Tok.TextLow, fontSize = spx(dateSp), maxLines = 1, raw = true, modifier = Modifier.padding(bottom = dpx(10)))
    }
}


/** Edit mode of the real home screen: every row gets a small bar to move it or switch it on and off, with the real titles below. */
object HomeEdit {
    var active by mutableStateOf(false)
}

/** Bar above a block of the home screen in edit mode. */
@Composable
internal fun HomeEditBar(ctx: Context, id: String, modifier: Modifier = Modifier) {
    val b = HomeLayout.block(id) ?: return
    val on = HomeLayout.shown(id)
    Row(
        modifier.fillMaxWidth().padding(horizontal = DS.PageX, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) Color(0x33FFFFFF) else Color(0x22FF8A4C)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(b.title, color = if (on) Color.White else Muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (!on) Text("Versteckt", color = Color(0xFFFFB86B), fontSize = 14.sp)
        if (b.fixed) Text("Immer oben", color = Muted, fontSize = 13.sp)
        else {
            SmallPill("Hoch", icon = AIcon.Up) { HomeLayout.move(ctx, id, -1) }
            SmallPill("Runter", icon = AIcon.Down) { HomeLayout.move(ctx, id, 1) }
            SmallPill(if (on) "Ausblenden" else "Einblenden", icon = if (on) AIcon.EyeOff else AIcon.Eye) { HomeLayout.setShown(ctx, id, !on) }
        }
    }
}
