package com.abodenestly.shopping

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID

fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

/** Polls [condition] for up to [timeoutMs]; the database and the engine answer on other threads. */
suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
    withTimeout(timeoutMs) { while (!condition()) delay(10) }
}
