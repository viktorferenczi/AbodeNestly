package com.abodenestly.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.switcher.HouseholdsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The last household and role read per user, so Settings stays readable offline. Keyed by user as well as
 * household so a second account on the device never reads the first one's role (web and iOS do the same).
 * There is no Room yet (it arrives with sync), so this is a JSON snapshot like [com.abodenestly.core.members.MembersCache].
 */
interface HouseholdCache {
    /** `null` when nothing was stored or the stored shape no longer decodes. */
    suspend fun read(userId: UUID, householdId: UUID): HouseholdWithRole?
    suspend fun write(userId: UUID, value: HouseholdWithRole)
    suspend fun clear(userId: UUID, householdId: UUID)
}

class DataStoreHouseholdCache(
    private val store: DataStore<Preferences>,
    /** The switcher's list follows Settings writes (a rename shows without a refetch), like iOS. */
    private val households: HouseholdsCache? = null,
) : HouseholdCache {
    override suspend fun read(userId: UUID, householdId: UUID): HouseholdWithRole? =
        store.data.first()[key(userId, householdId)]?.let {
            runCatching {
                Serializer.kotlinxSerializationJson.decodeFromString(HouseholdWithRole.serializer(), it)
            }.getOrNull()
        }

    override suspend fun write(userId: UUID, value: HouseholdWithRole) {
        val json = Serializer.kotlinxSerializationJson.encodeToString(HouseholdWithRole.serializer(), value)
        store.edit { it[key(userId, value.household.id)] = json }
        households?.upsert(userId, value)
    }

    override suspend fun clear(userId: UUID, householdId: UUID) {
        store.edit { it.remove(key(userId, householdId)) }
        households?.remove(userId, householdId)
    }

    companion object {
        private fun key(userId: UUID, householdId: UUID) = stringPreferencesKey("abodenestly.household.$userId.$householdId")

        fun create(context: Context, scope: CoroutineScope, households: HouseholdsCache? = null) = DataStoreHouseholdCache(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("household") },
            households,
        )
    }
}

class InMemoryHouseholdCache(private val households: HouseholdsCache? = null) : HouseholdCache {
    private val snapshots = mutableMapOf<Pair<UUID, UUID>, HouseholdWithRole>()
    override suspend fun read(userId: UUID, householdId: UUID): HouseholdWithRole? = snapshots[userId to householdId]
    override suspend fun write(userId: UUID, value: HouseholdWithRole) {
        snapshots[userId to value.household.id] = value
        households?.upsert(userId, value)
    }
    override suspend fun clear(userId: UUID, householdId: UUID) {
        snapshots.remove(userId to householdId)
        households?.remove(userId, householdId)
    }
}
