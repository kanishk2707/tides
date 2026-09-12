package com.mythron.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.mythron.aethertides.shared.math.MathX
import com.mythron.aethertides.shared.math.Noise
import com.mythron.aethertides.shared.net.SnapshotFrame
import com.mythron.aethertides.shared.sim.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ship.
 *
 * Drawn entirely from the hull's own physics state: pitch comes from the solver, the sail
 * billows toward the real apparent wind, the crew lean where the player is putting the weight,
 * and the water inside the hull rises with the actual bilge tonnage. There is no animation
 * system and no keyframes -- if it moves on screen, it is because something in the simulation
 * moved.
 *
 * The waterline is the trick that sells it: each slice of the hull is tested against the real
 * surface and tinted toward the sea's own colour where it is under water, so she is visibly
 * *in* the sea rather than sitting on a line drawn near it.
 */
class ShipRenderer(private val art: Art) {

    // Hull outline in local metres. Origin is amidships at mid-depth; +x is the bow, +y is up.
    private val hullLocal = floatArrayOf(
        -7.4f, 1.55f,      // transom, top
        -5.6f, 1.28f,
        -1.5f, 1.10f,
        3.2f, 1.24f,
        6.4f, 1.70f,
        8.1f, 2.25f,       // stem head
        7.5f, 0.55f,
        6.2f, -0.75f,      // forefoot
        2.6f, -1.32f,
        -2.4f, -1.38f,
        -5.8f, -1.05f,
        -7.2f, 0.05f
    )
    private val hullWorld = FloatArray(hullLocal.size)

    private val deckLocal = floatArrayOf(
        -7.2f, 1.50f, -5.6f, 1.24f, -1.5f, 1.06f, 3.2f, 1.20f, 6.3f, 1.64f,
        6.3f, 1.30f, 3.2f, 0.86f, -1.5f, 0.72f, -5.6f, 0.90f, -7.2f, 1.16f
    )
    private val deckWorld = FloatArray(deckLocal.size)

    private val sailPts = FloatArray(18 * 2)

    private val c = Color()
    private val c2 = Color()
    private val sliceColor = Color()
    private val waterTint = Color()

    private var wakeTimer = 0f
    private var time = 0f

    // Transform state for the current draw call.
    private var px = 0f
    private var py = 0f
    private var ca = 1f
    private var sa = 0f

    private fun setPose(x: Float, y: Float, angle: Float) {
        px = x; py = y; ca = cos(angle); sa = sin(angle)
    }

    private fun wx(lx: Float, ly: Float) = px + lx * ca - ly * sa
    private fun wy(lx: Float, ly: Float) = py + lx * sa + ly * ca

    private fun transform(local: FloatArray, out: FloatArray) {
        var i = 0
        while (i < local.size) {
            out[i] = wx(local[i], local[i + 1])
            out[i + 1] = wy(local[i], local[i + 1])
            i += 2
        }
    }

    // -----------------------------------------------------------------------

    fun update(dt: Float, f: SnapshotFrame, fx: Fx, sea: SeaRenderer) {
        time += dt
        wakeTimer += dt

        val speed = abs(f.shipVx)
        // Wake and bow wave, rate tied to how hard she is actually being driven.
        val interval = MathX.lerp(0.10f, 0.018f, MathX.clamp01(speed / 22f))
        if (wakeTimer >= interval && !f.airborne) {
            wakeTimer = 0f
            setPose(f.shipX, f.shipY, f.shipAngle)
            val bowX = wx(7.6f, 0f)
            val sternX = wx(-7.2f, 0f)
            val bowSurface = sea.surfaceYAt(bowX)
            val sternSurface = sea.surfaceYAt(sternX)

            if (speed > 2.5f) {
                // Bow wave: water pushed up and forward off the stem.
                fx.spray(bowX, bowSurface + 0.2f, 0.85f, 0.45f, 1.2f + speed * 0.42f,
                    if (speed > 12f) 2 else 1)
                fx.foam(bowX - 1.2f, bowSurface, -speed * 0.15f, 1, 0.34f)
            }
            // Wake astern, wider and whiter the faster she goes.
            fx.foam(sternX - 1f, sternSurface, -1.5f, 1, 0.3f + speed * 0.022f)
            if (speed > 9f) fx.spray(sternX, sternSurface, -0.5f, 0.7f, speed * 0.22f, 1)
        }

        // Water streaming off a hull that has just been buried.
        if (f.submerged > 0.55f && speed > 6f && wakeTimer < dt * 2f) {
            setPose(f.shipX, f.shipY, f.shipAngle)
            fx.spray(wx(2f, 1.2f), wy(2f, 1.2f), 0.2f, 1f, 4f, 1)
        }
    }

    fun draw(g: Painter, f: SnapshotFrame, sea: SeaRenderer, ambient: Float, seaState: Float) {
        setPose(f.shipX, f.shipY, f.shipAngle)
        transform(hullLocal, hullWorld)
        transform(deckLocal, deckWorld)

        val light = MathX.clamp(ambient, 0.3f, 1.3f)
        val damage = 1f - MathX.clamp01(f.hull / Config.MAX_HULL)

        drawRigging(g, f, light)
        drawHull(g, f, sea, light, damage, seaState)
        drawDeckFurniture(g, f, light)
        drawCrew(g, f, light)
        drawSail(g, f, light, seaState)
        drawWaterline(g, f, sea, ambient)
        drawDamage(g, f, damage)
    }

    // -----------------------------------------------------------------------

    private fun drawHull(
        g: Painter, f: SnapshotFrame, sea: SeaRenderer, light: Float,
        damage: Float, seaState: Float
    ) {
        // Body, shaded darker toward the keel.
        //
        // Each fan slice is also tested against the real water surface, and a slice whose
        // centroid is under water is shifted toward the sea's own colour. That way the covered
        // part of the hull is seen *through* the water, and she is genuinely half-buried when
        // a crest rolls across the deck. Overlaying a translucent rectangle instead -- the
        // obvious first attempt -- reads as a coloured box floating around the boat, because
        // that is exactly what it is.
        val storm = MathX.smoothstep(0.2f, 0.85f, seaState)
        waterTint.set(Palette.shallowCalm).lerp(Palette.shallowStorm, storm)
        waterTint.r *= light * 0.9f; waterTint.g *= light * 0.9f; waterTint.b *= light * 0.9f
        waterTint.a = 1f

        tint(Palette.hullMid, light, c)
        tint(Palette.hullDark, light * 0.75f, c2)
        val n = hullLocal.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            val yA = hullLocal[i * 2 + 1]
            val yB = hullLocal[j * 2 + 1]
            val shade = MathX.clamp01(((yA + yB) * 0.5f + 1.4f) / 3.6f)
            sliceColor.set(Palette.mix(c2, c, shade))

            val mx = (hullWorld[i * 2] + hullWorld[j * 2] + px) / 3f
            val my = (hullWorld[i * 2 + 1] + hullWorld[j * 2 + 1] + py) / 3f
            val submersion = sea.surfaceYAt(mx) - my
            if (submersion > 0f) {
                // Deeper slices have more water in front of them.
                sliceColor.lerp(waterTint, MathX.clamp01(0.20f + submersion * 0.16f))
            }

            g.tri(
                wx(0f, 0.1f), wy(0f, 0.1f), sliceColor,
                hullWorld[i * 2], hullWorld[i * 2 + 1], sliceColor,
                hullWorld[j * 2], hullWorld[j * 2 + 1], sliceColor
            )
        }

        // The rubbing strake: one bright line along the sheer is what makes a flat shape read
        // as a hull rather than a blob.
        tint(Palette.hullTrim, light, c)
        g.line(wx(-7.3f, 1.52f), wy(-7.3f, 1.52f), wx(-1.5f, 1.08f), wy(-1.5f, 1.08f), 0.20f, c)
        g.line(wx(-1.5f, 1.08f), wy(-1.5f, 1.08f), wx(3.2f, 1.22f), wy(3.2f, 1.22f), 0.20f, c)
        g.line(wx(3.2f, 1.22f), wy(3.2f, 1.22f), wx(8.0f, 2.20f), wy(8.0f, 2.20f), 0.20f, c)

        // Deck, seen edge-on as a lighter band.
        tint(Palette.deckPlank, light * 1.05f, c)
        g.fan(wx(0f, 1f), wy(0f, 1f), deckWorld, deckLocal.size / 2, c)

        // Planking seams.
        tint(Palette.hullDark, light * 0.9f, c)
        c.a = 0.5f
        for (k in 0 until 4) {
            val y = 0.9f - k * 0.62f
            g.line(wx(-6.9f, y), wy(-6.9f, y), wx(6.4f, y + 0.25f), wy(6.4f, y + 0.25f), 0.055f, c)
        }
        c.a = 1f

        // Transom and rudder.
        tint(Palette.hullLight, light, c)
        g.line(wx(-7.3f, 1.5f), wy(-7.3f, 1.5f), wx(-7.1f, 0.1f), wy(-7.1f, 0.1f), 0.3f, c)
        tint(Palette.mastWood, light, c)
        g.line(wx(-7.0f, 0.2f), wy(-7.0f, 0.2f), wx(-7.6f, -1.5f), wy(-7.6f, -1.5f), 0.28f, c)

        // Bowsprit.
        tint(Palette.mastWood, light, c)
        g.line(wx(7.6f, 2.0f), wy(7.6f, 2.0f), wx(11.4f, 2.9f), wy(11.4f, 2.9f), 0.22f, c)
    }

    private fun drawDeckFurniture(g: Painter, f: SnapshotFrame, light: Float) {
        // Cabin.
        tint(Palette.hullLight, light * 0.95f, c)
        g.quad(
            wx(-5.4f, 1.2f), wy(-5.4f, 1.2f), wx(-2.2f, 1.15f), wy(-2.2f, 1.15f),
            wx(-2.2f, 2.5f), wy(-2.2f, 2.5f), wx(-5.4f, 2.55f), wy(-5.4f, 2.55f),
            c, c, c, c
        )
        tint(Palette.hullDark, light, c)
        g.line(wx(-5.4f, 2.52f), wy(-5.4f, 2.52f), wx(-2.2f, 2.47f), wy(-2.2f, 2.47f), 0.18f, c)
        // Cabin window, lit from inside.
        g.additive(true)
        c.set(Palette.sunGlow); c.a = 0.55f
        g.glow(wx(-3.8f, 1.85f), wy(-3.8f, 1.85f), 0.75f, c)
        g.additive(false)

        // Stern lantern -- the warm point of light that anchors the whole silhouette in a storm.
        val flicker = 0.78f + 0.22f * Noise.value(time * 6f, 0f, 3)
        g.additive(true)
        c.set(Palette.sunCore); c.a = 0.9f * flicker
        g.glow(wx(-7.0f, 2.4f), wy(-7.0f, 2.4f), 1.9f, c)
        c.a = 0.35f * flicker
        g.glow(wx(-7.0f, 2.4f), wy(-7.0f, 2.4f), 5.5f, c)
        g.additive(false)
    }

    private fun drawRigging(g: Painter, f: SnapshotFrame, light: Float) {
        val broken = f.mast <= 1f
        val mastTop = if (broken) 4.2f else Config.MAST_HEIGHT
        tint(Palette.rigging, light, c)
        // Forestay and backstay.
        g.line(wx(0.6f, mastTop), wy(0.6f, mastTop), wx(11.2f, 2.85f), wy(11.2f, 2.85f), 0.065f, c)
        g.line(wx(0.6f, mastTop), wy(0.6f, mastTop), wx(-7.1f, 1.6f), wy(-7.1f, 1.6f), 0.065f, c)
        // Shrouds.
        g.line(wx(0.6f, mastTop * 0.72f), wy(0.6f, mastTop * 0.72f), wx(-3.6f, 1.2f), wy(-3.6f, 1.2f), 0.05f, c)
        g.line(wx(0.6f, mastTop * 0.72f), wy(0.6f, mastTop * 0.72f), wx(4.4f, 1.3f), wy(4.4f, 1.3f), 0.05f, c)
    }

    private fun drawSail(g: Painter, f: SnapshotFrame, light: Float, seaState: Float) {
        val broken = f.mast <= 1f
        val mastTop = if (broken) 4.2f else Config.MAST_HEIGHT

        // The mast.
        tint(Palette.mastWood, light, c)
        g.line(wx(0.6f, 0.9f), wy(0.6f, 0.9f), wx(0.6f, mastTop), wy(0.6f, mastTop), 0.36f, c)
        if (broken) {
            // Snapped: the upper spar hangs over the side in the water.
            tint(Palette.mastWood, light * 0.8f, c)
            g.line(wx(0.6f, 4.0f), wy(0.6f, 4.0f), wx(-6.5f, -0.4f), wy(-6.5f, -0.4f), 0.3f, c)
            tint(Palette.sailShade, light * 0.7f, c)
            c.a = 0.85f
            g.line(wx(-1.5f, 2.2f), wy(-1.5f, 2.2f), wx(-6.2f, -0.2f), wy(-6.2f, -0.2f), 1.1f, c)
            c.a = 1f
            return
        }

        val trim = MathX.clamp01(f.trim)
        if (trim < 0.03f) {
            // Furled: a tight bundle along the boom.
            tint(Palette.sailShade, light, c)
            g.line(wx(0.6f, 1.9f), wy(0.6f, 1.9f), wx(-5.6f, 1.6f), wy(-5.6f, 1.6f), 0.55f, c)
            return
        }

        // The sail is a gaff mainsail: luff on the mast, foot along the boom, and a leech that
        // bows out to leeward in proportion to how hard the wind is actually pressing on it.
        val press = MathX.clamp01(f.apparentWind / 22f) * trim
        val belly = 0.55f + 2.9f * press
        val luffTop = 1.9f + (mastTop - 2.4f) * trim
        val boomAft = -5.8f * trim - 0.4f

        var n = 0
        // Up the luff.
        sailPts[n++] = wx(0.62f, 1.9f); sailPts[n++] = wy(0.62f, 1.9f)
        sailPts[n++] = wx(0.62f, luffTop); sailPts[n++] = wy(0.62f, luffTop)
        // Out along the head, then down the curved leech to the clew.
        val segs = 7
        for (i in 0..segs) {
            val t = i / segs.toFloat()
            val lx = MathX.lerp(0.62f, boomAft, t)
            val ly = MathX.lerp(luffTop, 1.85f, t)
            // Bow the leech away from the mast; the deepest part of the curve sits at a third
            // of the way down, as a real sail does.
            val bow = kotlin.math.sin(t * MathX.PI) * belly * (0.6f + 0.4f * (1f - t))
            val flutter = Noise.signed(t * 3f, time * 5.5f, 61) * (0.16f + 0.3f * (1f - press))
            sailPts[n++] = wx(lx - bow * 0.18f, ly + bow + flutter)
            sailPts[n++] = wy(lx - bow * 0.18f, ly + bow + flutter)
        }

        val count = n / 2
        // Sail cloth, shaded by how much it is turned away from the light.
        tint(Palette.sailCloth, light * (0.86f + 0.14f * press), c)
        g.fan(wx(0.62f, 2.2f), wy(0.62f, 2.2f), sailPts, count, c)
        // Seam panels.
        tint(Palette.sailShade, light, c2)
        c2.a = 0.55f
        for (i in 2 until count - 1) {
            g.line(
                wx(0.62f, 1.95f), wy(0.62f, 1.95f),
                sailPts[i * 2], sailPts[i * 2 + 1], 0.05f, c2
            )
        }
        c2.a = 1f
        // Boom.
        tint(Palette.mastWood, light, c)
        g.line(wx(0.62f, 1.85f), wy(0.62f, 1.85f), wx(boomAft, 1.85f), wy(boomAft, 1.85f), 0.20f, c)

        // Masthead pennant, streaming with the true wind. Free, and it tells the player which
        // way the air is going before the sail does.
        val windSign = if (f.windDir >= 0f) 1f else -1f
        val flag = 2.4f * windSign
        val wave = Noise.signed(time * 4f, 0f, 5) * 0.35f
        tint(Palette.accent, light, c)
        g.tri(
            wx(0.62f, mastTop), wy(0.62f, mastTop), c,
            wx(0.62f + flag, mastTop - 0.35f + wave), wy(0.62f + flag, mastTop - 0.35f + wave), c,
            wx(0.62f, mastTop - 0.95f), wy(0.62f, mastTop - 0.95f), c
        )
    }

    private fun drawCrew(g: Painter, f: SnapshotFrame, light: Float) {
        // Two hands on deck. They lean where the player is putting the weight, which makes an
        // invisible input mechanic visible.
        val lean = MathX.clamp(f.lean, -1f, 1f)
        tint(Palette.ink, light * 1.4f, c)
        for (k in 0 until 2) {
            val baseX = if (k == 0) -1.2f else 2.6f
            val bx = baseX + lean * 1.3f
            val by = 1.15f
            val tiltX = lean * 0.55f
            // Legs, body, head.
            g.line(wx(bx, by), wy(bx, by), wx(bx + tiltX * 0.4f, by + 0.55f), wy(bx + tiltX * 0.4f, by + 0.55f), 0.17f, c)
            g.line(
                wx(bx + tiltX * 0.4f, by + 0.55f), wy(bx + tiltX * 0.4f, by + 0.55f),
                wx(bx + tiltX, by + 1.25f), wy(bx + tiltX, by + 1.25f), 0.22f, c
            )
            g.circle(wx(bx + tiltX * 1.15f, by + 1.45f), wy(bx + tiltX * 1.15f, by + 1.45f), 0.2f, 7, c)
        }
    }

    /**
     * Where the hull meets the water.
     *
     * The submerged part of the hull is tinted slice by slice in [drawHull], against the real
     * surface curve, so all that is left here is the foam collar she cuts and the water
     * standing inside her. An overlay quad wider or deeper than the boat reads as a coloured
     * box floating in the sea -- which is exactly what it looked like when it was one.
     */
    private fun drawWaterline(g: Painter, f: SnapshotFrame, sea: SeaRenderer, ambient: Float) {
        val left = minOf(wx(-7.6f, 0f), wx(8.4f, 0f)) - 0.4f
        val right = maxOf(wx(-7.6f, 0f), wx(8.4f, 0f)) + 0.4f
        val steps = 12
        val step = (right - left) / steps

        // Foam collar, following the true surface across the length of the hull.
        c.set(Palette.foam)
        c.a = 0.5f * MathX.clamp01(ambient)
        var ax = left
        var ay = sea.surfaceYAt(ax)
        for (i in 1..steps) {
            val x = left + step * i
            val y = sea.surfaceYAt(x)
            g.line(ax, ay, x, y, 0.16f, c)
            ax = x; ay = y
        }

        // Water standing inside her, rising with the real bilge tonnage.
        val flood = MathX.clamp01(f.bilge / Config.MAX_BILGE)
        if (flood > 0.04f) {
            c.set(Palette.deepStorm)
            c.a = 0.85f
            val level = MathX.lerp(-1.1f, 1.0f, flood)
            g.quad(
                wx(-6.6f, -1.2f), wy(-6.6f, -1.2f), wx(5.6f, -1.2f), wy(5.6f, -1.2f),
                wx(5.6f, level), wy(5.6f, level), wx(-6.6f, level), wy(-6.6f, level),
                c, c, c, c
            )
            c.set(Palette.foamShadow); c.a = 0.5f
            g.line(wx(-6.6f, level), wy(-6.6f, level), wx(5.6f, level), wy(5.6f, level), 0.1f, c)
        }
    }

    private fun drawDamage(g: Painter, f: SnapshotFrame, damage: Float) {
        if (damage < 0.2f) return
        // Scorched planking and sprung seams, appearing as she is worn down.
        c.set(Palette.ink)
        c.a = MathX.clamp01((damage - 0.2f) * 1.2f)
        val holes = (damage * 5f).toInt()
        for (k in 0 until holes) {
            val hx = -5.5f + k * 2.6f
            val hy = 0.1f + ((k * 37) % 7) * 0.16f
            g.circle(wx(hx, hy), wy(hx, hy), 0.28f + 0.12f * (k % 3), 8, c)
        }
        // Smoke from a badly hurt hull.
        if (damage > 0.62f) {
            g.additive(false)
            c.set(Palette.ink); c.a = 0.28f
            val t = time * 1.4f
            for (k in 0 until 3) {
                val o = k * 2.1f
                g.puff(
                    wx(-2f + k * 2.4f, 2f) + sin(t + o) * 0.8f,
                    wy(-2f + k * 2.4f, 2f) + (t * 1.6f + o * 3f) % 6f,
                    0.9f + ((t + o) % 3f) * 0.5f, c
                )
            }
        }
    }

    private fun tint(src: Color, k: Float, out: Color) {
        out.set(src)
        out.r *= k; out.g *= k; out.b *= k
        out.a = src.a
    }
}
