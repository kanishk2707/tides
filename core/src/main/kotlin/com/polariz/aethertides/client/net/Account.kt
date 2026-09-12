package com.polariz.aethertides.client.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.polariz.aethertides.client.Prefs
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The player's identity.
 *
 * On first launch the app signs in **anonymously**: the auth service issues a real user id and
 * a token pair with no email, no password and no form. That is the whole account. It is
 * enough for a rating, a name and a match history, and it costs the player nothing to start
 * playing -- which is the only onboarding a mobile game should have.
 *
 * Tokens are refreshed before they expire and stored in the app's private preferences. The
 * game server never sees the refresh token; it only ever gets the short-lived access token,
 * and only over TLS.
 *
 * Everything network-bound runs on a background thread and reports back through [state],
 * which the menu polls once a frame.
 */
class Account(
    private val prefs: Prefs,
    private val baseUrl: String,
    private val publishableKey: String
) {
    enum class State { SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }

    @Volatile var state = State.SIGNED_OUT
        private set
    @Volatile var lastError: String = ""
        private set

    private val busy = AtomicBoolean(false)

    val userId: String get() = prefs.userId
    val isSignedIn: Boolean get() = prefs.accessToken.isNotBlank() && prefs.userId.isNotBlank()

    /** Anonymous accounts can be upgraded later; for now this is always true when signed in. */
    val isAnonymous: Boolean get() = prefs.isAnonymous

    /** A token that is valid for at least a minute, or null if a refresh is needed first. */
    fun freshToken(): String? {
        if (!isSignedIn) return null
        val now = System.currentTimeMillis() / 1000
        return if (prefs.tokenExpiresAt - now > 60) prefs.accessToken else null
    }

    /**
     * Make sure there is a usable session. Signs in anonymously if there is none, refreshes if
     * the one we have is stale, and does nothing if it is already good. Safe to call every time
     * the player heads for the online screen.
     */
    fun ensureSignedIn() {
        if (freshToken() != null) { state = State.SIGNED_IN; return }
        if (!busy.compareAndSet(false, true)) return
        state = State.SIGNING_IN
        Thread({
            try {
                val ok = if (prefs.refreshToken.isNotBlank()) refresh() || signUpAnonymously()
                         else signUpAnonymously()
                state = if (ok) State.SIGNED_IN else State.FAILED
            } finally {
                busy.set(false)
            }
        }, "aether-auth").apply { isDaemon = true }.start()
    }

    /** Forget the local session. The server-side account is deleted separately, over the game socket. */
    fun signOutLocally() {
        prefs.accessToken = ""
        prefs.refreshToken = ""
        prefs.userId = ""
        prefs.tokenExpiresAt = 0L
        state = State.SIGNED_OUT
    }

    // -----------------------------------------------------------------------

    private fun signUpAnonymously(): Boolean {
        val res = post("$baseUrl/auth/v1/signup", "{}") ?: return false
        return storeSession(res)
    }

    private fun refresh(): Boolean {
        val body = "{\"refresh_token\":\"${escape(prefs.refreshToken)}\"}"
        val res = post("$baseUrl/auth/v1/token?grant_type=refresh_token", body) ?: return false
        return storeSession(res)
    }

    private fun storeSession(json: JsonValue): Boolean {
        val access = json.getString("access_token", "")
        val refreshTok = json.getString("refresh_token", "")
        val user = json.get("user")
        val id = user?.getString("id", "") ?: ""
        if (access.isBlank() || id.isBlank()) {
            lastError = json.getString("msg", json.getString("error_description", "no session"))
            return false
        }
        prefs.accessToken = access
        prefs.refreshToken = refreshTok
        prefs.userId = id
        prefs.isAnonymous = user?.getBoolean("is_anonymous", true) ?: true
        val expiresAt = json.getLong("expires_at", 0L)
        prefs.tokenExpiresAt = if (expiresAt > 0L) expiresAt
            else System.currentTimeMillis() / 1000 + json.getLong("expires_in", 3600L)
        return true
    }

    private fun post(url: String, body: String): JsonValue? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $publishableKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            val json = JsonReader().parse(text)
            if (code !in 200..299) {
                lastError = json?.getString("msg", json.getString("error_description", "HTTP $code")) ?: "HTTP $code"
                // A refresh token that has been revoked or rotated away is not coming back.
                if (code == 400 || code == 401) prefs.refreshToken = ""
                return null
            }
            json
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
