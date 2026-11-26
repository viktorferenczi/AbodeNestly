package com.abodenestly.sync

import android.content.Context
import com.abodenestly.contract.models.Activity
import com.abodenestly.contract.models.Attachment
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A pick waiting to upload (CORE-11). The bytes are a file under the app's files folder; [filePath] is relative to it. */
data class AttachmentUpload(
    val id: UUID,
    val householdId: UUID,
    val entity: String,
    val entityId: UUID,
    val filename: String,
    val mime: String,
    val size: Int,
    val filePath: String = "",
    val createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
)

/** The modules whose names CORE-09 searches, with the local tables each owns (SPEC §4 CORE-09; open question in the story). */
enum class SearchModule(val key: String, val tables: List<String>) {
    Shopping("shopping", listOf("list_item", "store")),
    Pantry("pantry", listOf("pantry_item", "location", "recipe")),
    Maintenance("maintenance", listOf("asset", "maint_task", "contractor")),
}

/** One name found in a local table. [listId] is set for list items only (the list they sit on). */
data class SearchRow(
    val table: String,
    val id: String,
    val name: String,
    val listId: String? = null,
    val deletedAt: String? = null,
)

/** A soft-deleted row of a restorable table (CORE-10). [name] is `title` for `maint_task`. [deletedAt] is the stored text, ISO 8601. */
data class DeletedRow(val table: String, val id: String, val name: String, val deletedAt: String)

/** One notification switch (CORE-12). [type] is the contract's `NotificationType` value. */
data class NotificationPrefRow(val userId: UUID, val householdId: UUID, val type: String, val enabled: Boolean)

/**
 * UUID v5 (RFC 4122 §4.3) of a switch: the outbox `row_id`, identical on web, iOS and Android because the table has no
 * `id`. Lives here because [DatabaseRegistry.setNotificationPref] derives it; core's `NotificationPrefs` forwards to it.
 */
object NotificationPrefId {
    private val URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    fun rowId(userId: UUID, householdId: UUID, type: String): UUID {
        val name = "abodenestly:notification_pref:$userId:$householdId:$type".lowercase()
        val ns = ByteBuffer.allocate(16).putLong(URL_NAMESPACE.mostSignificantBits).putLong(URL_NAMESPACE.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").digest(ns + name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buf = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buf.long, buf.long)
    }
}

/** A read or write that needs the open household database, with none open. */
class DatabaseNotOpenException : IllegalStateException("No household database is open")

/** A row of the placeholder `shopping_list` table. */
data class ShoppingList(val id: UUID, val householdId: UUID, val name: String)

/**
 * Opens and closes the household databases; port of iOS `HouseholdDatabase` (the actor). Switching closes the
 * database of the household being left before the next opens. [seedLists] (E2E only) names one list to insert
 * when a household is opened without any.
 */
open class DatabaseRegistry(
    private val context: Context,
    private val seedLists: Map<UUID, String> = emptyMap(),
) {
    private val mutex = Mutex()
    private var database: HouseholdDatabase? = null
    private val mutableCurrent = MutableStateFlow<UUID?>(null)

    /** The household whose database is open, if any. */
    val current: StateFlow<UUID?> = mutableCurrent.asStateFlow()

    fun fileName(id: UUID): String = "household-$id.db"

    /**
     * Opens (creating it on first use) the household's file and seeds `meta.cursor = 0`. Opening the open one is a no-op.
     * [isCurrent] is asked under the registry lock once the file is ready, before anything is swapped: when it says the
     * caller is stale (signed out, another account) the new file is closed and the open one stays; returns `false`.
     */
    open suspend fun open(id: UUID, isCurrent: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutableCurrent.value == id && database != null) return@withLock isCurrent()
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).addMigrations(*MIGRATIONS).build()
            try {
                db.meta().putIfAbsent(MetaEntity(CURSOR, "0"))
                val seed = seedLists[id]
                if (seed != null && db.shoppingLists().all().isEmpty()) {
                    db.shoppingLists().upsert(ShoppingListEntity(UUID.randomUUID().toString(), id.toString(), seed))
                }
            } catch (e: Throwable) {
                db.close()
                throw e
            }
            if (!isCurrent()) {
                db.close()
                return@withLock false
            }
            closeLocked()
            database = db
            mutableCurrent.value = id
            true
        }
    }

    /** Releases the open database. Safe to call when nothing is open. */
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { closeLocked() } }

    private fun closeLocked() {
        database?.close()
        database = null
        mutableCurrent.value = null
    }

    // Cursor: SHOP-08's pull loop moves it; this story only stores it.

    /** `last_seq` is a bigint (SPEC §6.1), so the cursor is a `Long`. */
    suspend fun readCursor(): Long = withOpen { it.meta().get(CURSOR)?.toLongOrNull() ?: 0L }

    suspend fun writeCursor(value: Long) = withOpen { it.meta().put(MetaEntity(CURSOR, value.toString())) }

    /**
     * Queued writes of a household. Counts on the open database when it is that household's own; otherwise a
     * short-lived read of the file, if it exists (a household never opened has nothing queued).
     */
    suspend fun pendingCount(id: UUID): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val open = database
            if (open != null && mutableCurrent.value == id) return@withLock open.outbox().count()
            if (!context.applicationContext.getDatabasePath(fileName(id)).exists()) return@withLock 0
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).addMigrations(*MIGRATIONS).build()
            try {
                db.outbox().count()
            } finally {
                db.close()
            }
        }
    }

    // Activity (CORE-08)

    /**
     * Stores pulled rows of household [id]. Dropped when [id] is not the open household: a pull that was in flight
     * when the person switched must not write into the new household's file.
     */
    suspend fun upsertActivity(id: UUID, rows: List<Activity>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val open = database
            if (open == null || mutableCurrent.value != id) return@withLock
            val own = rows.filter { it.householdId == id }
            if (own.isNotEmpty()) open.activity().upsertAll(own.map(::toEntity))
        }
    }

    /**
     * The household's newest stored rows, re-emitted when they change (cached rows read offline, SPEC §6.5). Follows the
     * open database: nothing is emitted while [id] is not the open household, and a switch away ends the inner flow.
     */
    fun activityFeed(id: UUID): Flow<List<Activity>> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else db.activity().observeFeed(id.toString(), FEED_ROWS)
            .map { rows -> rows.mapNotNull(::toActivity) }
            // The database may close under the collector during a switch.
            .catch { }
    }

    // Sync status (SHOP-08)

    /**
     * What the chip and sheet count: queued writes of the tables the server accepts plus failed ones. Held-back core
     * tables (`notification_pref`) are not counted, like iOS `observeSyncCounts`. Follows the open database like [activityFeed].
     */
    fun observeSyncCounts(id: UUID): Flow<SyncCounts> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else combine(db.outbox().observePending(PUSHABLE_TABLES.toList()), db.outbox().observeFailedCount()) { pending, failed -> SyncCounts(pending, failed) }
            .catch { }
    }

    /** The changes the server turned down, oldest first. Follows the open database like [activityFeed]. */
    fun observeFailed(id: UUID): Flow<List<FailedChange>> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else db.outbox().observeFailed()
            .map { rows -> rows.map { FailedChange(it.seq, it.tableName, it.op, it.rowId, it.reason, it.detail) } }
            .catch { }
    }

    // Attachments (CORE-11)

    /** Live attachments of one entity, re-emitted when they change; follows the open database like [activityFeed]. */
    fun observeAttachments(id: UUID, entity: String, entityId: UUID): Flow<List<Attachment>> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else db.attachments().observe(id.toString(), entity, entityId.toString())
            .map { rows -> rows.mapNotNull(::toAttachment) }
            .catch { }
    }

    /** The rows [observeAttachments] emits, read once. */
    suspend fun attachments(id: UUID, entity: String, entityId: UUID): List<Attachment> = withOpenFor(id) {
        it.attachments().list(id.toString(), entity, entityId.toString()).mapNotNull(::toAttachment)
    }

    /** Stores an attachment after its bytes reached Storage. Dropped when its household is not the open one. */
    suspend fun upsertAttachment(row: Attachment) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val open = database
            if (open == null || mutableCurrent.value != row.householdId) return@withLock
            open.attachments().upsert(toEntity(row))
        }
    }

    /** Writes the bytes under the files folder, then queues the pick, so a queued row always has its file. */
    suspend fun enqueueAttachmentUpload(upload: AttachmentUpload, bytes: ByteArray): AttachmentUpload = withOpenFor(upload.householdId) { db ->
        val relative = "attachments/${upload.householdId}/${upload.id}.bin"
        val file = File(context.applicationContext.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val queued = upload.copy(filePath = relative)
        db.attachments().enqueue(
            AttachmentOutboxEntity(
                id = queued.id.toString(), householdId = queued.householdId.toString(), entity = queued.entity,
                entityId = queued.entityId.toString(), filename = queued.filename, mime = queued.mime, size = queued.size,
                filePath = relative, createdAt = stamp(queued.createdAt),
            ),
        )
        queued
    }

    /** Queued picks of the open household in creation order, optionally only one entity's. */
    suspend fun pendingAttachmentUploads(id: UUID, entity: String? = null, entityId: UUID? = null): List<AttachmentUpload> = withOpenFor(id) { db ->
        db.attachments().pending(id.toString())
            .mapNotNull(::toUpload)
            .filter { (entity == null || it.entity == entity) && (entityId == null || it.entityId == entityId) }
    }

    /** The bytes a queued pick was saved with. */
    suspend fun attachmentBytes(upload: AttachmentUpload): ByteArray = withContext(Dispatchers.IO) {
        File(context.applicationContext.filesDir, upload.filePath).readBytes()
    }

    /** Drops a queued pick and its file, after it uploaded. */
    suspend fun removeAttachmentUpload(id: UUID, uploadId: UUID) = withOpenFor(id) { db ->
        val path = db.attachments().filePath(uploadId.toString())
        db.attachments().remove(uploadId.toString())
        if (path != null) File(context.applicationContext.filesDir, path).delete()
    }

    // Notification preferences (CORE-12)

    /** My switches in household [id], re-emitted when they change; follows the open database like [activityFeed]. A type with no row is enabled (the caller applies that default). */
    fun observeNotificationPrefs(id: UUID, userId: UUID): Flow<List<NotificationPrefRow>> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else db.notificationPrefs().observe(id.toString(), userId.toString())
            .map { rows -> rows.mapNotNull { r -> runCatching { NotificationPrefRow(UUID.fromString(r.userId), UUID.fromString(r.householdId), r.type, r.enabled) }.getOrNull() } }
            .catch { }
    }

    /**
     * One local transaction: upsert my row and queue the SPEC §6.2 upsert, so the two cannot diverge. The caller kicks sync.
     * `notification_pref` is not in SPEC's SyncTable yet; SHOP-08-api is to accept it and key the row off `data`.
     * Throws [DatabaseNotOpenException] when [householdId] is not the open household.
     */
    suspend fun setNotificationPref(userId: UUID, householdId: UUID, type: String, enabled: Boolean, now: Instant) = withContext(Dispatchers.IO) {
        require(type.matches(Regex("[a-z_]+"))) { "Not a notification type: $type" }
        mutex.withLock {
            val db = database
            if (db == null || mutableCurrent.value != householdId) throw DatabaseNotOpenException()
            val user = userId.toString().lowercase()
            val household = householdId.toString().lowercase()
            db.withTransaction {
                db.notificationPrefs().setEnabled(user, household, type, enabled)
                db.outbox().insert(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(), tableName = "notification_pref", op = "upsert",
                        rowId = NotificationPrefId.rowId(userId, householdId, type).toString(),
                        data = """{"user_id":"$user","household_id":"$household","type":"$type","enabled":$enabled}""",
                        clientTs = now.toString(),
                    ),
                )
            }
        }
    }

    // Search (CORE-09)

    /**
     * The live rows of [modules]' tables, re-emitted when those tables change; a blank [query] reads nothing. The
     * name match is `Search.group`'s, so every live row of the household comes back. Only enabled modules' tables are queried (AC4). Follows the open database like
     * [activityFeed]; nothing is emitted while [id] is not the open household.
     */
    fun searchNames(id: UUID, query: String, modules: Set<SearchModule>): Flow<List<SearchRow>> {
        val q = query.trim()
        if (q.isEmpty() || modules.isEmpty()) return flowOf(emptyList())
        return current.flatMapLatest { open ->
            val db = database
            if (open != id || db == null) emptyFlow()
            else {
                val dao = db.search()
                val hid = id.toString()
                val tables = modules.flatMap { it.tables }.toSet()
                val flows = buildList<Flow<List<SearchRow>>> {
                    if ("list_item" in tables) add(dao.listItems(hid).map { r -> r.map { SearchRow("list_item", it.id, it.name, it.listId, it.deletedAt) } })
                    if ("store" in tables) add(dao.stores(hid).map { r -> r.map { SearchRow("store", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("pantry_item" in tables) add(dao.pantryItems(hid).map { r -> r.map { SearchRow("pantry_item", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("location" in tables) add(dao.locations(hid).map { r -> r.map { SearchRow("location", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("recipe" in tables) add(dao.recipes(hid).map { r -> r.map { SearchRow("recipe", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("asset" in tables) add(dao.assets(hid).map { r -> r.map { SearchRow("asset", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("maint_task" in tables) add(dao.maintTasks(hid).map { r -> r.map { SearchRow("maint_task", it.id, it.title, deletedAt = it.deletedAt) } })
                    if ("contractor" in tables) add(dao.contractors(hid).map { r -> r.map { SearchRow("contractor", it.id, it.name, deletedAt = it.deletedAt) } })
                }
                combine(flows) { parts -> parts.toList().flatten() }
                    // The database may close under the collector during a switch.
                    .catch { }
            }
        }
    }

    // Recently deleted (CORE-10)

    /**
     * The soft-deleted rows of [modules]' tables, re-emitted when those tables change. The 30-day cut-off is the
     * caller's (`RecentlyDeleted.sections`), because the purge sends no tombstone. Follows the open database like [searchNames].
     */
    fun deletedRows(id: UUID, modules: Set<SearchModule>): Flow<List<DeletedRow>> {
        if (modules.isEmpty()) return flowOf(emptyList())
        return current.flatMapLatest { open ->
            val db = database
            if (open != id || db == null) emptyFlow()
            else {
                val dao = db.deleted()
                val hid = id.toString()
                val tables = modules.flatMap { it.tables }.toSet()
                val flows = buildList<Flow<List<DeletedRow>>> {
                    if ("list_item" in tables) add(dao.listItems(hid).map { r -> r.map { DeletedRow("list_item", it.id, it.name, it.deletedAt!!) } })
                    if ("store" in tables) add(dao.stores(hid).map { r -> r.map { DeletedRow("store", it.id, it.name, it.deletedAt!!) } })
                    if ("pantry_item" in tables) add(dao.pantryItems(hid).map { r -> r.map { DeletedRow("pantry_item", it.id, it.name, it.deletedAt!!) } })
                    if ("location" in tables) add(dao.locations(hid).map { r -> r.map { DeletedRow("location", it.id, it.name, it.deletedAt!!) } })
                    if ("recipe" in tables) add(dao.recipes(hid).map { r -> r.map { DeletedRow("recipe", it.id, it.name, it.deletedAt!!) } })
                    if ("asset" in tables) add(dao.assets(hid).map { r -> r.map { DeletedRow("asset", it.id, it.name, it.deletedAt!!) } })
                    if ("maint_task" in tables) add(dao.maintTasks(hid).map { r -> r.map { DeletedRow("maint_task", it.id, it.title, it.deletedAt!!) } })
                    if ("contractor" in tables) add(dao.contractors(hid).map { r -> r.map { DeletedRow("contractor", it.id, it.name, it.deletedAt!!) } })
                }
                combine(flows) { parts -> parts.toList().flatten() }
                    // The database may close under the collector during a switch.
                    .catch { }
            }
        }
    }

    /**
     * Restores one soft-deleted row of household [id]: clears `deleted_at` and queues the upsert in one transaction, so the
     * two cannot diverge. `data` is partial on purpose (the server merges it, so a rename made elsewhere survives) and the
     * null is written out: a missing key would mean "unchanged". Nothing is queued when no local row was restored. Throws [DatabaseNotOpenException] when [id] is not the open household.
     */
    suspend fun restore(id: UUID, table: String, rowId: String, now: Instant) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = database
            if (db == null || mutableCurrent.value != id) throw DatabaseNotOpenException()
            val dao = db.deleted()
            db.withTransaction {
                val changed = when (table) {
                    "list_item" -> dao.clearListItem(rowId)
                    "store" -> dao.clearStore(rowId)
                    "pantry_item" -> dao.clearPantryItem(rowId)
                    "location" -> dao.clearLocation(rowId)
                    "recipe" -> dao.clearRecipe(rowId)
                    "asset" -> dao.clearAsset(rowId)
                    "maint_task" -> dao.clearMaintTask(rowId)
                    "contractor" -> dao.clearContractor(rowId)
                    else -> throw IllegalArgumentException("Not restorable: $table")
                }
                // Nothing matched (a second tap, or a row the local table no longer has): queuing the partial upsert would insert a bare row on the server.
                if (changed == 0) return@withTransaction
                db.outbox().insert(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(), tableName = table, op = "upsert", rowId = rowId,
                        data = RESTORE_DATA, clientTs = now.toString(),
                    ),
                )
            }
        }
    }

    /** Test seam (and the future module stories' pull): runs [block] against the search DAO of the open household. */
    suspend fun <T> withSearchDao(block: suspend (SearchDao) -> T): T = withOpen { block(it.search()) }

    // Placeholder lists (replaced by SHOP-01/08).

    suspend fun lists(): List<ShoppingList> = withOpen { db ->
        db.shoppingLists().all().mapNotNull { row ->
            val id = runCatching { UUID.fromString(row.id) }.getOrNull()
            val household = runCatching { UUID.fromString(row.householdId) }.getOrNull()
            if (id == null || household == null) null else ShoppingList(id, household, row.name)
        }
    }

    suspend fun insertList(list: ShoppingList) = withOpen {
        it.shoppingLists().upsert(ShoppingListEntity(list.id.toString(), list.householdId.toString(), list.name))
    }

    /** Test seam: the queued mutations in insertion order. */
    suspend fun outboxForTesting(): List<OutboxEntity> = withOpen { it.outbox().all() }

    /** Test seam: queues one mutation so pending counts have something to count. */
    suspend fun enqueueForTesting(table: String = "shopping_list", rowId: UUID = UUID.randomUUID()) = withOpen {
        it.outbox().insert(
            OutboxEntity(id = UUID.randomUUID().toString(), tableName = table, op = "upsert", rowId = rowId.toString(), data = "{}", clientTs = Instant.now().toString()),
        )
    }

    /** Like [withOpen], but only when [id] is the open household; another household's rows are not this file's. */
    internal suspend fun <T> withOpenFor(id: UUID, block: suspend (HouseholdDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutableCurrent.value != id) throw DatabaseNotOpenException()
            block(database ?: throw DatabaseNotOpenException())
        }
    }

    internal suspend fun <T> withOpen(block: suspend (HouseholdDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(database ?: throw DatabaseNotOpenException()) }
    }

    internal companion object {
        const val CURSOR = "cursor"
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        const val FEED_ROWS = 100
        const val RESTORE_DATA = """{"deleted_at":null}"""

        /** Fixed width, so comparing the text compares the time. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun stamp(at: OffsetDateTime): String = STAMP.format(at)

        /** Normalises any ISO 8601 text the server sends (`…123456+00:00`) to [STAMP]'s fixed width; text that does not parse is kept. */
        fun normalizeStamp(text: String): String = runCatching { stamp(OffsetDateTime.parse(text)) }.getOrDefault(text)

        fun toEntity(a: Activity) = ActivityEntity(
            id = a.id.toString(), householdId = a.householdId.toString(), actorId = a.actorId?.toString(),
            module = a.module.value, entity = a.entity, entityId = a.entityId.toString(), action = a.action,
            summary = a.summary, createdAt = stamp(a.createdAt), updatedAt = stamp(a.updatedAt),
            deletedAt = a.deletedAt?.let(::stamp), syncSeq = a.syncSeq,
        )

        fun toEntity(a: Attachment) = AttachmentEntity(
            id = a.id.toString(), householdId = a.householdId.toString(), entity = a.entity, entityId = a.entityId.toString(),
            storagePath = a.storagePath, mime = a.mime, sizeBytes = a.sizeBytes, createdBy = a.createdBy?.toString(),
            createdAt = stamp(a.createdAt), updatedAt = stamp(a.updatedAt), deletedAt = a.deletedAt?.let(::stamp), syncSeq = a.syncSeq,
        )

        fun toAttachment(e: AttachmentEntity): Attachment? = runCatching {
            Attachment(
                id = UUID.fromString(e.id), householdId = UUID.fromString(e.householdId), entity = e.entity,
                entityId = UUID.fromString(e.entityId), storagePath = e.storagePath, mime = e.mime, sizeBytes = e.sizeBytes,
                createdBy = e.createdBy?.let(UUID::fromString), createdAt = OffsetDateTime.parse(e.createdAt),
                updatedAt = OffsetDateTime.parse(e.updatedAt), deletedAt = e.deletedAt?.let(OffsetDateTime::parse), syncSeq = e.syncSeq,
            )
        }.getOrNull()

        fun toUpload(e: AttachmentOutboxEntity): AttachmentUpload? = runCatching {
            AttachmentUpload(
                id = UUID.fromString(e.id), householdId = UUID.fromString(e.householdId), entity = e.entity,
                entityId = UUID.fromString(e.entityId), filename = e.filename, mime = e.mime, size = e.size,
                filePath = e.filePath, createdAt = OffsetDateTime.parse(e.createdAt),
            )
        }.getOrNull()

        fun toActivity(e: ActivityEntity): Activity? = runCatching {
            Activity(
                id = UUID.fromString(e.id), householdId = UUID.fromString(e.householdId),
                actorId = e.actorId?.let(UUID::fromString),
                module = Activity.Module.entries.first { it.value == e.module },
                entity = e.entity, entityId = UUID.fromString(e.entityId), action = e.action, summary = e.summary,
                createdAt = OffsetDateTime.parse(e.createdAt), updatedAt = OffsetDateTime.parse(e.updatedAt),
                deletedAt = e.deletedAt?.let(OffsetDateTime::parse), syncSeq = e.syncSeq,
            )
        }.getOrNull()
    }
}
