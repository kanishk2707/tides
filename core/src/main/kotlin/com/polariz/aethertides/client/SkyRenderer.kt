package com.polariz.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Noise
import com.polariz.aethertides.shared.math.Rng
import com.polariz.aethertides.shared.ocean.Wind
import kotlin.math.abs

/**
 * Everything above the waterline.
 *
 * The sky is the game's mood meter. It is driven entirely by sea state, so when the Tempest
 * spends malice on the storm dial the player sees the weather turn before they feel it: the
 * zenith drops toward black, the horizon loses its warmth, the cloud deck thickens and drops,
 * and the sun goes from a hard disc to a smear behind the overcast.
 *
 * Cloud layers run at three parallax depths against the camera, which is what gives a 2D scene
 * its sense of distance without any 3D.
 */
class SkyRenderer(private val art: Art, seed: Long) {

    private class Cloud(
        @JvmField var x: Float, @JvmField var y: Float,
        @JvmField var w: Float, @JvmField var h: Float,
        @JvmField var depth: Float, @JvmField var shade: Float
    )

    private val clouds = ArrayList<Cloud>(26)
    private val rng = Rng(seed xor 0xC10DL)

    /** Sun elevation for this match, 0 at the horizon to 1 overhead. Fixed per seed. */
    private val sunHeight = rng.range(0.42f, 0.86f)
    private val sunLead = rng.range(0.25f, 0.75f)

    private var flash = 0f
    private var time = 0f

    private val cHigh = Color()
    private val cLow = Color()
    private val cCloud = Color()
    private val cTmp = Color()

    init {
        repeat(26) {
            clouds.add(
                Cloud(
                    x = rng.range(-400f, 2000f),
                    y = rng.range(0.35f, 1.0f),
                    w = rng.range(40f, 160f),
                    h = rng.range(14f, 46f),
                    depth = rng.range(0.06f, 0.42f),
                    shade = rng.range(0f, 1f)
                )
            )
        }
    }

    fun update(dt: Float, windSpeed: Float) {
        time += dt
        if (flash > 0f) flash = MathX.approach(flash, 0f, 7f, dt)
        // Cloud drift scales with the real wind, so a storm sky visibly moves faster.
        for (c in clouds) {
            c.x -= windSpeed * (0.10f + c.depth * 0.5f) * dt
        }
    }

    /** Trigger the full-screen flash of a lightning strike. */
    fun lightning(intensity: Float = 1f) {
        flash = minOf(1.35f, flash + intensity)
    }

    fun draw(g: Painter, cam: Cam, seaState: Float, wind: Wind, shipX: Float) {
        val storm = MathX.smoothstep(0.2f, 0.85f, seaState)

        cHigh.set(Palette.skyHighCalm).lerp(Palette.skyHighStorm, storm)
        cLow.set(Palette.skyLowCalm).lerp(Palette.skyLowStorm, storm)

        val l = cam.left
        val w = cam.viewWidth
        val h = cam.viewHeight

        // The horizon sits at the camera height above the water, because that is simply where
        // a flat sea meets the sky for an eye at that height. Pinning it to a fixed fraction
        // of the screen instead is what makes side-view water read as a painted backdrop: the
        // sea can then never rise past its own horizon. Here a big crest genuinely does.
        val hz = cam.camera.position.y

        // Two bands rather than one, so most of the gradient happens near the horizon, which
        // is where it happens in a real sky.
        val midSky = Color(cLow).lerp(cHigh, 0.55f)
        g.rectV(l, hz, w, (cam.top - hz) * 0.42f, cLow, midSky)
        g.rectV(l, hz + (cam.top - hz) * 0.42f, w, (cam.top - hz) * 0.62f, midSky, cHigh)

        drawSun(g, cam, storm, hz)
        drawClouds(g, cam, storm, hz, h)
        drawSquallCurtains(g, cam, wind, hz)

        // Haze: sky and sea never meet at a hard line.
        cTmp.set(cLow)
        cTmp.a = 0.6f
        g.rectV(l, hz - h * 0.055f, w, h * 0.055f, Palette.alpha(cLow, 0f), cTmp)

        if (flash > 0.001f) {
            cTmp.set(Color.WHITE)
            cTmp.a = MathX.clamp01(flash) * 0.5f
            g.rect(l, hz - h * 0.5f, w, h, cTmp)
        }
    }

    private fun drawSun(g: Painter, cam: Cam, storm: Float, horizonY: Float) {
        // Parked relative to the camera: the sun is effectively at infinity.
        val sx = cam.left + cam.viewWidth * sunLead
        val sy = horizonY + cam.viewHeight * 0.40f * sunHeight
        val visible = 1f - storm * 0.82f
        if (visible <= 0.02f) return

        g.additive(true)
        // Broad atmospheric bloom, then the disc itself.
        g.glow(sx, sy, cam.viewWidth * 0.26f, Palette.alpha(Palette.sunGlow, 0.09f * visible))
        g.glow(sx, sy, cam.viewWidth * 0.080f, Palette.alpha(Palette.sunGlow, 0.20f * visible))
        g.glow(sx, sy, cam.viewWidth * 0.020f, Palette.alpha(Palette.sunCore, 0.85f * visible))
        // The glitter path it lays across the water, brightest at the waterline.
        g.rectV(
            sx - cam.viewWidth * 0.11f, horizonY - cam.viewHeight * 0.45f,
            cam.viewWidth * 0.22f, cam.viewHeight * 0.45f,
            Palette.alpha(Palette.sunGlow, 0f), Palette.alpha(Palette.sunGlow, 0.06f * visible)
        )
        g.additive(false)
    }

    private fun drawClouds(g: Painter, cam: Cam, storm: Float, horizonY: Float, viewH: Float) {
        val camX = cam.camera.position.x
        for (c in clouds) {
            // Parallax: distant cloud barely moves against the camera.
            val px = c.x - camX * c.depth
            // Wrap into a band around the camera so the deck never runs out.
            val span = maxOf(1200f, cam.viewWidth * 14f)
            var wrapped = ((px - cam.left) % span + span) % span + cam.left
            if (wrapped > cam.right + c.w) wrapped -= span

            // Storm clouds are lower, larger and darker.
            val heightT = MathX.lerp(c.y, c.y * 0.55f + 0.10f, storm)
            val cy = horizonY + viewH * 0.44f * heightT
            // Sized against the frame, so the deck reads the same at any view width.
            val scale = MathX.lerp(1f, 1.35f, storm) * (0.45f + c.depth) * (cam.viewWidth / 110f)

            val lit = MathX.lerp(0.85f, 0.08f, storm) * (0.4f + 0.6f * c.shade)
            cCloud.set(Palette.cloudDark).lerp(Palette.cloudLit, lit)
            cCloud.a = MathX.lerp(0.42f, 0.92f, storm) * (0.45f + 0.55f * c.depth)

            g.sprite(art.cloud, wrapped, cy, c.w * scale, c.h * scale, cCloud)
        }
    }

    private fun drawSquallCurtains(g: Painter, cam: Cam, wind: Wind, horizonY: Float) {
        for (i in 0 until Wind.MAX_SQUALLS) {
            if (!wind.squallAlive(i)) continue
            val x = wind.squallX(i)
            val r = wind.squallR(i)
            if (x + r < cam.left - 40f || x - r > cam.right + 40f) continue
            val strength = MathX.clamp01(wind.squallStrength(i) / 1.4f)
            // A dark, soft column of falling water reaching down to the sea.
            cTmp.set(Palette.cloudDark)
            cTmp.a = 0.34f * strength
            g.sprite(art.cloud, x, horizonY + cam.viewHeight * 0.26f,
                minOf(r * 2.2f, cam.viewWidth * 1.4f), cam.viewHeight * 0.26f, cTmp)
            cTmp.a = 0.18f * strength
            g.sprite(art.shaft, x, horizonY + cam.viewHeight * 0.02f,
                minOf(r * 1.7f, cam.viewWidth), cam.viewHeight * 0.42f, cTmp)
        }
    }

    /**
     * Light level of the scene, 0 to 1. The sea, the ship and the HUD all read from this so
     * that when the sky darkens, everything darkens together.
     */
    fun ambient(seaState: Float): Float {
        val storm = MathX.smoothstep(0.2f, 0.9f, seaState)
        return MathX.clamp(1f - storm * 0.55f + flash * 0.45f, 0.25f, 1.45f)
    }

    fun flashLevel(): Float = flash

    /** Slow, large-scale brightness variation from cloud shadow passing over the water. */
    fun cloudShadow(x: Float): Float =
        0.86f + 0.14f * (Noise.fbm(x * 0.004f - time * 0.05f, time * 0.02f, 2, 313) * 0.5f + 0.5f)
}
