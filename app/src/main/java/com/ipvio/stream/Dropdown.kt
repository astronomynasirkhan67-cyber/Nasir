package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Composable
fun FilterPill(text: String, active: Boolean, modifier: Modifier = Modifier, icon: FIcon? = null, caret: Boolean = false, aicon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "fp")
    val shape = RoundedCornerShape(50)
    // Consistent with the buttons of the detail page (round 15): slightly transparent with a fine border, selection filled light
    val c = ButtonColors(
        if (f) DS.FocusFill else if (active) Color(0x4DFFFFFF) else Color(0x33FFFFFF),
        if (f) DS.FocusInk else Color.White
    )
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }
            .height(dpx(42))
            .clip(shape)
            .background(c.bg)
            .border(1.dp, if (f) DS.FocusFill else if (active) Color(0xB3FFFFFF) else Color(0x40FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(18)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) FilterIcon(icon, c.text, 20.dp)
            if (aicon != null) AppIcon(aicon, c.text, size = 17.dp)
            if (text.isNotEmpty()) Text(text, color = c.text, fontSize = spx(14.3f), fontWeight = FontWeight.SemiBold, maxLines = 1, raw = raw)
            if (caret) AppIcon(AIcon.Caret, c.text, size = 16.dp)
        }
    }
}

private val belowAnchor = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize
    ): IntOffset {
        val x = anchorBounds.left.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
fun DropdownPill(
    label: String, value: String, options: List<String>, selected: Int,
    active: Boolean = false, modifier: Modifier = Modifier, icon: FIcon? = null, onPick: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // With icon: only icon + value (the setting's name is in the opened menu or recognizable from the icon)
        FilterPill(if (icon != null) value else "$label: $value", active, modifier, icon, caret = true) { open = true }
        if (open) OptionPopup(options, selected, { open = false }) { onPick(it); open = false }
    }
}

@Composable
fun OptionPopup(options: List<String>, selected: Int, onDismiss: () -> Unit, icons: List<AIcon?>? = null, onPick: (Int) -> Unit) {
    Popup(popupPositionProvider = belowAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val fr = remember { FocusRequester() }
        val state = rememberLazyListState(maxOf(selected - 2, 0))
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(300.dp).heightIn(max = 340.dp).clip(shape)
                .background(Surf.copy(alpha = 0.97f)).border(1.dp, DS.Overlay3, shape).padding(8.dp)
        ) {
            LazyColumn(state = state) {
                itemsIndexed(options) { i, o ->
                    OptionRow(o, i == selected, if (i == maxOf(selected, 0)) Modifier.focusRequester(fr) else Modifier, icons?.getOrNull(i)) { onPick(i) }
                }
            }
        }
    }
}

@Composable
fun OptionRow(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) DS.Overlay4 else Color.Transparent)
            .border(DS.Border, if (f) Accent2 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(26.dp)) {
            if (selected) AppIcon(AIcon.Check, Accent2, size = 18.dp)
            else if (icon != null) AppIcon(icon, if (icon == AIcon.Star) DS.Gold else Muted, size = 18.dp)
        }
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** Round plus/minus button. The sign is drawn instead of set as text so it sits exactly centered. */
@Composable
fun StepButton(plus: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.1f) else 1f, label = "step")
    val colors = buttonColors(f, primary = false)
    Box(
        modifier.size(38.dp).graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(CircleShape)
            .background(colors.bg)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val ink = colors.text
        Canvas(Modifier.size(16.dp)) {
            val w = 2.4.dp.toPx()
            val c = size.minDimension / 2f
            drawLine(ink, Offset(0f, c), Offset(size.width, c), strokeWidth = w, cap = StrokeCap.Round)
            if (plus) drawLine(ink, Offset(c, 0f), Offset(c, size.height), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(150.dp))
        StepButton(false, modifier) { onMinus() }
        Text(value, color = Accent2, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 40.dp))
        StepButton(true) { onPlus() }
    }
}

