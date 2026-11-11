package com.abodenestly.core.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens

/** DESIGN B2. States: idle, creating, joining, error-invalid-code, offline. */
@Composable
fun WelcomeScreen(
    viewModel: WelcomeViewModel,
    displayName: String?,
    isOnline: Boolean,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name by viewModel.name.collectAsState()
    val code by viewModel.code.collectAsState()
    val state by viewModel.state.collectAsState()
    WelcomeContent(
        name = name, code = code, state = state, displayName = displayName, isOnline = isOnline,
        onNameChange = viewModel::onNameChange, onCodeChange = viewModel::onCodeChange,
        onCreate = viewModel::create, onJoin = viewModel::join, onSignOut = onSignOut, modifier = modifier,
    )
}

// Copied from iOS `createMessage` / `joinMessage` (themselves from web `CREATE_MESSAGES` / `JOIN_MESSAGES`).
fun createMessage(failure: ApiFailure): String = when (failure) {
    ApiFailure.Validation -> "Enter a name for your household."
    ApiFailure.Network -> "Couldn't reach the server. Check your connection and try again."
    ApiFailure.InvalidCode, ApiFailure.Unknown -> "Something went wrong. Try again."
}

fun joinMessage(failure: ApiFailure): String = when (failure) {
    ApiFailure.Validation -> "Enter your invite code."
    ApiFailure.InvalidCode -> "That code isn't valid or has expired. Check it and try again."
    ApiFailure.Network -> "Couldn't reach the server. Check your connection and try again."
    ApiFailure.Unknown -> "Something went wrong. Try again."
}

@Composable
internal fun WelcomeContent(
    name: String,
    code: String,
    state: WelcomeState,
    displayName: String?,
    isOnline: Boolean,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val busy = state == WelcomeState.Creating || state == WelcomeState.Joining
    val disabled = !isOnline || busy
    val createError = (state as? WelcomeState.CreateError)?.let { createMessage(it.failure) }
    val joinError = when (state) {
        WelcomeState.ErrorInvalidCode -> joinMessage(ApiFailure.InvalidCode)
        is WelcomeState.JoinError -> joinMessage(state.failure)
        else -> null
    }
    Box(modifier.fillMaxSize().background(tokens.bg).testTag("welcome.screen"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Tokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                displayName?.takeIf { it.isNotEmpty() }?.let { "Welcome, $it" } ?: "Welcome",
                modifier = Modifier.testTag("welcome.title").semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.text,
            )
            if (!isOnline) OfflineBanner("You're offline. Connect to create or join a household.")
            Card(
                title = "Create a household", label = "Household name", value = name, onValueChange = onNameChange,
                error = createError, fieldTag = "welcome.name", errorTag = "welcome.createError",
                isCode = false, disabled = disabled, onSubmit = onCreate,
                buttonText = "Create household", busy = state == WelcomeState.Creating,
                buttonTag = "welcome.create", spinnerTag = "welcome.creating",
            )
            Card(
                title = "Join with a code", label = "Invite code", value = code, onValueChange = onCodeChange,
                error = joinError, fieldTag = "welcome.code", errorTag = "welcome.joinError",
                isCode = true, disabled = disabled, onSubmit = onJoin,
                buttonText = "Join household", busy = state == WelcomeState.Joining,
                buttonTag = "welcome.join", spinnerTag = "welcome.joining",
            )
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("welcome.signout"),
            ) { Text("Sign out", color = tokens.accent) }
        }
    }
}

@Composable
private fun Card(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    fieldTag: String,
    errorTag: String,
    isCode: Boolean,
    disabled: Boolean,
    onSubmit: () -> Unit,
    buttonText: String,
    busy: Boolean,
    buttonTag: String,
    spinnerTag: String,
) {
    val tokens = LocalTokens.current
    Surface(
        shape = RoundedCornerShape(Tokens.CardRadius),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Column(Modifier.padding(Tokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title, modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tokens.text,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                // Supporting text keeps the message and the field one control, so TalkBack reads them together;
                // the live region announces a message that appears while focus stays on the button.
                supportingText = error?.let {
                    {
                        Text(
                            it, color = tokens.danger,
                            modifier = Modifier.testTag(errorTag).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = if (isCode) KeyboardCapitalization.None else KeyboardCapitalization.Words,
                    autoCorrectEnabled = !isCode,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (!disabled) onSubmit() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag(fieldTag),
            )
            Button(
                onClick = onSubmit,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag(buttonTag)
                    .semantics { if (busy) stateDescription = "In progress" },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp).testTag(spinnerTag), color = Color.White, strokeWidth = 2.dp)
                    Box(Modifier.size(8.dp))
                }
                Text(buttonText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
