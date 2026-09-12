package com.polariz.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Noise
import com.polariz.aethertides.shared.math.Rng
import com.polariz.aethertides.shared.ocean.Ocean
import com.polariz.aethertides.shared.ocean.WaveSample
import com.polariz.aethertides.shared.sim.Config
import kotlin.math.abs

/**
 * The sea.
 *
 * The surface is sampled on the CPU across the visible span and emitted as a coloured triangle
 * strip four rows deep. That is what buys the look: every vertex carries its own colour, so a
 * wave can be dark and cold in the trough, lit and translucent where the light comes through
 * the back of the crest, and white where it is breaking -- all in one continuous surface, with
 * no texture and no shader.
 *
 * The one rule enforced everywhere here: this renderer samples the *same* [Ocean] the physics
 * used. The hull is never pasted onto a decorative wave; it is floating on this one.
 */
class SeaRenderer(private val art: Art, seed: Long) {

    companion object {
        /** Surface samples across the screen. More is smoother; this is plenty at 1080p. */
        const val COLUMNS = 150

        /** Depth of the translucent, backlit band under the surface, metres. */
        const val LIT_BAND = 2.4f

        /**
         * Depth at which the water has gone fully dark, metres.
         *
         * Real seawater loses most visible light inside ten metres or so. Keeping this larger
         * than the visible column meant the whole under-surface stayed bright, and the sea
         * read as a lit pool with no depth to it at all.
         */
        const val DARK_DEPTH = 11.5f
    }

    private val xs = FloatArray(COLUMNS + 1)
    private val ys = FloatArray(COLUMNS + 1)
    private val slopes = FloatArray(COLUMNS + 1)
    private val crests = FloatArray(COLUMNS + 1)

    private val ws = WaveSample()
    private val rng = Rng(seed xor 0x5EAL)

    private val cSurface = Color()
    private val cLit = Color()
    private val cMid = Color()
    private val cDeep = Color()
    private val cTmp = Color()

    private var foamTimer = 0f
    private var time = 0f

    // -----------------------------------------------------------------------

    /** Sample the visible surface once per frame; every pass below reuses it. */
    fun sample(cam: Cam, ocean: Ocean) {
        val left = cam.left - 6f
        val span = cam.viewWidth + 12f
        val step = span / COLUMNS
        for (i in 0..COLUMNS) {
            val x = left + step * i
            ocean.sample(x, ws)
            xs[i] = x
            ys[i] = ws.y
            slopes[i] = ws.dydx
            crests[i] = ws.crest
        }
    }

    fun surfaceYAt(x: Float): Float {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[COLUMNS]) return ys[COLUMNS]
        val step = xs[1] - xs[0]
        val f = (x - xs[0]) / step
        val i = MathX.clampI(f.toInt(), 0, COLUMNS - 1)
        return MathX.lerp(ys[i], ys[i + 1], f - i)
    }

    // -----------------------------------------------------------------------
    // Backdrop: distant swell, drawn before anything else in the water
    // -----------------------------------------------------------------------

    fun drawBackdrop(g: Painter, cam: Cam, ocean: Ocean, seaState: Float, ambient: Float) {
        val storm = MathX.smoothstep(0.2f, 0.85f, seaState)
        // Distant water meets the sky at the camera height. Everything here is a thin band
        // just beneath that line: at this range an individual wave is under a pixel, so what
        // reads is tone and texture rather than shape.
        val horizon = cam.camera.position.y
        val band = cam.viewHeight * 0.10f

        for (layer in 2 downTo 0) {
            val amp = cam.viewHeight * MathX.lerp(0.016f, 0.005f, layer / 2f)
            val yOff = horizon - band * (layer * 0.22f)
            val scale = 0.5f - layer * 0.12f

            cTmp.set(Palette.deepCalm).lerp(Palette.deepStorm, storm)
            // Distance washes colour toward the sky. Plain aerial perspective, and it is what
            // stops the far water from looking like a second, closer ocean.
            cTmp.lerp(Palette.skyLowCalm, 0.26f + layer * 0.11f)
            cTmp.mul(ambient * 0.92f, ambient * 0.92f, ambient, 1f)
            cTmp.a = 1f

            val cols = 52
            val step = (cam.viewWidth + 12f) / cols
            val floor = horizon - band
            var px = cam.left - 6f
            var py = yOff + ocean.heightAtApprox(px * scale + layer * 137f, time) * amp
            for (i in 1..cols) {
                val x = cam.left - 6f + step * i
                val y = yOff + ocean.heightAtApprox(x * scale + layer * 137f, time) * amp
                g.quad(px, py, x, y, x, floor, px, floor, cTmp, cTmp, cTmp, cTmp)
                px = x; py = y
            }
        }

        // Between the far band and the near sea is open water seen almost edge-on. It has to
        // *arrive* at the colour the near surface is actually painted, or the join between them
        // shows up as a hard horizontal step across the screen -- which is exactly what it did.
        cTmp.set(Palette.deepCalm).lerp(Palette.deepStorm, storm)
        cTmp.lerp(Palette.skyLowCalm, 0.24f)
        cTmp.mul(ambient * 0.85f, ambient * 0.85f, ambient * 0.92f, 1f)
        cTmp.a = 1f
        cSurface.set(Palette.shallowCalm).lerp(Palette.shallowStorm, storm)
        cSurface.mul(ambient * 0.9f, ambient * 0.9f, ambient * 0.95f, 1f)
        cSurface.a = 1f
        val floor = horizon - band
        g.rectV(cam.left - 6f, cam.bottom - 4f, cam.viewWidth + 12f,
            floor - (cam.bottom - 4f), cSurface, cTmp)
    }

    // -----------------------------------------------------------------------
    // The water body and its surface
    // -----------------------------------------------------------------------

    // Per-column colour rows, preallocated. The body pass runs 150 columns every frame, so
    // it must not allocate a single object.
    private val rowSurface = Array(COLUMNS + 1) { Color() }
    private val rowLit = Array(COLUMNS + 1) { Color() }
    private val rowMid = Array(COLUMNS + 1) { Color() }
    private val rowDeep = Array(COLUMNS + 1) { Color() }

    fun drawBody(g: Painter, cam: Cam, seaState: Float, ambient: Float, sky: SkyRenderer) {
        val storm = MathX.smoothstep(0.2f, 0.85f, seaState)
        val bottom = cam.bottom - 4f

        cDeep.set(Palette.deepCalm).lerp(Palette.deepStorm, storm)
        cMid.set(Palette.shallowCalm).lerp(Palette.shallowStorm, storm)
        cLit.set(Palette.translucentCalm).lerp(Palette.translucentStorm, storm)

        // 1. Resolve every column's four colours first.
        for (i in 0..COLUMNS) {
            val shadow = sky.cloudShadow(xs[i])
            surfaceColor(i, storm, ambient * shadow, rowSurface[i])
            // Light only gets through where the water is standing up and therefore thin: the
            // back of a crest. In a trough, and in flat water, this band is just body colour.
            // Making it unconditionally translucent is what turned calm sea into a swimming pool.
            val translucency = MathX.clamp01(
                MathX.clamp01(ys[i] / 2.6f) * 0.7f + MathX.clamp01(abs(slopes[i]) * 1.6f) * 0.6f
            )
            cSurface.set(cMid).lerp(cLit, translucency)
            shadeInto(cSurface, ambient * shadow, rowLit[i])
            shadeInto(cMid, ambient * 0.62f * shadow, rowMid[i])
            shadeInto(cDeep, ambient * 0.34f, rowDeep[i])
        }

        // 2. Emit the strip, four rows deep.
        for (i in 0 until COLUMNS) {
            val x0 = xs[i]; val x1 = xs[i + 1]
            val y0 = ys[i]; val y1 = ys[i + 1]

            // The lit, translucent band immediately under the surface.
            g.quad(
                x0, y0, x1, y1,
                x1, y1 - LIT_BAND, x0, y0 - LIT_BAND,
                rowSurface[i], rowSurface[i + 1], rowLit[i + 1], rowLit[i]
            )

            // The body, falling away to cold.
            val midY0 = y0 - DARK_DEPTH * 0.34f
            val midY1 = y1 - DARK_DEPTH * 0.34f
            g.quad(
                x0, y0 - LIT_BAND, x1, y1 - LIT_BAND,
                x1, midY1, x0, midY0,
                rowLit[i], rowLit[i + 1], rowMid[i + 1], rowMid[i]
            )

            // Down into the dark.
            val deepY0 = maxOf(bottom, y0 - DARK_DEPTH)
            val deepY1 = maxOf(bottom, y1 - DARK_DEPTH)
            g.quad(
                x0, midY0, x1, midY1,
                x1, deepY1, x0, deepY0,
                rowMid[i], rowMid[i + 1], rowDeep[i + 1], rowDeep[i]
            )

            // And flat black from there to the bottom of the frame.
            if (deepY0 > bottom || deepY1 > bottom) {
                g.quad(
                    x0, deepY0, x1, deepY1, x1, bottom, x0, bottom,
                    rowDeep[i], rowDeep[i + 1], Palette.abyss, Palette.abyss
                )
            }
        }
    }

    /**
     * Surface colour for one column.
     *
     * Three things decide it: how high this piece of water is standing (high water is lit from
     * behind and goes translucent), how steep it is (steep faces catch the sky), and how close
     * it is to breaking (which turns it white).
     */
    private fun surfaceColor(i: Int, storm: Float, light: Float, out: Color) {
        val y = ys[i]
        val steep = abs(slopes[i])
        val crest = crests[i]

        val lift = MathX.clamp01(y / 3.4f) * 0.5f + MathX.clamp01(steep * 1.4f) * 0.5f
        out.set(cMid).lerp(cLit, MathX.clamp01(lift))
        // A rising face catches the sky; a falling one is in its own shadow.
        val facing = MathX.clamp01(slopes[i] * 1.1f + 0.5f)
        out.lerp(Palette.skyLowCalm, 0.10f + 0.16f * facing * (1f - storm))
        out.lerp(Palette.foam, MathX.clamp01(crest * 0.82f))
        out.r *= light; out.g *= light; out.b *= light
        out.a = 1f
    }

    private fun shadeInto(c: Color, k: Float, out: Color) {
        out.set(c)
        out.r *= k; out.g *= k; out.b *= k
        out.a = 1f
    }

    private val shadeTmp = Color()

    private fun shade(c: Color, k: Float): Color {
        shadeTmp.set(c)
        shadeTmp.r *= k
        shadeTmp.g *= k
        shadeTmp.b *= k
        shadeTmp.a = c.a
        return shadeTmp
    }

    /**
     * The waterline itself: a bright, variable-thickness line of foam that thickens wherever
     * the wave is breaking. This is the single highest-value detail in the whole sea.
     */
    fun drawSurfaceDetail(g: Painter, cam: Cam, ambient: Float) {
        // Pass 1: the crisp waterline.
        for (i in 0 until COLUMNS) {
            val crest = (crests[i] + crests[i + 1]) * 0.5f
            cTmp.set(Palette.foam)
            cTmp.a = (0.16f + 0.8f * crest) * MathX.clamp01(ambient)
            val thickness = 0.10f + crest * 0.55f
            g.line(xs[i], ys[i], xs[i + 1], ys[i + 1], thickness, cTmp)
        }

        // Pass 2: breaking water, thrown forward off the face of steep crests.
        g.additive(false)
        for (i in 0 until COLUMNS step 2) {
            val crest = crests[i]
            if (crest < 0.45f) continue
            val strength = MathX.smoothstep(0.45f, 1f, crest)
            cTmp.set(Palette.foam)
            cTmp.a = strength * 0.55f * MathX.clamp01(ambient)
            val r = 0.5f + strength * 1.7f
            val wobble = Noise.signed(xs[i] * 0.4f, time * 2.2f, 17) * 0.6f
            g.puff(xs[i] + wobble, ys[i] + 0.3f + strength * 0.5f, r, cTmp, xs[i] * 0.3f)
        }
    }

    /** Shafts of light reaching down into the water. Only in decent weather. */
    fun drawLightShafts(g: Painter, cam: Cam, seaState: Float, ambient: Float) {
        val clarity = (1f - MathX.smoothstep(0.25f, 0.7f, seaState)) * MathX.clamp01(ambient)
        if (clarity < 0.05f) return
        g.additive(true)
        val spacing = 11f
        var x = MathX.clamp(cam.left - (cam.left % spacing), -1e6f, 1e6f)
        while (x < cam.right + spacing) {
            val sway = Noise.signed(x * 0.05f, time * 0.25f, 77) * 2.2f
            val top = surfaceYAt(x)
            cTmp.set(Palette.translucentCalm)
            cTmp.a = 0.075f * clarity
            g.sprite(art.shaft, x + sway, top - 9f, 3.2f, 20f, cTmp)
            x += spacing
        }
        g.additive(false)
    }

    // -----------------------------------------------------------------------
    // Foreground: a near layer of water drawn over everything, for depth
    // -----------------------------------------------------------------------

    fun drawForeground(g: Painter, cam: Cam, seaState: Float, ambient: Float) {
        val storm = MathX.smoothstep(0.2f, 0.85f, seaState)
        val bottom = cam.bottom - 2f
        // A closer, faster, darker band of water across the bottom of the frame. It reads as
        // the wave the camera itself is behind, and it gives the scene real depth.
        val cols = 44
        val step = (cam.viewWidth + 16f) / cols
        val amp = 0.45f + 1.3f * storm
        val baseY = cam.bottom + cam.viewHeight * 0.012f

        cTmp.set(Palette.deepStorm).lerp(Palette.abyss, 0.45f)
        cTmp.mul(ambient, ambient, ambient * 1.1f, 1f)
        cTmp.a = 0.6f

        var px = cam.left - 8f
        var py = baseY + wavelet(px)* amp
        for (i in 1..cols) {
            val x = cam.left - 8f + step * i
            val y = baseY + wavelet(x) * amp
            g.quad(px, py, x, y, x, bottom, px, bottom, cTmp, cTmp, cTmp, cTmp)
            px = x; py = y
        }
        // No foam rim on this band: a hard bright line across the bottom of the frame reads
        // as a UI element, not as the wave the camera is sitting behind.
    }

    private fun wavelet(x: Float): Float =
        Noise.fbm(x * 0.09f - time * 0.75f, time * 0.35f, 3, 505)

    // -----------------------------------------------------------------------
    // The shore, at the far end of the course
    // -----------------------------------------------------------------------

    fun drawShore(g: Painter, cam: Cam, ambient: Float) {
        val shoreX = Config.COURSE_LENGTH
        if (cam.right < shoreX - 30f) return

        val base = cam.bottom
        val h = cam.viewHeight

        // Headland behind the beach.
        cTmp.set(Palette.reefStone).lerp(Palette.skyLowCalm, 0.35f)
        cTmp.mul(ambient, ambient, ambient, 1f); cTmp.a = 1f
        val pts = FloatArray(12)
        pts[0] = shoreX + 6f; pts[1] = base
        pts[2] = shoreX + 16f; pts[3] = base + h * 0.30f
        pts[4] = shoreX + 34f; pts[5] = base + h * 0.44f
        pts[6] = shoreX + 58f; pts[7] = base + h * 0.26f
        pts[8] = shoreX + 90f; pts[9] = base + h * 0.38f
        pts[10] = shoreX + 140f; pts[11] = base
        g.polygon(pts, 6, cTmp)

        // The beach itself, and the surf running up it.
        cTmp.set(Palette.crate).lerp(Color.WHITE, 0.28f)
        cTmp.mul(ambient, ambient, ambient, 1f); cTmp.a = 1f
        g.quad(shoreX - 4f, -1.2f, shoreX + 150f, 1.6f, shoreX + 150f, base, shoreX - 4f, base,
            cTmp, cTmp, cTmp, cTmp)

        cTmp.set(Palette.foam); cTmp.a = 0.7f
        g.line(shoreX - 5f, -1.1f, shoreX + 18f, 0.2f, 0.6f, cTmp)

        // The finish gate, so there is no ambiguity about where the voyage ends.
        cTmp.set(Palette.accent); cTmp.a = 0.85f
        g.line(shoreX, -3f, shoreX, base + h * 0.55f, 0.35f, cTmp)
        g.additive(true)
        g.glow(shoreX, base + h * 0.25f, 9f, Palette.alpha(Palette.accent, 0.25f))
        g.additive(false)
    }

    // -----------------------------------------------------------------------

    /** Spawn foam and spray from breaking water. Called once per frame. */
    fun emit(dt: Float, cam: Cam, fx: Fx, seaState: Float) {
        time += dt
        foamTimer += dt
        val interval = 0.035f
        if (foamTimer < interval) return
        foamTimer = 0f

        val budget = (2 + (seaState * 7f).toInt())
        repeat(budget) {
            val i = rng.nextInt(COLUMNS)
            if (crests[i] < 0.52f) return@repeat
            val strength = MathX.smoothstep(0.52f, 1f, crests[i])
            // Breaking crests throw water forward and down the face.
            fx.spray(xs[i], ys[i] + 0.2f, 0.75f, 0.55f, 3.5f + 7f * strength, 1)
            if (rng.chance(0.4f)) fx.foam(xs[i], ys[i], 1.2f, 1, 0.8f)
        }
    }
}

/**
 * Cheap approximate height for decorative layers.
 *
 * The real [Ocean.sample] inverts the Gerstner parameterisation, which is the right thing for
 * anything that has to agree with the physics. Background swell agrees with nothing, so it
 * gets a plain sum of sines and the frame time back.
 */
fun Ocean.heightAtApprox(x: Float, t: Float): Float {
    val a = kotlin.math.sin(x * 0.055f + t * 0.55f) * 1.0f
    val b = kotlin.math.sin(x * 0.021f - t * 0.31f) * 1.7f
    val c = kotlin.math.sin(x * 0.13f + t * 0.9f) * 0.42f
    return (a + b + c) * (0.45f + seaState)
}
