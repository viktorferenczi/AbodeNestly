package com.abodenestly.core.members

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.core.api.MemberRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * The last member list read per household, so Members stays readable offline. There is no Room yet (it arrives
 * with sync), so this is a JSON snapshot like iOS's `MembersCache` and web's `membersCache.ts`.
 */
interface MembersCache {
    /** `null` when nothing was stored or the stored shape no longer decodes, so a stale snapshot never crashes the screen. */
    suspend fun read(householdId: UUID): List<MemberRow>?
    suspend fun write(rows: List<MemberRow>, householdId: UUID)
}

class DataStoreMembersCache(private val store: DataStore<Preferences>) : MembersCache {
    override suspend fun read(householdId: UUID): List<MemberRow>? =
        store.data.first()[key(householdId)]?.let {
            runCatching {
                Serializer.kotlinxSerializationJson.decodeFromString(ListSerializer(MemberRow.serializer()), it)
            }.getOrNull()
        }

    override suspend fun write(rows: List<MemberRow>, householdId: UUID) {
        val json = Serializer.kotlinxSerializationJson.encodeToString(ListSerializer(MemberRow.serializer()), rows)
        store.edit { it[key(householdId)] = json }
    }

    companion object {
        private fun key(householdId: UUID) = stringPreferencesKey("abodenestly.members.$householdId")

        fun create(context: Context, scope: CoroutineScope) = DataStoreMembersCache(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("members") },
        )
    }
}

class InMemoryMembersCache : MembersCache {
    private val snapshots = mutableMapOf<UUID, List<MemberRow>>()
    override suspend fun read(householdId: UUID): List<MemberRow>? = snapshots[householdId]
    override suspend fun write(rows: List<MemberRow>, householdId: UUID) {
        snapshots[householdId] = rows
    }
}
