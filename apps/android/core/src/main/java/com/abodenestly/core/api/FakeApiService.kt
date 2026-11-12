package com.abodenestly.core.api

import com.abodenestly.contract.models.Household
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
 * Lives in the main source set, like [com.abodenestly.core.auth.FakeAuthService].
 */
class FakeApiService : ApiService {
    sealed interface Call {
        data class CreateHousehold(val name: String) : Call
        data class AcceptInvite(val code: String) : Call
    }

    private val recorded = CopyOnWriteArrayList<Call>()
    @Volatile private var gate: CompletableDeferred<Unit>? = null

    @Volatile var createResult: ApiResult<Household> = ApiResult.Failure(ApiFailure.Unknown)
    @Volatile var acceptResult: ApiResult<UUID> = ApiResult.Failure(ApiFailure.Unknown)

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
}
