package com.abodenestly.core.profile

import com.abodenestly.contract.models.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The fields a profile edit can change; `null` means "leave as is". */
@Serializable
data class ProfileData(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
)

/**
 * A local SPEC §6.2 envelope for the user-scoped profile outbox. The contract has no generated Mutation model
 * yet (SHOP-08), so this is an internal queue record, not a DTO.
 */
@Serializable
data class ProfileMutation(
    val id: String,
    val table: String = TABLE,
    val op: String = OP_UPSERT,
    @SerialName("row_id") val rowId: String,
    val data: ProfileData,
    @SerialName("client_ts") val clientTs: String,
) {
    companion object {
        const val TABLE = "profile"
        const val OP_UPSERT = "upsert"

        /** Overlays queued edits onto a fetched server row so a stale read does not hide an unsynced edit. */
        fun overlay(profile: Profile, pending: List<ProfileMutation>): Profile =
            pending.fold(profile) { p, m ->
                if (m.rowId != p.id.toString()) p
                else p.copy(
                    displayName = m.data.displayName ?: p.displayName,
                    avatarPath = m.data.avatarPath ?: p.avatarPath,
                )
            }
    }
}

/** A picture waiting to be uploaded: the local JPEG at [filePath] belongs to [userId]. */
@Serializable
data class PendingAvatar(@SerialName("user_id") val userId: String, @SerialName("file_path") val filePath: String)
