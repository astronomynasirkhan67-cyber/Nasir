package com.ipvio.stream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource

fun trackLabel(f: Format, n: Int): String {
    val base = f.label ?: langLabel(f.language) ?: "Spur $n"
    val ch = if (f.channelCount > 0) channelLabel(f.channelCount) else ""
    return if (ch.isNotEmpty()) "$base · $ch" else base
}

class TrackOpt(val label: String, val group: Tracks.Group, val idx: Int, val selected: Boolean)

fun collectTracks(player: Player, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    var n = 0
    for (g in player.currentTracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            // Always show audio and subtitles completely (even if headphones/box cannot do the format) – the user decides.
            // Only video levels the device cannot decode at all stay hidden.
            val ok = g.isTrackSupported(i)
            if (!ok && type == C.TRACK_TYPE_VIDEO) continue
            n++
            val f = g.getTrackFormat(i)
            val base = if (type == C.TRACK_TYPE_VIDEO) (if (f.height > 0) "${f.height}p" else "Spur $n") else trackLabel(f, n)
            val label = if (ok || type == C.TRACK_TYPE_VIDEO) base else "$base (evtl. nicht abspielbar)"
            out.add(TrackOpt(label, g, i, g.isTrackSelected(i)))
        }
    }
    return out
}

/** HTTP status behind a playback error (0 if there is none). */
fun httpStatusOf(e: PlaybackException): Int {
    var t: Throwable? = e.cause
    var depth = 0
    while (t != null && depth < 6) {
        if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
        t = t.cause
        depth++
    }
    return 0
}
