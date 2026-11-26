package com.abodenestly.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// The /sync request and response shapes (SPEC §6.2-6.3). The generated :contract has no usable types for them
// (docs/openapi.yaml types `changes` and `mutations` loosely), so they are written here, next to the loop that uses them.

/** One queued write in the SPEC §6.2 envelope. [data] is absent for `delete`. */
@Serializable
data class SyncMutation(
    val id: String,
    val table: String,
    val op: String,
    @SerialName("row_id") val rowId: String,
    val data: JsonObject? = null,
    @SerialName("client_ts") val clientTs: String,
)

@Serializable
data class SyncPushBody(
    @SerialName("household_id") val householdId: String,
    val mutations: List<SyncMutation>,
)

/** Why the server turned a mutation down. A reason this app does not know yet is [Unknown], so a newer server never breaks the loop. */
sealed class SyncRejectionReason(val raw: String) {
    data object RlsDenied : SyncRejectionReason("rls_denied")
    data object Validation : SyncRejectionReason("validation")
    data object UnknownTable : SyncRejectionReason("unknown_table")
    data object ModuleDisabled : SyncRejectionReason("module_disabled")
    class Unknown(raw: String) : SyncRejectionReason(raw)

    companion object {
        fun parse(raw: String): SyncRejectionReason = when (raw) {
            "rls_denied" -> RlsDenied
            "validation" -> Validation
            "unknown_table" -> UnknownTable
            "module_disabled" -> ModuleDisabled
            else -> Unknown(raw)
        }
    }
}

@Serializable
data class SyncRejected(val id: String, val reason: String, val detail: String? = null)

@Serializable
data class SyncPushResult(val applied: List<String> = emptyList(), val rejected: List<SyncRejected> = emptyList())

/** One page of `GET /sync`: [changes] maps a table name to its full rows (tombstones included). */
@Serializable
data class SyncPullPage(
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean = false,
    val changes: Map<String, List<JsonObject>> = emptyMap(),
)

/** `ignoreUnknownKeys` so a newer server never breaks an older client. */
val SyncJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
