package com.abodenestly.shared

/** Build-time configuration injected from `env/<flavor>.properties` via BuildConfig (SPEC §3). Never hard-code these. */
data class AppConfig(
    val apiBaseUrl: String,
    /** The web app's origin; invite links are `<webBaseUrl>/join/<code>`. */
    val webBaseUrl: String,
    val supabaseUrl: String,
    /** May be blank until `env/dev.properties` is filled in locally; requests will 401 rather than crash. */
    val supabaseAnonKey: String,
    /** Custom URL scheme for auth callbacks, e.g. `abodenestly-dev`. */
    val authScheme: String,
) {
    /** Where Supabase redirects after a magic link or OAuth round trip. */
    val authCallbackUrl: String get() = "$authScheme://$AUTH_HOST$AUTH_PATH"

    companion object {
        const val AUTH_HOST = "auth"
        const val AUTH_PATH = "/callback"
    }
}
