package com.mythron.aethertides.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A plain HTTP endpoint beside the websocket port, for load balancers and uptime monitors.
 *
 *   GET /healthz  -> 200 while the simulation thread is alive, 503 once it is not
 *   GET /metrics  -> a few counters in Prometheus text format
 *
 * Kept on its own port so it can be firewalled to the monitoring network while the game port
 * stays public, and so a health probe can never be confused for a client.
 */
class Health(port: Int, private val stats: () -> Snapshot) {

    class Snapshot(
        val alive: Boolean,
        val players: Int,
        val rooms: Int,
        val queued: Int,
        val uptimeSeconds: Long,
        val authFailures: Long,
        val rejectedFull: Long
    )

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 8)

    init {
        server.createContext("/healthz") { ex ->
            val s = stats()
            val body = """{"ok":${s.alive},"players":${s.players},"rooms":${s.rooms},"queued":${s.queued},"uptime":${s.uptimeSeconds}}"""
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(if (s.alive) 200 else 503, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/metrics") { ex ->
            val s = stats()
            val body = buildString {
                appendLine("# TYPE aether_players gauge"); appendLine("aether_players ${s.players}")
                appendLine("# TYPE aether_rooms gauge"); appendLine("aether_rooms ${s.rooms}")
                appendLine("# TYPE aether_queued gauge"); appendLine("aether_queued ${s.queued}")
                appendLine("# TYPE aether_uptime_seconds counter"); appendLine("aether_uptime_seconds ${s.uptimeSeconds}")
                appendLine("# TYPE aether_auth_failures_total counter"); appendLine("aether_auth_failures_total ${s.authFailures}")
                appendLine("# TYPE aether_rejected_full_total counter"); appendLine("aether_rejected_full_total ${s.rejectedFull}")
                appendLine("# TYPE aether_sim_alive gauge"); appendLine("aether_sim_alive ${if (s.alive) 1 else 0}")
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/plain; version=0.0.4")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.executor = null
    }

    fun start() = server.start()
    fun stop() = server.stop(0)
}
