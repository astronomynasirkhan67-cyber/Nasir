package com.ipvio.stream

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/** Own channel lists (per profile). Logic in [ChannelListLogic]. */
object ChannelLists {
    val lists = mutableStateListOf<ChList>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_chlists")

    fun init(c: Context) {
        if (inited) return
        inited = true
        lists.addAll(ChannelListLogic.decode(prefs(c).getString("l", null)))
    }

    private fun set(c: Context, l: List<ChList>) {
        lists.clear(); lists.addAll(l)
        prefs(c).edit().putString("l", ChannelListLogic.encode(l)).apply()
    }

    fun byKey(k: String): ChList? = ChannelListLogic.idOfKey(k)?.let { id -> lists.firstOrNull { it.id == id } }

    /** New list (optionally right away with a channel); returns the new ID. */
    fun create(c: Context, name: String, first: String? = null): Int {
        init(c)
        val n = ChannelListLogic.create(lists.toList(), name, first)
        set(c, n)
        return n.last().id
    }

    fun rename(c: Context, id: Int, name: String) = set(c, ChannelListLogic.rename(lists.toList(), id, name))
    fun delete(c: Context, id: Int) = set(c, ChannelListLogic.delete(lists.toList(), id))
    fun add(c: Context, id: Int, ch: String) = set(c, ChannelListLogic.add(lists.toList(), id, ch))
    fun remove(c: Context, id: Int, ch: String) = set(c, ChannelListLogic.remove(lists.toList(), id, ch))
    fun move(c: Context, id: Int, ch: String, delta: Int) = set(c, ChannelListLogic.move(lists.toList(), id, ch, delta))
}

/**
 * Reminders for shows from the TV guide. If the app is open, a card with "Switch" appears;
 * if it is closed, a notification that opens the channel. Applies to the whole device (not per profile).
 */
object Reminders {
    const val EXTRA_CH = "ipvio_remind_ch"
    private const val CHANNEL = "reminders"
    val list = mutableStateListOf<Reminder>()
    /** Lead time in minutes. */
    var leadMin by mutableIntStateOf(5)
        private set
    /** Already reported (card or notification) – not twice. */
    private val done = HashSet<String>()
    @Volatile private var inited = false
    /** App in the foreground? Then a card in the app instead of a notification. */
    @Volatile var foreground = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        leadMin = p.getInt("lead", 5).coerceIn(0, 60)
        val now = System.currentTimeMillis() / 1000
        list.addAll(ReminderLogic.prune(ReminderLogic.decode(p.getString("l", null)), now))
        done.addAll((p.getString("done", "") ?: "").split('\n').filter { it.isNotBlank() })
        done.retainAll(list.map { it.key }.toSet())
    }

    private fun save(c: Context) {
        prefs(c).edit().putString("l", ReminderLogic.encode(list.toList())).putString("done", done.joinToString("\n")).putInt("lead", leadMin).apply()
    }

    fun isSet(chId: String, start: Long): Boolean = list.any { it.chId == chId && it.start == start }

    /** On/off for a show. true = set now. */
    @Synchronized
    fun toggle(c: Context, r: Reminder): Boolean {
        init(c)
        val n = ReminderLogic.toggle(list.toList(), r)
        list.clear(); list.addAll(n)
        done.remove(r.key)
        save(c)
        schedule(c)
        return list.any { it.key == r.key }
    }

    fun setLead(c: Context, min: Int) {
        init(c)
        leadMin = min.coerceIn(0, 60)
        save(c); schedule(c)
    }

    /** Due, not yet reported reminders. */
    @Synchronized
    fun pending(nowSec: Long): List<Reminder> = ReminderLogic.due(list.toList(), nowSec, leadMin * 60L).filter { it.key !in done }

    @Synchronized
    fun markDone(c: Context, r: Reminder) {
        done.add(r.key); save(c)
    }

    /** Remove old entries and set the next alarm. */
    @Synchronized
    fun schedule(c: Context) {
        init(c)
        val now = System.currentTimeMillis() / 1000
        val pruned = ReminderLogic.prune(list.toList(), now)
        if (pruned.size != list.size) { list.clear(); list.addAll(pruned); done.retainAll(pruned.map { it.key }.toSet()); save(c) }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            c.applicationContext, 4711, Intent(c.applicationContext, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = ReminderLogic.nextAlarm(pruned.filter { it.key !in done }, now, leadMin * 60L)
        if (next == null) { am.cancel(pi); return }
        val at = next * 1000L
        try {
            // Exact if allowed; otherwise approximate (the system may shift by a few minutes)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** Called by the alarm: due reminders as a notification (app open: the card in the app takes over). */
    fun fire(c: Context) {
        init(c)
        if (!foreground) {
            val now = System.currentTimeMillis() / 1000
            for (r in pending(now)) { notify(c, r, now); markDone(c, r) }
        }
        schedule(c)
    }

    /** Text of the reminder: "In 5 min" or "Running now". */
    fun whenText(r: Reminder, nowSec: Long): String {
        val m = (r.start - nowSec + 30) / 60
        return if (m <= 0) Lang.tr("Läuft jetzt") else Lang.tr("In $m Min")
    }

    private fun notify(c: Context, r: Reminder, nowSec: Long) {
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                NotificationChannel(CHANNEL, Lang.tr("Erinnerungen"), NotificationManager.IMPORTANCE_HIGH)
            )
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CH, r.chId)
            val pi = PendingIntent.getActivity(c, r.key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH)
            val n = b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(whenText(r, nowSec) + ": " + r.title)
                .setContentText(r.chTitle + " · " + Lang.tr("Antippen öffnet den Sender"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            nm.notify(r.key.hashCode(), n)
        }
    }

    /** Android 13+: ask once for permission for notifications (when a reminder is first set). */
    fun askPermission(a: Activity?) {
        if (a == null || Build.VERSION.SDK_INT < 33) return
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71) }
        }
    }
}

/** Alarm for reminders (and after a device restart: set the alarm again). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Language of the app for the notification text (the app may be closed)
        runCatching { General.init(c) }
        runCatching { Reminders.fire(c) }
    }
}
