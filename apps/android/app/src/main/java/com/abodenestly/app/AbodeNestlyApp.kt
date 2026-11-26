package com.abodenestly.app

import android.app.Application
import com.abodenestly.sync.SyncEngine
import com.abodenestly.sync.SyncEngineProvider

class AbodeNestlyApp : Application(), SyncEngineProvider {
    lateinit var graph: AppGraph
        private set

    /** The background worker runs the same engine the screens kick. */
    override val syncEngine: SyncEngine get() = graph.sync

    override suspend fun prepareBackgroundSync() {
        if (graph.database.current.value != null) return
        val id = graph.lastHousehold.read() ?: return
        graph.database.open(id)
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
