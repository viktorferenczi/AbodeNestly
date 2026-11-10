package com.abodenestly.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.abodenestly.core.auth.AuthService
import com.abodenestly.core.auth.DataStoreLastHouseholdStore
import com.abodenestly.core.auth.LastHouseholdStore
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.shared.AppConfig
import com.abodenestly.ui.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written DI container (no Hilt, SKILL "Architecture"): created once in [AbodeNestlyApp]. */
class AppGraph(context: Context) {
    val config = AppConfig(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        authScheme = BuildConfig.AUTH_URL_SCHEME,
    )
    val auth: AuthService = SupabaseAuthService(config)
    val network: NetworkMonitor = NetworkMonitor.observing(context)
    val lastHousehold: LastHouseholdStore =
        DataStoreLastHouseholdStore.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val viewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            SessionViewModel::class.java -> SessionViewModel(auth, lastHousehold, isOnline = { network.isOnline.value })
            SignInViewModel::class.java -> SignInViewModel(auth, redirectTo = config.authCallbackUrl)
            else -> error("Unknown view model ${modelClass.name}")
        } as T
    }
}
