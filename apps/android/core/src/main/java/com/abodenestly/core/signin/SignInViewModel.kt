package com.abodenestly.core.signin

import androidx.lifecycle.ViewModel
import com.abodenestly.core.auth.AuthError
import com.abodenestly.core.auth.AuthFailure
import com.abodenestly.core.auth.AuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Same states as the web `SignIn` reducer and iOS `SignInState` (DESIGN B1). */
sealed interface SignInState {
    data object Idle : SignInState
    data object Sending : SignInState
    data class LinkSent(val secondsLeft: Int, val resending: Boolean, val error: AuthError?) : SignInState
    data class Error(val error: AuthError) : SignInState
}

/** Which control started the request in flight; the spinner belongs on that control (DESIGN B1 `sending`). */
enum class SignInAction { MagicLink, Apple, Google }

class SignInViewModel(
    private val auth: AuthService,
    private val redirectTo: String,
    /** Injected so tests advance the resend countdown without waiting. */
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableEmail = MutableStateFlow("")
    private val mutableState = MutableStateFlow<SignInState>(SignInState.Idle)
    private val mutableAction = MutableStateFlow<SignInAction?>(null)

    val email: StateFlow<String> = mutableEmail.asStateFlow()
    val state: StateFlow<SignInState> = mutableState.asStateFlow()
    val action: StateFlow<SignInAction?> = mutableAction.asStateFlow()

    private var countdown: Job? = null

    private val isBusy get() = mutableState.value == SignInState.Sending
    private val trimmedEmail get() = mutableEmail.value.trim()

    fun onEmailChange(value: String) {
        mutableEmail.value = value
    }

    /** AC3: a request is only sent for a plausible address. */
    fun submit() {
        if (isBusy) return
        if (!isValid(trimmedEmail)) {
            mutableState.value = SignInState.Error(AuthError.InvalidEmail)
            return
        }
        scope.launch {
            begin(SignInAction.MagicLink)
            try {
                auth.sendMagicLink(trimmedEmail, redirectTo)
                enterLinkSent()
            } catch (e: AuthFailure) {
                mutableState.value = SignInState.Error(e.error)
            } finally {
                mutableAction.value = null
            }
        }
    }

    fun resend() {
        val current = mutableState.value
        if (current !is SignInState.LinkSent || current.resending || current.secondsLeft != 0) return
        mutableState.value = SignInState.LinkSent(0, resending = true, error = null)
        scope.launch {
            try {
                auth.sendMagicLink(trimmedEmail, redirectTo)
                enterLinkSent()
            } catch (e: AuthFailure) {
                mutableState.value = SignInState.LinkSent(0, resending = false, error = e.error)
            }
        }
    }

    fun signInWithApple() = provider(SignInAction.Apple) { auth.signInWithApple() }

    fun signInWithGoogle() = provider(SignInAction.Google) { auth.signInWithGoogle() }

    /** The browser opening is the end of this call; the session arrives through the callback URL. */
    private fun provider(action: SignInAction, call: suspend () -> Unit) {
        if (isBusy) return
        scope.launch {
            begin(action)
            try {
                call()
                mutableState.value = SignInState.Idle
            } catch (e: AuthFailure) {
                // Dismissing the browser is not an error.
                mutableState.value = if (e.error == AuthError.Cancelled) SignInState.Idle else SignInState.Error(e.error)
            } finally {
                mutableAction.value = null
            }
        }
    }

    private fun begin(action: SignInAction) {
        mutableState.value = SignInState.Sending
        mutableAction.value = action
    }

    private fun enterLinkSent() {
        mutableState.value = SignInState.LinkSent(RESEND_SECONDS, resending = false, error = null)
        countdown?.cancel()
        countdown = scope.launch {
            while (true) {
                sleep(1000)
                if (!tick()) return@launch
            }
        }
    }

    /** Returns whether the countdown should keep running. */
    private fun tick(): Boolean {
        val current = mutableState.value as? SignInState.LinkSent ?: return false
        if (current.secondsLeft <= 0) return false
        val next = current.secondsLeft - 1
        mutableState.value = current.copy(secondsLeft = next)
        return next > 0
    }

    companion object {
        const val RESEND_SECONDS = 30
        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

        fun isValid(email: String) = EMAIL.matches(email)
    }
}
