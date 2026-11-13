package com.abodenestly.core.invite

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Invite
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** DESIGN B6 states. [Error] keeps the old invite when a "New code" attempt fails (port of iOS `InviteSheetState`). */
sealed interface InviteSheetState {
    data object Loading : InviteSheetState
    data class Ready(val invite: Invite) : InviteSheetState
    data class Rotating(val invite: Invite) : InviteSheetState
    data class Error(val failure: ApiFailure, val invite: Invite?) : InviteSheetState
}

/** The invite on screen, whatever the state. */
val InviteSheetState.shownInvite: Invite?
    get() = when (this) {
        InviteSheetState.Loading -> null
        is InviteSheetState.Ready -> invite
        is InviteSheetState.Rotating -> invite
        is InviteSheetState.Error -> invite
    }

/** Port of iOS `InviteSheetModel`. */
class InviteSheetViewModel(
    private val householdId: UUID,
    private val api: ApiService,
    private val copy: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow<InviteSheetState>(InviteSheetState.Loading)
    private val mutableOldCodeMayWork = MutableStateFlow(false)
    private val mutableNotice = MutableStateFlow<String?>(null)
    private var loading: Job? = null

    val state: StateFlow<InviteSheetState> = mutableState.asStateFlow()

    /** The new code is ready but retiring the old one failed (AC2). */
    val oldCodeMayWork: StateFlow<Boolean> = mutableOldCodeMayWork.asStateFlow()

    /** Copy feedback ("Code copied"). */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private val isRotating get() = mutableState.value is InviteSheetState.Rotating

    /** Fetches the live invite, minting one if there is none. No-op while an invite is on screen or a load runs. */
    fun loadIfNeeded() {
        if (mutableState.value.shownInvite != null || loading?.isActive == true) return
        mutableState.value = InviteSheetState.Loading
        loading = scope.launch {
            mutableState.value = when (val live = api.fetchLiveInvite(householdId)) {
                is ApiResult.Failure -> InviteSheetState.Error(live.failure, null)
                is ApiResult.Success -> live.value?.let { InviteSheetState.Ready(it) } ?: when (val created = api.createInvite(householdId)) {
                    is ApiResult.Success -> InviteSheetState.Ready(created.value)
                    is ApiResult.Failure -> InviteSheetState.Error(created.failure, null)
                }
            }
        }
    }

    /** Creates a code, then retires the old one; the API never retires older codes, the client does (SPEC §14). */
    fun newCode() {
        val old = mutableState.value.shownInvite ?: return
        if (isRotating) return
        mutableNotice.value = null
        mutableOldCodeMayWork.value = false
        mutableState.value = InviteSheetState.Rotating(old)
        scope.launch {
            when (val created = api.createInvite(householdId)) {
                is ApiResult.Failure -> mutableState.value = InviteSheetState.Error(created.failure, old)
                is ApiResult.Success -> {
                    if (api.retireInvite(old.id) is ApiResult.Failure) mutableOldCodeMayWork.value = true
                    mutableState.value = InviteSheetState.Ready(created.value)
                }
            }
        }
    }

    fun copyCode() {
        val invite = mutableState.value.shownInvite ?: return
        if (isRotating) return
        copy(invite.code)
        mutableNotice.value = "Code copied"
    }

    companion object {
        /**
         * `<base>/join/<code>`; web routes it to the Welcome join card. The code is one path segment, so `/` and `?`
         * stay inert. `null` when [base] is blank or not an HTTP(S) URL, so Share is disabled instead of sending a codeless link.
         */
        fun link(base: String, code: String): String? =
            base.trimEnd('/').toHttpUrlOrNull()
                ?.newBuilder()?.addPathSegment("join")?.addPathSegment(code)?.build()?.toString()

        fun usesLeft(invite: Invite): Int = invite.maxUses - invite.uses

        /** "19 Nov" in the viewer's locale; [locale] and [zone] are injectable for tests. */
        fun expiryText(date: OffsetDateTime, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
            DateTimeFormatter.ofPattern("d MMM", locale).format(date.atZoneSameInstant(zone))

        /** "N uses left · Expires 19 Nov". */
        fun metaText(invite: Invite, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
            val left = usesLeft(invite)
            return "${if (left == 1) "1 use left" else "$left uses left"} · Expires ${expiryText(invite.expiresAt, locale, zone)}"
        }
    }
}
