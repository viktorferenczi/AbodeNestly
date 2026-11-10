package com.abodenestly.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/** The household the person last used. CORE-07 (household switcher) will write it too. */
interface LastHouseholdStore {
    suspend fun read(): UUID?
    suspend fun write(id: UUID)
}

class DataStoreLastHouseholdStore(private val store: DataStore<Preferences>) : LastHouseholdStore {
    override suspend fun read(): UUID? =
        store.data.first()[KEY]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    override suspend fun write(id: UUID) {
        store.edit { it[KEY] = id.toString() }
    }

    companion object {
        private val KEY = stringPreferencesKey("abodenestly.lastHousehold")

        fun create(context: Context, scope: CoroutineScope) = DataStoreLastHouseholdStore(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("session") },
        )
    }
}

class InMemoryLastHouseholdStore(private var id: UUID? = null) : LastHouseholdStore {
    override suspend fun read(): UUID? = id
    override suspend fun write(id: UUID) {
        this.id = id
    }
}
