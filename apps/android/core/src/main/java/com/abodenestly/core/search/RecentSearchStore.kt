package com.abodenestly.core.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.abodenestly.contract.infrastructure.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Locale
import java.util.UUID

/** Recent searches per household: last [RecentSearches.MAX], newest first, deduplicated ignoring case. */
interface RecentSearchStore {
    suspend fun read(householdId: UUID): List<String>

    /** Records [query] (trimmed; blank is ignored) and returns the new list. */
    suspend fun record(householdId: UUID, query: String): List<String>
}

object RecentSearches {
    const val MAX = 8

    fun add(recents: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return recents
        val key = q.lowercase(Locale.ROOT)
        return (listOf(q) + recents.filter { it.lowercase(Locale.ROOT) != key }).take(MAX)
    }
}

/** Key `abodenestly.search.<householdId>` holds a JSON array of strings. Twins: iOS `UserDefaults`, web `localStorage`. */
class DataStoreRecentSearchStore(private val store: DataStore<Preferences>) : RecentSearchStore {
    private val serializer = ListSerializer(String.serializer())

    override suspend fun read(householdId: UUID): List<String> =
        store.data.first()[key(householdId)]?.let {
            // A shape that no longer decodes reads as no history rather than crashing the screen.
            runCatching { Serializer.kotlinxSerializationJson.decodeFromString(serializer, it) }.getOrNull()
        } ?: emptyList()

    override suspend fun record(householdId: UUID, query: String): List<String> {
        var result = emptyList<String>()
        store.edit { prefs ->
            val current = prefs[key(householdId)]?.let { runCatching { Serializer.kotlinxSerializationJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
            result = RecentSearches.add(current, query)
            prefs[key(householdId)] = Serializer.kotlinxSerializationJson.encodeToString(serializer, result)
        }
        return result
    }

    companion object {
        fun key(householdId: UUID) = stringPreferencesKey("abodenestly.search.$householdId")

        fun create(context: Context, scope: CoroutineScope) = DataStoreRecentSearchStore(
            PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("search") },
        )
    }
}

class InMemoryRecentSearchStore : RecentSearchStore {
    private val lists = mutableMapOf<UUID, List<String>>()
    override suspend fun read(householdId: UUID): List<String> = lists[householdId] ?: emptyList()
    override suspend fun record(householdId: UUID, query: String): List<String> =
        RecentSearches.add(read(householdId), query).also { lists[householdId] = it }
}
