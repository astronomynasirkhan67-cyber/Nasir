package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings > Live TV: rewind (buffer on the device) and recordings. */
@Composable
internal fun LiveBufferSection(ctx: Context) {
    LiveSettings.init(ctx)
    SectionTitle("Zurückspulen und Aufnehmen")
    ToggleRow(
        "Live TV zurückspulen",
        "Speichert den laufenden Sender auf dem Gerät. Dann kannst du pausieren, ohne etwas zu verpassen, mit den Spultasten zurückspringen und mit „Zurück zu Live“ wieder aufholen. Gilt für normale Live-Streams (nicht HLS).",
        LiveSettings.timeshift
    ) { LiveSettings.setTimeshift(ctx, it) }
    val mbs = LiveSettings.BUFFER_MB
    val mbLabels = mbs.map { if (it >= 1024) "${it / 1024} GB" else "$it MB" }
    SelectRow("Puffergröße", mbLabels[mbs.indexOf(LiveSettings.bufferMb).coerceAtLeast(0)], mbLabels, mbs.indexOf(LiveSettings.bufferMb).coerceAtLeast(0)) {
        LiveSettings.setBufferMb(ctx, mbs[it])
    }
    Hint("Ungefähr: 512 MB reichen für 10–20 Minuten in HD. Der Puffer wird beim Schließen des Players gelöscht.")
    val dirs = remember { Recorder.dirs(ctx) }
    val labels = dirs.mapIndexed { i, f -> Recorder.dirLabel(ctx, i, f) }
    SelectRow("Speicherort für Aufnahmen", labels.getOrElse(LiveSettings.recDir) { labels.first() }, labels, LiveSettings.recDir.coerceIn(0, labels.lastIndex)) {
        LiveSettings.setRecDir(ctx, it)
    }
    val pads = listOf(0, 2, 3, 5, 10, 15)
    SelectRow("Nachlauf nach Sendungsende", "${LiveSettings.recPad} min", pads.map { "$it min" }, pads.indexOf(LiveSettings.recPad).coerceAtLeast(0)) {
        LiveSettings.setRecPad(ctx, pads[it])
    }
    Hint("Aufnehmen: im Player bei einem Sender auf den roten Knopf. Die laufende Sendung wird bis zu ihrem Ende laut TV-Guide aufgenommen, beginnt sie schon im Puffer, auch von ihrem Anfang an. Schaust du währenddessen einen anderen Sender, braucht das eine zweite Verbindung zu deinem Anbieter. Aufnahmen liegen im App-Ordner und werden beim Deinstallieren der App gelöscht.")
    RecordingsList(ctx)
}

@Composable
private fun RecordingsList(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    val running = Recorder.active.size
    val list = remember(tick, running) { Recorder.list(ctx) }
    SectionTitle("Aufnahmen (${list.size})")
    if (list.isEmpty()) { Hint("Noch keine Aufnahmen."); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in list.take(40)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x12FFFFFF)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text((if (f.running) "● " else "") + f.title, color = if (f.running) Color(0xFFFF6B6B) else Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
                    Text(
                        listOf(f.channel, dayLabel(f.startMs / 1000) + " " + hhmm(f.startMs / 1000), (f.bytes / (1024 * 1024)).toString() + " MB").filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Muted, fontSize = 13.sp, maxLines = 1, raw = true
                    )
                }
                if (f.running) SmallPill("Stoppen") { Recorder.active.firstOrNull { it.file == f.file }?.let { Recorder.stop(ctx, it) }; tick++ }
                SmallPill("Löschen") { Recorder.delete(ctx, f); tick++ }
            }
        }
    }
    Hint("Abspielen: Live TV > „Aufnahmen“.")
}
