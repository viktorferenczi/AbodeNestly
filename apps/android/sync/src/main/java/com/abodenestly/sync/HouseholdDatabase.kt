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

/**
 * The server's `shopping_list` columns (SHOP-08 widened the CORE-era placeholder, like iOS v6 and web). Everything
 * past `deleted_at` is nullable: rows written before a pull carry no server stamps yet.
 */
@Entity(tableName = "shopping_list")
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "store_id") val storeId: String? = null,
    @ColumnInfo(name = "is_template") val isTemplate: Boolean? = null,
    @ColumnInfo(name = "share_token") val shareToken: String? = null,
    @ColumnInfo(name = "archived_at") val archivedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
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
 * (SHOP-*, PANT-*, MAINT-*) widen them to the full server columns; SHOP-08 did `list_item`, `store` and `location`.
 * Web twin: Dexie tables of the same names.
 */
@Entity(tableName = "list_item", indices = [Index(value = ["household_id"])])
data class ListItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "list_id") val listId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "name_key") val nameKey: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val note: String? = null,
    val barcode: String? = null,
    val checked: Boolean? = null,
    @ColumnInfo(name = "checked_at") val checkedAt: String? = null,
    @ColumnInfo(name = "checked_by") val checkedBy: String? = null,
    @ColumnInfo(name = "manual_position") val manualPosition: Int? = null,
    val source: String? = null,
    @ColumnInfo(name = "pantry_item_id") val pantryItemId: String? = null,
    @ColumnInfo(name = "maint_task_id") val maintTaskId: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
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
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
)

@Entity(tableName = "location", indices = [Index(value = ["household_id"])])
data class LocationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    val kind: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
)

@Entity(tableName = "contractor", indices = [Index(value = ["household_id"])])
data class ContractorEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val name: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

/**
 * CORE-11: the server's `attachment` columns, so a household's photos and PDFs list offline. Web twin: Dexie `attachment`.
 */
@Entity(tableName = "attachment", indices = [Index(value = ["entity", "entity_id"])])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "storage_path") val storagePath: String,
    val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Int,
    @ColumnInfo(name = "created_by") val createdBy: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
    @ColumnInfo(name = "sync_seq") val syncSeq: Int,
)

/**
 * CORE-12: one notification switch of one person in one household. The server's PK is (user_id, household_id, type);
 * there is no `id`, no soft delete and no row until the first change (a missing row means enabled). Web twin: Dexie `notification_pref`.
 */
@Entity(
    tableName = "notification_pref",
    primaryKeys = ["user_id", "household_id", "type"],
    indices = [Index(value = ["household_id"])],
)
data class NotificationPrefEntity(
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val type: String,
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Int? = null,
)

/**
 * CORE-11: a pick made offline, waiting to upload. Separate from `outbox` on purpose: /sync carries rows, not bytes,
 * and nothing drains `outbox` yet (SHOP-08). `file_path` is relative to the app's files folder and holds the bytes.
 */
@Entity(tableName = "attachment_outbox")
data class AttachmentOutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val filename: String,
    val mime: String,
    val size: Int,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/**
 * A change the server turned down (SPEC §6.3), kept so the sync sheet can offer Retry and Discard. `seq` is the old
 * `outbox.seq`, so Retry puts the row back at its place in the queue. Local-only: never pulled, never pushed.
 */
@Entity(tableName = "outbox_failed")
data class OutboxFailedEntity(
    @PrimaryKey val seq: Long,
    val id: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    val op: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    val data: String,
    @ColumnInfo(name = "client_ts") val clientTs: String,
    val reason: String,
    val detail: String?,
    @ColumnInfo(name = "failed_at") val failedAt: String,
)

/** Pull-only copies of the core tables (SPEC §6.3), same columns as the server. Screens still read their DataStore caches. */
@Entity(tableName = "household")
data class HouseholdEntity(
    @PrimaryKey val id: String,
    val name: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "module_shopping") val moduleShopping: Boolean? = null,
    @ColumnInfo(name = "module_pantry") val modulePantry: Boolean? = null,
    @ColumnInfo(name = "module_maintenance") val moduleMaintenance: Boolean? = null,
    val currency: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
)

@Entity(tableName = "member", primaryKeys = ["household_id", "user_id"])
data class MemberEntity(
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val role: String? = null,
    @ColumnInfo(name = "joined_at") val joinedAt: String? = null,
    @ColumnInfo(name = "left_at") val leftAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
)

@Entity(tableName = "invite", indices = [Index(value = ["household_id"])])
data class InviteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    val code: String? = null,
    val role: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: String? = null,
    @ColumnInfo(name = "max_uses") val maxUses: Int? = null,
    val uses: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
)

/** The learned aisle order of one store (SHOP-06 reads it offline). */
@Entity(tableName = "store_item_order", primaryKeys = ["store_id", "name_key"], indices = [Index(value = ["household_id"])])
data class StoreItemOrderEntity(
    @ColumnInfo(name = "store_id") val storeId: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "household_id") val householdId: String,
    @ColumnInfo(name = "mean_position") val meanPosition: Double? = null,
    val samples: Int? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    @ColumnInfo(name = "sync_seq") val syncSeq: Long? = null,
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

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox_failed ORDER BY seq")
    suspend fun failed(): List<OutboxFailedEntity>

    /** The next batch for /sync: only the tables the server accepts; held-back rows stay queued. */
    @Query("SELECT * FROM outbox WHERE table_name IN (:tables) ORDER BY seq LIMIT :limit")
    suspend fun pushable(tables: List<String>, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM outbox WHERE table_name = :table AND row_id = :rowId")
    suspend fun countTargeting(table: String, rowId: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE table_name IN (:tables)")
    fun observePending(tables: List<String>): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailed(row: OutboxFailedEntity)

    @Query("SELECT * FROM outbox_failed WHERE seq = :seq")
    suspend fun failedBySeq(seq: Long): OutboxFailedEntity?

    @Query("DELETE FROM outbox_failed WHERE seq = :seq")
    suspend fun deleteFailed(seq: Long)

    @Query("SELECT COUNT(*) FROM outbox_failed WHERE table_name = :table AND row_id = :rowId")
    suspend fun countFailedTargeting(table: String, rowId: String): Int

    @Query("SELECT COUNT(*) FROM outbox_failed")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM outbox_failed ORDER BY seq")
    fun observeFailed(): Flow<List<OutboxFailedEntity>>
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

@Dao
interface NotificationPrefDao {
    @Query("SELECT * FROM notification_pref WHERE household_id = :householdId AND user_id = :userId")
    fun observe(householdId: String, userId: String): Flow<List<NotificationPrefEntity>>

    /** An upsert that keeps the pulled `updated_at` / `sync_seq` of an existing row. */
    @Query(
        "INSERT INTO notification_pref (user_id, household_id, type, enabled) VALUES (:userId, :householdId, :type, :enabled) " +
            "ON CONFLICT(user_id, household_id, type) DO UPDATE SET enabled = excluded.enabled",
    )
    suspend fun setEnabled(userId: String, householdId: String, type: String, enabled: Boolean)
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AttachmentEntity)

    @Query("SELECT * FROM attachment WHERE household_id = :householdId AND entity = :entity AND entity_id = :entityId AND deleted_at IS NULL ORDER BY created_at, id")
    fun observe(householdId: String, entity: String, entityId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE household_id = :householdId AND entity = :entity AND entity_id = :entityId AND deleted_at IS NULL ORDER BY created_at, id")
    suspend fun list(householdId: String, entity: String, entityId: String): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(row: AttachmentOutboxEntity)

    @Query("SELECT * FROM attachment_outbox WHERE household_id = :householdId ORDER BY created_at, id")
    suspend fun pending(householdId: String): List<AttachmentOutboxEntity>

    @Query("SELECT file_path FROM attachment_outbox WHERE id = :id")
    suspend fun filePath(id: String): String?

    @Query("DELETE FROM attachment_outbox WHERE id = :id")
    suspend fun remove(id: String)
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

/**
 * One query per restorable table (CORE-10): the household's soft-deleted rows, and the local half of a restore. Same
 * table set as [SearchDao]. A table name cannot be a Room parameter, so each table has its own pair of statements;
 * `DatabaseRegistry.restore` picks by name.
 */
@Dao
interface DeletedDao {
    @Query("SELECT * FROM list_item WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun listItems(householdId: String): Flow<List<ListItemEntity>>

    @Query("UPDATE list_item SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearListItem(id: String): Int

    @Query("SELECT * FROM pantry_item WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun pantryItems(householdId: String): Flow<List<PantryItemEntity>>

    @Query("UPDATE pantry_item SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearPantryItem(id: String): Int

    @Query("SELECT * FROM recipe WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun recipes(householdId: String): Flow<List<RecipeEntity>>

    @Query("UPDATE recipe SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearRecipe(id: String): Int

    @Query("SELECT * FROM asset WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun assets(householdId: String): Flow<List<AssetEntity>>

    @Query("UPDATE asset SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearAsset(id: String): Int

    @Query("SELECT * FROM maint_task WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun maintTasks(householdId: String): Flow<List<MaintTaskEntity>>

    @Query("UPDATE maint_task SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearMaintTask(id: String): Int

    @Query("SELECT * FROM store WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun stores(householdId: String): Flow<List<StoreEntity>>

    @Query("UPDATE store SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearStore(id: String): Int

    @Query("SELECT * FROM location WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun locations(householdId: String): Flow<List<LocationEntity>>

    @Query("UPDATE location SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearLocation(id: String): Int

    @Query("SELECT * FROM contractor WHERE household_id = :householdId AND deleted_at IS NOT NULL")
    fun contractors(householdId: String): Flow<List<ContractorEntity>>

    @Query("UPDATE contractor SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun clearContractor(id: String): Int
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

/** v4 adds the CORE-11 attachment table and its offline upload queue. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

// Same statements Room exports in schemas/…/4.json for the attachment tables.
internal val MIGRATION_3_4_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `attachment` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `entity` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `storage_path` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `created_by` TEXT, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, `deleted_at` TEXT, `sync_seq` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_attachment_entity_entity_id` ON `attachment` (`entity`, `entity_id`)",
    "CREATE TABLE IF NOT EXISTS `attachment_outbox` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `entity` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime` TEXT NOT NULL, `size` INTEGER NOT NULL, `file_path` TEXT NOT NULL, `created_at` TEXT NOT NULL, PRIMARY KEY(`id`))",
)

/** v5 adds the CORE-12 notification switches. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_4_5_SQL.forEach(db::execSQL)
    }
}

// Same statements Room exports in schemas/…/5.json for `notification_pref`. Public so the core tests can run them.
val MIGRATION_4_5_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `notification_pref` (`user_id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `updated_at` TEXT, `sync_seq` INTEGER, PRIMARY KEY(`user_id`, `household_id`, `type`))",
    "CREATE INDEX IF NOT EXISTS `index_notification_pref_household_id` ON `notification_pref` (`household_id`)",
)

/**
 * v6 (SHOP-08): widens `shopping_list`, `list_item`, `store` and `location` to the server columns, adds the pull-only
 * core tables and `outbox_failed`, and resets the pull cursor. Rule (same as web and iOS): a migration that adds a
 * pulled table must reset the cursor, or the rows already behind it are never pulled.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_5_6_SQL.forEach(db::execSQL)
    }
}

// Same statements Room exports in schemas/…/6.json.
val MIGRATION_5_6_SQL = listOf(
    "ALTER TABLE `shopping_list` ADD COLUMN `store_id` TEXT",
    "ALTER TABLE `shopping_list` ADD COLUMN `is_template` INTEGER",
    "ALTER TABLE `shopping_list` ADD COLUMN `share_token` TEXT",
    "ALTER TABLE `shopping_list` ADD COLUMN `archived_at` TEXT",
    "ALTER TABLE `shopping_list` ADD COLUMN `created_at` TEXT",
    "ALTER TABLE `shopping_list` ADD COLUMN `updated_at` TEXT",
    "ALTER TABLE `shopping_list` ADD COLUMN `sync_seq` INTEGER",
    "ALTER TABLE `list_item` ADD COLUMN `name_key` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `quantity` REAL",
    "ALTER TABLE `list_item` ADD COLUMN `unit` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `note` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `barcode` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `checked` INTEGER",
    "ALTER TABLE `list_item` ADD COLUMN `checked_at` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `checked_by` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `manual_position` INTEGER",
    "ALTER TABLE `list_item` ADD COLUMN `source` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `pantry_item_id` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `maint_task_id` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `created_by` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `created_at` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `updated_at` TEXT",
    "ALTER TABLE `list_item` ADD COLUMN `sync_seq` INTEGER",
    "ALTER TABLE `store` ADD COLUMN `created_at` TEXT",
    "ALTER TABLE `store` ADD COLUMN `updated_at` TEXT",
    "ALTER TABLE `store` ADD COLUMN `sync_seq` INTEGER",
    "ALTER TABLE `location` ADD COLUMN `kind` TEXT",
    "ALTER TABLE `location` ADD COLUMN `created_at` TEXT",
    "ALTER TABLE `location` ADD COLUMN `updated_at` TEXT",
    "ALTER TABLE `location` ADD COLUMN `sync_seq` INTEGER",
    "CREATE TABLE IF NOT EXISTS `outbox_failed` (`seq` INTEGER NOT NULL, `id` TEXT NOT NULL, `table_name` TEXT NOT NULL, `op` TEXT NOT NULL, `row_id` TEXT NOT NULL, `data` TEXT NOT NULL, `client_ts` TEXT NOT NULL, `reason` TEXT NOT NULL, `detail` TEXT, `failed_at` TEXT NOT NULL, PRIMARY KEY(`seq`))",
    "CREATE TABLE IF NOT EXISTS `household` (`id` TEXT NOT NULL, `name` TEXT, `created_by` TEXT, `module_shopping` INTEGER, `module_pantry` INTEGER, `module_maintenance` INTEGER, `currency` TEXT, `created_at` TEXT, `updated_at` TEXT, `deleted_at` TEXT, `sync_seq` INTEGER, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `member` (`household_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT, `joined_at` TEXT, `left_at` TEXT, `updated_at` TEXT, `sync_seq` INTEGER, PRIMARY KEY(`household_id`, `user_id`))",
    "CREATE TABLE IF NOT EXISTS `invite` (`id` TEXT NOT NULL, `household_id` TEXT NOT NULL, `code` TEXT, `role` TEXT, `created_by` TEXT, `expires_at` TEXT, `max_uses` INTEGER, `uses` INTEGER, `created_at` TEXT, `updated_at` TEXT, `deleted_at` TEXT, `sync_seq` INTEGER, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS `index_invite_household_id` ON `invite` (`household_id`)",
    "CREATE TABLE IF NOT EXISTS `store_item_order` (`store_id` TEXT NOT NULL, `name_key` TEXT NOT NULL, `household_id` TEXT NOT NULL, `mean_position` REAL, `samples` INTEGER, `updated_at` TEXT, `sync_seq` INTEGER, PRIMARY KEY(`store_id`, `name_key`))",
    "CREATE INDEX IF NOT EXISTS `index_store_item_order_household_id` ON `store_item_order` (`household_id`)",
    "UPDATE `meta` SET `value` = '0' WHERE `key` = 'cursor'",
)

/** One Room database per household (SPEC §6.1): own rows, own outbox, own pull cursor. */
@Database(
    entities = [MetaEntity::class, OutboxEntity::class, ShoppingListEntity::class, ActivityEntity::class,
        ListItemEntity::class, PantryItemEntity::class, RecipeEntity::class, AssetEntity::class, MaintTaskEntity::class, StoreEntity::class, LocationEntity::class, ContractorEntity::class,
        AttachmentEntity::class, AttachmentOutboxEntity::class, NotificationPrefEntity::class,
        OutboxFailedEntity::class, HouseholdEntity::class, MemberEntity::class, InviteEntity::class, StoreItemOrderEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class HouseholdDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao
    abstract fun outbox(): OutboxDao
    abstract fun shoppingLists(): ShoppingListDao
    abstract fun activity(): ActivityDao
    abstract fun search(): SearchDao
    abstract fun deleted(): DeletedDao
    abstract fun attachments(): AttachmentDao
    abstract fun notificationPrefs(): NotificationPrefDao
}
