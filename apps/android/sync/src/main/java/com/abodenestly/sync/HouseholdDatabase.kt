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

/** One Room database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
@Database(
    entities = [MetaEntity::class, OutboxEntity::class, ShoppingListEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HouseholdDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao
    abstract fun outbox(): OutboxDao
    abstract fun shoppingLists(): ShoppingListDao
}
