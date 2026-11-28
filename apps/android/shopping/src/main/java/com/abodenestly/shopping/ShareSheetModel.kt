package com.abodenestly.shopping

import com.abodenestly.core.api.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the C8 sheet shows: the switch's [share] state, whether "Link copied" is up, and the [link] while sharing is on. */
data class ShareSheetUiState(
    val share: ShareState,
    val copied: Boolean = false,
    val link: String? = null,
)

/**
 * DESIGN C8 logic. Online only: with no connection nothing is sent. Twins: web `ShareSheet.tsx`, iOS `ShareSheetModel`.
 * The calls are injected; [ListDetailViewModel.makeShare] supplies the local write of the server's token ([record]).
 */
class ShareSheetModel(
    initialToken: String?,
    private val webBase: String,
    private val share: suspend () -> ApiResult<String>,
    private val stop: suspend () -> ApiResult<Unit>,
    private val record: suspend (String?) -> Unit,
    private val isOnline: () -> Boolean,
    private val copy: (String) -> Unit,
    private val scope: CoroutineScope,
    private val copiedMillis: Long = SHARE_COPIED_MILLIS,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableState = MutableStateFlow(snapshot(ShareState(initialToken), false))
    val state: StateFlow<ShareSheetUiState> = mutableState.asStateFlow()

    private var copiedJob: Job? = null

    private fun snapshot(share: ShareState, copied: Boolean): ShareSheetUiState =
        ShareSheetUiState(share, copied, share.token?.let { Share.link(webBase, it) })

    private fun update(change: (ShareState) -> ShareState) {
        mutableState.update { snapshot(change(it.share), it.copied) }
    }

    /** The switch and "Stop sharing" work: online and no call running. */
    val canChange: Boolean get() = isOnline() && !mutableState.value.share.isBusy

    /** The switch was flipped. */
    fun setOn(on: Boolean) {
        if (on) turnOn() else turnOff()
    }

    fun turnOn() {
        if (!canChange || mutableState.value.share.isOn) return
        update { it.begin(ShareBusy.TurningOn) }
        scope.launch {
            val result: ApiResult<String> = guarded { share() }
            // The link is built from the token, not the returned URL, which is the API's JSON route.
            val token: String? = (result as? ApiResult.Success<String>)?.let { Share.token(it.value) }
            if (token == null) {
                update { it.failed(ShareError.TurnOn) }
                return@launch
            }
            update { it.turnedOn(token) }
            record(token)
        }
    }

    fun turnOff() {
        if (!canChange || !mutableState.value.share.isOn) return
        update { it.begin(ShareBusy.Stopping) }
        scope.launch {
            val result: ApiResult<Unit> = guarded { stop() }
            if (result !is ApiResult.Success) {
                update { it.failed(ShareError.Stop) }
                return@launch
            }
            update { it.stopped() }
            copiedJob?.cancel()
            mutableState.update { it.copy(copied = false) }
            record(null)
        }
    }

    /** Copies the link and shows "Link copied" for 3 s. Works offline: a known link stays copyable. */
    fun copyLink() {
        val link: String = mutableState.value.link ?: return
        copy(link)
        mutableState.update { it.copy(copied = true) }
        copiedJob?.cancel()
        copiedJob = scope.launch {
            delayer(copiedMillis)
            mutableState.update { it.copy(copied = false) }
        }
    }

    /** The sheet went away: the "Link copied" timer stops. A call that is running still finishes and is recorded. */
    fun close() {
        copiedJob?.cancel()
    }

    /** A call that throws counts as a failure; only cancellation passes through. */
    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failure(com.abodenestly.core.api.ApiFailure.Unknown)
    }
}
