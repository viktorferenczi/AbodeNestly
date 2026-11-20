package com.abodenestly.core

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.notificationprefs.NotificationPrefs
import com.abodenestly.core.notificationprefs.NotificationType
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.MIGRATION_4_5_SQL
import com.abodenestly.sync.NotificationPrefRow
import com.abodenestly.sync.SearchModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

// Golden values: identical in the web and iOS suites.
private val USER: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
private val HOUSE: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
private const val GOLDEN_EAT_SOON = "38c53767-3538-5d08-9384-d9843b1761ad"
private const val GOLDEN_ACTIVITY = "4aa3e84e-d57f-53f9-8472-e9fcf82e8393"
private val NOW: Instant = Instant.parse("2026-11-20T10:00:00Z")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    @Test
    fun `CORE-12 row_id is the cross-platform golden UUID v5`() {
        assertEquals(GOLDEN_EAT_SOON, NotificationPrefs.rowId(USER, HOUSE, NotificationType.EatSoon).toString())
        assertEquals(GOLDEN_ACTIVITY, NotificationPrefs.rowId(USER, HOUSE, NotificationType.Activity).toString())
    }

    @Test
    fun `CORE-12 types keep the DESIGN order and labels`() {
        assertEquals(listOf("Eat soon", "Low stock", "Task due", "Warranty", "Activity"), NotificationType.entries.map { it.label })
        assertEquals(listOf("eat_soon", "low_stock", "task_due", "warranty", "activity"), NotificationType.entries.map { it.key })
    }

    @Test
    fun `CORE-12 AC1 AC4 AC5 visible rows match the module flags`() {
        val cases = listOf(
            SearchModule.entries.toSet() to listOf("eat_soon", "low_stock", "task_due", "warranty", "activity"),
            setOf(SearchModule.Shopping, SearchModule.Maintenance) to listOf("task_due", "warranty", "activity"),
            setOf(SearchModule.Shopping, SearchModule.Pantry) to listOf("eat_soon", "low_stock", "activity"),
            setOf(SearchModule.Shopping) to listOf("activity"),
        )
        cases.forEach { (modules, expected) ->
            assertEquals("$modules", expected, NotificationPrefs.visibleTypes(modules).map { it.key })
        }
    }

    @Test
    fun `CORE-12 a missing row means enabled`() {
        val off = NotificationPrefRow(USER, HOUSE, "eat_soon", false)
        assertTrue(NotificationPrefs.enabled(emptyList(), NotificationType.EatSoon))
        assertFalse(NotificationPrefs.enabled(listOf(off), NotificationType.EatSoon))
        assertTrue(NotificationPrefs.enabled(listOf(off), NotificationType.LowStock))
    }

    @Test
    fun `CORE-12 AC2 turning Eat soon off writes the row and queues one upsert with the v5 row_id`() = runBlocking {
        registry.open(HOUSE)
        registry.setNotificationPref(USER, HOUSE, "eat_soon", false, NOW)
        val out = registry.outboxForTesting()
        assertEquals(1, out.size)
        assertEquals("notification_pref", out[0].tableName)
        assertEquals("upsert", out[0].op)
        assertEquals(GOLDEN_EAT_SOON, out[0].rowId)
        assertEquals(
            """{"user_id":"$USER","household_id":"$HOUSE","type":"eat_soon","enabled":false}""",
            out[0].data,
        )
        assertEquals(listOf(NotificationPrefRow(USER, HOUSE, "eat_soon", false)), registry.observeNotificationPrefs(HOUSE, USER).first())
    }

    @Test
    fun `CORE-12 toggling twice keeps one local row and queues two upserts`() = runBlocking {
        registry.open(HOUSE)
        registry.setNotificationPref(USER, HOUSE, "eat_soon", false, NOW)
        registry.setNotificationPref(USER, HOUSE, "eat_soon", true, NOW)
        assertEquals(listOf(NotificationPrefRow(USER, HOUSE, "eat_soon", true)), registry.observeNotificationPrefs(HOUSE, USER).first())
        assertEquals(2, registry.outboxForTesting().size)
    }

    @Test
    fun `CORE-12 another person's rows are not mine`() = runBlocking {
        registry.open(HOUSE)
        val other = UUID.fromString("33333333-3333-4333-8333-333333333333")
        registry.setNotificationPref(other, HOUSE, "eat_soon", false, NOW)
        assertEquals(emptyList<NotificationPrefRow>(), registry.observeNotificationPrefs(HOUSE, USER).first())
    }

    @Test
    fun `CORE-12 migration 4 to 5 creates a table keyed by user, household and type`() {
        val db = SQLiteDatabase.create(null)
        try {
            MIGRATION_4_5_SQL.forEach(db::execSQL)
            val insert = "INSERT INTO notification_pref (user_id, household_id, type, enabled) VALUES ('u', 'h', 'eat_soon', 0)"
            db.execSQL(insert)
            try {
                db.execSQL(insert)
                fail("the primary key must reject a second row for the same switch")
            } catch (_: SQLiteConstraintException) {
            }
            db.execSQL("INSERT INTO notification_pref (user_id, household_id, type, enabled) VALUES ('u', 'h', 'low_stock', 1)")
            db.rawQuery("SELECT COUNT(*) FROM notification_pref", null).use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
