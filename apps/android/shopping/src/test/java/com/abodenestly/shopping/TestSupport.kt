package com.abodenestly.shopping

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

/** Polls [condition] for up to [timeoutMs]; the database and the engine answer on other threads. */
suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMs) { while (!condition()) delay(10) }
}

/** The server's row for Milk (id `i1` on list `l1`) after another member checked it off, as a pull delivers it. */
fun milkCheckedByAnotherMember(): JsonObject = buildJsonObject {
    put("id", "i1")
    put("list_id", "l1")
    put("name", "Milk")
    put("name_key", "milk")
    put("checked", true)
    put("checked_at", "2026-11-26T09:00:00.000Z")
    put("checked_by", uuid(9).toString())
    put("created_at", "2026-11-01T00:00:00.000Z")
}
