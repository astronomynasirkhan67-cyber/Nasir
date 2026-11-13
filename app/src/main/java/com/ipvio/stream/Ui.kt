package com.ipvio.stream

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colors come from the design state (Look.kt): when the theme is switched the whole app changes immediately.
val Bg: Color get() = Look.bg
val Surf: Color get() = Look.surf
val Surf2: Color get() = Look.surf2
val Accent1: Color get() = Look.accent1
val Accent2: Color get() = Look.accent2
/** Text color on Accent1 surfaces (dark for very light colors such as IPVIO green). */
val OnAccent: Color get() = Look.onAccent
/** Metadata gray (round 17 lighter: contrast ≥ 6:1 on all surfaces, readable even on glass over images). */
val Muted = Color(0xFFA0A6B8)

/**
 * Color tokens (round 17). Neutral text levels apply in every design; surfaces derive from the chosen design (Look).
 * Contrast on IPVIO Navy #0B0F19 (WCAG): TextHi 17:1, TextMid 12:1, TextLow/Muted 7.9:1; on the lightest surface always ≥ 5.9:1.
 * Focus: buttons white with dark text, cards white frame with shadow.
 */
object Tok {
    val TextHi = Color(0xFFF4F6FB)
    val TextMid = Color(0xFFD3D7E2)
    val TextLow = Color(0xFFA0A6B8)
    /** Card/row at rest, marked row */
    val Card: Color get() = lerp(Look.surf, Color.White, 0.03f)
    val CardFocus: Color get() = lerp(Look.surf2, Color.White, 0.08f)
    /** Glass over images (bars, pills), strong for cards with a lot of text */
    val Glass: Color get() = Look.bg.copy(alpha = 0.8f)
    val GlassStrong: Color get() = Look.bg.copy(alpha = 0.94f)
    val Line = Color(0x26FFFFFF)
    /** Shadow for raised (selected) cards */
    val Lift = Color(0xE6000000)
}

/**
 * Design system: central measures and colors. New UIs use only these values.
 *
 * Focus (remote control), the same everywhere:
 *  - Buttons and pills: white surface, dark text, slightly larger ([ButtonColors]).
 *  - Cards and posters: frame in the highlight color, larger, soft glow ([focusGlow]).
 *  - List and settings rows: lighter surface plus frame in the highlight color ([RowFocus]).
 */
object DS {
    // Spacing (dp)
    val SpaceXs = 4.dp
    /** Side margin of the content (TV-safe area). */
    val PageX = 48.dp
    /** Top/bottom margin: at least 27 dp (Android TV guideline against overscan). */
    val PageY = 28.dp
    /** Width of the sidebar (always with texts as in the design: 230 of 1658 design pixels). */
    /** Space of the collapsed sidebar (round 14: icons only, expands over the content when entered). */
    val SidebarW: Dp get() = if (SidebarPrefs.labels) SidebarOpenW else SidebarCollapsedW
    /** Collapsed sidebar (icons only). */
    val SidebarCollapsedW: Dp get() = dpx(92)
    /** Width of the expanded sidebar. */
    val SidebarOpenW: Dp get() = dpx(250)

    // Corners
    val RadiusXs = 8.dp
    val RadiusS = 12.dp
    val RadiusM = 16.dp
    val RadiusL = 20.dp
    val RadiusXl = 24.dp

    // Border
    val Border = 2.dp
    val FocusBorder = 3.dp

    // Font sizes
    val TextCaption = 12.sp
    val TextSmall = 14.sp
    val TextBody = 16.sp
    val TextLead = 18.sp

    // Layers above the background (white with opacity)
    val Overlay1 = Color(0x14FFFFFF)
    val Overlay2 = Color(0x22FFFFFF)
    val Overlay3 = Color(0x33FFFFFF)
    val Overlay4 = Color(0x40FFFFFF)
    val Hairline = Color(0x1AFFFFFF)
    val Scrim = Color(0xAA000000)

    // Text and signal colors
    val TextSoft = Color(0xFFD7DAE5)
    val Danger = Color(0xFFFF6B6B)
    val Gold = Color(0xFFFFD166)

    // Focus for buttons
    val FocusFill = Color.White
    val FocusInk = Color(0xFF0B0F19)
}

/** Surface of a row with focus (derived from the theme, fits every background). */
val RowFocus: Color get() = lerp(Look.surf, Color.White, 0.08f)

/** Colors of a button: focused white, otherwise accent (primary) or semi-transparent (secondary). */
class ButtonColors(val bg: Color, val text: Color)

fun buttonColors(focused: Boolean, primary: Boolean): ButtonColors = when {
    focused -> ButtonColors(DS.FocusFill, DS.FocusInk)
    // Round 14: main button no longer fully colored (looked selected) – only slightly tinted; only the white surface marks selection
    primary -> ButtonColors(lerp(DS.Overlay3, Look.accent1, 0.32f), Color.White)
    else -> ButtonColors(DS.Overlay3, Color.White)
}

/** Soft glow in the highlight color around focused cards (colored on Android 9+, neutral below). */
fun Modifier.focusGlow(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    // Round 14 (user's wish): no colored glow around selected fields any more, only a subtle dark shadow
    if (!focused) this else this.shadow(10.dp, shape, clip = false, ambientColor = Tok.Lift, spotColor = Tok.Lift)

@Composable
fun FocusButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "btn")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (f) SolidColor(DS.FocusFill) else if (primary) SolidColor(lerp(DS.Overlay3, Look.accent1, 0.32f)) else SolidColor(DS.Overlay3))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 9.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (f) DS.FocusInk else Color.White, fontSize = if (compact) 16.sp else DS.TextLead, fontWeight = FontWeight.SemiBold) }
}

/**
 * Text field for remote-control use: focus only highlights the field, OK (or a tap) starts editing and opens the
 * keyboard. While editing, Up/Down leave the field; Done closes the keyboard and returns to the highlighted state.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier, inner: Modifier = Modifier, password: Boolean = false,
    /** Digits only (number keyboard), e.g. "go to episode". */
    number: Boolean = false,
    /** "Done" on the on-screen keyboard. */
    onDone: (() -> Unit)? = null,
    /** Start in edit mode right away (keyboard opens without pressing OK first). */
    autoOpen: Boolean = false
) {
    var f by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(autoOpen) }
    var hadFocus by remember { mutableStateOf(false) }
    var returnToBox by remember { mutableStateOf(false) }
    val boxReq = remember { FocusRequester() }
    val textReq = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { textReq.requestFocus() }
            kb?.show()
        } else if (returnToBox) {
            returnToBox = false
            runCatching { boxReq.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth().height(62.dp)
            .clip(shape).background(Surf)
            .border(DS.Border, if (f) Accent2 else Surf2, shape)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Focus target while not editing; OK / tap starts editing
        Box(
            inner.fillMaxSize()
                .focusRequester(boxReq)
                .onFocusChanged { f = it.isFocused || it.hasFocus }
                .clickable { editing = true }
        )
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, enabled = editing,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Accent2),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onDone?.invoke()
                kb?.hide()
                returnToBox = true
                editing = false
            }),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(textReq)
                .onFocusChanged {
                    if (it.isFocused) { hadFocus = true; f = true }
                    else if (hadFocus) { hadFocus = false; if (editing) editing = false }
                }
                .onPreviewKeyEvent { e ->
                    val up = e.key == Key.DirectionUp
                    if (e.type == KeyEventType.KeyDown && (up || e.key == Key.DirectionDown)) {
                        kb?.hide()
                        fm.moveFocus(if (up) FocusDirection.Up else FocusDirection.Down)
                        true
                    } else false
                }
        )
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 20.sp)
    }
}

/** Plain loading circle (replaces the one from material3). */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spinA")
    Canvas(modifier.rotate(if (Look.motion > 0) a else 0f)) {
        val w = size.minDimension * 0.09f
        val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
        val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(color, -90f, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Placeholder color that pulses gently (loading skeleton instead of a spinner). Calm with "Motion: off". */
@Composable
fun shimmerColor(): Color {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.12f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "ska")
    return Color.White.copy(alpha = if (Look.motion > 0) a else 0.08f)
}

/** Gray placeholder in the shape of the later content. */
@Composable
fun SkeletonBox(modifier: Modifier, color: Color = shimmerColor(), shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(DS.RadiusXs)) {
    Box(modifier.clip(shape).background(color))
}
