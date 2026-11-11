package com.abodenestly.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.contract.models.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * The last profile read per user, so the name still renders when the profile read fails offline
 * (iOS `ProfileCache`, web `AuthProvider`).
 */
interface ProfileCache {
    suspend fun read(userId: UUID): Profile?
    suspend fun write(userId: UUID, profile: Profile)
}

class DataStoreProfileCache(private val store: DataStore<Preferences>) : ProfileCache {
    override suspend fun read(userId: UUID): Profile? =
        store.data.first()[key(userId)]?.let {
            runCatching { Serializer.kotlinxSerializationJson.decodeFromString(Profile.serializer(), it) }.getOrNull()
        }

    override suspend fun write(userId: UUID, profile: Profile) {
        val json = Serializer.kotlinxSerializationJson.encodeToString(Profile.serializer(), profile)
        store.edit { it[key(userId)] = json }
    }

    companion object {
        private fun key(userId: UUID) = stringPreferencesKey("abodenestly.profile.$userId")

        fun create(context: Context, scope: CoroutineScope) = DataStoreProfileCache(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("profile") },
        )
    }
}

class InMemoryProfileCache : ProfileCache {
    private val profiles = mutableMapOf<UUID, Profile>()
    override suspend fun read(userId: UUID): Profile? = profiles[userId]
    override suspend fun write(userId: UUID, profile: Profile) {
        profiles[userId] = profile
    }
}
