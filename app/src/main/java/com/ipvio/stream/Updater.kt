package com.ipvio.stream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Background refresh of the playlist according to the set plan (daily, weekly, every X hours). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        General.init(c)
        Config.init(c)
        val now = System.currentTimeMillis()
        if (!SettingsLogic.refreshDue(General.plan(), General.lastRefresh, now, General.zone())) return Result.success()
        val url = PlaylistStore.savedUrl(c) ?: return Result.success()
        // Someone is watching right now: do not reload now (large lists cost memory and processing time). The job runs hourly
        // and catches up as soon as the player is closed – "last updated" stays unchanged until then.
        if (AppNav.playing) return Result.success()
        // App open: load as before so the UI shows the new state.
        // App closed: only renew the cache – nothing in working memory, no TV guide download.
        val ok = if (PlaylistStore.hasData) PlaylistStore.load(c, url, false, ask = false) && PlaylistStore.error == null
        else PlaylistStore.refreshCacheOnly(c, url).also { ok0 ->
            // Also refresh the other playlists shown at the same time
            if (ok0) for (u in PlaylistStore.extraUrls(c)) runCatching { PlaylistStore.refreshCacheOnly(c, u) }
        }
        if (ok) General.markRefreshed(c)
        return Result.success()
    }
}

object Updater {
    private const val NAME = "playlist_update"

    /** Sets up the background job to match the setting. For "at every start" and "by hand only" there is none. */
    fun apply(c: Context) {
        try {
            val wm = WorkManager.getInstance(c.applicationContext)
            if (General.refreshMode in 1..3) {
                val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else wm.cancelUniqueWork(NAME)
        } catch (e: Exception) {
        }
    }
}
