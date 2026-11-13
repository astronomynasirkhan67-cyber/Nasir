package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

class Profile(val id: Int, val name: String)

/**
 * Profiles separate everything personal: history, resume, favorites, view, player settings and parental control.
 * Playlist, login data, language and refresh schedule are the same for all profiles.
 * Profile 0 uses the previous file names so existing data is preserved.
 */
object Profiles {
    const val MAX = 5
    const val META = "ipvio_profiles"

    /** Files that are separate per profile. */
    private val SCOPED = listOf(
        "ipvio_settings", "ipvio_lib", "ipvio_quality", "ipvio_hist", "ipvio_watch", "ipvio_catfavs",
        "ipvio_variants", "ipvio_guide", "ipvio_browse", "ipvio_cat", "ipvio_general", "ipvio_lang",
        "ipvio_chlists", "ipvio_tracks", "ipvio_eprog"
    )

    val list = mutableStateListOf<Profile>()
    var active = 0
        private set
    var askAtStart by mutableStateOf(true)
        private set
    private var ready = false

    private fun meta(c: Context) = c.applicationContext.getSharedPreferences(META, Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (ready) return
        ready = true
        val m = meta(c)
        val ids = (m.getString("ids", "0") ?: "0").split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(0) }
        list.clear()
        ids.forEach { list.add(Profile(it, m.getString("name_$it", null) ?: defaultName(it))) }
        active = m.getInt("active", 0).takeIf { a -> list.any { it.id == a } } ?: 0
        askAtStart = m.getBoolean("ask", true)
    }

    fun defaultName(id: Int) = if (id == 0) "Hauptprofil" else "Profil ${id + 1}"

    /** Display name: default names are translated, custom names stay as they are. */
    fun displayName(p: Profile): String = if (p.name == defaultName(p.id)) Lang.tr(p.name) else p.name

    /** Settings file of the active profile. */
    fun sp(c: Context, name: String): SharedPreferences {
        init(c)
        return c.applicationContext.getSharedPreferences(scopedName(name, active), Context.MODE_PRIVATE)
    }

    fun scopedName(name: String, id: Int) = if (id == 0) name else name + "_p" + id

    /** Settings files that are read before or with the first frame (see MainActivity.onCreate and AppContent). */
    private val START_SCOPED = listOf("ipvio_settings", "ipvio_general", "ipvio_lib", "ipvio_quality", "ipvio_hist", "ipvio_watch", "ipvio_chlists")
    private val START_PLAIN = listOf("ipvio_general", "ipvio_keys", "ipvio_playback", "ipvio_dead")

    /**
     * Opens the settings files of the start-up path. Android begins to read each file on its own background thread as soon
     * as it is opened, so the files load in parallel while onCreate goes on, instead of one after the other when the first
     * value is read. Reads nothing and changes nothing; a name without a file costs nothing.
     */
    fun prewarm(c: Context) {
        init(c)
        val app = c.applicationContext
        START_SCOPED.forEach { app.getSharedPreferences(scopedName(it, active), Context.MODE_PRIVATE) }
        START_PLAIN.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
    }

    fun activeName(): String = list.firstOrNull { it.id == active }?.name ?: defaultName(0)

    private fun save(c: Context) {
        meta(c).edit().putString("ids", list.joinToString(",") { it.id.toString() })
            .also { e -> list.forEach { e.putString("name_${it.id}", it.name) } }
            .putInt("active", active).putBoolean("ask", askAtStart).commit()
    }

    fun setAsk(c: Context, v: Boolean) { askAtStart = v; save(c) }

    /** On a cold start: show the selection if there are several profiles (except right after a switch). */
    fun needsPicker(c: Context): Boolean {
        init(c)
        val m = meta(c)
        val skip = m.getBoolean("skip", false)
        if (skip) m.edit().putBoolean("skip", false).commit()
        return list.size > 1 && askAtStart && !skip
    }

    fun add(c: Context): Boolean {
        init(c)
        if (list.size >= MAX) return false
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        list.add(Profile(id, defaultName(id)))
        save(c)
        return true
    }

    fun rename(c: Context, id: Int, name: String) {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val n = name.trim().take(20).ifEmpty { defaultName(id) }
        list[i] = Profile(id, n)
        save(c)
    }

    /** Deletes a profile with its data. The main profile and the active profile remain. */
    fun remove(c: Context, id: Int): Boolean {
        if (id == 0 || id == active) return false
        if (!list.removeAll { it.id == id }) return false
        SCOPED.forEach { c.applicationContext.getSharedPreferences(scopedName(it, id), Context.MODE_PRIVATE).edit().clear().commit() }
        save(c)
        return true
    }

    /** Set the active profile (without restart yet; only for the start before the data is loaded). */
    fun select(c: Context, id: Int) {
        init(c)
        if (list.any { it.id == id }) { active = id; save(c) }
    }

    /** Switch profile: the app restarts so all data is loaded cleanly from the new profile. */
    fun switchTo(c: Context, id: Int) {
        init(c)
        if (id == active || list.none { it.id == id }) return
        active = id
        save(c)
        meta(c).edit().putBoolean("skip", true).commit()
        restart(c)
    }

    fun restart(c: Context) {
        val ctx = c.applicationContext
        val next = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(
            Intent(ctx, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("next", next).putExtra("pid", android.os.Process.myPid())
        )
    }
}

/** Runs in its own process: restarts the app and ends the old process. */
class RestartActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = intent.getParcelableExtra<Intent>("next")
        val pid = intent.getIntExtra("pid", -1)
        if (next != null) startActivity(next)
        if (pid > 0) android.os.Process.killProcess(pid)
        finish()
        Runtime.getRuntime().exit(0)
    }
}

/** "Who is watching?" at start if there are several profiles. */
@Composable
fun ProfilePicker(onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    // Last active profile locked (parental control with PIN)? Then another profile can only be left with the PIN.
    var pending by remember { mutableStateOf<Int?>(null) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    val pid = pending
    if (pid != null) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Box(Modifier.width(420.dp)) {
                PinPad(
                    "PIN eingeben", "Das zuletzt genutzte Profil ist durch den Jugendschutz gesperrt. Zum Wechseln bitte dessen PIN eingeben.", pinErr,
                    { if (Parental.verifyFor(ctx, Profiles.active, it)) { pending = null; pinErr = null; onPick(pid) } else pinErr = Parental.pinError() },
                    { pending = null; pinErr = null }
                )
            }
        }
        return
    }
    val pick: (Int) -> Unit = { id ->
        if (id != Profiles.active && Parental.profileLocked(ctx, Profiles.active)) pending = id else onPick(id)
    }
    val first = remember { FocusRequester() }
    val start = remember { Profiles.list.indexOfFirst { it.id == Profiles.active }.coerceAtLeast(0) }
    LaunchedEffect(Unit) {
        delay(150)
        repeat(8) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) }
    }
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
            Text("Wer schaut?", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Profiles.list.forEachIndexed { i, p ->
                    var f by remember { mutableStateOf(false) }
                    Column(
                        Modifier.clip(RoundedCornerShape(DS.RadiusL))
                            .then(if (i == start) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { f = it.isFocused }
                            .clickable { pick(p.id) }
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(110.dp).clip(CircleShape).background(if (f) Accent2 else Surf2)
                                .border(3.dp, if (f) Color.White else DS.Overlay2, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.text.BasicText(
                                Profiles.displayName(p).take(1).uppercase(),
                                style = androidx.compose.ui.text.TextStyle(color = if (f) Color.Black else Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                            )
                        }
                        androidx.compose.foundation.text.BasicText(
                            Profiles.displayName(p),
                            style = androidx.compose.ui.text.TextStyle(color = if (f) Color.White else Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}
