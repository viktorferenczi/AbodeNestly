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


/**
 * CORE-09: the minimal local copies the household search reads (id, household, name, tombstone). The module stories
 * (SHOP-*, PANT-*, MAINT-*) widen them to the full server columns. Web twin: Dexie tables of the same names.
 */
@Entity(tableName = "list_item", indices = [Index(value = ["household_id"])])
data class ListItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "list_id") val listId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "pantry_item", indices = [Index(value = ["household_id"])])
data class PantryItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "recipe", indices = [Index(value = ["household_id"])])
data class RecipeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "asset", indices = [Index(value = ["household_id"])])
data class AssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "maint_task", indices = [Index(value = ["household_id"])])
data class MaintTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val title: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "store", indices = [Index(value = ["household_id"])])
data class StoreEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "location", indices = [Index(value = ["household_id"])])
data class LocationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(tableName = "contractor", indices = [Index(value = ["household_id"])])
data class ContractorEntity(
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

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ActivityEntity>)

    /** Newest live rows of one household; the screen applies the 30-day / 20-row window, the cap only keeps the flow small. */
    @Query("SELECT * FROM activity WHERE household_id = :householdId AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeFeed(householdId: String, limit: Int): Flow<List<ActivityEntity>>
}

/**
 * One query per searchable table (CORE-09): the household's live rows. The name match is `Search.group` in `:core`
 * (locale-insensitive); SQLite `LIKE` only folds ASCII case, so it is not used. These tables hold names only, so the scan is the household.
 */
@Dao
interface SearchDao {
    @Query("SELECT * FROM list_item WHERE household_id = :householdId AND deleted_at IS NULL")
    fun listItems(householdId: String): Flow<List<ListItemEntity>>

    @Query("SELECT * FROM pantry_item WHERE household_id = :householdId AND deleted_at IS NULL")
    fun pantryItems(householdId: String): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM recipe WHERE household_id = :householdId AND deleted_at IS NULL")
    fun recipes(householdId: String): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM asset WHERE household_id = :householdId AND deleted_at IS NULL")
    fun assets(householdId: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM maint_task WHERE household_id = :householdId AND deleted_at IS NULL")
    fun maintTasks(householdId: String): Flow<List<MaintTaskEntity>>

    @Query("SELECT * FROM store WHERE household_id = :householdId AND deleted_at IS NULL")
    fun stores(householdId: String): Flow<List<StoreEntity>>

    @Query("SELECT * FROM location WHERE household_id = :householdId AND deleted_at IS NULL")
    fun locations(householdId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM contractor WHERE household_id = :householdId AND deleted_at IS NULL")
    fun contractors(householdId: String): Flow<List<ContractorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListItem(row: ListItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPantryItem(row: PantryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipe(row: RecipeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(row: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMaintTask(row: MaintTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStore(row: StoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(row: LocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContractor(row: ContractorEntity)
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

/** v3 adds the CORE-09 search tables. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_SQL.forEach(db::execSQL)
    }
}

// Same statements Room exports in schemas/…/3.json for the search tables.
internal val MIGRATION_2_3_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `list_item` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `list_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_list_item_household_id` ON `list_item` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `pantry_item` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_pantry_item_household_id` ON `pantry_item` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `recipe` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_recipe_household_id` ON `recipe` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `asset` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_asset_household_id` ON `asset` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `maint_task` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `title` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_maint_task_household_id` ON `maint_task` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `store` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_store_household_id` ON `store` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `location` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_location_household_id` ON `location` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `contractor` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `name` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_contractor_household_id` ON `contractor` (`household_id`)",
)

/** One Room database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
@Database(
    entities = [MetaEntity::class, OutboxEntity::class, ShoppingListEntity::class, ActivityEntity::class,
        ListItemEntity::class, PantryItemEntity::class, RecipeEntity::class, AssetEntity::class, MaintTaskEntity::class, StoreEntity::class, LocationEntity::class, ContractorEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class HouseholdDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao
    abstract fun outbox(): OutboxDao
    abstract fun shoppingLists(): ShoppingListDao
    abstract fun activity(): ActivityDao
    abstract fun search(): SearchDao
}
