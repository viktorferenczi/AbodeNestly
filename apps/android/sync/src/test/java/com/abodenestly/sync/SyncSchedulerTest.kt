package com.abodenestly.sync

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** SPEC §6.4: a periodic 15-minute job that needs a network, enqueued once however often the app starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the periodic job is unique, 15 minutes and needs a network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = SyncScheduler(context)
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertEquals(NetworkType.CONNECTED, infos[0].constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(15), infos[0].periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `scheduleOnce replaces a pending one-time pass`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = SyncScheduler(context)
        scheduler.scheduleOnce()
        scheduler.scheduleOnce()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.ONCE_NAME).get()
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
    }
}
