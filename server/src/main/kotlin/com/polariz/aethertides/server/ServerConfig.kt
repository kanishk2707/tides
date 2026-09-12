package com.polariz.aethertides.server

import com.polariz.aethertides.shared.sim.Config

/**
 * Everything the server needs to know about its environment, read once from the process
 * environment. No config files: the deployment story is "set these variables", which works
 * identically under systemd, Docker, and a shell.
 *
 * Nothing secret is ever read from the command line, where it would show in `ps`.
 */
class ServerConfig private constructor(
    val port: Int,
    val healthPort: Int,
    val supabaseUrl: String?,
    val supabaseSecretKey: String?,
    val maxPlayers: Int,
    val maxPerIp: Int,
    val allowGuests: Boolean,
    val tlsKeystore: String?,
    val tlsKeystorePassword: String?,
    val botFallbackSeconds: Float
) {
    /** Authentication is on whenever an identity provider is configured. */
    val authEnabled: Boolean get() = !supabaseUrl.isNullOrBlank() && !supabaseSecretKey.isNullOrBlank()

    fun describe(): String = buildString {
        append("port=").append(port)
        append(" health=").append(healthPort)
        append(" auth=").append(if (authEnabled) "supabase" else "OFF")
        append(" guests=").append(allowGuests)
        append(" maxPlayers=").append(maxPlayers)
        append(" maxPerIp=").append(maxPerIp)
        append(" tls=").append(if (tlsKeystore != null) "keystore" else "none (terminate at proxy)")
    }

    companion object {
        fun fromEnv(args: Array<String>): ServerConfig {
            fun env(name: String): String? = System.getenv(name)?.trim()?.ifBlank { null }
            fun envInt(name: String, default: Int): Int = env(name)?.toIntOrNull() ?: default

            val port = args.firstOrNull()?.toIntOrNull() ?: envInt("PORT", Config.DEFAULT_PORT)
            val auth = env("SUPABASE_URL") != null && env("SUPABASE_SECRET_KEY") != null
            return ServerConfig(
                port = port,
                healthPort = envInt("HEALTH_PORT", port + 1),
                supabaseUrl = env("SUPABASE_URL")?.trimEnd('/'),
                supabaseSecretKey = env("SUPABASE_SECRET_KEY"),
                maxPlayers = envInt("MAX_PLAYERS", 400),
                maxPerIp = envInt("MAX_PER_IP", 6),
                // With no identity provider the server can only run as an open guest server,
                // which is fine for a LAN and for development. With one configured, guests
                // are off unless explicitly re-enabled.
                allowGuests = env("ALLOW_GUESTS")?.equals("true", ignoreCase = true) ?: !auth,
                tlsKeystore = env("TLS_KEYSTORE"),
                tlsKeystorePassword = env("TLS_KEYSTORE_PASSWORD"),
                botFallbackSeconds = env("BOT_FALLBACK_SECONDS")?.toFloatOrNull() ?: 12f
            )
        }
    }
}
