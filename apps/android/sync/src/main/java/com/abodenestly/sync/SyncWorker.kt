package com.abodenestly.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs one sync pass while the app is in the background (SPEC §6.4). The engine comes from the [Application][android.app.Application]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val provider = applicationContext as? SyncEngineProvider ?: return Result.failure()
        // A failed open is retried by WorkManager; cancellation propagates.
        try {
            provider.prepareBackgroundSync()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        provider.syncEngine.run()
        return Result.success()
    }
}

/** The two background requests, behind an interface so the app's lifecycle code is testable without WorkManager. */
interface BackgroundSyncScheduler {
    /** Keeps one 15-minute periodic job (needs a network) enqueued; calling it again changes nothing. */
    fun schedulePeriodic()

    /** One extra pass soon, for when the app goes to the background with writes still queued. */
    fun scheduleOnce()
}

class SyncScheduler(private val context: Context) : BackgroundSyncScheduler {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    // Not setExpedited: before Android 12 an expedited request must show a foreground notification, which a quiet
    // sync is not worth. A one-time request with the network constraint starts as soon as the device is online.
    override fun scheduleOnce() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_NAME = "sync.periodic"
        const val ONCE_NAME = "sync.once"
        const val PERIOD_MINUTES = 15L
    }
}
