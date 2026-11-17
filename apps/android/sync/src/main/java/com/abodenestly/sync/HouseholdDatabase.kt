package com.abodenestly.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** `meta` key/value rows; today only `cursor` (SPEC §6.1). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A queued write in the SPEC §6.2 envelope. `id` is the client-generated mutation id the server dedupes on. */
@Entity(tableName = "outbox", indices = [Index(value = ["id"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    val op: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    val data: String,
    @ColumnInfo(name = "client_ts") val clientTs: String,
)

/** Placeholder until SHOP-01/08 replace it with the real list table (same as iOS and web). */
@Entity(tableName = "shopping_list")
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

/**
 * The server's `activity` columns (CORE-08), so the Home feed reads offline. Timestamps are fixed-width UTC text
 * (`DatabaseRegistry.stamp`), so ordering by the column is ordering by time. Web twin: Dexie `activity`.
 */
@Entity(tableName = "activity", indices = [Index(value = ["household_id", "created_at"])])
data class ActivityEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "actor_id") val actorId: String?,
    val module: String,
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val action: String,
    val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
    @ColumnInfo(name = "sync_seq") val syncSeq: Int,
)

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putIfAbsent(entity: MetaEntity)
}

@Dao
interface OutboxDao {
    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: OutboxEntity)
}

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_list WHERE deleted_at IS NULL ORDER BY name")
    suspend fun all(): List<ShoppingListEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShoppingListEntity)
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ActivityEntity>)

    /** Newest live rows of one household; the screen applies the 30-day / 20-row window, the cap only keeps the flow small. */
    @Query("SELECT * FROM activity WHERE household_id = :householdId AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeFeed(householdId: String, limit: Int): Flow<List<ActivityEntity>>
}

/** Append-only (kotlin-android skill): v2 adds the CORE-08 `activity` table. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach(db::execSQL)
    }
}

// Same statements Room exports in schemas/…/2.json for `activity`.
internal val MIGRATION_1_2_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `activity` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `actor_id` TEXT, `module` TEXT NOT NULL, `entity` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `action` TEXT NOT NULL, `summary` TEXT NOT NULL, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, `deleted_at` TEXT, `sync_seq` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_activity_household_id_created_at` ON `activity` (`household_id`, `created_at`)",
)

/** One Room database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
@Database(
    entities = [MetaEntity::class, OutboxEntity::class, ShoppingListEntity::class, ActivityEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class HouseholdDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao
    abstract fun outbox(): OutboxDao
    abstract fun shoppingLists(): ShoppingListDao
    abstract fun activity(): ActivityDao
}
