package com.abodenestly.core

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.Text
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.shared.AppConfig
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.TopBar
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * E2E-CORE-1, first half: sign in with a real PKCE magic link against `supabase start`.
 *
 * Lives in `:core`, so it cannot start `:app`'s MainActivity. It hosts the real [SignInScreen],
 * [SessionViewModel] and [SupabaseAuthService] in a plain activity instead, and delivers the
 * `abodenestly-dev://auth/callback?code=…` URL to `auth.handle` (what MainActivity does with the intent).
 * "Relaunch" closes the activity and builds a fresh service, which must read the persisted session.
 *
 * Needs instrumentation args `anonKey` and `serviceRoleKey` (apps/android/README.md); skipped without them.
 * Never uses Apple or Google (TESTING §5).
 */
class SignInE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()
    private val supabaseUrl = "http://10.0.2.2:54321"
    private val mailpitUrl = "http://10.0.2.2:54324"
    private lateinit var serviceRoleKey: String
    private lateinit var config: AppConfig
    private var userId = ""
    private lateinit var localPart: String
    private lateinit var email: String

    @Before
    fun createUser() {
        val service = args.getString("serviceRoleKey").orEmpty()
        val anon = args.getString("anonKey").orEmpty()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", service.isNotEmpty() && anon.isNotEmpty())
        serviceRoleKey = service
        config = AppConfig("http://10.0.2.2:3000", supabaseUrl, anon, "abodenestly-dev")
        // TESTING §5: every run creates its own user and deletes it at the end.
        localPart = "e2e-${UUID.randomUUID().toString().lowercase()}"
        email = "$localPart@test.local"
        val body = JSONObject().put("email", email).put("email_confirm", true).toString()
        userId = JSONObject(request("$supabaseUrl/auth/v1/admin/users", "POST", body, admin = true).second).getString("id")
    }

    @After
    fun deleteUser() {
        if (userId.isNotEmpty()) runCatching { request("$supabaseUrl/auth/v1/admin/users/$userId", "DELETE", null, admin = true) }
    }

    @Test
    fun magicLinkLandsOnWelcomeWithDisplayNameAndSurvivesRelaunch() {
        // AC1: signed out → sign-in screen.
        var auth = SupabaseAuthService(config)
        var scenario = launch(auth, reset = true)
        rule.waitUntil(10_000) { exists("signin.title") }

        rule.onNodeWithTag("signin.email").performTextInput(email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }

        // AC4: a real PKCE exchange. The verify URL answers with a redirect to the app's custom scheme;
        // handing that Location to the same install lets the SDK use the stored code verifier.
        runBlocking { auth.handle(callbackFromMagicLink()) }

        // AC4: no household yet → Welcome. AC8: the profile trigger set display_name to the email local part.
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("topbar.trailing").assertTextEquals(localPart)

        // AC6: closed and reopened (a fresh service, no reset) → still signed in.
        scenario.close()
        auth = SupabaseAuthService(config)
        scenario = launch(auth, reset = false)
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("topbar.trailing").assertTextEquals(localPart)
        scenario.close()
    }

    // MARK: Hosting

    private fun launch(auth: SupabaseAuthService, reset: Boolean): ActivityScenario<ComponentActivity> {
        if (reset) runBlocking { auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val signIn = SignInViewModel(auth, config.authCallbackUrl)
        return ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn) } }
            session.start()
        }
    }

    /** What `:app`'s AppRoot shows, reduced to the parts this journey asserts on. */
    @Composable
    private fun Host(session: SessionViewModel, signIn: SignInViewModel) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> {
                TopBar("AbodeNestly", state.profile?.displayName)
                // Only the landing rule may put "Welcome" here: no household → Destination.Welcome (AC4).
                if (state.destination == Destination.Welcome) Text("Welcome", modifier = Modifier.testTag("welcome.title"))
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // MARK: HTTP helpers

    private fun callbackFromMagicLink(): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val search = JSONObject(request("$mailpitUrl/api/v1/search?query=${Uri.encode("to:$email")}", "GET", null).second)
            val id = search.optJSONArray("messages")?.optJSONObject(0)?.optString("ID")
            if (!id.isNullOrEmpty()) {
                val html = JSONObject(request("$mailpitUrl/api/v1/message/$id", "GET", null).second).optString("HTML")
                val match = Regex("""href="([^"]*/auth/v1/verify[^"]*)"""").find(html)
                if (match != null) {
                    // The email links to 127.0.0.1, which on the emulator is the emulator itself.
                    val verify = match.groupValues[1].replace("&amp;", "&")
                        .replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
                    val (code, _, location) = request(verify, "GET", null, followRedirects = false)
                    check(code in 300..399 && location != null) { "verify answered $code without a redirect" }
                    return location
                }
            }
            Thread.sleep(500)
        }
        error("No magic link email for $email in Mailpit (is `supabase start` running?)")
    }

    private fun request(
        url: String, method: String, body: String?, admin: Boolean = false, followRedirects: Boolean = true,
    ): Triple<Int, String, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            if (admin) {
                connection.setRequestProperty("apikey", serviceRoleKey)
                connection.setRequestProperty("Authorization", "Bearer $serviceRoleKey")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Triple(code, text, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}
