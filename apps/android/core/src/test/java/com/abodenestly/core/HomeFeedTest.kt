package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.contract.models.Activity
import com.abodenestly.core.Fixtures.activity
import com.abodenestly.core.Fixtures.homeNow
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.home.HomeFeed
import com.abodenestly.core.home.HomeModules
import com.abodenestly.core.home.HomeState
import com.abodenestly.sync.DatabaseRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.UUID

private val DAY: Duration = Duration.ofDays(1)

/** CORE-08 logic: the window, the state rule and the local table (the pull is `LiveSyncEngine`, tested in `:sync`). Real Room files in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeFeedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun summaries(rows: List<Activity>) = rows.map { it.summary }

    @Test
    fun `AC2 - the window keeps 30 days and drops older and soft-deleted rows`() {
        val rows = listOf(
            activity("inside", DAY.multipliedBy(30).minusSeconds(1)),
            activity("edge", DAY.multipliedBy(30)),
            activity("old", DAY.multipliedBy(30).plusSeconds(1)),
            activity("deleted", Duration.ofSeconds(5), deleted = true),
        )
        assertEquals(listOf("edge", "inside"), summaries(HomeFeed.window(rows, homeNow)).sorted())
    }

    @Test
    fun `AC2 - newest first, ties broken by id descending`() {
        val low = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val high = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val rows = listOf(
            activity("old", Duration.ofSeconds(300)), activity("tie-low", Duration.ofSeconds(100), id = low),
            activity("new", Duration.ofSeconds(10)), activity("tie-high", Duration.ofSeconds(100), id = high),
        )
        assertEquals(listOf("new", "tie-high", "tie-low", "old"), summaries(HomeFeed.window(rows, homeNow)))
    }

    @Test
    fun `AC2 - caps at 20 and keeps the newest`() {
        val rows = (0 until 25).map { activity("r$it", Duration.ofMinutes(it + 1L)) }
        val feed = HomeFeed.window(rows, homeNow)
        assertEquals(20, feed.size)
        assertEquals("r0", feed.first().summary)
        assertEquals("r19", feed.last().summary)
    }

    @Test
    fun `state - all modules off wins, unknown flags never show the prompt`() {
        val on = HomeModules(shopping = true, pantry = false, maintenance = false)
        val off = HomeModules(shopping = false, pantry = false, maintenance = false)
        val one = listOf(activity("a", Duration.ofSeconds(10)))
        assertEquals(HomeState.Empty, HomeFeed.state(emptyList(), on))
        assertEquals(HomeState.Populated, HomeFeed.state(one, on))
        assertEquals(HomeState.AllModulesOff, HomeFeed.state(emptyList(), off))
        assertEquals(HomeState.AllModulesOff, HomeFeed.state(one, off))
        assertEquals(HomeState.Empty, HomeFeed.state(emptyList(), null))
    }

    @Test
    fun `AC5 - upserted rows read back newest first with every column intact, and a foreign row is dropped`() = runBlocking {
        registry.open(household)
        val older = activity("Anna checked off Milk", Duration.ofMinutes(10))
        val newer = activity("Ben joined the household", Duration.ofMinutes(1), module = Activity.Module.core)
            .copy(actorId = UUID.randomUUID())
        val other = activity("someone else's", Duration.ofSeconds(5), householdId = id(2))
        registry.upsertActivity(household, listOf(older, newer, other))

        val rows = registry.activityFeed(household).first()
        assertEquals(listOf("Ben joined the household", "Anna checked off Milk"), summaries(rows))
        assertEquals(newer, rows.first())
    }

    @Test
    fun `the feed re-emits when a pull stores rows, and a replaced row updates in place`() = runBlocking {
        registry.open(household)
        val row = activity("first", Duration.ofSeconds(100))
        registry.upsertActivity(household, listOf(row))
        val later = async { withTimeout(5_000) { registry.activityFeed(household).drop(1).first() } }
        // Let the collector subscribe before the write.
        kotlinx.coroutines.delay(200)
        registry.upsertActivity(household, listOf(row.copy(summary = "edited"), activity("second", Duration.ofSeconds(10))))
        assertEquals(listOf("second", "edited"), summaries(later.await()))
    }

    @Test
    fun `soft-deleted rows are not in the feed, and rows survive a reopen`() = runBlocking {
        registry.open(household)
        registry.upsertActivity(household, listOf(activity("kept", Duration.ofSeconds(5)), activity("gone", Duration.ofSeconds(6), deleted = true)))
        registry.close()
        registry.open(household)
        assertEquals(listOf("kept"), summaries(registry.activityFeed(household).first()))
    }

    @Test
    fun `v1 to v2 migration adds the activity table and keeps the old rows`() = runBlocking {
        // A real version-1 file, built from the committed schema export.
        val v1 = Json.parseToJsonElement(File("../sync/schemas/com.abodenestly.sync.HouseholdDatabase/1.json").readText())
            .jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(registry.fileName(household)).also { it.parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v1["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                e["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                }
            }
            db.execSQL("INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES ('m1', 'shopping_list', 'upsert', 'r1', '{}', 't')")
            db.execSQL("PRAGMA user_version = 1")
        }

        registry.open(household) // Room runs the migration and validates the result against the v2 entities.
        assertEquals(1, registry.pendingCount(household))
        registry.upsertActivity(household, listOf(activity("after migration", Duration.ofSeconds(5))))
        assertEquals(listOf("after migration"), summaries(registry.activityFeed(household).first()))
    }
}
