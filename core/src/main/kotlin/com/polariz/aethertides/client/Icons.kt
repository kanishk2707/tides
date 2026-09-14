package com.polariz.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.SpellKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * The symbol set, drawn rather than typed.
 *
 * The definitions in `shared` carry a Unicode glyph per spell and per deployable -- ⟳ ● ☇ ▲ ✹
 * ❦ ☠ and so on -- and the HUD used to render them with the UI font. It never worked: Cinzel
 * Decorative and Rajdhani are Latin faces and contain none of those codepoints, so every
 * action button in the game came out blank. The Tempest's nine cards were nine identical empty
 * discs.
 *
 * Bundling a dingbat font to fix nine pictures would be silly, and there are no raster assets
 * in this build by design. So the symbols are drawn, from the same primitives as everything
 * else, which also means they inherit the palette, scale to any button size, and can be tinted
 * and animated like any other geometry.
 *
 * One drawing language throughout: a single stroke weight per icon, flat ends, a 1.9:1 ratio
 * between the outer and inner radius of anything concentric, and every shape fitted to the
 * box [-1,1] so two icons side by side read at the same visual size.
 */
object Icons {

    const val NONE = -1

    /** Add [SpellKind.id] (0..6). */
    const val SPELL = 0

    /** Add [DeployKind.id] (0..9). */
    const val DEPLOY = 16

    const val BRACE = 40
    const val PUMP = 41
    const val STORM = 42

    fun spell(kind: SpellKind): Int = SPELL + kind.id
    fun deploy(kind: DeployKind): Int = DEPLOY + kind.id

    // Scratch. Icons are drawn one at a time on the render thread, so one buffer does.
    private val p = FloatArray(96)
    private val c = Color()

    /**
     * Draw [icon] centred on (cx, cy), fitted to a box of half-extent [r].
     *
     * Stroke weight is derived from r so a 16 px chip and a 60 px button icon have the same
     * apparent weight.
     */
    fun draw(g: Painter, icon: Int, cx: Float, cy: Float, r: Float, color: Color) {
        if (icon < 0) return
        val w = maxOf(1.1f, r * 0.17f)
        c.set(color)
        when (icon) {
            SPELL + 0 -> gale(g, cx, cy, r, w)
            SPELL + 1 -> mist(g, cx, cy, r, w)
            SPELL + 2 -> voidWell(g, cx, cy, r, w)
            SPELL + 3 -> bolt(g, cx, cy, r, w)
            SPELL + 4 -> { mist(g, cx, cy, r, w * 0.85f); bolt(g, cx, cy, r * 0.72f, w) }
            SPELL + 5 -> singularity(g, cx, cy, r, w)
            SPELL + 6 -> wave(g, cx, cy, r, w, double = true)

            DEPLOY + 0 -> reef(g, cx, cy, r, w)
            DEPLOY + 1 -> mine(g, cx, cy, r, w)
            DEPLOY + 2 -> floe(g, cx, cy, r, w)
            DEPLOY + 3 -> spiral(g, cx, cy, r, w)
            DEPLOY + 4 -> tentacle(g, cx, cy, r, w)
            DEPLOY + 5 -> corsairs(g, cx, cy, r, w)
            DEPLOY + 6 -> wave(g, cx, cy, r, w, double = false)
            DEPLOY + 7 -> squall(g, cx, cy, r, w)
            DEPLOY + 8 -> snare(g, cx, cy, r, w)
            DEPLOY + 9 -> kraken(g, cx, cy, r, w)

            BRACE -> brace(g, cx, cy, r, w)
            PUMP -> pump(g, cx, cy, r, w)
            STORM -> squall(g, cx, cy, r, w)
        }
    }

    // -----------------------------------------------------------------------
    // Spells
    // -----------------------------------------------------------------------

    /** Three nested arcs turning the same way: hard air folding in on itself. */
    private fun gale(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        arc(g, cx, cy, r * 0.92f, 1.9f, 5.5f, w, 14)
        arc(g, cx, cy, r * 0.60f, 3.6f, 7.1f, w, 12)
        arc(g, cx, cy, r * 0.30f, 5.2f, 8.2f, w * 0.9f, 9)
    }

    /** Three drifting bands. The middle one is longest, so the shape has a waist. */
    private fun mist(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        band(g, cx, cy + r * 0.52f, r * 0.72f, r * 0.15f, w, 0f)
        band(g, cx, cy, r * 0.95f, r * 0.17f, w, 1.1f)
        band(g, cx, cy - r * 0.52f, r * 0.72f, r * 0.15f, w, 2.2f)
    }

    /** A rim falling into a solid core, with the gap between them marked by four teeth. */
    private fun voidWell(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        g.ringLine(cx, cy, r * 0.92f, w * 0.8f, 24, c)
        g.circle(cx, cy, r * 0.34f, 18, c)
        for (i in 0 until 4) {
            val a = MathX.PI * 0.25f + i * MathX.PI * 0.5f
            g.line(
                cx + cos(a) * r * 0.78f, cy + sin(a) * r * 0.78f,
                cx + cos(a) * r * 0.50f, cy + sin(a) * r * 0.50f, w, c
            )
        }
    }

    /** A struck zigzag. Two strokes of unequal length, so it leans. */
    private fun bolt(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        p[0] = 0.34f; p[1] = 0.95f
        p[2] = -0.34f; p[3] = 0.10f
        p[4] = 0.16f; p[5] = 0.02f
        p[6] = -0.30f; p[7] = -0.95f
        stroke(g, cx, cy, r, 4, w * 1.15f)
    }

    /** Six rays of two lengths around a solid point. */
    private fun singularity(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        for (i in 0 until 6) {
            val a = MathX.PI * 0.5f + i * MathX.TAU / 6f
            val len = if (i % 2 == 0) 0.98f else 0.62f
            g.line(
                cx + cos(a) * r * 0.22f, cy + sin(a) * r * 0.22f,
                cx + cos(a) * r * len, cy + sin(a) * r * len, w, c
            )
        }
        g.circle(cx, cy, r * 0.22f, 14, c)
    }

    /** A curling crest over a flat sea. [double] stacks a second, smaller crest behind. */
    private fun wave(g: Painter, cx: Float, cy: Float, r: Float, w: Float, double: Boolean) {
        // The face, rising left to right, then the lip folding back over itself.
        p[0] = -0.98f; p[1] = -0.34f
        p[2] = -0.40f; p[3] = -0.16f
        p[4] = 0.06f; p[5] = 0.34f
        p[6] = 0.40f; p[7] = 0.86f
        p[8] = 0.72f; p[9] = 0.72f
        p[10] = 0.66f; p[11] = 0.34f
        p[12] = 0.30f; p[13] = 0.40f
        stroke(g, cx, cy, r, 7, w)
        band(g, cx, cy - r * 0.72f, r * 0.92f, r * 0.13f, w * 0.85f, 1.6f)
        if (double) band(g, cx, cy - r * 0.98f, r * 0.62f, r * 0.10f, w * 0.75f, 3.1f)
    }

    // -----------------------------------------------------------------------
    // Deployables
    // -----------------------------------------------------------------------

    /** Three teeth on a seabed line, the middle one tallest. */
    private fun reef(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        val base = cy - r * 0.72f
        tooth(g, cx - r * 0.60f, base, r * 0.34f, r * 0.78f)
        tooth(g, cx + r * 0.02f, base, r * 0.44f, r * 1.42f)
        tooth(g, cx + r * 0.66f, base, r * 0.30f, r * 0.62f)
        g.line(cx - r * 0.98f, base, cx + r * 0.98f, base, w, c)
    }

    private fun tooth(g: Painter, x: Float, base: Float, halfW: Float, h: Float) {
        g.tri(x - halfW, base, c, x + halfW, base, c, x, base + h, c)
    }

    /** A shell with eight horns. */
    private fun mine(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        for (i in 0 until 8) {
            val a = i * MathX.TAU / 8f
            g.line(
                cx + cos(a) * r * 0.46f, cy + sin(a) * r * 0.46f,
                cx + cos(a) * r * 0.98f, cy + sin(a) * r * 0.98f, w * 0.9f, c
            )
        }
        g.circle(cx, cy, r * 0.52f, 18, c)
    }

    /** A slab seen at an angle, with one facet scored across it. */
    private fun floe(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        p[0] = -0.94f; p[1] = -0.18f
        p[2] = -0.46f; p[3] = -0.86f
        p[4] = 0.58f; p[5] = -0.74f
        p[6] = 0.94f; p[7] = 0.22f
        p[8] = 0.26f; p[9] = 0.88f
        p[10] = -0.62f; p[11] = 0.60f
        closedStroke(g, cx, cy, r, 6, w)
        g.line(cx - r * 0.46f, cy - r * 0.86f, cx + r * 0.08f, cy + r * 0.22f, w * 0.8f, c)
        g.line(cx + r * 0.08f, cy + r * 0.22f, cx + r * 0.94f, cy + r * 0.22f, w * 0.8f, c)
    }

    /** Two and a bit turns, tightening to a point. */
    private fun spiral(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        val turns = 2.25f
        val segs = 34
        var px = cx + r * 0.98f
        var py = cy
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val a = t * turns * MathX.TAU
            val rad = r * (0.98f - 0.90f * t)
            val x = cx + cos(a) * rad
            val y = cy + sin(a) * rad
            g.line(px, py, x, y, w * (1f - t * 0.45f), c)
            px = x; py = y
        }
    }

    /** A limb curling out of the bottom of the box, suckers on the inside of the curl. */
    private fun tentacle(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        val segs = 16
        var px = cx - r * 0.52f
        var py = cy - r * 0.98f
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            // A curl that opens out then hooks back: sampled, not hand-placed.
            val a = -1.2f + t * 4.6f
            val rad = r * (0.86f - 0.44f * t)
            val x = cx + cos(a) * rad * 0.9f
            val y = cy + sin(a) * rad + r * 0.10f
            g.line(px, py, x, y, w * (1.55f - t * 1.0f), c)
            if (i % 5 == 0) g.circle(x, y, w * 0.75f, 8, c)
            px = x; py = y
        }
    }

    /** Three chevrons in echelon: a flight seen head on. */
    private fun corsairs(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        chevron(g, cx + r * 0.34f, cy, r * 0.62f, w)
        chevron(g, cx - r * 0.46f, cy + r * 0.56f, r * 0.42f, w * 0.9f)
        chevron(g, cx - r * 0.46f, cy - r * 0.56f, r * 0.42f, w * 0.9f)
    }

    private fun chevron(g: Painter, x: Float, y: Float, size: Float, w: Float) {
        g.line(x - size * 0.7f, y + size, x + size * 0.8f, y, w, c)
        g.line(x + size * 0.8f, y, x - size * 0.7f, y - size, w, c)
    }

    /** A cloud body over three falling strokes. */
    private fun squall(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        val top = cy + r * 0.30f
        g.circle(cx - r * 0.46f, top, r * 0.34f, 14, c)
        g.circle(cx + r * 0.06f, top + r * 0.16f, r * 0.46f, 16, c)
        g.circle(cx + r * 0.58f, top, r * 0.32f, 14, c)
        g.rect(cx - r * 0.80f, top - r * 0.34f, r * 1.70f, r * 0.36f, c)
        for (i in 0 until 3) {
            val x = cx + (i - 1) * r * 0.52f
            g.line(x + r * 0.14f, cy - r * 0.24f, x - r * 0.06f, cy - r * 0.94f, w * 0.9f, c)
        }
    }

    /** A ring with three bands clamped across it. */
    private fun snare(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        g.ringLine(cx, cy, r * 0.80f, w * 0.85f, 22, c)
        for (i in 0 until 3) {
            val a = MathX.PI * 0.5f + i * MathX.TAU / 3f
            val nx = cos(a)
            val ny = sin(a)
            // A short band across the rim, perpendicular to the radius.
            g.line(
                cx + nx * r * 0.80f - ny * r * 0.34f, cy + ny * r * 0.80f + nx * r * 0.34f,
                cx + nx * r * 0.80f + ny * r * 0.34f, cy + ny * r * 0.80f - nx * r * 0.34f,
                w * 1.5f, c
            )
        }
    }

    /** An eye above three limbs. It only has to read at 30 px. */
    private fun kraken(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        val ey = cy + r * 0.30f
        // Lens: two arcs meeting at the corners.
        arcBetween(g, cx - r * 0.90f, ey, cx + r * 0.90f, ey, r * 0.58f, w)
        arcBetween(g, cx + r * 0.90f, ey, cx - r * 0.90f, ey, r * 0.58f, w)
        g.circle(cx, ey, r * 0.26f, 12, c)
        for (i in 0 until 3) {
            val x = cx + (i - 1) * r * 0.62f
            val sway = if (i == 1) 0f else (if (i == 0) -1f else 1f) * r * 0.24f
            g.line(x, ey - r * 0.52f, x + sway * 0.4f, cy - r * 0.52f, w * 0.95f, c)
            g.line(x + sway * 0.4f, cy - r * 0.52f, x - sway * 0.5f, cy - r * 0.98f, w * 0.8f, c)
        }
    }

    // -----------------------------------------------------------------------
    // Modifiers
    // -----------------------------------------------------------------------

    /** A shield, with the chevron of a braced stance inside it. */
    private fun brace(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        p[0] = -0.82f; p[1] = 0.52f
        p[2] = 0f; p[3] = 0.96f
        p[4] = 0.82f; p[5] = 0.52f
        p[6] = 0.82f; p[7] = -0.14f
        p[8] = 0f; p[9] = -0.96f
        p[10] = -0.82f; p[11] = -0.14f
        closedStroke(g, cx, cy, r, 6, w)
        g.line(cx - r * 0.40f, cy + r * 0.10f, cx, cy - r * 0.28f, w, c)
        g.line(cx, cy - r * 0.28f, cx + r * 0.40f, cy + r * 0.10f, w, c)
    }

    /** Water leaving: three bars narrowing upward, with an arrow through them. */
    private fun pump(g: Painter, cx: Float, cy: Float, r: Float, w: Float) {
        for (i in 0 until 3) {
            val y = cy - r * 0.72f + i * r * 0.52f
            val half = r * (0.86f - i * 0.20f)
            g.rect(cx - half, y - w * 0.55f, half * 2f, w * 1.1f, c)
        }
        g.line(cx, cy + r * 0.28f, cx, cy + r * 0.96f, w, c)
        g.line(cx - r * 0.30f, cy + r * 0.62f, cx, cy + r * 0.96f, w, c)
        g.line(cx + r * 0.30f, cy + r * 0.62f, cx, cy + r * 0.96f, w, c)
    }

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    /** Polyline through [n] points held in [p] as normalised -1..1 pairs. */
    private fun stroke(g: Painter, cx: Float, cy: Float, r: Float, n: Int, w: Float) {
        for (i in 0 until n - 1) {
            g.line(
                cx + p[i * 2] * r, cy + p[i * 2 + 1] * r,
                cx + p[i * 2 + 2] * r, cy + p[i * 2 + 3] * r, w, c
            )
        }
    }

    private fun closedStroke(g: Painter, cx: Float, cy: Float, r: Float, n: Int, w: Float) {
        for (i in 0 until n) {
            val j = (i + 1) % n
            g.line(
                cx + p[i * 2] * r, cy + p[i * 2 + 1] * r,
                cx + p[j * 2] * r, cy + p[j * 2 + 1] * r, w, c
            )
        }
    }

    private fun arc(
        g: Painter, cx: Float, cy: Float, r: Float,
        a0: Float, a1: Float, w: Float, segs: Int
    ) {
        var px = cx + cos(a0) * r
        var py = cy + sin(a0) * r
        for (i in 1..segs) {
            val a = a0 + (a1 - a0) * i / segs
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            g.line(px, py, x, y, w, c)
            px = x; py = y
        }
    }

    /** A circular arc bulging [bulge] to the left of the line from A to B. */
    private fun arcBetween(
        g: Painter, ax: Float, ay: Float, bx: Float, by: Float, bulge: Float, w: Float
    ) {
        val segs = 10
        var px = ax
        var py = ay
        val dx = bx - ax
        val dy = by - ay
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val hump = sin(t * MathX.PI) * bulge
            val x = ax + dx * t - dy * 0f + (-dy) * 0f
            // Perpendicular offset, normalised by the chord length.
            val len = MathX.len(dx, dy)
            val nx = if (len > 1e-4f) -dy / len else 0f
            val ny = if (len > 1e-4f) dx / len else 0f
            val fx = x + nx * hump
            val fy = ay + dy * t + ny * hump
            g.line(px, py, fx, fy, w, c)
            px = fx; py = fy
        }
    }

    /** A soft horizontal band: a drifting line of fog or water. */
    private fun band(
        g: Painter, cx: Float, cy: Float, halfW: Float, amp: Float, w: Float, phase: Float
    ) {
        val segs = 10
        var px = cx - halfW
        var py = cy + sin(phase) * amp
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val x = cx - halfW + halfW * 2f * t
            val y = cy + sin(phase + t * MathX.PI * 2.1f) * amp
            g.line(px, py, x, y, w, c)
            px = x; py = y
        }
    }
}
