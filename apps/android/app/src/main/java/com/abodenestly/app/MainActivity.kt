package com.abodenestly.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as AbodeNestlyApp).graph
    private val session: SessionViewModel by viewModels { graph.viewModelFactory }
    private val signIn: SignInViewModel by viewModels { graph.viewModelFactory }
    private val welcome: WelcomeViewModel by viewModels { graph.welcomeFactory(session) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AbodeTheme { AppRoot(session, signIn, welcome, graph.network, graph.api, graph.lastHousehold, graph.config.webBaseUrl) }
        }
        lifecycleScope.launch {
            // E2E starts signed out; the session is cleared before it is read.
            if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_RESET_SESSION, false)) graph.auth.signOut()
            session.start()
            // A recreated activity (rotation) must not replay a code the first instance already used.
            if (savedInstanceState == null) handleCallback(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { handleCallback(intent) }
    }

    private suspend fun handleCallback(intent: Intent) {
        intent.data?.toString()?.let { graph.auth.handle(it) }
    }

    companion object {
        /** Debug builds only, like iOS `-reset-session`. */
        const val EXTRA_RESET_SESSION = "reset-session"
    }
}
