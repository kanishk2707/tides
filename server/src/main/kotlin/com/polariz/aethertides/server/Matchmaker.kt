package com.polariz.aethertides.server

import com.polariz.aethertides.shared.net.Packets
import com.polariz.aethertides.shared.net.QueueMode
import com.polariz.aethertides.shared.sim.Role
import kotlin.math.abs

/**
 * Pairing.
 *
 * Three lanes. Private codes pair exactly; ranked widens a rating window the longer you wait;
 * quick play takes anyone. If a quick-play queue goes unanswered for long enough the player is
 * given a bot rather than left staring at a spinner -- on mobile, a match that never starts is
 * worse than a match against a machine.
 */
class Matchmaker(private val botFallbackAfter: Float = 12f) {

    private class Entry(val player: Player) {
        val since = System.nanoTime()
        fun waitedSeconds(): Float = (System.nanoTime() - since) / 1e9f
    }

    private val waiting = ArrayList<Entry>()

    @Synchronized
    fun add(p: Player) {
        if (waiting.any { it.player === p }) return
        p.queued = true
        waiting.add(Entry(p))
        broadcastStatus()
    }

    @Synchronized
    fun remove(p: Player) {
        waiting.removeAll { it.player === p }
        p.queued = false
    }

    @Synchronized
    fun size(): Int = waiting.size

    /**
     * Rating window for ranked, in points. Starts tight and opens up so nobody waits forever.
     */
    private fun window(waited: Float): Int = (120 + waited * 55f).toInt()

    @Synchronized
    fun pump(create: (Player?, Player?, Role, Float) -> Unit) {
        // 1. Private codes: exact matches only.
        var i = 0
        while (i < waiting.size) {
            val a = waiting[i]
            // Codes shorter than the minimum were refused at the door; this is belt and braces.
            if (a.player.queueMode != QueueMode.PRIVATE ||
                a.player.privateCode.length < com.polariz.aethertides.shared.net.Limits.PRIVATE_CODE_MIN
            ) { i++; continue }
            val j = waiting.indexOfFirst {
                it !== a && it.player.queueMode == QueueMode.PRIVATE &&
                        it.player.privateCode == a.player.privateCode &&
                        it.player.userId != a.player.userId
            }
            if (j >= 0) {
                val b = waiting[j]
                pair(a, b, create)
                i = 0
            } else i++
        }

        // 2. Ranked and quick play.
        i = 0
        while (i < waiting.size) {
            val a = waiting[i]
            if (a.player.queueMode == QueueMode.PRIVATE) { i++; continue }
            var matched = false
            for (j in i + 1 until waiting.size) {
                val b = waiting[j]
                if (b.player.queueMode == QueueMode.PRIVATE) continue
                val ranked = a.player.queueMode == QueueMode.RANKED || b.player.queueMode == QueueMode.RANKED
                if (ranked) {
                    val allowed = window(maxOf(a.waitedSeconds(), b.waitedSeconds()))
                    if (abs(a.player.rating - b.player.rating) > allowed) continue
                }
                pair(a, b, create)
                matched = true
                break
            }
            if (!matched) i++ else i = 0
        }

        // 3. Nobody came. Give them a game anyway.
        i = 0
        while (i < waiting.size) {
            val a = waiting[i]
            if (a.player.queueMode != QueueMode.PRIVATE && a.waitedSeconds() > botFallbackAfter) {
                waiting.removeAt(i)
                a.player.queued = false
                val role = preferredOrDefault(a.player)
                // Bot skill tracks rating so a strong player still gets a game worth playing.
                val skill = ((a.player.rating - 900) / 1300f).coerceIn(0.35f, 0.95f)
                create(a.player, null, role, skill)
            } else i++
        }

        broadcastStatus()
    }

    private fun preferredOrDefault(p: Player): Role =
        if (p.preferredRole == Role.TEMPEST.id) Role.TEMPEST else Role.NAVIGATOR

    private fun pair(a: Entry, b: Entry, create: (Player?, Player?, Role, Float) -> Unit) {
        waiting.remove(a)
        waiting.remove(b)
        a.player.queued = false
        b.player.queued = false

        // Honour a stated preference when only one player has one; otherwise coin flip. It
        // matters less than it looks: the roles swap for round two regardless.
        val aRole = when {
            a.player.preferredRole >= 0 && b.player.preferredRole != a.player.preferredRole ->
                Role.of(a.player.preferredRole)
            b.player.preferredRole >= 0 ->
                Role.of(b.player.preferredRole).other()
            else ->
                if ((System.nanoTime() ushr 8) and 1L == 0L) Role.NAVIGATOR else Role.TEMPEST
        }
        create(a.player, b.player, aRole, 0.65f)
    }

    private fun broadcastStatus() {
        for (e in waiting) {
            val eta = if (waiting.size >= 2) 1 else (botFallbackAfter - e.waitedSeconds()).toInt().coerceAtLeast(0)
            e.player.send(Packets.queueStatus(waiting.size, eta))
        }
    }
}
