package com.polariz.aethertides.client.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.polariz.aethertides.client.Art
import com.polariz.aethertides.client.Ease
import com.polariz.aethertides.client.Icons
import com.polariz.aethertides.client.Painter
import com.polariz.aethertides.client.Palette
import com.polariz.aethertides.shared.math.MathX
import kotlin.math.cos
import kotlin.math.sin

/**
 * The HUD toolkit.
 *
 * Immediate mode: no widget tree, no layout pass, no retained state beyond a little animation
 * per id. For a game HUD that is entirely regenerated every frame from simulation state, a
 * retained UI framework is all cost and no benefit.
 *
 * Everything is sized in *scaled pixels* -- multiply by [Art.uiScale] once at the call site --
 * so a 6 inch phone and a 13 inch tablet both get thumb-sized controls.
 *
 * Two rules hold the look together, and both were learned the hard way here:
 *
 * **Measure, never guess.** Vertical rhythm comes from the font's real line height, asked for
 * at draw time. Hand-picked pixel offsets look fine at the size they were picked at and
 * collide at every other one -- which is precisely what every panel in this game used to do.
 *
 * **Nothing appears.** Every arrival is eased in over 120-350 ms and every departure eased
 * out. A thing that pops into existence reads as a bug even when it is not.
 */
class Widgets(private val art: Art, private val touch: Touch) {

    private val press = HashMap<Int, Float>()
    private val c = Color()
    private val c2 = Color()

    /** Free-running seconds, for breathing and ready-state pulses. */
    private var clock = 0f

    // Banner entry animation, keyed on the text so a new callout replays it.
    private var bannerKey: String? = null
    private var bannerAge = 9f

    fun update(dt: Float) {
        clock += dt
        bannerAge += dt
        val it = press.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = MathX.approach(e.value, 0f, 7.5f, dt)
            if (v < 0.004f) it.remove() else e.setValue(v)
        }
    }

    private fun pressLevel(id: Int): Float = press[id] ?: 0f

    private fun kick(id: Int) {
        press[id] = 1f
    }

    // -----------------------------------------------------------------------
    // Rhythm
    // -----------------------------------------------------------------------

    /**
     * The pitch of a stacked row of text in [font], measured.
     *
     * Everything that lists things vertically goes through this, so a panel written today and
     * a panel written next month breathe at the same rate.
     */
    fun rowPitch(font: BitmapFont, tightness: Float = 1.12f): Float = font.lineHeight * tightness

    /** Baseline for text vertically centred in a box of height [h] whose bottom is at [y]. */
    fun midlineY(font: BitmapFont, y: Float, h: Float): Float =
        y + h * 0.5f + font.capHeight * 0.5f

    /** Height a panel needs to hold [rows] rows of [font] plus the standard insets. */
    fun panelHeight(font: BitmapFont, rows: Int, extra: Float = 0f): Float =
        art.unit * 2.6f + rows * rowPitch(font) + extra

    // -----------------------------------------------------------------------
    // Chrome
    // -----------------------------------------------------------------------

    /**
     * The standard smoked-glass panel.
     *
     * A graded body so it sits into the scene rather than on it, one bright rule along the top
     * edge in the owning colour, and a hairline everywhere else. The top rule is what makes a
     * dozen separately authored panels read as one system.
     */
    fun panel(
        g: Painter, x: Float, y: Float, w: Float, h: Float,
        alpha: Float = 1f, tint: Color = Palette.panelEdge
    ) {
        if (alpha <= 0.004f) return
        // Shadow: the panel has to lift off bright foam as well as dark water.
        c.set(Palette.ink); c.a = 0f
        c2.set(Palette.ink); c2.a = 0.34f * alpha
        val drop = art.unit * 0.9f
        g.rect(x, y - drop, w, drop, c, c, c2, c2)

        c.set(Palette.panel)
        c.a *= alpha
        c2.set(Palette.ink)
        c2.a = 0.88f * alpha
        g.rectV(x, y, w, h, c2, c)

        // Top rule, in the owning colour, plus the hairline it throws downward.
        c.set(tint); c.a = 0.80f * alpha
        g.rect(x, y + h - art.uiScale * 1.8f, w, art.uiScale * 1.8f, c)
        c.a = 0.10f * alpha
        g.rect(x, y + h - art.unit * 1.4f, w, art.unit * 1.4f - art.uiScale * 1.8f, c)

        c.set(tint); c.a = 0.22f * alpha
        val hair = art.uiScale * 1.1f
        g.rect(x, y, w, hair, c)
        g.rect(x, y, hair, h, c)
        g.rect(x + w - hair, y, hair, h, c)
    }

    /** A hairline divider. Fades at both ends so it never looks like a table border. */
    fun rule(g: Painter, x: Float, y: Float, w: Float, tint: Color = Palette.panelEdge, alpha: Float = 0.4f) {
        c.set(tint); c.a = alpha
        c2.set(tint); c2.a = 0f
        g.rect(x, y, w * 0.5f, art.uiScale, c2, c, c, c2)
        g.rect(x + w * 0.5f, y, w * 0.5f, art.uiScale, c, c2, c2, c)
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
        c.set(Palette.ink); c.a = 0.78f
        g.rect(x, y, w, h, c)

        val low = warn > 0f && v < warn
        c2.set(if (low) Palette.danger else tint)
        if (low) {
            val breathe = 0.66f + 0.34f * Ease.breathe(pulse, 1.15f, 1f)
            c2.r *= breathe; c2.g *= breathe; c2.b *= breathe
        }
        // Fill, with a brighter cap so the bar has a readable leading edge.
        c.set(c2); c.r *= 0.55f; c.g *= 0.55f; c.b *= 0.55f
        g.rectV(x, y, w * v, h, c, c2)
        if (v > 0.01f) {
            c.set(Color.WHITE); c.a = 0.42f
            g.rect(x + w * v - art.uiScale * 1.6f, y, art.uiScale * 1.6f, h, c)
        }

        c.set(Palette.panelEdge); c.a = 0.45f
        g.rect(x, y, w, art.uiScale, c)
        g.rect(x, y + h - art.uiScale, w, art.uiScale, c)

        if (label != null) {
            g.text(art.small, label, x, y + h + art.small.lineHeight, Palette.textDim)
        }
    }

    /**
     * Label on the left, meter on the right, one shared midline, optional readout after it.
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
        val textY = midlineY(font, barY, barH)
        g.text(font, label, x, textY, Palette.textDim)
        bar(g, x + labelW, barY, barW, barH, value, tint, null, warn, pulse)
        if (readout != null) {
            g.text(font, readout, x + labelW + barW + art.unit, textY, readoutTint)
        }
    }

    /** Segmented progress, used for the voyage leagues. */
    fun segments(
        g: Painter, x: Float, y: Float, w: Float, h: Float,
        count: Int, progress: Float, tint: Color
    ) {
        val gap = maxOf(art.uiScale * 3f, h * 0.42f)
        val seg = (w - gap * (count - 1)) / count
        for (i in 0 until count) {
            val sx = x + i * (seg + gap)
            val from = i / count.toFloat()
            val fill = MathX.clamp01((progress - from) * count)
            c.set(Palette.ink); c.a = 0.72f
            g.rect(sx, y, seg, h, c)
            if (fill > 0f) {
                c2.set(tint)
                c.set(tint); c.r *= 0.5f; c.g *= 0.5f; c.b *= 0.5f
                g.rectV(sx, y, seg * fill, h, c, c2)
                // The league actually being sailed gets a live leading edge.
                if (fill < 0.999f) {
                    c.set(Color.WHITE)
                    c.a = 0.35f + 0.35f * Ease.breathe(clock, 0.9f, 1f)
                    g.rect(sx + seg * fill - art.uiScale * 1.6f, y, art.uiScale * 1.6f, h, c)
                }
            }
            c.set(Palette.panelEdge); c.a = 0.4f
            g.rect(sx, y, seg, art.uiScale, c)
        }
    }

    /** A small cost badge. Dark chip, tinted rim, number centred. */
    fun chip(g: Painter, cx: Float, cy: Float, text: String, tint: Color, alpha: Float = 1f) {
        val pad = art.unit * 0.55f
        val tw = g.textWidth(art.small, text)
        val w = tw + pad * 2f
        val h = art.small.capHeight + pad * 1.7f
        c.set(Palette.ink); c.a = 0.9f * alpha
        g.rect(cx - w * 0.5f, cy - h * 0.5f, w, h, c)
        c.set(tint); c.a = 0.55f * alpha
        g.rect(cx - w * 0.5f, cy - h * 0.5f, w, art.uiScale, c)
        g.rect(cx - w * 0.5f, cy + h * 0.5f - art.uiScale, w, art.uiScale, c)
        c.set(Palette.textDim); c.a = alpha
        g.textCentered(art.small, text, cx, cy + art.small.capHeight * 0.5f, c)
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
            touch.pressedIn(id, x, y, w, h)?.let { p ->
                if (touch.claim(p, id)) kick(id)
            }
            val owned = touch.ownedBy(id)
            if (owned != null) {
                held = owned.x >= x && owned.x <= x + w && owned.y >= y && owned.y <= y + h
                if (owned.justUp && held) fired = true
            }
        }

        val raw = pressLevel(id)
        val lvl = raw * 0.55f + (if (held) 0.45f else 0f)
        // Held presses sink; the release then springs back out past rest.
        val sink = (if (held) 1f else Ease.outBack(1f - raw, 1.1f) * raw) * h * 0.045f
        val ix = x + sink
        val iy = y + sink
        val iw = w - sink * 2f
        val ih = h - sink * 2f

        c.set(Palette.panel); c.a = if (enabled) 0.95f else 0.5f
        c2.set(tint); c2.r *= 0.20f; c2.g *= 0.20f; c2.b *= 0.20f
        c2.a = if (enabled) 0.95f else 0.4f
        g.rectV(ix, iy, iw, ih, c, c2)

        // A wash of the tint that blooms from the press and drains away.
        if (enabled && raw > 0.01f) {
            c.set(tint); c.a = 0.16f * raw
            g.rect(ix, iy, iw, ih, c)
        }

        // The left edge carries the colour: a keyline, thicker while held.
        c.set(tint); c.a = if (enabled) 0.9f else 0.3f
        g.rect(ix, iy, art.uiScale * (2.4f + lvl * 2.2f), ih, c)

        c.a = if (enabled) 0.55f + 0.35f * lvl else 0.22f
        g.outline(
            floatArrayOf(ix, iy, ix + iw, iy, ix + iw, iy + ih, ix, iy + ih), 4,
            art.uiScale * (1.3f + lvl * 1.2f), c
        )

        val textColor = if (enabled) Palette.textBright else Palette.textFaint
        g.textCentered(font, label, ix + iw * 0.5f, midlineY(font, iy, ih), textColor)
        return fired
    }

    /**
     * Round action button with a radial cooldown sweep.
     *
     * The symbol is drawn, not typed -- see [Icons] for why. The cost rides as a badge on the
     * rim rather than sitting inside the disc, where it used to land on top of the caption.
     *
     * @param cooldown 0 ready, 1 just used
     * @param affordable whether the resource cost can currently be paid
     */
    fun actionButton(
        g: Painter, id: Int, cx: Float, cy: Float, r: Float,
        icon: Int, label: String, tint: Color,
        cooldown: Float, affordable: Boolean, selected: Boolean,
        locked: Boolean = false, cost: String? = null
    ): Boolean {
        val ready = cooldown <= 0.001f && affordable && !locked
        var fired = false
        var held = false

        touch.pressedIn(id, cx - r, cy - r, r * 2, r * 2)?.let { p ->
            if (touch.claim(p, id)) kick(id)
        }
        touch.ownedBy(id)?.let { p ->
            held = MathX.dist(p.x, p.y, cx, cy) <= r * 1.25f
            if (p.justUp && held) fired = true
        }

        val lvl = pressLevel(id)
        // Press springs rather than steps: out-back on the way back to rest.
        val rr = r * (1f + Ease.outBack(lvl, 2.2f) * 0.055f) * (if (selected) 1.07f else 1f)

        // A ready control breathes, so the eye is drawn to what can actually be used.
        val idle = if (ready && !selected) Ease.breathe(clock, 0.42f, 1f) else 0f

        // Body.
        c.set(Palette.ink); c.a = 0.92f
        g.circle(cx, cy, rr, 24, c)
        c.set(tint)
        c.r *= 0.26f; c.g *= 0.26f; c.b *= 0.26f
        c.a = if (ready) 0.95f else 0.5f
        g.circle(cx, cy, rr * 0.93f, 24, c)

        g.additive(true)
        // Selection halo, and a softer one for anything merely ready.
        if (selected) g.glow(cx, cy, rr * 2.2f, Palette.alpha(tint, 0.34f))
        else if (ready) g.glow(cx, cy, rr * 1.6f, Palette.alpha(tint, 0.05f + 0.06f * idle))
        // The press throws a ring outward.
        if (lvl > 0.01f) {
            g.glow(cx, cy, rr * (1.3f + (1f - lvl) * 1.3f), Palette.alpha(tint, 0.42f * lvl))
        }
        g.additive(false)

        // Rim, brighter when usable.
        c.set(tint)
        c.a = if (ready) 0.82f + 0.18f * idle else 0.32f
        g.ringLine(cx, cy, rr, art.uiScale * (2.1f + lvl * 2.4f), 28, c)

        // Cooldown: a dark wedge that unwinds, with a bright arc tracking its edge.
        if (cooldown > 0.001f) {
            val cd = MathX.clamp01(cooldown)
            c.set(Palette.ink); c.a = 0.7f
            val segs = 28
            val filled = (segs * cd).toInt()
            for (i in 0 until filled) {
                val a0 = MathX.PI * 0.5f + MathX.TAU * i / segs
                val a1 = MathX.PI * 0.5f + MathX.TAU * (i + 1) / segs
                g.tri(
                    cx, cy, c,
                    cx + cos(a0) * rr, cy + sin(a0) * rr, c,
                    cx + cos(a1) * rr, cy + sin(a1) * rr, c
                )
            }
            c.set(tint); c.a = 0.75f
            val edge = MathX.PI * 0.5f + MathX.TAU * cd
            g.line(cx, cy, cx + cos(edge) * rr, cy + sin(edge) * rr, art.uiScale * 1.4f, c)
        }

        // Symbol.
        val glyphColor = when {
            locked -> Palette.danger
            ready -> Palette.textBright
            else -> Palette.textFaint
        }
        c2.set(glyphColor)
        if (ready) {
            c2.lerp(tint, 0.18f)
            c2.a = 1f
        } else {
            c2.a = 0.85f
        }
        Icons.draw(g, icon, cx, cy, rr * 0.50f, c2)

        if (label.isNotEmpty()) {
            g.textCentered(
                art.small, label, cx, cy - rr - art.unit * 0.45f,
                if (ready) Palette.textDim else Palette.textFaint
            )
        }

        // Cost badge, hung on the upper-right of the rim.
        if (cost != null) {
            chip(
                g, cx + rr * 0.80f, cy + rr * 0.76f, cost,
                if (affordable) tint else Palette.danger, if (ready) 1f else 0.75f
            )
        }

        if (locked) {
            c.set(Palette.danger); c.a = 0.9f
            g.line(cx - rr * 0.62f, cy - rr * 0.62f, cx + rr * 0.62f, cy + rr * 0.62f, art.uiScale * 2.6f, c)
            g.line(cx - rr * 0.62f, cy + rr * 0.62f, cx + rr * 0.62f, cy - rr * 0.62f, art.uiScale * 2.6f, c)
        } else if (!affordable) {
            c.set(Palette.danger); c.a = 0.45f
            g.ringLine(cx, cy, rr * 0.84f, art.uiScale * 1.3f, 22, c)
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
        touch.pressedIn(id, x - pad, y - pad, w + pad * 2, h + pad * 2)?.let { p ->
            touch.claim(p, id)
        }
        val owned = touch.ownedBy(id)
        if (owned != null) {
            v = MathX.clamp01((owned.y - y) / h)
        }

        c.set(Palette.ink); c.a = 0.82f
        g.rect(x, y, w, h, c)
        c2.set(tint)
        c.set(tint); c.r *= 0.35f; c.g *= 0.35f; c.b *= 0.35f
        g.rectV(x, y, w, h * v, c, c2)

        // Tick marks, so the dial is a scale rather than a mystery slot.
        c.set(Palette.panelEdge); c.a = 0.5f
        for (i in 0..4) {
            val ty = y + h * i / 4f
            g.rect(x - w * 0.32f, ty - art.uiScale * 0.5f, w * 0.32f, art.uiScale, c)
        }
        g.outline(floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h), 4, art.uiScale * 1.1f, c)

        // Grip.
        val gy = y + h * v
        c.set(tint); c.a = 1f
        g.rect(x - w * 0.34f, gy - w * 0.30f, w * 1.68f, w * 0.60f, c)
        if (owned != null) {
            g.additive(true)
            g.glow(x + w * 0.5f, gy, w * 2.2f, Palette.alpha(tint, 0.45f))
            g.additive(false)
        }

        // Caption below the track, clear of the grip's travel.
        g.textCentered(
            art.small, label, x + w * 0.5f,
            y - art.unit * 0.5f, Palette.textDim
        )
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

        touch.pressedIn(id, zoneX, zoneY, zoneW, zoneH)?.let { p ->
            if (touch.claim(p, id)) {
                padResult.originX = p.startX
                padResult.originY = p.startY
                kick(id)
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
            // The ring grows into place rather than appearing under the thumb.
            val born = Ease.outExpo(MathX.clamp01(p.age * 6.5f))
            val rad = radius * (0.82f + 0.18f * born)

            c.set(tint); c.a = 0.14f * born
            g.circle(ox, oy, rad, 28, c)
            c.a = 0.5f * born
            g.ringLine(ox, oy, rad, art.uiScale * 1.9f, 28, c)
            // Axis guides: the player can see which way is trim and which way is weight.
            c.a = 0.18f * born
            g.line(ox - rad, oy, ox + rad, oy, art.uiScale * 1.3f, c)
            g.line(ox, oy - rad, ox, oy + rad, art.uiScale * 1.3f, c)

            // The stick, with a leash back to the anchor so the offset is legible.
            val sx = ox + padResult.x * radius
            val sy = oy + padResult.y * radius
            c.set(tint); c.a = 0.35f * born
            g.line(ox, oy, sx, sy, art.uiScale * 2.2f, c)
            g.additive(true)
            g.glow(sx, sy, radius * 0.55f, Palette.alpha(tint, 0.35f * born))
            g.additive(false)
            c.set(tint); c.a = 0.95f * born
            g.circle(sx, sy, radius * 0.29f, 20, c)
            c.set(Palette.ink); c.a = 0.5f * born
            g.circle(sx, sy, radius * 0.13f, 14, c)
        }
        return padResult
    }

    // -----------------------------------------------------------------------
    // Callouts
    // -----------------------------------------------------------------------

    /**
     * A short-lived callout across the middle of the screen.
     *
     * A soft horizontal scrim rather than a panel: a hard box over the sea during a countdown
     * hides the very thing the countdown exists to let you read.
     *
     * The entry is animated here rather than by the caller. The play screen holds a plain
     * countdown timer and knows nothing about motion; keeping the animation with the drawing
     * means every banner in the game arrives the same way, and a new line of text replays it.
     */
    fun banner(
        g: Painter, text: String, sub: String?, cx: Float, cy: Float,
        tint: Color, alpha: Float
    ) {
        if (alpha <= 0.01f) return
        if (text != bannerKey) {
            bannerKey = text
            bannerAge = 0f
        }
        // 220 ms arrival: rises, widens and settles.
        val t = Ease.outExpo(MathX.clamp01(bannerAge / 0.22f))
        val a = alpha * t
        val rise = (1f - t) * art.unit * 2.2f
        val y = cy - rise

        val w = maxOf(g.textWidth(art.title, text), if (sub != null) g.textWidth(art.hud, sub) else 0f)
        val h = art.title.lineHeight * (if (sub != null) 2.0f else 1.35f)
        val span = (w * 0.5f + h * 3.2f) * (0.72f + 0.28f * t)

        c.set(Palette.ink); c.a = 0f
        c2.set(Palette.ink); c2.a = 0.6f * a
        g.rect(cx - span, y - h * 0.5f, span, h, c, c2, c2, c)
        g.rect(cx, y - h * 0.5f, span, h, c2, c, c, c2)

        // The rule under the text sweeps out from the centre as it arrives.
        c.set(tint); c.a = 0.6f * a
        g.lineGradient(cx - span, y - h * 0.5f, cx, y - h * 0.5f, art.uiScale * 1.4f, Palette.alpha(tint, 0f), c)
        g.lineGradient(cx, y - h * 0.5f, cx + span, y - h * 0.5f, art.uiScale * 1.4f, c, Palette.alpha(tint, 0f))

        g.additive(true)
        g.glow(cx, y, span * 0.55f, Palette.alpha(tint, 0.10f * a))
        g.additive(false)

        c.set(tint); c.a = a
        g.textCentered(
            art.title, text, cx,
            y + (if (sub != null) art.title.capHeight * 0.85f else art.title.capHeight * 0.5f), c
        )
        if (sub != null) {
            c.set(Palette.textDim); c.a = a * 0.95f
            g.textCentered(art.hud, sub, cx, y - art.hud.lineHeight * 0.45f, c)
        }
    }

    /**
     * The countdown, which is the first thing a player ever sees of a match and so is the one
     * callout that gets to be enormous.
     *
     * The numeral is drawn from the display face scaled well past its raster size -- acceptable
     * because it is a single glyph with a linear filter and a mip chain behind it -- and each
     * new number fires a ring that expands and fades. The eye counts the rings, not the digits.
     */
    fun countdown(
        g: Painter, text: String, sub: String?, cx: Float, cy: Float,
        tint: Color, alpha: Float
    ) {
        if (alpha <= 0.01f) return
        if (text != bannerKey) {
            bannerKey = text
            bannerAge = 0f
        }
        val beat = MathX.clamp01(bannerAge / 0.55f)
        // The numeral lands hard and settles: big overshoot, fast decay.
        val scale = 3.0f * (1f + (1f - Ease.outExpo(MathX.clamp01(bannerAge / 0.30f))) * 0.30f)
        val a = alpha

        val font = art.title
        font.data.setScale(scale)
        val capH = font.capHeight
        val tw = g.textWidth(font, text)

        // Scrim, sized to the numeral.
        c.set(Palette.ink); c.a = 0f
        c2.set(Palette.ink); c2.a = 0.5f * a
        val span = tw * 0.5f + capH * 2.6f
        val bandH = capH * 2.1f
        g.rect(cx - span, cy - bandH * 0.5f, span, bandH, c, c2, c2, c)
        g.rect(cx, cy - bandH * 0.5f, span, bandH, c2, c, c, c2)

        // The ring each beat throws off.
        g.additive(true)
        if (beat < 1f) {
            val ringR = capH * (0.75f + Ease.outQuint(beat) * 2.3f)
            c.set(tint); c.a = (1f - beat) * 0.5f * a
            g.ring(cx, cy, ringR, c)
        }
        g.glow(cx, cy, capH * 1.9f, Palette.alpha(tint, 0.16f * a))
        g.additive(false)

        c.set(tint); c.a = a
        g.textCentered(font, text, cx, cy + capH * 0.5f, c)
        font.data.setScale(1f)

        if (sub != null) {
            c.set(Palette.textDim); c.a = a * 0.9f
            g.textCentered(art.hud, sub, cx, cy - bandH * 0.5f - art.unit * 0.4f, c)
        }
    }
}
