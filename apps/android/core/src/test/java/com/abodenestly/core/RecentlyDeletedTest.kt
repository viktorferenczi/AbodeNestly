package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.recentlydeleted.RecentlyDeleted
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.DeletedRow
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.StoreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

private val NOW: Instant = Instant.parse("2026-11-19T12:00:00Z")
private fun ago(d: Duration) = NOW.minus(d).toString()
private fun row(table: String, id: String, d: Duration) = DeletedRow(table, id, id, ago(d))
private val ALL = SearchModule.entries.toSet()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentlyDeletedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    @Test
    fun `CORE-10 daysLeft is 30 minus whole days elapsed`() {
        listOf(
            Duration.ZERO to 30,
            Duration.ofDays(2) to 28,
            Duration.ofDays(3).minusSeconds(1) to 28,
            Duration.ofDays(29) to 1,
            Duration.ofDays(30) to 0,
            Duration.ofDays(-1) to 30, // clock skew: deleted "in the future"
        ).forEach { (elapsed, expected) ->
            assertEquals("$elapsed", expected, RecentlyDeleted.daysLeft(NOW.minus(elapsed), NOW))
        }
    }

    @Test
    fun `CORE-10 AC4 sections group by module newest first and hide rows at 0 days`() {
        val rows = listOf(
            row("list_item", "a", Duration.ofDays(1)), row("store", "b", Duration.ofDays(3)), row("pantry_item", "c", Duration.ofDays(1)),
            row("asset", "d", Duration.ofDays(30)), row("maint_task", "e", Duration.ofDays(2)),
        )
        val s = RecentlyDeleted.sections(rows, ALL, NOW)
        assertEquals(listOf(SearchModule.Shopping, SearchModule.Pantry, SearchModule.Maintenance), s.map { it.module })
        assertEquals(listOf("a", "b"), s[0].items.map { it.id })
        assertEquals(listOf("e"), s[2].items.map { it.id })
    }

    @Test
    fun `CORE-10 AC5 sections omit disabled modules`() {
        val rows = listOf(row("list_item", "a", Duration.ofDays(1)), row("pantry_item", "c", Duration.ofDays(1)))
        val s = RecentlyDeleted.sections(rows, setOf(SearchModule.Shopping, SearchModule.Maintenance), NOW)
        assertEquals(listOf(SearchModule.Shopping, SearchModule.Maintenance), s.map { it.module })
        assertEquals(emptyList<String>(), s[1].items.map { it.id })
    }

    @Test
    fun `CORE-10 restore clears deleted_at and queues one upsert with an explicit null`() = runBlocking {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", household.toString(), "Lidl", ago(Duration.ofDays(1)))) }
        assertEquals(1, registry.deletedRows(household, ALL).first().size)
        registry.restore(household, "store", "s1", NOW)
        assertEquals(emptyList<DeletedRow>(), registry.deletedRows(household, ALL).first())
        val out = registry.outboxForTesting()
        assertEquals(1, out.size)
        assertEquals("store", out[0].tableName)
        assertEquals("upsert", out[0].op)
        assertEquals("s1", out[0].rowId)
        assertEquals("""{"deleted_at":null}""", out[0].data)
        assertEquals(NOW.toString(), out[0].clientTs)
        assertNull(runCatching { java.util.UUID.fromString(out[0].id) }.exceptionOrNull())
    }

    @Test
    fun `CORE-10 deletedRows reads only the enabled modules tables`() = runBlocking {
        registry.open(household)
        registry.withSearchDao {
            it.upsertStore(StoreEntity("s1", household.toString(), "Lidl", ago(Duration.ofDays(1))))
            it.upsertLocation(com.abodenestly.sync.LocationEntity("p1", household.toString(), "Shelf", ago(Duration.ofDays(1))))
        }
        assertEquals(listOf("store"), registry.deletedRows(household, setOf(SearchModule.Shopping)).first().map { it.table })
    }

    @Test
    fun `CORE-10 a second restore or a missing row queues nothing`() = runBlocking {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", household.toString(), "Lidl", ago(Duration.ofDays(1)))) }
        registry.restore(household, "store", "s1", NOW)
        registry.restore(household, "store", "s1", NOW)
        registry.restore(household, "store", "gone", NOW)
        assertEquals(1, registry.outboxForTesting().size)
    }

    @Test
    fun `CORE-10 a restore that finds the database closed leaves the list and does not kick sync`() {
        val sync = FakeSyncEngine()
        val item = RecentlyDeleted.sections(listOf(row("store", "s1", Duration.ofDays(1))), ALL, NOW)[0].items[0]
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val vm = com.abodenestly.core.recentlydeleted.RecentlyDeletedViewModel(
            kotlinx.coroutines.flow.flowOf(listOf(row("store", "s1", Duration.ofDays(1)))), ALL,
            restore = { _, _, _ -> throw com.abodenestly.sync.DatabaseNotOpenException() }, sync, now = { NOW }, scope = scope,
        )
        vm.onEvent(com.abodenestly.core.recentlydeleted.RecentlyDeletedEvent.Restore(item))
        assertEquals(0, sync.kicks.get())
        assertEquals(1, vm.state.value.sections[0].items.size)
    }
}
