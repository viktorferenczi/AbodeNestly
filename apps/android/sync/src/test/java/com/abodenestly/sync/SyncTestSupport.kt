package com.abodenestly.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/** Hand-written transport (TESTING §2: no mocking libraries): records calls, answers from queues, then from the defaults. */
class FakeTransport : SyncTransport {
    val pushes = CopyOnWriteArrayList<SyncPushBody>()
    data class PullCall(val householdId: String, val since: Long, val limit: Int)
    val pulls = CopyOnWriteArrayList<PullCall>()
    @Volatile var refreshes = 0

    val pushResults = ConcurrentLinkedQueue<SyncResult<SyncPushResult>>()
    val pullResults = ConcurrentLinkedQueue<SyncResult<SyncPullPage>>()

    /** Applies every mutation unless a queued result says otherwise. */
    @Volatile var pushDefault: (SyncPushBody) -> SyncResult<SyncPushResult> =
        { body -> SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id })) }
    @Volatile var pullDefault: SyncResult<SyncPullPage> = SyncResult.Success(SyncPullPage(cursor = 0))

    /** While set, [pull] waits for it after recording the call. */
    @Volatile var pullGate: CompletableDeferred<Unit>? = null

    override suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult> {
        pushes += body
        return pushResults.poll() ?: pushDefault(body)
    }

    override suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage> {
        pulls += PullCall(householdId, since, limit)
        val result = pullResults.poll() ?: pullDefault
        pullGate?.await()
        return result
    }

    override suspend fun refreshSession() {
        refreshes++
    }
}

fun listItem(id: String, name: String, listId: String = "00000000-0000-4000-8000-0000000000a1"): JsonObject = buildJsonObject {
    put("id", id)
    put("list_id", listId)
    put("name", name)
}

fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

/** Polls [condition] for up to [timeoutMs]; the engine's passes run on other threads. */
suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMs) { while (!condition()) delay(10) }
}
