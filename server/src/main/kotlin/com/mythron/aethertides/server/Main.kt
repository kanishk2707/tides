package com.mythron.aethertides.server

import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Entry point.
 *
 * Configuration is entirely from the environment (see [ServerConfig]). The recommended
 * production layout terminates TLS at a reverse proxy in front of this process -- see
 * deploy/Caddyfile -- but a PKCS12 keystore can be supplied directly for a bare-metal box.
 *
 *   PORT=7788 SUPABASE_URL=https://xxxx.supabase.co SUPABASE_SECRET_KEY=sb_secret_... \
 *     java -jar aether-tides-server.jar
 */
fun main(args: Array<String>) {
    val cfg = ServerConfig.fromEnv(args)

    val supabase = if (cfg.authEnabled) Supabase(cfg.supabaseUrl!!, cfg.supabaseSecretKey!!) else null
    if (supabase == null) {
        println("WARNING: no identity provider configured; running as an open guest server.")
        println("         Set SUPABASE_URL and SUPABASE_SECRET_KEY for production.")
    }

    val server = GameServer(cfg, supabase)
    server.isReuseAddr = true

    cfg.tlsKeystore?.let { path ->
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(path).use { ks.load(it, (cfg.tlsKeystorePassword ?: "").toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, (cfg.tlsKeystorePassword ?: "").toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        server.setWebSocketFactory(DefaultSSLWebSocketServerFactory(ctx))
        println("TLS: serving wss:// from $path")
    }

    val health = Health(cfg.healthPort) { server.health() }
    health.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        println("shutting down: " + server.stats())
        health.stop()
        server.shutdown()
    })

    server.run()
}
