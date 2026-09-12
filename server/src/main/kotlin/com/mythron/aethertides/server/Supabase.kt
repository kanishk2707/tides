package com.mythron.aethertides.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** What the identity provider says about a token. */
class Identity(
    val userId: String,
    val isAnonymous: Boolean,
    /** Unix seconds. Cached verifications are dropped at this moment. */
    val expiresAt: Long
)

/** The stored profile. Owned by the server; the client only ever sees a copy. */
class Profile(
    val userId: String,
    var displayName: String,
    var rating: Int,
    var played: Int,
    var won: Int,
    var bestDistance: Float
)

/**
 * The identity and profile store.
 *
 * Two trust boundaries are kept apart on purpose:
 *
 *  - **Token verification** asks the auth service itself whether a token is good, rather than
 *    checking the signature locally. It costs one HTTPS round trip per login, cached for the
 *    token's lifetime, and it can never be wrong about key rotation, revocation or algorithm
 *    -- which a home-rolled JWT verifier can, silently, in the way that ends up on the news.
 *
 *  - **Profile writes** use the service-role secret, which exists only in this process's
 *    environment. Clients never write to the database at all. Ratings, win counts and names
 *    change only because this server said so.
 *
 * All calls block; callers run them off the simulation thread.
 */
class Supabase(private val baseUrl: String, private val secretKey: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val gson = Gson()

    /** token-hash -> identity. Only the hash is kept; the token itself is never stored. */
    private val verified = ConcurrentHashMap<String, Identity>()

    // -----------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------

    /** Returns null for any token the provider will not vouch for. Never throws. */
    fun verify(token: String): Identity? {
        if (token.isBlank() || token.length > 4000) return null
        val key = hash(token)
        val now = System.currentTimeMillis() / 1000
        verified[key]?.let { if (it.expiresAt > now + 5) return it else verified.remove(key) }

        return try {
            val req = HttpRequest.newBuilder(URI.create("$baseUrl/auth/v1/user"))
                .timeout(Duration.ofSeconds(8))
                .header("apikey", secretKey)
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null
            val body = JsonParser.parseString(res.body()).asJsonObject
            val id = body.get("id")?.asString ?: return null
            if (id.isBlank()) return null
            val anon = body.get("is_anonymous")?.asBoolean ?: false
            val exp = jwtExpiry(token) ?: (now + 3600)
            val identity = Identity(id, anon, exp)
            verified[key] = identity
            identity
        } catch (e: Exception) {
            null
        }
    }

    /** Read `exp` straight out of the token so the cache expires when the token does. */
    private fun jwtExpiry(token: String): Long? {
        return try {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payload = String(Base64.getUrlDecoder().decode(padBase64(parts[1])), Charsets.UTF_8)
            JsonParser.parseString(payload).asJsonObject.get("exp")?.asLong
        } catch (e: Exception) {
            null
        }
    }

    private fun padBase64(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    private fun hash(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d)
    }

    fun forget(token: String) {
        verified.remove(hash(token))
    }

    // -----------------------------------------------------------------------
    // Profiles
    // -----------------------------------------------------------------------

    private fun rest(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$baseUrl/rest/v1/$path"))
            .timeout(Duration.ofSeconds(8))
            .header("apikey", secretKey)
            .header("Authorization", "Bearer $secretKey")
            .header("Content-Type", "application/json")

    /** Fetch the profile, creating it on first sight. Returns null only if the store is down. */
    fun loadOrCreate(userId: String, requestedName: String): Profile? {
        try {
            val get = rest("profiles?id=eq.$userId&select=*").GET().build()
            val res = http.send(get, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val arr = JsonParser.parseString(res.body()).asJsonArray
                if (arr.size() > 0) return fromJson(arr[0].asJsonObject)
            } else if (res.statusCode() !in 200..299) {
                return null
            }

            val body = JsonObject()
            body.addProperty("id", userId)
            body.addProperty("display_name", requestedName)
            val post = rest("profiles")
                .header("Prefer", "return=representation,resolution=merge-duplicates")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build()
            val created = http.send(post, HttpResponse.BodyHandlers.ofString())
            if (created.statusCode() !in 200..299) return null
            val arr = JsonParser.parseString(created.body()).asJsonArray
            return if (arr.size() > 0) fromJson(arr[0].asJsonObject) else null
        } catch (e: Exception) {
            return null
        }
    }

    fun updateName(userId: String, name: String): Boolean = patch(userId, JsonObject().apply {
        addProperty("display_name", name)
    })

    fun touch(userId: String): Boolean = patch(userId, JsonObject().apply {
        addProperty("last_seen_at", "now()")
    })

    /**
     * Persist the outcome of a rated series for one player, and record the match itself.
     * Both writes are idempotent enough that a retry after a timeout does no harm.
     */
    fun recordResult(
        p: Profile, opponentId: String?, won: Boolean, ratingAfter: Int,
        distance: Float, duration: Float, seed: Long
    ): Boolean {
        val patchOk = patch(p.userId, JsonObject().apply {
            addProperty("rating", ratingAfter)
            addProperty("matches_played", p.played)
            addProperty("matches_won", p.won)
            addProperty("best_distance", p.bestDistance)
            addProperty("last_seen_at", "now()")
        })
        val row = JsonObject().apply {
            addProperty("player_id", p.userId)
            if (opponentId != null) addProperty("opponent_id", opponentId)
            addProperty("won", won)
            addProperty("rating_after", ratingAfter)
            addProperty("distance", distance)
            addProperty("duration", duration)
            addProperty("seed", seed)
        }
        return try {
            val req = rest("match_results")
                .header("Prefer", "return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(row)))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            patchOk && res.statusCode() in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun patch(userId: String, body: JsonObject): Boolean = try {
        val req = rest("profiles?id=eq.$userId")
            .header("Prefer", "return=minimal")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() in 200..299
    } catch (e: Exception) {
        false
    }

    /**
     * Account deletion, as the platform stores require it: the auth user is removed, and the
     * profile and match rows go with it through the foreign keys. Nothing is soft-deleted.
     */
    fun deleteUser(userId: String): Boolean = try {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/auth/v1/admin/users/$userId"))
            .timeout(Duration.ofSeconds(10))
            .header("apikey", secretKey)
            .header("Authorization", "Bearer $secretKey")
            .DELETE()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        res.statusCode() in 200..299 || res.statusCode() == 404
    } catch (e: Exception) {
        false
    }

    private fun fromJson(o: JsonObject): Profile = Profile(
        userId = o.get("id").asString,
        displayName = o.get("display_name")?.takeIf { !it.isJsonNull }?.asString ?: "Sailor",
        rating = o.get("rating")?.takeIf { !it.isJsonNull }?.asInt ?: 1200,
        played = o.get("matches_played")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
        won = o.get("matches_won")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
        bestDistance = o.get("best_distance")?.takeIf { !it.isJsonNull }?.asFloat ?: 0f
    )
}
