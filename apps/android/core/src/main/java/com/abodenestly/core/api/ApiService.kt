package com.abodenestly.core.api

import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Invite
import com.abodenestly.contract.models.Member
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Why an API call failed, as the Welcome screen needs to tell the person (mirrors iOS `ApiFailure`). */
sealed class ApiFailure {
    data object Validation : ApiFailure()

    /** Accept-invite only: the code is wrong, used up or expired. */
    data object InvalidCode : ApiFailure()
    data object Network : ApiFailure()
    data object Unknown : ApiFailure()

    /** Remove/leave: the caller is the only owner left (409 `last_owner`). */
    data object LastOwner : ApiFailure()
    data object Forbidden : ApiFailure()
    data object NotFound : ApiFailure()

    /** Attachment sign only: the entity's module is off (403 `module_disabled`). */
    data object ModuleDisabled : ApiFailure()
}

/** `POST /attachments/sign` response: the attachment row exists; the bytes go to [uploadUrl] (CORE-11). */
data class SignedUpload(val attachmentId: UUID, val uploadUrl: String, val storagePath: String)

/**
 * The `profile` columns the Members screen shows. Hand-written because the read selects only these, so the
 * generated `Profile` (which also needs `created_at` / `updated_at`) cannot decode it; iOS has the same type.
 */
@Serializable
data class MemberProfile(
    @Contextual val id: UUID,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
)

/** An active member with the profile shown beside them (`null` when RLS hides it). */
@Serializable
data class MemberRow(val member: Member, val profile: MemberProfile?) {
    val id: UUID get() = member.userId
}

/** A household with the viewer's role in it: the `member?select=role,household:household!inner(*)` read, and the Settings cache entry. */
@Serializable
data class HouseholdWithRole(val household: Household, val role: Member.Role)

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

/** The API calls the app makes; mirrors iOS `ApiService`. Tests use [FakeApiService]. */
interface ApiService {
    /** `POST /households` (201). The server seeds the Groceries list and Fridge/Freezer/Pantry locations. */
    suspend fun createHousehold(name: String): ApiResult<Household>

    /** `POST /invites/{code}/accept` (CORE-03). Success carries the joined household's id. */
    suspend fun acceptInvite(code: String): ApiResult<UUID>

    /** `POST /households/{id}/invites` (201). The API never retires older codes; the client does (SPEC §14). */
    suspend fun createInvite(householdId: UUID): ApiResult<Invite>

    /** The newest invite that is neither retired, expired nor used up, or `null`. PostgREST read under RLS. */
    suspend fun fetchLiveInvite(householdId: UUID): ApiResult<Invite?>

    /** Soft-deletes an invite through PostgREST. A row RLS hides counts as a failure. */
    suspend fun retireInvite(id: UUID): ApiResult<Unit>

    /** Active members (`left_at` null) with their profiles. Two PostgREST reads under RLS. */
    suspend fun fetchMembers(householdId: UUID): ApiResult<List<MemberRow>>

    /** `DELETE /households/{id}/members/{user_id}` (204): an owner removes someone, or a person leaves. */
    suspend fun removeMember(householdId: UUID, userId: UUID): ApiResult<Unit>

    /** Promotes a member through PostgREST (`PATCH member`). A row RLS hides counts as a failure. */
    suspend fun makeOwner(householdId: UUID, userId: UUID): ApiResult<Unit>

    /** The household and the viewer's role, or `null` when they are no longer an active member (or it was deleted). PostgREST read under RLS. */
    suspend fun fetchHousehold(householdId: UUID, userId: UUID): ApiResult<HouseholdWithRole?>

    /** Every active household of [userId] with their role: [fetchHousehold]'s PostgREST read without the household filter (CORE-07). */
    suspend fun fetchMyHouseholds(userId: UUID): ApiResult<List<HouseholdWithRole>>

    /** `PATCH /households/{id}/modules`: only the flags given are sent. */
    suspend fun updateModules(householdId: UUID, shopping: Boolean? = null, pantry: Boolean? = null, maintenance: Boolean? = null): ApiResult<Household>

    /** `PATCH household` through PostgREST (owners only, by RLS). A non-owner is `Forbidden`; a row RLS hides is `Unknown`. */
    suspend fun updateHousehold(householdId: UUID, name: String? = null, currency: String? = null): ApiResult<Household>

    /** `POST /attachments/sign` (CORE-11): creates the attachment row and returns where to PUT the bytes. 403 `module_disabled` → `ModuleDisabled`, other 403 → `Forbidden`, 400 → `Validation`. */
    suspend fun signAttachment(householdId: UUID, entity: String, entityId: UUID, filename: String, mime: String, size: Int): ApiResult<SignedUpload>

    /** PUTs the bytes to a signed [url] (no bearer: the URL carries the token). `Network` when it cannot be reached. */
    suspend fun uploadToSignedUrl(url: String, bytes: ByteArray, mime: String): ApiResult<Unit>
}
