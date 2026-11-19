package com.abodenestly.core.api

import com.abodenestly.contract.models.Activity
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Invite
import kotlinx.coroutines.CompletableDeferred
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
 * Lives in the main source set, like [com.abodenestly.core.auth.FakeAuthService].
 */
class FakeApiService : ApiService {
    sealed interface Call {
        data class CreateHousehold(val name: String) : Call
        data class AcceptInvite(val code: String) : Call
        data class CreateInvite(val householdId: UUID) : Call
        data class FetchLiveInvite(val householdId: UUID) : Call
        data class RetireInvite(val id: UUID) : Call
        data class FetchMembers(val householdId: UUID) : Call
        data class RemoveMember(val householdId: UUID, val userId: UUID) : Call
        data class MakeOwner(val householdId: UUID, val userId: UUID) : Call
        data class FetchHousehold(val householdId: UUID, val userId: UUID) : Call
        data class FetchMyHouseholds(val userId: UUID) : Call
        data class FetchActivity(val householdId: UUID, val since: OffsetDateTime, val limit: Int) : Call
        data class UpdateModules(val householdId: UUID, val shopping: Boolean?, val pantry: Boolean?, val maintenance: Boolean?) : Call
        data class UpdateHousehold(val householdId: UUID, val name: String?, val currency: String?) : Call
        data class SignAttachment(val householdId: UUID, val entity: String, val entityId: UUID, val filename: String, val mime: String, val size: Int) : Call
        data class Upload(val url: String, val byteCount: Int, val mime: String) : Call
    }

    private val recorded = CopyOnWriteArrayList<Call>()
    @Volatile private var gate: CompletableDeferred<Unit>? = null

    @Volatile var createResult: ApiResult<Household> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var acceptResult: ApiResult<UUID> = ApiResult.Failure(ApiFailure.Unknown)

    /** Every `createInvite` returns this once [createInviteResults] is drained. */
    @Volatile var createInviteResult: ApiResult<Invite> = ApiResult.Failure(ApiFailure.Unknown)
    /** Queued results for `createInvite`, consumed in order before falling back to [createInviteResult]. */
    val createInviteResults = ConcurrentLinkedQueue<ApiResult<Invite>>()
    @Volatile var liveInviteResult: ApiResult<Invite?> = ApiResult.Success(null)
    @Volatile var retireResult: ApiResult<Unit> = ApiResult.Success(Unit)
    @Volatile var membersResult: ApiResult<List<MemberRow>> = ApiResult.Success(emptyList())
    @Volatile var removeResult: ApiResult<Unit> = ApiResult.Success(Unit)
    @Volatile var makeOwnerResult: ApiResult<Unit> = ApiResult.Success(Unit)
    @Volatile var fetchHouseholdResult: ApiResult<HouseholdWithRole?> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var myHouseholdsResult: ApiResult<List<HouseholdWithRole>> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var activityResult: ApiResult<List<Activity>> = ApiResult.Success(emptyList())
    @Volatile var updateModulesResult: ApiResult<Household> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var updateHouseholdResult: ApiResult<Household> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var signResult: ApiResult<SignedUpload> = ApiResult.Success(SignedUpload(UUID.randomUUID(), "https://upload.test/object", "households/h/pantry_item/e/a.jpg"))
    @Volatile var uploadResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val calls: List<Call> get() = recorded.toList()

    /** Makes the calls suspend until [release], so tests can observe the busy states. */
    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun createHousehold(name: String): ApiResult<Household> {
        recorded += Call.CreateHousehold(name)
        gate?.await()
        return createResult
    }

    override suspend fun acceptInvite(code: String): ApiResult<UUID> {
        recorded += Call.AcceptInvite(code)
        gate?.await()
        return acceptResult
    }

    override suspend fun createInvite(householdId: UUID): ApiResult<Invite> {
        recorded += Call.CreateInvite(householdId)
        gate?.await()
        return createInviteResults.poll() ?: createInviteResult
    }

    override suspend fun fetchLiveInvite(householdId: UUID): ApiResult<Invite?> {
        recorded += Call.FetchLiveInvite(householdId)
        gate?.await()
        return liveInviteResult
    }

    override suspend fun retireInvite(id: UUID): ApiResult<Unit> {
        recorded += Call.RetireInvite(id)
        gate?.await()
        return retireResult
    }

    override suspend fun fetchMembers(householdId: UUID): ApiResult<List<MemberRow>> {
        recorded += Call.FetchMembers(householdId)
        val result = membersResult
        // The result is read before the gate, so a held fetch returns what the server had when it started.
        gate?.await()
        return result
    }

    override suspend fun removeMember(householdId: UUID, userId: UUID): ApiResult<Unit> {
        recorded += Call.RemoveMember(householdId, userId)
        gate?.await()
        return removeResult
    }

    override suspend fun makeOwner(householdId: UUID, userId: UUID): ApiResult<Unit> {
        recorded += Call.MakeOwner(householdId, userId)
        gate?.await()
        return makeOwnerResult
    }

    override suspend fun fetchHousehold(householdId: UUID, userId: UUID): ApiResult<HouseholdWithRole?> {
        recorded += Call.FetchHousehold(householdId, userId)
        val result = fetchHouseholdResult
        // Read before the gate, like fetchMembers: a held fetch returns what the server had when it started.
        gate?.await()
        return result
    }

    override suspend fun fetchActivity(householdId: UUID, since: OffsetDateTime, limit: Int): ApiResult<List<Activity>> {
        recorded += Call.FetchActivity(householdId, since, limit)
        val result = activityResult
        gate?.await()
        return result
    }

    override suspend fun fetchMyHouseholds(userId: UUID): ApiResult<List<HouseholdWithRole>> {
        recorded += Call.FetchMyHouseholds(userId)
        val result = myHouseholdsResult
        gate?.await()
        return result
    }

    override suspend fun updateModules(
        householdId: UUID, shopping: Boolean?, pantry: Boolean?, maintenance: Boolean?,
    ): ApiResult<Household> {
        recorded += Call.UpdateModules(householdId, shopping, pantry, maintenance)
        gate?.await()
        return updateModulesResult
    }

    override suspend fun updateHousehold(householdId: UUID, name: String?, currency: String?): ApiResult<Household> {
        recorded += Call.UpdateHousehold(householdId, name, currency)
        gate?.await()
        return updateHouseholdResult
    }

    override suspend fun signAttachment(
        householdId: UUID, entity: String, entityId: UUID, filename: String, mime: String, size: Int,
    ): ApiResult<SignedUpload> {
        recorded += Call.SignAttachment(householdId, entity, entityId, filename, mime, size)
        gate?.await()
        return signResult
    }

    override suspend fun uploadToSignedUrl(url: String, bytes: ByteArray, mime: String): ApiResult<Unit> {
        recorded += Call.Upload(url, bytes.size, mime)
        gate?.await()
        return uploadResult
    }
}
