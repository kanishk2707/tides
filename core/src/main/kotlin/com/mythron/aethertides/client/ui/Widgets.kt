package com.mythron.aethertides.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.mythron.aethertides.client.Art
import com.mythron.aethertides.client.Painter
import com.mythron.aethertides.client.Palette
import com.mythron.aethertides.shared.math.MathX
import kotlin.math.cos
import kotlin.math.sin

/**
 * The HUD toolkit.
 *
 * Immediate mode: no widget tree, no layout pass, no retained state beyond a press animation
 * per id. For a game HUD that is entirely regenerated every frame from simulation state, a
 * retained UI framework is all cost and no benefit.
 *
 * Everything is sized in *scaled pixels* -- multiply by [Art.uiScale] once at the call site --
 * so a 6 inch phone and a 13 inch tablet both get thumb-sized controls.
 */
class Widgets(private val art: Art, private val touch: Touch) {

    private val press = HashMap<Int, Float>()
    private val c = Color()
    private val c2 = Color()

    fun update(dt: Float) {
        val it = press.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = MathX.approach(e.value, 0f, 9f, dt)
            if (v < 0.004f) it.remove() else e.setValue(v)
        }
    }

    private fun pressLevel(id: Int): Float = press[id] ?: 0f

    private fun kick(id: Int) {
        press[id] = 1f
    }

    // -----------------------------------------------------------------------
    // Chrome
    // -----------------------------------------------------------------------

    /** The standard smoked-glass panel. */
    fun panel(g: Painter, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        c.set(Palette.panel)
        c.a *= alpha
        c2.set(Palette.ink)
        c2.a = 0.82f * alpha
        g.rectV(x, y, w, h, c2, c)
        c.set(Palette.panelEdge)
        c.a = 0.55f * alpha
        g.line(x, y + h, x + w, y + h, 1.5f, c)
        c.a = 0.22f * alpha
        g.line(x, y, x + w, y, 1.5f, c)
        g.line(x, y, x, y + h, 1.5f, c)
        g.line(x + w, y, x + w, y + h, 1.5f, c)
    }

    /**
     * A labelled meter.
     *
     * @param warn below this fraction the bar turns to the danger colour and breathes
     */
    fun bar(
        g: Painter, x: Float, y: Float, w: Float, h: Float,
        value: Float, tint: Color, label: String? = null,
        warn: Float = -1f, pulse: Float = 0f
    ) {
        val v = MathX.clamp01(value)
        c.set(Palette.ink); c.a = 0.75f
        g.rect(x, y, w, h, c)

        val low = warn > 0f && v < warn
        c2.set(if (low) Palette.danger else tint)
        if (low) {
            val breathe = 0.72f + 0.28f * sin(pulse * 7f)
            c2.r *= breathe; c2.g *= breathe; c2.b *= breathe
        }
        // Fill, with a brighter cap so the bar has a readable leading edge.
        c.set(c2); c.r *= 0.6f; c.g *= 0.6f; c.b *= 0.6f
        g.rectV(x, y, w * v, h, c, c2)
        if (v > 0.01f) {
            c.set(Color.WHITE); c.a = 0.35f
            g.rect(x + w * v - 1.5f, y, 1.5f, h, c)
        }

        c.set(Palette.panelEdge); c.a = 0.5f
        g.line(x, y, x + w, y, 1f, c)
        g.line(x, y + h, x + w, y + h, 1f, c)

        if (label != null) {
            g.text(art.small, label, x, y + h + art.small.lineHeight * 0.92f, Palette.textDim)
        }
    }

    /**
     * Label on the left, meter on the right, one shared baseline.
     *
     * Both HUDs lay every readout out through this, which is what stops the panels drifting
     * out of alignment as numbers get added to them.
     */
    fun meterRow(
        g: Painter, font: BitmapFont, label: String, x: Float, barY: Float,
        labelW: Float, barW: Float, barH: Float, value: Float, tint: Color,
        warn: Float = -1f, pulse: Float = 0f, readout: String? = null,
        readoutTint: Color = Palette.textBright
    ) {
        val textY = barY + barH * 0.5f + font.capHeight * 0.5f
        g.text(font, label, x, textY, Palette.textDim)
        bar(g, x + labelW, barY, barW, barH, value, tint, null, warn, pulse)
        if (readout != null) g.text(font, readout, x + labelW + barW + 10f, textY, readoutTint)
    }

    /** Segmented progress, used for the voyage leagues. */
    fun segments(
        g: Painter, x: Float, y: Float, w: Float, h: Float,
        count: Int, progress: Float, tint: Color
    ) {
        val gap = h * 0.45f
        val seg = (w - gap * (count - 1)) / count
        for (i in 0 until count) {
            val sx = x + i * (seg + gap)
            val from = i / count.toFloat()
            val fill = MathX.clamp01((progress - from) * count)
            c.set(Palette.ink); c.a = 0.7f
            g.rect(sx, y, seg, h, c)
            if (fill > 0f) {
                c2.set(tint)
                c.set(tint); c.r *= 0.55f; c.g *= 0.55f; c.b *= 0.55f
                g.rectV(sx, y, seg * fill, h, c, c2)
            }
            c.set(Palette.panelEdge); c.a = 0.45f
            g.line(sx, y, sx + seg, y, 1f, c)
        }
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------

    /** Rectangular button. Returns true on the frame it is released inside. */
    fun button(
        g: Painter, id: Int, x: Float, y: Float, w: Float, h: Float,
        label: String, font: BitmapFont = art.hud,
        tint: Color = Palette.accent, enabled: Boolean = true
    ): Boolean {
        var fired = false
        var held = false

        if (enabled) {
            touch.pressedIn(x, y, w, h)?.let { p ->
                if (touch.claim(p, id)) kick(id)
            }
            val owned = touch.ownedBy(id)
            if (owned != null) {
                held = owned.x >= x && owned.x <= x + w && owned.y >= y && owned.y <= y + h
                if (owned.justUp && held) fired = true
            }
        }

        val lvl = pressLevel(id) * 0.6f + (if (held) 0.4f else 0f)
        val inset = lvl * h * 0.04f

        c.set(Palette.panel); c.a = if (enabled) 0.95f else 0.5f
        c2.set(tint); c2.r *= 0.22f; c2.g *= 0.22f; c2.b *= 0.22f
        c2.a = if (enabled) 0.95f else 0.4f
        g.rectV(x + inset, y + inset, w - inset * 2, h - inset * 2, c, c2)

        c.set(tint)
        c.a = if (enabled) 0.85f + 0.15f * lvl else 0.3f
        g.outline(
            floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h), 4,
            1.6f + lvl * 1.4f, c
        )

        val textColor = if (enabled) Palette.textBright else Palette.textFaint
        g.textCentered(font, label, x + w * 0.5f, y + h * 0.5f + font.capHeight * 0.5f, textColor)
        return fired
    }

    /**
     * Round action button with a radial cooldown sweep.
     *
     * @param cooldown 0 ready, 1 just used
     * @param affordable whether the resource cost can currently be paid
     */
    fun actionButton(
        g: Painter, id: Int, cx: Float, cy: Float, r: Float,
        glyph: String, label: String, tint: Color,
        cooldown: Float, affordable: Boolean, selected: Boolean,
        locked: Boolean = false
    ): Boolean {
        val ready = cooldown <= 0.001f && affordable && !locked
        var fired = false
        var held = false

        touch.pressedIn(cx - r, cy - r, r * 2, r * 2)?.let { p ->
            if (touch.claim(p, id)) kick(id)
        }
        touch.ownedBy(id)?.let { p ->
            held = MathX.dist(p.x, p.y, cx, cy) <= r * 1.25f
            if (p.justUp && held) fired = true
        }

        val lvl = pressLevel(id)
        val rr = r * (1f + lvl * 0.06f) * (if (selected) 1.06f else 1f)

        // Body.
        c.set(Palette.ink); c.a = 0.9f
        g.circle(cx, cy, rr, 22, c)
        c.set(tint)
        c.r *= 0.24f; c.g *= 0.24f; c.b *= 0.24f
        c.a = if (ready) 0.95f else 0.55f
        g.circle(cx, cy, rr * 0.94f, 22, c)

        // Selection halo.
        if (selected) {
            g.additive(true)
            g.glow(cx, cy, rr * 2.1f, Palette.alpha(tint, 0.3f))
            g.additive(false)
        }

        // Rim, brighter when usable.
        c.set(tint)
        c.a = if (ready) 0.95f else 0.35f
        g.ringLine(cx, cy, rr, 2.2f + lvl * 2f, 26, c)

        // Cooldown sweep: an unfilled wedge that closes as it comes back.
        if (cooldown > 0.001f) {
            c.set(Palette.ink); c.a = 0.68f
            val segs = 24
            val filled = (segs * MathX.clamp01(cooldown)).toInt()
            for (i in 0 until filled) {
                val a0 = MathX.PI * 0.5f + MathX.TAU * i / segs
                val a1 = MathX.PI * 0.5f + MathX.TAU * (i + 1) / segs
                g.tri(
                    cx, cy, c,
                    cx + cos(a0) * rr, cy + sin(a0) * rr, c,
                    cx + cos(a1) * rr, cy + sin(a1) * rr, c
                )
            }
        }

        // Glyph and caption.
        val glyphColor = when {
            locked -> Palette.danger
            ready -> Palette.textBright
            else -> Palette.textFaint
        }
        g.textCentered(art.hudLarge, glyph, cx, cy + art.hudLarge.capHeight * 0.28f, glyphColor)
        g.textCentered(
            art.small, label, cx, cy - rr - art.small.lineHeight * 0.15f,
            if (ready) Palette.textDim else Palette.textFaint
        )

        if (locked) {
            c.set(Palette.danger); c.a = 0.85f
            g.line(cx - rr * 0.6f, cy - rr * 0.6f, cx + rr * 0.6f, cy + rr * 0.6f, 3f, c)
        } else if (!affordable) {
            c.set(Palette.danger); c.a = 0.5f
            g.ringLine(cx, cy, rr * 0.82f, 1.4f, 20, c)
        }
        return fired
    }

    /** Vertical slider. Returns the new value. */
    fun vSlider(
        g: Painter, id: Int, x: Float, y: Float, w: Float, h: Float,
        value: Float, tint: Color, label: String
    ): Float {
        var v = MathX.clamp01(value)
        val pad = w * 0.9f
        touch.pressedIn(x - pad, y - pad, w + pad * 2, h + pad * 2)?.let { p ->
            touch.claim(p, id)
        }
        touch.ownedBy(id)?.let { p ->
            v = MathX.clamp01((p.y - y) / h)
        }

        c.set(Palette.ink); c.a = 0.8f
        g.rect(x, y, w, h, c)
        c2.set(tint)
        c.set(tint); c.r *= 0.4f; c.g *= 0.4f; c.b *= 0.4f
        g.rectV(x, y, w, h * v, c, c2)
        c.set(Palette.panelEdge); c.a = 0.5f
        g.outline(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h), 4, 1.2f, c)

        // Grip.
        val gy = y + h * v
        c.set(tint); c.a = 1f
        g.rect(x - w * 0.35f, gy - w * 0.28f, w * 1.7f, w * 0.56f, c)

        g.textCentered(art.small, label, x + w * 0.5f, y - art.small.lineHeight * 0.4f, Palette.textDim)
        return v
    }

    /**
     * Two-axis pad. The stick is anchored wherever the thumb first lands inside the zone,
     * which is what lets a player put their hand down without looking.
     */
    class PadResult {
        @JvmField var x = 0f
        @JvmField var y = 0f
        @JvmField var active = false
        @JvmField var originX = 0f
        @JvmField var originY = 0f
    }

    private val padResult = PadResult()

    fun pad(
        g: Painter, id: Int,
        zoneX: Float, zoneY: Float, zoneW: Float, zoneH: Float,
        radius: Float, tint: Color
    ): PadResult {
        padResult.active = false
        padResult.x = 0f
        padResult.y = 0f

        touch.pressedIn(zoneX, zoneY, zoneW, zoneH)?.let { p ->
            if (touch.claim(p, id)) {
                padResult.originX = p.startX
                padResult.originY = p.startY
            }
        }
        val p = touch.ownedBy(id)
        if (p != null) {
            padResult.active = true
            padResult.originX = p.startX
            padResult.originY = p.startY
            padResult.x = MathX.clamp((p.x - p.startX) / radius, -1f, 1f)
            padResult.y = MathX.clamp((p.y - p.startY) / radius, -1f, 1f)

            val ox = p.startX
            val oy = p.startY
            c.set(tint); c.a = 0.16f
            g.circle(ox, oy, radius, 26, c)
            c.a = 0.55f
            g.ringLine(ox, oy, radius, 2f, 26, c)
            // Axis guides: the player can see which way is trim and which way is weight.
            c.a = 0.22f
            g.line(ox - radius, oy, ox + radius, oy, 1.4f, c)
            g.line(ox, oy - radius, ox, oy + radius, 1.4f, c)

            c.set(tint); c.a = 0.95f
            g.circle(ox + padResult.x * radius, oy + padResult.y * radius, radius * 0.3f, 18, c)
        }
        return padResult
    }

    /**
     * A short-lived callout across the middle of the screen.
     *
     * A soft horizontal scrim rather than a panel: a hard box over the sea during a countdown
     * hides the very thing the countdown exists to let you read.
     */
    fun banner(
        g: Painter, text: String, sub: String?, cx: Float, cy: Float,
        tint: Color, alpha: Float
    ) {
        if (alpha <= 0.01f) return
        val w = maxOf(g.textWidth(art.title, text), if (sub != null) g.textWidth(art.hud, sub) else 0f)
        val h = art.title.lineHeight * (if (sub != null) 2.0f else 1.35f)
        val span = w * 0.5f + h * 3.2f

        c.set(Palette.ink); c.a = 0f
        c2.set(Palette.ink); c2.a = 0.55f * alpha
        g.rect(cx - span, cy - h * 0.5f, span, h, c, c2, c2, c)
        g.rect(cx, cy - h * 0.5f, span, h, c2, c, c, c2)

        c.set(tint); c.a = 0.55f * alpha
        g.lineGradient(cx - span, cy - h * 0.5f, cx, cy - h * 0.5f, 1.5f, Palette.alpha(tint, 0f), c)
        g.lineGradient(cx, cy - h * 0.5f, cx + span, cy - h * 0.5f, 1.5f, c, Palette.alpha(tint, 0f))

        c.set(tint); c.a = alpha
        g.textCentered(
            art.title, text, cx,
            cy + (if (sub != null) art.title.capHeight * 0.8f else art.title.capHeight * 0.5f), c
        )
        if (sub != null) {
            c.set(Palette.textDim); c.a = alpha
            g.textCentered(art.hud, sub, cx, cy - art.hud.lineHeight * 0.5f, c)
        }
    }
}
