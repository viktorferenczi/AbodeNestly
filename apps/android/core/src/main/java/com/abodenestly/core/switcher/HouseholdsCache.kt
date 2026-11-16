package com.abodenestly.core.switcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.core.api.HouseholdWithRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * Every household the person belongs to, per user, so the switcher works offline (SPEC §6.1). Keyed by user so a
 * second account on the device never sees the first one's households. Port of iOS `HouseholdsCache`.
 */
interface HouseholdsCache {
    /** Empty when nothing was stored or the stored shape no longer decodes. */
    suspend fun read(userId: UUID): List<HouseholdWithRole>
    suspend fun write(userId: UUID, list: List<HouseholdWithRole>)

    /** Adds or replaces one household, keeping the rest. */
    suspend fun upsert(userId: UUID, entry: HouseholdWithRole) {
        val list = read(userId)
        val index = list.indexOfFirst { it.household.id == entry.household.id }
        write(userId, if (index >= 0) list.toMutableList().also { it[index] = entry } else list + entry)
    }

    suspend fun remove(userId: UUID, householdId: UUID) {
        write(userId, read(userId).filter { it.household.id != householdId })
    }

    suspend fun clear(userId: UUID)
}

class DataStoreHouseholdsCache(private val store: DataStore<Preferences>) : HouseholdsCache {
    // Read-modify-write must not interleave, or a Settings rename and a list refresh lose one of the two.
    private val lock = Mutex()
    private val serializer = ListSerializer(HouseholdWithRole.serializer())

    override suspend fun read(userId: UUID): List<HouseholdWithRole> =
        store.data.first()[key(userId)]?.let {
            runCatching { Serializer.kotlinxSerializationJson.decodeFromString(serializer, it) }.getOrNull()
        } ?: emptyList()

    override suspend fun write(userId: UUID, list: List<HouseholdWithRole>) {
        val json = Serializer.kotlinxSerializationJson.encodeToString(serializer, list)
        store.edit { it[key(userId)] = json }
    }

    override suspend fun upsert(userId: UUID, entry: HouseholdWithRole) = lock.withLock {
        val list = read(userId)
        val index = list.indexOfFirst { it.household.id == entry.household.id }
        write(userId, if (index >= 0) list.toMutableList().also { it[index] = entry } else list + entry)
    }

    override suspend fun remove(userId: UUID, householdId: UUID) = lock.withLock {
        write(userId, read(userId).filter { it.household.id != householdId })
    }

    override suspend fun clear(userId: UUID) {
        store.edit { it.remove(key(userId)) }
    }

    companion object {
        private fun key(userId: UUID) = stringPreferencesKey("abodenestly.households.$userId")

        fun create(context: Context, scope: CoroutineScope) = DataStoreHouseholdsCache(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("households") },
        )
    }
}

class InMemoryHouseholdsCache : HouseholdsCache {
    private val lists = mutableMapOf<UUID, List<HouseholdWithRole>>()
    override suspend fun read(userId: UUID): List<HouseholdWithRole> = lists[userId].orEmpty()
    override suspend fun write(userId: UUID, list: List<HouseholdWithRole>) {
        lists[userId] = list
    }
    override suspend fun clear(userId: UUID) {
        lists.remove(userId)
    }
}
