package com.abodenestly.core.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abodenestly.core.R
import com.abodenestly.core.auth.AuthError
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens

/** DESIGN B1. States: idle, sending, link-sent, error, offline. */
@Composable
fun SignInScreen(viewModel: SignInViewModel, isOnline: Boolean, modifier: Modifier = Modifier) {
    val email by viewModel.email.collectAsState()
    val state by viewModel.state.collectAsState()
    val action by viewModel.action.collectAsState()
    SignInContent(
        email = email, state = state, action = action, isOnline = isOnline,
        onEmailChange = viewModel::onEmailChange, onSend = viewModel::submit, onResend = viewModel::resend,
        onApple = viewModel::signInWithApple, onGoogle = viewModel::signInWithGoogle,
        modifier = modifier,
    )
}

fun signInMessage(error: AuthError): String = when (error) {
    AuthError.InvalidEmail -> "Enter a valid email address."
    AuthError.RateLimited -> "Too many attempts. Wait a minute and try again."
    AuthError.Network -> "Couldn't reach the server. Check your connection and try again."
    AuthError.Cancelled, AuthError.Unknown -> "Something went wrong. Try again."
}

@Composable
internal fun SignInContent(
    email: String,
    state: SignInState,
    action: SignInAction?,
    isOnline: Boolean,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onResend: () -> Unit,
    onApple: () -> Unit,
    onGoogle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val disabled = !isOnline || state == SignInState.Sending
    Box(modifier.fillMaxSize().background(tokens.bg).testTag("signin.screen"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Tokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AbodeNestly", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.accent)
            Text(
                "Sign in to AbodeNestly",
                modifier = Modifier.testTag("signin.title").semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.text,
            )
            if (!isOnline) OfflineBanner("You're offline. Connect to sign in.")
            if (state is SignInState.LinkSent) {
                LinkSent(email.trim(), state, isOnline, onResend)
            } else {
                Form(email, (state as? SignInState.Error)?.error, action, disabled, onEmailChange, onSend)
                Divider()
                Providers(action, disabled, onApple, onGoogle)
            }
            Text(
                "By continuing you agree to the Terms of Service and Privacy Policy.",
                style = MaterialTheme.typography.bodySmall, color = tokens.text2,
            )
        }
    }
}

@Composable
private fun Form(
    email: String,
    error: AuthError?,
    action: SignInAction?,
    disabled: Boolean,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!disabled) onSend() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("signin.email"),
        )
        if (error != null) {
            Text(signInMessage(error), style = MaterialTheme.typography.bodySmall, color = tokens.danger, modifier = Modifier.testTag("signin.error"))
        }
        Button(
            onClick = onSend,
            enabled = !disabled,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = Color.White),
            shape = RoundedCornerShape8,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("signin.send"),
        ) {
            if (action == SignInAction.MagicLink) {
                Spinner(Color.White, "signin.spinner")
                Spacer8()
            }
            Text("Send magic link", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Divider() {
    val tokens = LocalTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = tokens.line)
        Text("or", style = MaterialTheme.typography.bodyMedium, color = tokens.text2)
        HorizontalDivider(Modifier.weight(1f), color = tokens.line)
    }
}

@Composable
private fun Providers(action: SignInAction?, disabled: Boolean, onApple: () -> Unit, onGoogle: () -> Unit) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onApple,
            enabled = !disabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
            shape = RoundedCornerShape8,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("signin.apple"),
        ) {
            if (action == SignInAction.Apple) {
                Spinner(Color.White, "signin.apple.spinner")
                Spacer8()
            }
            Text("Sign in with Apple", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onGoogle,
            enabled = !disabled,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = tokens.surface2, contentColor = tokens.text),
            shape = RoundedCornerShape8,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("signin.google"),
        ) {
            if (action == SignInAction.Google) {
                Spinner(tokens.accent, "signin.google.spinner")
            } else {
                Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer8()
            Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LinkSent(email: String, state: SignInState.LinkSent, isOnline: Boolean, onResend: () -> Unit) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Check your email",
            modifier = Modifier.testTag("signin.linkSent").semantics { heading() },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
        )
        Text("We sent a sign-in link to $email.", color = tokens.text2)
        OutlinedButton(
            onClick = onResend,
            enabled = isOnline && !state.resending && state.secondsLeft == 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("signin.resend"),
        ) {
            Text(if (state.secondsLeft > 0) "Resend (${state.secondsLeft} s)" else "Resend")
        }
        if (state.error != null) {
            Text(signInMessage(state.error), style = MaterialTheme.typography.bodySmall, color = tokens.danger, modifier = Modifier.testTag("signin.error"))
        }
    }
}

@Composable
private fun Spinner(color: Color, tag: String) {
    CircularProgressIndicator(Modifier.size(18.dp).testTag(tag), color = color, strokeWidth = 2.dp)
}

@Composable
private fun Spacer8() = Spacer(Modifier.size(8.dp))

private val RoundedCornerShape8 = RoundedCornerShape(8.dp)
