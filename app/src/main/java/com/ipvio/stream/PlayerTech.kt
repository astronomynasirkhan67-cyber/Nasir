package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** "Technik-Info" (technical info; round 15, addendum 17): codec, bitrate, frame rate, dropped frames, buffer. Top left of the picture. */
@Composable
fun BoxScope.PTechOverlay(player: ExoPlayer) {
    var lines by remember { mutableStateOf(listOf("…")) }
    LaunchedEffect(player) {
        while (true) {
            val out = ArrayList<String>()
            val vf = player.videoFormat
            if (vf != null) {
                out.add("Video: " + codecName(vf.sampleMimeType).ifBlank { vf.sampleMimeType ?: "?" } + "  " + vf.width + "×" + vf.height + "  " + TechText.fps(vf.frameRate) + " fps")
                val br = if (vf.averageBitrate > 0) vf.averageBitrate else vf.bitrate
                if (br > 0) out.add("Video-Rate (laut Datei): " + TechText.kbps(br))
            } else out.add("Video: –")
            val af = player.audioFormat
            if (af != null) out.add("Ton: " + codecName(af.sampleMimeType).ifBlank { af.sampleMimeType ?: "?" } + "  " + channelLabel(af.channelCount) + "  " + (af.sampleRate / 1000.0) + " kHz")
            val dc = player.videoDecoderCounters
            if (dc != null) {
                dc.ensureUpdated()
                out.add("Bilder: ${dc.renderedOutputBufferCount} gezeigt · ${dc.droppedBufferCount} verworfen")
            }
            out.add("Puffer: " + TechText.secs(player.totalBufferedDuration))
            lines = out
            delay(1000)
        }
    }
    Column(
        Modifier.align(Alignment.TopStart).padding(16.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        lines.forEach { androidx.compose.foundation.text.BasicText(it, style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)) }
    }
}
