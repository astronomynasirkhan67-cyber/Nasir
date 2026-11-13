package com.ipvio.stream

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Screen edge calibration (like the "Fit to your screen" step on game consoles): a frame is drawn at the current margin.
 * Left/Right change the side margin, Up/Down the top/bottom margin, until all four corners of the frame are visible on the TV.
 * Writes the same setting as the chips in Design ([General.fitX], [General.fitY]).
 */
@Composable
fun FitCalibration(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { General.setFit(ctx, x = (General.fitX + 1).coerceAtMost(MAX)); true }
                        Key.DirectionLeft -> { General.setFit(ctx, x = (General.fitX - 1).coerceAtLeast(0)); true }
                        Key.DirectionDown -> { General.setFit(ctx, y = (General.fitY + 1).coerceAtMost(MAX)); true }
                        Key.DirectionUp -> { General.setFit(ctx, y = (General.fitY - 1).coerceAtLeast(0)); true }
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> { onDone(); true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val dx = size.width * General.fitX / 100f
                val dy = size.height * General.fitY / 100f
                // Outer edge of the picture (what the TV might cut off)
                drawRect(Color(0xFFFF4D4D), Offset.Zero, size, style = Stroke(2.dp.toPx()))
                // Safe frame at the current margin
                val tl = Offset(dx + 3.dp.toPx(), dy + 3.dp.toPx())
                val sz = Size(size.width - 2 * dx - 6.dp.toPx(), size.height - 2 * dy - 6.dp.toPx())
                drawRect(Color.White, tl, sz, style = Stroke(6.dp.toPx()))
                val c = 40.dp.toPx()
                for ((cx, cy) in listOf(tl.x to tl.y, tl.x + sz.width to tl.y, tl.x to tl.y + sz.height, tl.x + sz.width to tl.y + sz.height)) {
                    drawCircle(Color(0xFF4ADE80), c / 3, Offset(cx, cy))
                }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bildschirmrand einstellen", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Du siehst einen weißen Rahmen mit vier grünen Punkten. Wenn eine Ecke fehlt, schneidet dein Fernseher den Rand ab. " +
                        "Rechts/Links ändert den Rand seitlich, Hoch/Runter oben und unten, bis alle vier Ecken sichtbar sind. OK speichert.",
                    color = Color(0xFFD0D0D6), fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.background(Color(0xCC000000))
                )
                Text(raw = true, text = Lang.tr("Seitlich") + ": " + General.fitX + " %    " + Lang.tr("Oben/unten") + ": " + General.fitY + " %", color = Color(0xFF4ADE80), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val MAX = 10
