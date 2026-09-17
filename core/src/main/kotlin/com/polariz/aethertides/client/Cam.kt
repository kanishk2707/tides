package com.polariz.aethertides.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Noise
import com.polariz.aethertides.shared.sim.Role
import kotlin.math.abs

/**
 * The camera.
 *
 * Two very different jobs behind one class. The Navigator camera sits close, leads the bow by
 * an amount that grows with speed, and pulls back as she goes faster so the sense of speed
 * comes from the frame widening rather than from the ship moving faster across it. It also
 * only partly follows the vertical -- if it tracked the hull exactly through a six metre swell
 * the sea would look still and the ship would look nailed down, which is the single most
 * common way a wave game throws away its own water.
 *
 * The Tempest camera sits back and ahead: they are working in the water the ship has not
 * reached yet, so the useful frame is the one containing the lane, not the hull.
 */
class Cam {

    val camera = OrthographicCamera()

    /** Metres of world visible across the width of the screen. */
    var viewWidth = 92f
        private set
    var viewHeight = 50f
        private set

    var x = 0f
        private set
    var y = 0f
        private set

    private var shake = 0f
    private var shakeSeed = 0f
    private var zoom = 1f
    private var targetZoom = 1f

    /** Extra pan the Tempest player has dragged in, metres. */
    var pan = 0f

    private var aspect = 16f / 9f

    fun resize(width: Int, height: Int) {
        aspect = width.toFloat() / height.toFloat()
    }

    fun snapTo(px: Float, py: Float) {
        x = px; y = py
    }

    /**
     * @param baseWidth metres across the screen before the speed zoom-out
     */
    fun update(
        dt: Float,
        role: Role,
        shipX: Float,
        shipY: Float,
        shipVx: Float,
        seaState: Float,
        baseWidth: Float
    ) {
        // Widen with speed and with the sea. Both changes read as "this is getting serious".
        targetZoom = 1f + MathX.clamp01(abs(shipVx) / 26f) * 0.30f + seaState * 0.14f
        zoom = MathX.approach(zoom, targetZoom, 2.2f, dt)

        viewWidth = baseWidth * zoom
        viewHeight = viewWidth / aspect

        val lead: Float
        val followY: Float
        if (role == Role.NAVIGATOR) {
            // Lead the bow harder the faster she goes, so the water she is about to reach is
            // always on screen.
            lead = viewWidth * 0.11f + MathX.clamp(shipVx, -6f, 26f) * 0.62f
            // Partial vertical follow: she visibly rises and falls inside the frame.
            // The offset puts mean sea level around a third up the screen, which leaves room
            // above for corsairs and rogue crests without losing sight of submerged reef.
            followY = shipY * 0.5f + viewHeight * 0.085f
        } else {
            // The Tempest works ahead of the bow, and can drag the frame further.
            lead = viewWidth * 0.22f + MathX.clamp(shipVx, 0f, 26f) * 0.85f + pan
            followY = shipY * 0.3f + viewHeight * 0.07f
        }

        val targetX = shipX + lead
        // Snap if we are wildly out of position (a round just started, or a reconnect).
        if (abs(targetX - x) > viewWidth * 1.5f) x = targetX else
            x = MathX.approach(x, targetX, if (role == Role.NAVIGATOR) 7.5f else 4.5f, dt)
        y = MathX.approach(y, followY, 4.2f, dt)

        if (shake > 0f) {
            shake = MathX.approach(shake, 0f, 3.4f, dt)
            if (shake < 0.02f) shake = 0f
        }
        shakeSeed += dt * 34f

        val sx = if (shake > 0f) Noise.signed(shakeSeed, 0.5f, 11) * shake else 0f
        val sy = if (shake > 0f) Noise.signed(0.5f, shakeSeed, 29) * shake else 0f

        camera.viewportWidth = viewWidth
        camera.viewportHeight = viewHeight
        camera.position.set(x + sx, y + sy, 0f)
        camera.update()
    }

    /** Add camera shake, in metres of displacement. Impacts and explosions call this. */
    fun addShake(amount: Float) {
        shake = minOf(2.4f, shake + amount)
    }

    val left: Float get() = camera.position.x - viewWidth * 0.5f
    val right: Float get() = camera.position.x + viewWidth * 0.5f
    val bottom: Float get() = camera.position.y - viewHeight * 0.5f
    val top: Float get() = camera.position.y + viewHeight * 0.5f

    /** World position of a screen touch. */
    fun unprojectX(screenX: Float): Float =
        left + (screenX / Gdx.graphics.width.toFloat()) * viewWidth

    fun unprojectY(screenY: Float): Float =
        top - (screenY / Gdx.graphics.height.toFloat()) * viewHeight

    /** Screen pixel (bottom-left origin, HUD space) of a world x. */
    fun projectX(worldX: Float, screenW: Float): Float =
        (worldX - left) / viewWidth * screenW

    fun projectY(worldY: Float, screenH: Float): Float =
        (worldY - bottom) / viewHeight * screenH
}
