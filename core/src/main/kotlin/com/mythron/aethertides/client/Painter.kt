package com.mythron.aethertides.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.mythron.aethertides.shared.math.MathX
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawing surface.
 *
 * Sprites and raw coloured triangles cannot be in flight at the same time on one GL context,
 * so this owns both a [SpriteBatch] and an [ImmediateModeRenderer20] and switches between them
 * on demand, flushing as it goes. Callers just draw; the mode juggling stays in here.
 *
 * Triangles carry per-vertex colour, which is how the water gets its depth ramp, its
 * translucent backlit crests and its foam without a single texture lookup.
 */
class Painter(private val art: Art) : Disposable {

    val batch = SpriteBatch(4000)
    private val tris = ImmediateModeRenderer20(48000, false, true, 0)

    private var mode = Mode.NONE
    private var projection = Matrix4()
    private var additive = false

    private enum class Mode { NONE, SPRITE, TRIS }

    private val c = Color()

    fun begin(camera: OrthographicCamera) {
        flush()
        projection.set(camera.combined)
        Gdx.gl.glEnable(GL20.GL_BLEND)
    }

    fun beginMatrix(m: Matrix4) {
        flush()
        projection.set(m)
        Gdx.gl.glEnable(GL20.GL_BLEND)
    }

    fun end() = flush()

    fun flush() {
        when (mode) {
            Mode.SPRITE -> batch.end()
            Mode.TRIS -> tris.end()
            Mode.NONE -> {}
        }
        mode = Mode.NONE
    }

    private fun sprites() {
        if (mode == Mode.SPRITE) return
        flush()
        batch.projectionMatrix = projection
        batch.begin()
        applyBlend()
        mode = Mode.SPRITE
    }

    private fun triangles() {
        if (mode == Mode.TRIS) return
        flush()
        applyBlend()
        tris.begin(projection, GL20.GL_TRIANGLES)
        mode = Mode.TRIS
    }

    private fun applyBlend() {
        // SpriteBatch.end() disables GL_BLEND on its way out, so any triangle pass that
        // follows a sprite pass would otherwise render completely opaque -- alpha silently
        // ignored. That is why the foam waterline came out as a hard white bar.
        Gdx.gl.glEnable(GL20.GL_BLEND)
        if (additive) {
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            if (mode == Mode.SPRITE) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        } else {
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            if (mode == Mode.SPRITE) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    /** Additive blending, for anything that emits light rather than reflecting it. */
    fun additive(on: Boolean) {
        if (additive == on) return
        flush()
        additive = on
    }

    // -----------------------------------------------------------------------
    // Triangles
    // -----------------------------------------------------------------------

    fun tri(
        x1: Float, y1: Float, c1: Color,
        x2: Float, y2: Float, c2: Color,
        x3: Float, y3: Float, c3: Color
    ) {
        triangles()
        tris.color(c1.r, c1.g, c1.b, c1.a); tris.vertex(x1, y1, 0f)
        tris.color(c2.r, c2.g, c2.b, c2.a); tris.vertex(x2, y2, 0f)
        tris.color(c3.r, c3.g, c3.b, c3.a); tris.vertex(x3, y3, 0f)
    }

    /** Axis-aligned rectangle with a colour per corner. */
    fun rect(
        x: Float, y: Float, w: Float, h: Float,
        bl: Color, br: Color, tr: Color, tl: Color
    ) {
        tri(x, y, bl, x + w, y, br, x + w, y + h, tr)
        tri(x, y, bl, x + w, y + h, tr, x, y + h, tl)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) =
        rect(x, y, w, h, color, color, color, color)

    /** Vertical gradient rectangle: the workhorse for sky, panels and bars. */
    fun rectV(x: Float, y: Float, w: Float, h: Float, bottom: Color, top: Color) =
        rect(x, y, w, h, bottom, bottom, top, top)

    /** A quad given by its four corners, wound counter-clockwise. */
    fun quad(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        c1: Color, c2: Color, c3: Color, c4: Color
    ) {
        tri(ax, ay, c1, bx, by, c2, cx, cy, c3)
        tri(ax, ay, c1, cx, cy, c3, dx, dy, c4)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Color) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = MathX.len(dx, dy)
        if (len < 1e-5f) return
        val nx = -dy / len * width * 0.5f
        val ny = dx / len * width * 0.5f
        quad(
            x1 + nx, y1 + ny, x2 + nx, y2 + ny,
            x2 - nx, y2 - ny, x1 - nx, y1 - ny,
            color, color, color, color
        )
    }

    fun lineGradient(
        x1: Float, y1: Float, x2: Float, y2: Float, width: Float, c1: Color, c2: Color
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = MathX.len(dx, dy)
        if (len < 1e-5f) return
        val nx = -dy / len * width * 0.5f
        val ny = dx / len * width * 0.5f
        quad(
            x1 + nx, y1 + ny, x2 + nx, y2 + ny,
            x2 - nx, y2 - ny, x1 - nx, y1 - ny,
            c1, c2, c2, c1
        )
    }

    /** Filled convex polygon as a fan from the first vertex. */
    fun polygon(pts: FloatArray, n: Int, color: Color) {
        var i = 1
        while (i < n - 1) {
            tri(
                pts[0], pts[1], color,
                pts[i * 2], pts[i * 2 + 1], color,
                pts[(i + 1) * 2], pts[(i + 1) * 2 + 1], color
            )
            i++
        }
    }

    /**
     * Filled polygon fanned from an explicit centre. Works for any star-shaped outline, which
     * covers hulls, sails, reef heads and tentacles without needing a real triangulator.
     */
    fun fan(cx: Float, cy: Float, pts: FloatArray, n: Int, color: Color, closed: Boolean = true) {
        val last = if (closed) n else n - 1
        for (i in 0 until last) {
            val j = (i + 1) % n
            tri(cx, cy, color, pts[i * 2], pts[i * 2 + 1], color, pts[j * 2], pts[j * 2 + 1], color)
        }
    }

    /** Outline of a point list, optionally closed. */
    fun outline(pts: FloatArray, n: Int, width: Float, color: Color, closed: Boolean = true) {
        val last = if (closed) n else n - 1
        for (i in 0 until last) {
            val j = (i + 1) % n
            line(pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1], width, color)
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, segments: Int, color: Color) {
        var prevX = cx + r
        var prevY = cy
        for (i in 1..segments) {
            val a = MathX.TAU * i / segments
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            tri(cx, cy, color, prevX, prevY, color, px, py, color)
            prevX = px; prevY = py
        }
    }

    /** Disc that fades from centre colour to rim colour -- a cheap soft light. */
    fun disc(cx: Float, cy: Float, r: Float, segments: Int, inner: Color, outer: Color) {
        var prevX = cx + r
        var prevY = cy
        for (i in 1..segments) {
            val a = MathX.TAU * i / segments
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            tri(cx, cy, inner, prevX, prevY, outer, px, py, outer)
            prevX = px; prevY = py
        }
    }

    fun ringLine(cx: Float, cy: Float, r: Float, width: Float, segments: Int, color: Color) {
        var prevX = cx + r
        var prevY = cy
        for (i in 1..segments) {
            val a = MathX.TAU * i / segments
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            line(prevX, prevY, px, py, width, color)
            prevX = px; prevY = py
        }
    }

    // -----------------------------------------------------------------------
    // Sprites
    // -----------------------------------------------------------------------

    fun sprite(region: TextureRegion, cx: Float, cy: Float, w: Float, h: Float, color: Color) {
        sprites()
        batch.color = color
        batch.draw(region, cx - w * 0.5f, cy - h * 0.5f, w, h)
    }

    fun sprite(
        region: TextureRegion, cx: Float, cy: Float, w: Float, h: Float,
        rotationRad: Float, color: Color
    ) {
        sprites()
        batch.color = color
        batch.draw(
            region, cx - w * 0.5f, cy - h * 0.5f, w * 0.5f, h * 0.5f, w, h, 1f, 1f,
            rotationRad * 57.29578f
        )
    }

    /** A glow blob. The single most used effect in the game. */
    fun glow(cx: Float, cy: Float, radius: Float, color: Color, intensity: Float = 1f) {
        c.set(color)
        c.a = MathX.clamp01(color.a * intensity)
        sprite(art.glow, cx, cy, radius * 2f, radius * 2f, c)
    }

    fun puff(cx: Float, cy: Float, radius: Float, color: Color, rotation: Float = 0f) {
        sprite(art.puff, cx, cy, radius * 2f, radius * 2f, rotation, color)
    }

    fun spark(cx: Float, cy: Float, radius: Float, color: Color) {
        sprite(art.spark, cx, cy, radius * 2f, radius * 2f, color)
    }

    fun ring(cx: Float, cy: Float, radius: Float, color: Color) {
        sprite(art.ring, cx, cy, radius * 2f, radius * 2f, color)
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    fun text(font: BitmapFont, s: CharSequence, x: Float, y: Float, color: Color) {
        sprites()
        batch.color = Color.WHITE
        font.color = color
        font.draw(batch, s, x, y)
    }

    fun textCentered(font: BitmapFont, s: CharSequence, cx: Float, y: Float, color: Color) {
        art.layout.setText(font, s)
        text(font, s, cx - art.layout.width * 0.5f, y, color)
    }

    fun textRight(font: BitmapFont, s: CharSequence, rightX: Float, y: Float, color: Color) {
        art.layout.setText(font, s)
        text(font, s, rightX - art.layout.width, y, color)
    }

    fun textWidth(font: BitmapFont, s: CharSequence): Float {
        art.layout.setText(font, s)
        return art.layout.width
    }

    fun textHeight(font: BitmapFont, s: CharSequence): Float {
        art.layout.setText(font, s)
        return art.layout.height
    }

    override fun dispose() {
        batch.dispose()
        tris.dispose()
    }
}

/** Angle of a vector, in radians. Small helper used all over the renderers. */
fun angleOf(dx: Float, dy: Float): Float = atan2(dy, dx)
