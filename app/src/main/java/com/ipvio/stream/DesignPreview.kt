package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live preview of the design page. It sits fixed at the top right (placed by HomeScreen over the settings)
 * so you do not have to scroll up and down when changing.
 * Highlighted but not yet chosen colors (focus) are shown only in the preview – the app itself changes only with OK.
 */
object DesignPreview {
    val WIDTH = 330.dp
    /** Design page is open. */
    var visible by mutableStateOf(false)
    /** Marked on trial (null = current setting). */
    var accent by mutableStateOf<Int?>(null)
    var bg by mutableStateOf<Int?>(null)
    var custom by mutableStateOf<Int?>(null)
    var custom2 by mutableStateOf<Int?>(null)
    /** Round 14: also animations, display size, font and font size as a preview (focus, without OK). */
    var motion by mutableStateOf<Int?>(null)
    var scale by mutableStateOf<Int?>(null)
    var font by mutableStateOf<Int?>(null)
    var textScale by mutableStateOf<Int?>(null)
    /** Name of the highlighted choice ("Ocean"), for the heading. */
    var label by mutableStateOf<String?>(null)

    fun clear() { accent = null; bg = null; custom = null; custom2 = null; motion = null; scale = null; font = null; textScale = null; label = null }
}

@Composable
fun DesignPreviewPanel(modifier: Modifier) {
    val probe = DesignPreview.accent != null || DesignPreview.bg != null || DesignPreview.custom != null || DesignPreview.custom2 != null ||
        DesignPreview.motion != null || DesignPreview.scale != null || DesignPreview.font != null || DesignPreview.textScale != null
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    // Sizes relative to the current setting (the app is already scaled with it)
    val uiRel = ThemeMath.uiFactor(DesignPreview.scale ?: Look.uiScale, cfg.screenWidthDp) / ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp)
    val txtRel = General.textFactor(DesignPreview.textScale ?: General.textScaleIdx) / General.textFactor()
    val fam = General.fontFamily(DesignPreview.font ?: General.fontIdx)
    val motion = DesignPreview.motion ?: Look.motion
    // Highlighted card "breathes" as much as the chosen animation level enlarges on focus
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pv")
    val pv by pulse.animateFloat(1f, ThemeMath.focusScale(1.08f, motion),
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pvs")
    val p = Look.palette(
        DesignPreview.accent ?: Look.accentIdx,
        DesignPreview.custom ?: Look.custom,
        DesignPreview.custom2 ?: Look.custom2,
        DesignPreview.bg ?: Look.bgIdx
    )
    val outer = RoundedCornerShape(DS.RadiusL)
    Column(
        modifier.width(DesignPreview.WIDTH).clip(outer).background(Color(0xF2101218))
            .border(1.dp, if (probe) p.high else DS.Overlay3, outer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIcon(AIcon.Eye, if (probe) p.high else Muted, size = 16.dp)
            Text(if (probe) "Vorschau" else "Aktuelles Design", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            DesignPreview.label?.takeIf { probe }?.let { Text(it, color = p.high, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        // Small screen in the chosen design
        val screen = RoundedCornerShape(DS.RadiusM)
        Row(Modifier.fillMaxWidth().height(196.dp).clip(screen).background(p.bg)) {
            // Sidebar
            Column(
                Modifier.width(34.dp).fillMaxHeight().background(lerp(p.bg, Color.Black, 0.25f)).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(p.surf2), contentAlignment = Alignment.Center) {
                    AppIcon(AIcon.Play, p.high, size = 11.dp)
                }
                repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0x55FFFFFF))) }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Neu", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.clip(RoundedCornerShape(50)).background(p.fill).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("4K", color = p.onFill, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // highlighted card (frame in color 2) and two normal ones
                    val card = RoundedCornerShape(8.dp)
                    Box(Modifier.width(66.dp * uiRel).height(92.dp * uiRel).graphicsLayer { scaleX = pv; scaleY = pv }.clip(card).background(Brush.verticalGradient(listOf(p.surf2, p.surf))).border(2.dp, p.high, card)) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight().background(p.high))
                        }
                    }
                    repeat(2) { Box(Modifier.width(60.dp * uiRel).height(84.dp * uiRel).clip(card).background(p.surf2)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(p.fill).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AppIcon(AIcon.Play, p.onFill, size = 11.dp)
                        Text("Abspielen", color = p.onFill, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(p.surf).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text("Meine Liste", color = Color.White, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hervorhebung", color = p.high, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            Box(Modifier.size(16.dp).clip(CircleShape).background(p.fill))
            Box(Modifier.size(16.dp).clip(CircleShape).background(p.high))
            Box(Modifier.size(16.dp).clip(CircleShape).background(p.surf2).border(1.dp, Color(0x33FFFFFF), CircleShape))
        }
        // Font: type and size of the choice
        Text(raw = true, text = "Aa  " + Lang.tr("Beispieltext") + " 123", color = Color.White, fontSize = 18.sp * txtRel * uiRel,
            fontWeight = FontWeight.SemiBold, fontFamily = fam, maxLines = 1)
        Text(
            if (probe) "Nur Vorschau – OK übernimmt das Design." else "Markiere eine Einstellung, um sie hier zu sehen.",
            color = Muted, fontSize = 13.sp
        )
    }
}
