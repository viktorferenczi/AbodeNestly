package com.abodenestly.core

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.shared.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Helpers shared by the e2e journeys (TESTING §5): every run creates its own user through the admin API,
 * reads the magic link from Mailpit and deletes what it made. The emulator reaches the host as `10.0.2.2`.
 *
 * Needs instrumentation args `anonKey` and `serviceRoleKey` (apps/android/README.md).
 */
object E2E {
    const val SUPABASE_URL = "http://10.0.2.2:54321"
    const val MAILPIT_URL = "http://10.0.2.2:54324"
    const val API_URL = "http://10.0.2.2:3000"
    const val WEB_URL = "http://localhost:5173"

    class Keys(val anon: String, val serviceRole: String) {
        fun config() = AppConfig(API_URL, WEB_URL, SUPABASE_URL, anon, "abodenestly-dev")
    }

    /** `null` when either key is missing, so the journey can skip with `assumeTrue`. */
    fun keys(): Keys? {
        val args = InstrumentationRegistry.getArguments()
        val service = args.getString("serviceRoleKey").orEmpty()
        val anon = args.getString("anonKey").orEmpty()
        return if (service.isNotEmpty() && anon.isNotEmpty()) Keys(anon, service) else null
    }

    class TestUser(val id: String, val localPart: String, val email: String)

    fun createUser(keys: Keys): TestUser {
        val localPart = "e2e-${UUID.randomUUID().toString().lowercase()}"
        val email = "$localPart@test.local"
        val body = JSONObject().put("email", email).put("email_confirm", true).toString()
        val id = JSONObject(request("$SUPABASE_URL/auth/v1/admin/users", "POST", body, keys).second).getString("id")
        return TestUser(id, localPart, email)
    }

    fun deleteUser(keys: Keys, id: String) {
        runCatching { request("$SUPABASE_URL/auth/v1/admin/users/$id", "DELETE", null, keys) }
    }

    /** Rows of a table through PostgREST with the service role (bypasses RLS). */
    fun rows(keys: Keys, table: String, query: String): List<JSONObject> {
        val array = JSONArray(request("$SUPABASE_URL/rest/v1/$table?$query", "GET", null, keys).second)
        return List(array.length()) { array.getJSONObject(it) }
    }

    fun delete(keys: Keys, table: String, query: String) {
        runCatching { request("$SUPABASE_URL/rest/v1/$table?$query", "DELETE", null, keys) }
    }

    /** Waits for the magic-link email and returns the `abodenestly-dev://auth/callback?code=…` it redirects to. */
    fun callbackFromMagicLink(email: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val search = JSONObject(request("$MAILPIT_URL/api/v1/search?query=${Uri.encode("to:$email")}", "GET", null).second)
            val id = search.optJSONArray("messages")?.optJSONObject(0)?.optString("ID")
            if (!id.isNullOrEmpty()) {
                val html = JSONObject(request("$MAILPIT_URL/api/v1/message/$id", "GET", null).second).optString("HTML")
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

    fun request(
        url: String, method: String, body: String?, keys: Keys? = null, followRedirects: Boolean = true,
    ): Triple<Int, String, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            if (keys != null) {
                connection.setRequestProperty("apikey", keys.serviceRole)
                connection.setRequestProperty("Authorization", "Bearer ${keys.serviceRole}")
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
