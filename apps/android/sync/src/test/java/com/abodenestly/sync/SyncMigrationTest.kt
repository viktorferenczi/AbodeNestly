package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** v5 → v6 (SHOP-08): rows and queued writes survive, the new columns read null, the cursor restarts at 0. v6 → v7 (SHOP-12): `item_price`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    @Test
    fun `5 to 6 keeps existing rows and the outbox, adds the columns and resets the cursor`() = runBlocking {
        // A real version-5 file, built from the committed schema export.
        val v5 = Json.parseToJsonElement(File("schemas/com.abodenestly.sync.HouseholdDatabase/5.json").readText())
            .jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(registry.fileName(household)).also { it.parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v5["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            db.execSQL("INSERT INTO shopping_list (id, household_id, name) VALUES ('${uuid(10)}', '$household', 'Groceries')")
            db.execSQL("INSERT INTO list_item (id, household_id, list_id, name) VALUES ('i1', '$household', '${uuid(10)}', 'Milk')")
            db.execSQL("INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES ('m1', 'list_item', 'upsert', 'i1', '{}', 't')")
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('cursor', '42')")
            db.execSQL("PRAGMA user_version = 5")
        }

        registry.open(household) // Room runs MIGRATION_5_6 and validates the result against the v6 entities.
        assertEquals(listOf("Groceries"), registry.lists().map { it.name })
        assertEquals(1, registry.pendingCount(household))
        assertEquals(0L, registry.readCursor())
        val item = registry.withSearchDao { it.listItems(household.toString()).first() }.single()
        assertEquals("Milk", item.name)
        assertEquals(null, item.quantity)
        assertEquals(null, item.syncSeq)
        // The new tables exist and take rows.
        registry.applyPull(household, SyncPullPage(cursor = 1, changes = mapOf("member" to listOf(kotlinx.serialization.json.buildJsonObject {
            put("household_id", kotlinx.serialization.json.JsonPrimitive(household.toString()))
            put("user_id", kotlinx.serialization.json.JsonPrimitive("u1"))
            put("role", kotlinx.serialization.json.JsonPrimitive("owner"))
        }))))
        assertEquals(1L, registry.readCursor())
    }
    @Test
    fun `6 to 7 adds item_price, keeps rows and the outbox, resets the cursor and stores a pulled number as canonical text`() = runBlocking {
        // A real version-6 file, built from the committed schema export.
        val v6 = Json.parseToJsonElement(File("schemas/com.abodenestly.sync.HouseholdDatabase/6.json").readText())
            .jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(registry.fileName(household)).also { it.parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v6["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            db.execSQL("INSERT INTO shopping_list (id, household_id, name) VALUES ('${uuid(10)}', '$household', 'Groceries')")
            db.execSQL("INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES ('m1', 'list_item', 'upsert', 'i1', '{}', 't')")
            db.execSQL("INSERT INTO meta (`key`, value) VALUES ('cursor', '42')")
            db.execSQL("PRAGMA user_version = 6")
        }

        registry.open(household) // Room runs MIGRATION_6_7 and validates the result against the v7 entities.
        assertEquals(listOf("Groceries"), registry.lists().map { it.name })
        assertEquals(1, registry.pendingCount(household))
        assertEquals(0L, registry.readCursor())
        assertEquals(emptyList<ItemPriceRecord>(), registry.observeListDetail(household, "l1").first().prices)
        // The new table takes a pulled row; the JSON number 1.20 lands as the canonical text "1.2", not a binary float.
        val row = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive("p1"))
            put("store_id", JsonPrimitive("s1"))
            put("name_key", JsonPrimitive("milk"))
            put("price", JsonPrimitive(java.math.BigDecimal("1.20")))
            put("seen_at", JsonPrimitive("2026-11-03T10:00:00.000000+00:00"))
        }
        registry.applyPull(household, SyncPullPage(cursor = 1, changes = mapOf("item_price" to listOf(row))))
        val price = registry.observeListDetail(household, "l1").first().prices.single()
        assertEquals("1.2", price.price.toPlainString())
        assertEquals("2026-11-03T10:00:00.000Z", price.seenAt)
        assertEquals(1L, registry.readCursor())
    }
}
