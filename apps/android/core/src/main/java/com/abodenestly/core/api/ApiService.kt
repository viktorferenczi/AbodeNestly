package com.abodenestly.core.api

import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Invite
import java.util.UUID

/** Why an API call failed, as the Welcome screen needs to tell the person (mirrors iOS `ApiFailure`). */
sealed class ApiFailure {
    data object Validation : ApiFailure()

    /** Accept-invite only: the code is wrong, used up or expired. */
    data object InvalidCode : ApiFailure()
    data object Network : ApiFailure()
    data object Unknown : ApiFailure()
}

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
}
