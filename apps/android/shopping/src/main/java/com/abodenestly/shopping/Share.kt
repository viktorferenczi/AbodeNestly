package com.abodenestly.shopping

import java.net.URI
import java.net.URLEncoder

/** What the C8 sheet says when a call failed; the texts are the web and iOS twins'. */
enum class ShareError(val message: String) {
    TurnOn("Could not turn on sharing. Try again."),
    Stop("Could not stop sharing. Try again."),
}

/** A call the sheet is waiting for. */
enum class ShareBusy { TurningOn, Stopping }

const val SHARE_OFFLINE_MESSAGE = "Sharing needs a connection"
const val SHARE_COPIED_MESSAGE = "Link copied"
const val SHARE_VIEW_NOTE = "Anyone with the link can view unchecked items"
const val SHARE_STOP_HINT = "The current link will stop working"

/** How long "Link copied" stays. */
const val SHARE_COPIED_MILLIS = 3_000L

/**
 * The pure parts of SHOP-13 (DESIGN C8). Twins: web `ShareSheet.ts`, iOS `Share`.
 */
object Share {
    /**
     * The page a link opens is the web app's `/shared/<token>`. The API's own link is the JSON route on another origin, so
     * only its last path segment is taken, percent-decoded. A relative path, an empty path and text that is not a URL have no token.
     */
    fun token(fromUrl: String): String? {
        val uri: URI = try {
            URI(fromUrl)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val path: String = uri.rawPath ?: return null
        if (!path.startsWith("/")) return null
        val last: String = path.split('/').lastOrNull { it.isNotEmpty() } ?: return null
        val decoded: String = try {
            URI("http://h/$last").path.removePrefix("/")
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        return decoded.ifEmpty { null }
    }

    /**
     * `<web base>/shared/<token>`; the token is percent-encoded as one path segment. `null` when [webBase] is not an
     * absolute http(s) URL (a build without `WEB_BASE_URL`), so the sheet never offers a broken relative link.
     */
    fun link(webBase: String, token: String): String? {
        val base: URI = try {
            URI(webBase.trim())
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme: String = base.scheme?.lowercase() ?: return null
        if ((scheme != "http" && scheme != "https") || base.host.isNullOrEmpty()) return null
        val encoded: String = URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        return webBase.trim().trimEnd('/') + "/shared/" + encoded
    }
}

/**
 * The sheet's state: off, on(link), busy or an error. [token] is what the switch shows; it only changes when a call has
 * succeeded, so a failure leaves the switch where it was.
 */
data class ShareState(val token: String?, val busy: ShareBusy? = null, val error: ShareError? = null) {
    val isOn: Boolean get() = token != null
    val isBusy: Boolean get() = busy != null

    fun begin(busy: ShareBusy): ShareState = ShareState(token, busy, null)
    fun turnedOn(token: String): ShareState = ShareState(token)
    fun stopped(): ShareState = ShareState(null)
    fun failed(error: ShareError): ShareState = ShareState(token, null, error)
}
