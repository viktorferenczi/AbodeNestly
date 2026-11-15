package com.abodenestly.core.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Profile edits waiting to reach the server, per user. `profile` is user-scoped, so it cannot go through
 * `/sync` (household tables only); [ProfileSync] drains this queue through PostgREST instead.
 */
interface ProfileOutbox {
    /** Queued edits, oldest first. */
    suspend fun pending(userId: UUID): List<ProfileMutation>
    suspend fun append(userId: UUID, mutation: ProfileMutation)
    suspend fun remove(userId: UUID, id: String)

    /** The picture waiting for an upload, if any. Picking again replaces it. */
    suspend fun pendingAvatar(userId: UUID): PendingAvatar?
    suspend fun setPendingAvatar(userId: UUID, avatar: PendingAvatar?)
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val listSerializer = ListSerializer(ProfileMutation.serializer())

class DataStoreProfileOutbox(private val store: DataStore<Preferences>) : ProfileOutbox {
    override suspend fun pending(userId: UUID): List<ProfileMutation> = decode(store.data.first()[queueKey(userId)])

    override suspend fun append(userId: UUID, mutation: ProfileMutation) {
        // One edit transaction: two writers cannot lose each other's entry.
        store.edit { it[queueKey(userId)] = json.encodeToString(listSerializer, decode(it[queueKey(userId)]) + mutation) }
    }

    override suspend fun remove(userId: UUID, id: String) {
        store.edit { prefs ->
            val rest = decode(prefs[queueKey(userId)]).filter { it.id != id }
            if (rest.isEmpty()) prefs.remove(queueKey(userId)) else prefs[queueKey(userId)] = json.encodeToString(listSerializer, rest)
        }
    }

    override suspend fun pendingAvatar(userId: UUID): PendingAvatar? =
        store.data.first()[avatarKey(userId)]?.let { runCatching { json.decodeFromString(PendingAvatar.serializer(), it) }.getOrNull() }

    override suspend fun setPendingAvatar(userId: UUID, avatar: PendingAvatar?) {
        store.edit {
            if (avatar == null) it.remove(avatarKey(userId))
            else it[avatarKey(userId)] = json.encodeToString(PendingAvatar.serializer(), avatar)
        }
    }

    /** A shape that no longer decodes yields an empty queue, so a stale file never crashes the screen. */
    private fun decode(raw: String?): List<ProfileMutation> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    companion object {
        private fun queueKey(userId: UUID) = stringPreferencesKey("abodenestly.profile.outbox.$userId")
        private fun avatarKey(userId: UUID) = stringPreferencesKey("abodenestly.profile.avatar.$userId")

        fun create(context: Context, scope: CoroutineScope) = DataStoreProfileOutbox(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("profile-outbox") },
        )
    }
}

class InMemoryProfileOutbox : ProfileOutbox {
    private val lock = Any()
    private val queues = mutableMapOf<UUID, List<ProfileMutation>>()
    private val avatars = mutableMapOf<UUID, PendingAvatar>()

    override suspend fun pending(userId: UUID): List<ProfileMutation> = synchronized(lock) { queues[userId].orEmpty() }

    override suspend fun append(userId: UUID, mutation: ProfileMutation) = synchronized(lock) {
        queues[userId] = queues[userId].orEmpty() + mutation
    }

    override suspend fun remove(userId: UUID, id: String) = synchronized(lock) {
        queues[userId] = queues[userId].orEmpty().filter { it.id != id }
    }

    override suspend fun pendingAvatar(userId: UUID): PendingAvatar? = synchronized(lock) { avatars[userId] }

    override suspend fun setPendingAvatar(userId: UUID, avatar: PendingAvatar?) = synchronized(lock) {
        if (avatar == null) avatars.remove(userId) else avatars[userId] = avatar
        Unit
    }
}
