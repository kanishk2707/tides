package com.mythron.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.mythron.aethertides.shared.math.MathX
import com.mythron.aethertides.shared.math.Rng
import com.mythron.aethertides.shared.ocean.Ocean
import com.mythron.aethertides.shared.ocean.WaveSample
import kotlin.math.abs

/**
 * Particles, arcs and floating text.
 *
 * A fixed pool, no allocation, and -- the part that matters for how the game reads -- water
 * particles are aware of the water. Spray falls back into the sea and dies at the surface it
 * actually came from, foam rides the orbital current of the wave it was born on, and bubbles
 * rise to the real waterline rather than to y = 0. Effects that ignore the surface they sit on
 * are the fastest way to make good water look fake.
 */
class Fx(private val art: Art) {

    enum class Kind { SPRAY, FOAM, SMOKE, EMBER, DEBRIS, BUBBLE, SHOCK, ARC, TEXT, RAIN }

    private class P {
        @JvmField var alive = false
        @JvmField var kind = Kind.SPRAY
        @JvmField var x = 0f
        @JvmField var y = 0f
        @JvmField var vx = 0f
        @JvmField var vy = 0f
        @JvmField var life = 0f
        @JvmField var maxLife = 1f
        @JvmField var size = 1f
        @JvmField var sizeEnd = 1f
        @JvmField var rot = 0f
        @JvmField var spin = 0f
        @JvmField var drag = 1.2f
        @JvmField var gravity = 1f
        @JvmField var additive = false
        @JvmField var x2 = 0f
        @JvmField var y2 = 0f
        @JvmField var seed = 0
        @JvmField val color = Color()
        @JvmField val colorEnd = Color()
        @JvmField var label: String? = null
    }

    private val pool = Array(1400) { P() }
    private var cursor = 0
    private val rng = Rng(0xF00DL)
    private val ws = WaveSample()
    private val tmp = Color()

    private fun next(): P {
        // Ring allocation: when the pool is saturated the oldest effect is replaced, which is
        // always better than dropping the newest one the player is looking at.
        var tries = 0
        while (tries < pool.size) {
            cursor = (cursor + 1) % pool.size
            if (!pool[cursor].alive) return pool[cursor]
            tries++
        }
        cursor = (cursor + 1) % pool.size
        return pool[cursor]
    }

    fun clear() {
        for (p in pool) p.alive = false
    }

    fun count(): Int {
        var n = 0
        for (p in pool) if (p.alive) n++
        return n
    }

    // -----------------------------------------------------------------------
    // Emitters
    // -----------------------------------------------------------------------

    /** Water thrown off a hull, a landing, or a breaking crest. */
    fun spray(x: Float, y: Float, dirX: Float, dirY: Float, power: Float, count: Int) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.SPRAY
            p.x = x + rng.range(-0.9f, 0.9f)
            p.y = y + rng.range(-0.4f, 0.7f)
            val spread = rng.range(-0.55f, 0.55f)
            val sx = dirX + spread
            val sy = dirY + rng.range(-0.15f, 0.75f)
            val l = MathX.len(sx, sy).coerceAtLeast(0.01f)
            val speed = power * rng.range(0.45f, 1.35f)
            p.vx = sx / l * speed
            p.vy = sy / l * speed
            p.maxLife = rng.range(0.55f, 1.5f)
            p.life = p.maxLife
            p.size = rng.range(0.16f, 0.5f)
            p.sizeEnd = p.size * rng.range(1.9f, 3.6f)
            p.gravity = 1f
            p.drag = 0.55f
            p.additive = false
            p.color.set(Palette.foam).a = 0.95f
            p.colorEnd.set(Palette.foamShadow).a = 0f
        }
    }

    /** Persistent surface foam left behind by a wake or a broken crest. */
    fun foam(x: Float, y: Float, drift: Float, count: Int, scale: Float = 1f) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.FOAM
            p.x = x + rng.range(-1.2f, 1.2f)
            p.y = y
            p.vx = drift + rng.range(-1.2f, 1.2f)
            p.vy = 0f
            p.maxLife = rng.range(1.4f, 3.4f)
            p.life = p.maxLife
            p.size = rng.range(0.32f, 0.85f) * scale
            p.sizeEnd = p.size * rng.range(1.5f, 2.8f)
            p.gravity = 0f
            p.drag = 1.4f
            p.additive = false
            p.rot = rng.range(0f, MathX.TAU)
            p.spin = rng.range(-0.6f, 0.6f)
            p.color.set(Palette.foam).a = 0.62f
            p.colorEnd.set(Palette.foamShadow).a = 0f
        }
    }

    fun smoke(x: Float, y: Float, count: Int, tint: Color, rise: Float = 2.4f) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.SMOKE
            p.x = x + rng.range(-0.8f, 0.8f)
            p.y = y + rng.range(-0.5f, 0.5f)
            p.vx = rng.range(-1.5f, 1.5f)
            p.vy = rise * rng.range(0.5f, 1.4f)
            p.maxLife = rng.range(1.2f, 2.8f)
            p.life = p.maxLife
            p.size = rng.range(0.8f, 1.9f)
            p.sizeEnd = p.size * rng.range(2.4f, 4.2f)
            p.gravity = -0.08f
            p.drag = 0.8f
            p.additive = false
            p.rot = rng.range(0f, MathX.TAU)
            p.spin = rng.range(-0.9f, 0.9f)
            p.color.set(tint).a = 0.72f
            p.colorEnd.set(tint).a = 0f
        }
    }

    fun embers(x: Float, y: Float, power: Float, count: Int, tint: Color) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.EMBER
            p.x = x; p.y = y
            val a = rng.range(0f, MathX.TAU)
            val speed = power * rng.range(0.3f, 1.3f)
            p.vx = kotlin.math.cos(a) * speed
            p.vy = kotlin.math.sin(a) * speed + power * 0.25f
            p.maxLife = rng.range(0.4f, 1.1f)
            p.life = p.maxLife
            p.size = rng.range(0.16f, 0.42f)
            p.sizeEnd = p.size * 0.25f
            p.gravity = 0.75f
            p.drag = 0.9f
            p.additive = true
            p.color.set(tint).a = 1f
            p.colorEnd.set(tint).a = 0f
        }
    }

    fun debris(x: Float, y: Float, power: Float, count: Int, tint: Color) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.DEBRIS
            p.x = x; p.y = y
            p.vx = rng.range(-power, power)
            p.vy = rng.range(power * 0.2f, power * 1.1f)
            p.maxLife = rng.range(1.4f, 3f)
            p.life = p.maxLife
            p.size = rng.range(0.2f, 0.55f)
            p.sizeEnd = p.size
            p.gravity = 1f
            p.drag = 0.35f
            p.rot = rng.range(0f, MathX.TAU)
            p.spin = rng.range(-7f, 7f)
            p.additive = false
            p.color.set(tint).a = 1f
            p.colorEnd.set(tint).a = 0.2f
        }
    }

    fun bubbles(x: Float, y: Float, count: Int) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.BUBBLE
            p.x = x + rng.range(-1.5f, 1.5f)
            p.y = y - rng.range(0.5f, 4f)
            p.vx = rng.range(-0.5f, 0.5f)
            p.vy = rng.range(1.4f, 3.6f)
            p.maxLife = rng.range(0.8f, 2.2f)
            p.life = p.maxLife
            p.size = rng.range(0.1f, 0.34f)
            p.sizeEnd = p.size * 1.5f
            p.gravity = 0f
            p.drag = 0.4f
            p.additive = false
            p.color.set(Palette.foam).a = 0.55f
            p.colorEnd.set(Palette.foam).a = 0f
        }
    }

    /** Expanding shockwave ring. */
    fun shock(x: Float, y: Float, radius: Float, tint: Color, duration: Float = 0.5f) {
        val p = next()
        p.alive = true
        p.kind = Kind.SHOCK
        p.x = x; p.y = y
        p.vx = 0f; p.vy = 0f
        p.maxLife = duration
        p.life = duration
        p.size = radius * 0.15f
        p.sizeEnd = radius
        p.additive = true
        p.color.set(tint).a = 0.95f
        p.colorEnd.set(tint).a = 0f
    }

    /** A lightning arc between two points. Drawn as a jagged polyline from a fixed seed. */
    fun arc(x1: Float, y1: Float, x2: Float, y2: Float, tint: Color, duration: Float = 0.22f) {
        val p = next()
        p.alive = true
        p.kind = Kind.ARC
        p.x = x1; p.y = y1; p.x2 = x2; p.y2 = y2
        p.maxLife = duration
        p.life = duration
        p.size = 0.28f
        p.sizeEnd = 0.05f
        p.additive = true
        p.seed = rng.nextInt(9999)
        p.color.set(tint).a = 1f
        p.colorEnd.set(tint).a = 0f
    }

    /** Rising label, for damage numbers and callouts. */
    fun text(x: Float, y: Float, label: String, tint: Color, duration: Float = 1.3f) {
        val p = next()
        p.alive = true
        p.kind = Kind.TEXT
        p.x = x; p.y = y
        p.vx = 0f
        p.vy = 6.5f
        p.maxLife = duration
        p.life = duration
        p.size = 1f
        p.sizeEnd = 1f
        p.drag = 1.6f
        p.gravity = 0f
        p.additive = false
        p.label = label
        p.color.set(tint).a = 1f
        p.colorEnd.set(tint).a = 0f
    }

    fun rain(x: Float, y: Float, windX: Float, count: Int) {
        repeat(count) {
            val p = next()
            p.alive = true
            p.kind = Kind.RAIN
            p.x = x + rng.range(-2f, 2f)
            p.y = y
            p.vx = windX * rng.range(0.7f, 1.1f)
            p.vy = -rng.range(26f, 40f)
            p.maxLife = rng.range(0.5f, 1.4f)
            p.life = p.maxLife
            p.size = rng.range(0.5f, 1.3f)
            p.sizeEnd = p.size
            p.gravity = 0f
            p.drag = 0f
            p.additive = false
            p.color.set(Palette.foamShadow).a = 0.26f
            p.colorEnd.set(Palette.foamShadow).a = 0.08f
        }
    }

    // -----------------------------------------------------------------------
    // Step and draw
    // -----------------------------------------------------------------------

    fun update(dt: Float, ocean: Ocean) {
        for (p in pool) {
            if (!p.alive) continue
            p.life -= dt
            if (p.life <= 0f) { p.alive = false; p.label = null; continue }

            when (p.kind) {
                Kind.SHOCK, Kind.ARC -> {}

                Kind.FOAM -> {
                    // Foam belongs to the water: it rides the surface and its orbital flow.
                    ocean.sample(p.x, ws)
                    p.x += (p.vx * 0.35f + ws.vx) * dt
                    p.y = ws.y + 0.12f
                    p.vx -= p.vx * MathX.clamp01(p.drag * dt)
                    p.rot += p.spin * dt
                }

                Kind.BUBBLE -> {
                    ocean.sample(p.x, ws)
                    p.x += (p.vx + ws.vx * 0.5f) * dt
                    p.y += p.vy * dt
                    // Pops at the real surface, not at sea level.
                    if (p.y > ws.y) { p.alive = false; continue }
                }

                Kind.RAIN -> {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    ocean.sample(p.x, ws)
                    if (p.y <= ws.y) {
                        // Every raindrop that lands leaves a mark on the water.
                        p.alive = false
                        if (rng.chance(0.16f)) spray(p.x, ws.y, 0f, 1f, 1.6f, 1)
                        continue
                    }
                }

                Kind.TEXT -> {
                    p.y += p.vy * dt
                    p.vy -= p.vy * MathX.clamp01(p.drag * dt)
                }

                else -> {
                    p.vy -= 9.80665f * p.gravity * dt
                    p.vx -= p.vx * MathX.clamp01(p.drag * dt)
                    p.vy -= p.vy * MathX.clamp01(p.drag * 0.35f * dt)
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.rot += p.spin * dt

                    if (p.kind == Kind.SPRAY || p.kind == Kind.DEBRIS) {
                        ocean.sample(p.x, ws)
                        if (p.y <= ws.y) {
                            if (p.kind == Kind.SPRAY) {
                                p.alive = false
                                // Spray does not vanish, it becomes foam on the sea it fell into.
                                if (rng.chance(0.5f)) foam(p.x, ws.y, ws.vx * 0.5f, 1, 0.7f)
                                continue
                            } else {
                                p.y = ws.y
                                p.vy = abs(p.vy) * 0.25f
                                p.vx *= 0.8f
                            }
                        }
                    }
                }
            }
        }
    }

    fun draw(g: Painter, cameraLeft: Float, cameraRight: Float) {
        // Two passes so additive light never has to fight alpha-blended foam for ordering.
        drawPass(g, cameraLeft, cameraRight, additive = false)
        drawPass(g, cameraLeft, cameraRight, additive = true)
    }

    private fun drawPass(g: Painter, left: Float, right: Float, additive: Boolean) {
        g.additive(additive)
        for (p in pool) {
            if (!p.alive || p.additive != additive) continue
            if (p.x < left - 30f || p.x > right + 30f) continue
            val t = 1f - p.life / p.maxLife
            val size = MathX.lerp(p.size, p.sizeEnd, t)
            tmp.r = MathX.lerp(p.color.r, p.colorEnd.r, t)
            tmp.g = MathX.lerp(p.color.g, p.colorEnd.g, t)
            tmp.b = MathX.lerp(p.color.b, p.colorEnd.b, t)
            tmp.a = MathX.lerp(p.color.a, p.colorEnd.a, t)

            when (p.kind) {
                Kind.SHOCK -> g.ring(p.x, p.y, size, tmp)
                Kind.ARC -> drawArc(g, p, tmp)
                Kind.TEXT -> {
                    val s = p.label ?: continue
                    g.textCentered(art.small, s, p.x, p.y, tmp)
                }
                Kind.RAIN -> {
                    // A streak, oriented along its own velocity.
                    val len = size * 1.5f
                    val l = MathX.len(p.vx, p.vy).coerceAtLeast(0.01f)
                    g.line(p.x, p.y, p.x - p.vx / l * len, p.y - p.vy / l * len, 0.05f, tmp)
                }
                Kind.EMBER -> g.spark(p.x, p.y, size, tmp)
                Kind.DEBRIS -> g.sprite(art.white, p.x, p.y, size, size * 0.45f, p.rot, tmp)
                Kind.BUBBLE -> g.sprite(art.ring, p.x, p.y, size * 2f, size * 2f, tmp)
                else -> g.puff(p.x, p.y, size, tmp, p.rot)
            }
        }
        g.additive(false)
    }

    private fun drawArc(g: Painter, p: P, color: Color) {
        val segs = 7
        var px = p.x
        var py = p.y
        val dx = p.x2 - p.x
        val dy = p.y2 - p.y
        val len = MathX.len(dx, dy)
        val nx = -dy / len.coerceAtLeast(0.01f)
        val ny = dx / len.coerceAtLeast(0.01f)
        val jag = len * 0.11f
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            var qx = p.x + dx * t
            var qy = p.y + dy * t
            if (i < segs) {
                // Deterministic jitter from the stored seed, so the bolt does not crawl.
                val h = ((p.seed * 73856093) xor (i * 19349663))
                val j = (((h ushr 8) and 0xFF) / 255f - 0.5f) * 2f * jag
                qx += nx * j
                qy += ny * j
            }
            g.line(px, py, qx, qy, p.size * 1.9f, color)
            px = qx; py = qy
        }
        g.glow(p.x2, p.y2, 1.6f, color, 0.8f)
    }
}
