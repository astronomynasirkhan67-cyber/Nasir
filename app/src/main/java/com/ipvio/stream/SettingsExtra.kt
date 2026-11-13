package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Settings sections from round 10 (own file so SettingsHub.kt does not keep growing).
 * Uses the building blocks from SettingsHub.kt (SelectRow, ToggleRow, Hint, SectionTitle, ConfirmPill).
 */

/** Sound & subtitles: first and second audio language, when subtitles appear, language, CC. */
@Composable
fun TrackSettingsSection(ctx: Context) {
    val ai = Langs.AUDIO.indexOfFirst { it.first == AppSettings.audioLang }.coerceAtLeast(0)
    val a2 = Langs.AUDIO2.indexOfFirst { it.first == AppSettings.audioLang2 }.coerceAtLeast(0)
    val si = Langs.SUBS.indexOfFirst { it.first == AppSettings.subLang }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectRow("Tonspur", Langs.AUDIO[ai].second, Langs.AUDIO.map { it.second }, ai) {
            AppSettings.setLangs(ctx, Langs.AUDIO[it].first, AppSettings.subLang)
        }
        if (AppSettings.audioLang.isNotEmpty()) SelectRow("Zweite Tonspur", Langs.AUDIO2[a2].second, Langs.AUDIO2.map { it.second }, a2) {
            AppSettings.setTrackExtra(ctx, Langs.AUDIO2[it].first, AppSettings.subMode, AppSettings.ccPref)
        }
        SelectRow("Untertitel", Langs.SUB_MODES[AppSettings.subMode], Langs.SUB_MODES, AppSettings.subMode) {
            AppSettings.setTrackExtra(ctx, AppSettings.audioLang2, it, AppSettings.ccPref)
        }
        if (AppSettings.subMode != TrackLogic.SUB_OFF) {
            SelectRow("Sprache der Untertitel", Langs.SUBS[si].second, Langs.SUBS.map { it.second }, si) {
                AppSettings.setLangs(ctx, AppSettings.audioLang, Langs.SUBS[it].first)
            }
            ToggleRow(
                "Untertitel für Hörgeschädigte (CC)",
                "Bevorzugt Untertitel mit Geräuschen und Sprecherangaben ([Musik], [Tür knallt]), wenn der Stream welche hat.",
                AppSettings.ccPref
            ) { AppSettings.setTrackExtra(ctx, AppSettings.audioLang2, AppSettings.subMode, it) }
        }
    }
    Hint(
        "„Nur bei fremder Tonspur“: Läuft ein Titel in einer Sprache, die du nicht eingestellt hast (z. B. Japanisch), " +
            "erscheinen Untertitel automatisch; bei deiner Sprache bleiben sie aus. Wählst du im Player unter Audio & Untertitel " +
            "von Hand, merkt sich die App das für diese Serie bzw. diesen Film – die nächste Folge startet gleich richtig."
    )
}

/** Player: which source the app takes when there are several qualities, and falling back on stutter/errors. */
@Composable
fun SourceSettingsSection(ctx: Context) {
    PlaybackPrefs.init(ctx)
    DeviceCaps.init(ctx)
    SectionTitle("Quelle & Qualität")
    val opts = listOf("Automatisch (passend zum Gerät)", "Immer die höchste", "Höchstens Full HD", "Höchstens HD (spart Daten)")
    SelectRow("Bei mehreren Quellen", opts[PlaybackPrefs.quality], opts, PlaybackPrefs.quality) { PlaybackPrefs.setQuality(ctx, it) }
    ToggleRow(
        "Bei Störung automatisch wechseln",
        "Ruckelt ein Titel mehrmals oder lässt er sich nicht abspielen, nimmt die App eine andere (kleinere) Quelle desselben Titels – an derselben Stelle.",
        PlaybackPrefs.autoFallback
    ) { PlaybackPrefs.setFallback(ctx, it) }
    Text("Dieses Gerät: " + DeviceCaps.summary(), color = Muted, fontSize = 15.sp)
    if (PlaybackPrefs.learnedCap in 1..3) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Gelernt: 4K ruckelte hier mehrmals – Automatik nimmt höchstens Full HD.", color = DS.Gold, fontSize = 15.sp)
        SmallPill("Zurücksetzen") { PlaybackPrefs.resetLearned(ctx) }
    }
    Hint(
        "Viele Anbieter haben denselben Film mehrfach (4K, FHD, HD). „Automatisch“ nimmt die beste Quelle, die dein Gerät flüssig " +
            "schafft: 4K nur mit 4K-Bildschirm und passendem Decoder. Eine feste Wahl auf der Detailseite hat immer Vorrang."
    )
}

/** Player: links that no longer worked during playback. */
@Composable
fun DeadLinksSection(ctx: Context) {
    DeadLinks.init(ctx)
    SectionTitle("Defekte Links")
    Text(
        if (DeadLinks.count == 0) "Keine gestörten Titel bekannt." else "${DeadLinks.count} Titel als gestört gemerkt",
        color = if (DeadLinks.count == 0) Muted else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
    )
    ToggleRow(
        "Gestörte Titel ausblenden",
        "Blendet Filme und Sender aus, die beim Abspielen „nicht mehr vorhanden“ meldeten oder mehrmals scheiterten. Sonst tragen sie ein Warnsymbol.",
        DeadLinks.hide
    ) { DeadLinks.setHide(ctx, it) }
    if (DeadLinks.count > 0) ConfirmPill("Liste leeren") { DeadLinks.clear(ctx) }
    Hint(
        "Die App prüft nicht von sich aus Tausende Links – das würde dein Anbieter als Missbrauch werten. Sie merkt sich nur, " +
            "was beim Abspielen schiefging. Ohne Internet, bei Zugangs- oder Serverproblemen wird nichts gemerkt. Spielt ein Titel wieder, " +
            "ist er sofort freigegeben."
    )
}

/** Live TV: own channel lists (overview, operation). */
@Composable
fun ChannelListsSection(ctx: Context) {
    ChannelLists.init(ctx)
    SectionTitle("Eigene Senderlisten")
    val ls = ChannelLists.lists
    Text(if (ls.isEmpty()) "Noch keine eigenen Listen." else ls.joinToString("  ·  ") { it.name + " (" + it.ids.size + ")" }, color = if (ls.isEmpty()) Muted else Color.White, fontSize = 16.sp, raw = ls.isNotEmpty())
    Hint(
        "Anlegen: Live TV > „Neue Senderliste“ oder auf einem Sender OK gedrückt halten > „Neue Senderliste …“. " +
            "OK gedrückt halten auf einem Sender: Favorit, zu einer Liste hinzufügen oder entfernen, in der Liste nach oben/unten schieben. " +
            "OK gedrückt halten auf einer Liste: umbenennen oder löschen. Hat deine Fernbedienung eine Menü-Taste, geht es auch damit. " +
            "Im Player wechselst du mit Links zu deinen Listen; Umschalten bleibt in der Liste."
    )
}

/** Live TV: reminders for shows (lead time, upcoming reminders). */
@Composable
fun ReminderSettingsSection(ctx: Context) {
    Reminders.init(ctx)
    SectionTitle("Erinnerungen")
    val leads = ReminderLogic.LEADS
    val li = leads.indexOf(Reminders.leadMin).coerceAtLeast(0)
    SelectRow("Erinnern", if (Reminders.leadMin == 0) "Zum Beginn" else "${Reminders.leadMin} Min vorher",
        leads.map { if (it == 0) "Zum Beginn" else "$it Min vorher" }, li) { Reminders.setLead(ctx, leads[it]) }
    val list = Reminders.list.toList()
    if (list.isEmpty()) Text("Keine Erinnerungen gesetzt.", color = Muted, fontSize = 16.sp)
    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(raw = true, text = dayLabel(r.start) + " " + hhmm(r.start) + "  " + r.title + "  ·  " + r.chTitle, color = Color.White, fontSize = 16.sp, maxLines = 1)
                SmallPill("Löschen") { Reminders.toggle(ctx, r) }
            }
        }
    }
    Hint(
        "Im TV-Guide auf einer späteren Sendung OK drücken > „Erinnern“. Ist die App offen, erscheint eine Karte mit „Umschalten“ " +
            "(auch während du etwas anderes schaust). Ist sie geschlossen, kommt eine Benachrichtigung – antippen öffnet den Sender. " +
            "Das Gerät muss dafür eingeschaltet sein (Standby reicht meist); manche Geräte zeigen Benachrichtigungen nur in ihrer Mitteilungsliste."
    )
}

/** Live TV: archive (catchup) – how many channels offer it and how to use it. */
@Composable
fun CatchupSection() {
    SectionTitle("Archiv (Catchup)")
    val n = Catchup.count
    IconLabel(
        AIcon.Catchup,
        if (n == 0) "Deine Playlist meldet keine Sender mit Archiv." else "$n Sender mit Archiv",
        if (n == 0) Muted else Color.White, 16.sp, FontWeight.SemiBold, iconSize = 18.dp, iconTint = if (n == 0) Muted else Accent2
    )
    Hint(
        "Sender mit Archiv tragen das Archiv-Symbol. Im TV-Guide lassen sich vergangene Sendungen dieser Sender mit OK abspielen " +
            "(Spulen-Tasten = einen Tag zurück/vor). Im Player: „Von Anfang an“ startet die laufende Sendung neu, „Archiv“ listet " +
            "die verpassten Sendungen. Am Ende einer Sendung geht es mit der nächsten weiter, Zurück führt zum Live-Bild. " +
            "Welche Sender ein Archiv haben und wie viele Tage es reicht, bestimmt dein Anbieter."
    )
}
