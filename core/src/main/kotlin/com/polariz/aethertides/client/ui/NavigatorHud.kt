package com.polariz.aethertides.client.ui

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.client.Art
import com.polariz.aethertides.client.Cam
import com.polariz.aethertides.client.Icons
import com.polariz.aethertides.client.Painter
import com.polariz.aethertides.client.Palette
import com.polariz.aethertides.client.SeaRenderer
import com.polariz.aethertides.client.net.Session
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.net.SnapshotFrame
import com.polariz.aethertides.shared.sim.Command
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.Spells
import kotlin.math.abs

/**
 * The Navigator's deck.
 *
 * The design constraint is a phone held in two hands: the left thumb never leaves the helm,
 * the right thumb does everything else. So the helm is a floating pad anchored wherever the
 * left thumb lands, the four workings sit under the right thumb, and casting is a tap on the
 * water itself rather than a drag from a button -- you point at the sea you want changed.
 *
 * Everything that can kill you is shown as a bar you can read without looking directly at it:
 * hull, rig, and the water coming aboard.
 */
class NavigatorHud(
    private val art: Art,
    private val w: Widgets,
    private val touch: Touch
) {

    private companion object {
        /** Must match the gate in World.castSpell; the HUD only predicts what the sim will accept. */
        const val CAST_RANGE = 120f
    }

    private object Id {
        const val HELM = 100
        const val SPELL0 = 110
        const val BRACE = 120
        const val PUMP = 121
        const val CAST = 130
    }

    var selectedSpell = SpellKind.GALE
        private set

    private var trim = 0.7f
    private var lean = 0f
    private var pump = false
    private var braceRequested = false

    private var warnTimer = 0f
    private var lastHull = Config.MAX_HULL
    private var damageFlash = 0f
    private var time = 0f

    private val c = Color()
    private val c2 = Color()

    /** Set by the play screen so the cast tap knows where the water is. */
    var castHintX = 0f
        private set
    var castHintY = 0f
        private set
    var castHintValid = false
        private set

    fun update(dt: Float, f: SnapshotFrame) {
        time += dt
        if (warnTimer > 0f) warnTimer -= dt
        if (f.hull < lastHull - 0.6f) damageFlash = 1f
        lastHull = f.hull
        damageFlash = MathX.approach(damageFlash, 0f, 3.2f, dt)
    }

    fun helmTrim(): Float = trim
    fun helmLean(): Float = lean
    fun helmPump(): Boolean = pump
    fun takeBrace(): Boolean {
        val b = braceRequested
        braceRequested = false
        return b
    }

    // -----------------------------------------------------------------------

    fun draw(
        g: Painter, f: SnapshotFrame, session: Session, cam: Cam,
        screenW: Float, screenH: Float
    ) {
        val s = art.uiScale
        val playing = f.phase == MatchPhase.SAILING

        drawCastLayerHitTest(f, session, cam, screenW, screenH, playing)
        drawVitals(g, f, screenW, screenH, s)
        drawVoyage(g, f, screenW, screenH, s)
        drawConditions(g, f, session, screenW, screenH, s)
        drawHelm(g, f, screenW, screenH, s, playing)
        drawWorkings(g, f, screenW, screenH, s, playing)
        drawWarnings(g, f, screenW, screenH, s)
        drawDamageVignette(g, f, screenW, screenH)
    }

    // --- casting -----------------------------------------------------------

    /**
     * A tap on open water casts the selected working there.
     *
     * The whole screen is live except the strips the controls occupy, so the player can aim at
     * anything they can see. Out-of-range taps are rejected with a visible marker rather than
     * silently eaten -- being told why is worth a lot when you are learning the range.
     */
    private fun drawCastLayerHitTest(
        f: SnapshotFrame, session: Session, cam: Cam,
        screenW: Float, screenH: Float, playing: Boolean
    ) {
        castHintValid = false
        if (!playing) return
        val s = art.uiScale
        val controlsBottom = 190f * s
        val helmZoneW = screenW * 0.36f

        val p = touch.pressedIn(Id.CAST, 0f, 0f, screenW, screenH) ?: return
        // Do not steal taps that belong to the helm zone or the button strip.
        if (p.x < helmZoneW) return
        if (p.y < controlsBottom && p.x > screenW - 520f * s) return
        if (!touch.claim(p, Id.CAST)) return

        val wx = cam.unprojectX(p.x)
        val wy = cam.unprojectY(screenH - p.y)
        castHintX = wx
        castHintY = wy

        val def = Spells[selectedSpell]
        val inRange = abs(wx - f.shipX) <= CAST_RANGE
        val ready = f.spellCooldown[selectedSpell.id] <= 0.001f
        val afford = f.aether >= def.cost
        val snared = f.snaredSpell == selectedSpell.id

        castHintValid = inRange && ready && afford && !snared
        if (castHintValid) {
            session.sendCommand(Command.CAST_SPELL, selectedSpell.id, wx, wy)
        } else {
            warnTimer = 1.1f
        }
    }

    /** Drawn in world space by the play screen, so it lands on the water not over the HUD. */
    fun drawCastMarker(g: Painter, f: SnapshotFrame, cam: Cam, sea: SeaRenderer) {
        val def = Spells[selectedSpell]
        // The reach: where the aether can actually be shaped from here.
        //
        // This was a single straight line 240 m long at the ship's height. It cut clean across
        // every crest and hung in the air over every trough, and at 12% alpha it did not read
        // as a control at all -- it read as a seam in the sea. Two posts on the water at the
        // ends of the reach say the same thing and belong to the scene.
        val breathe = 0.6f + 0.4f * kotlin.math.sin(time * 2.2f)
        for (side in -1..1 step 2) {
            val px = f.shipX + side * CAST_RANGE
            if (px < cam.left - 2f || px > cam.right + 2f) continue
            val py = sea.surfaceYAt(px)
            c.set(Palette.aetherBar); c.a = 0.35f * breathe
            c2.set(Palette.aetherBar); c2.a = 0f
            g.lineGradient(px, py - 1f, px, py + 5f, 0.22f, c, c2)
            c.a = 0.55f * breathe
            g.circle(px, py + 0.4f, 0.4f, 10, c)
        }

        if (warnTimer > 0f) {
            c.set(Palette.danger)
            c.a = MathX.clamp01(warnTimer) * 0.8f
            g.ringLine(castHintX, castHintY, def.radius * 0.5f, 0.35f, 20, c)
            g.line(castHintX - 2f, castHintY - 2f, castHintX + 2f, castHintY + 2f, 0.3f, c)
            g.line(castHintX - 2f, castHintY + 2f, castHintX + 2f, castHintY - 2f, 0.3f, c)
        }
    }

    // --- panels ------------------------------------------------------------
    //
    // Every panel here sizes itself from the metrics of the fonts it is about to draw with.
    // The previous version stepped its rows by hand-picked scaled pixels -- 26, then 24, then
    // 24 -- which are smaller than the real line height of the label font at *every* ui scale,
    // so every label sat on the bar above it and the speed readout hung out of the bottom of
    // its own panel. Measuring costs one GlyphLayout and removes the entire class of bug.

    private fun drawVitals(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        val u = art.unit
        val inset = u * 1.7f
        val pw = art.px(300f)
        val barH = art.px(13f)
        val pitch = maxOf(barH + u * 0.95f, art.small.lineHeight * 1.04f)
        val headH = art.hudLarge.capHeight + u * 1.5f
        val ph = inset * 2f + headH + u * 1.6f + pitch * 4f
        val x = u * 1.8f
        val y = sh - ph - u * 1.8f
        w.panel(g, x, y, pw, ph, tint = Palette.aetherBar)

        var top = y + ph - inset

        // Speed leads the panel. It is the number the whole role is about, so it is the first
        // thing the eye lands on in the first thing it reads.
        val knots = abs(f.shipVx) / 0.5144f
        val kStr = "%.0f".format(knots)
        val headMid = top - headH * 0.5f
        g.text(art.hudLarge, kStr, x + inset, headMid + art.hudLarge.capHeight * 0.5f, Palette.textBright)
        g.text(
            art.small, "KNOTS",
            x + inset + g.textWidth(art.hudLarge, kStr) + u * 0.7f,
            headMid + art.small.capHeight * 0.5f, Palette.textDim
        )
        if (f.surf > 0.35f) {
            c.set(Palette.tsunami); c.a = MathX.clamp01(f.surf)
            g.textRight(art.hud, "SURFING", x + pw - inset, headMid + art.hud.capHeight * 0.5f, c)
        } else if (f.airborne) {
            g.textRight(art.hud, "AIRBORNE", x + pw - inset, headMid + art.hud.capHeight * 0.5f, Palette.aetherBar)
        }
        top -= headH + u * 0.8f
        w.rule(g, x + inset, top, pw - inset * 2f, Palette.aetherBar, 0.3f)
        top -= u * 0.8f

        // One label column and one bar column, both measured, so the four meters line up and
        // all end on the same edge instead of the ragged 0.68-width stumps they used to be.
        val labelW = maxOf(
            g.textWidth(art.small, "HULL"), g.textWidth(art.small, "RIG"),
            g.textWidth(art.small, "WATER"), g.textWidth(art.small, "AETHER")
        ) + u * 1.1f
        val readoutW = g.textWidth(art.small, "88x") + u
        val barW = pw - inset * 2f - labelW - readoutW

        fun meter(label: String, v: Float, tint: Color, warn: Float = -1f, readout: String? = null,
                  readoutTint: Color = Palette.danger) {
            val barY = top - pitch + (pitch - barH) * 0.5f
            w.meterRow(
                g, art.small, label, x + inset, barY, labelW, barW, barH,
                v, tint, warn, time, readout, readoutTint
            )
            top -= pitch
        }

        meter("HULL", f.hull / Config.MAX_HULL, Palette.hullBar, warn = 0.3f)
        meter("RIG", f.mast / Config.MAX_MAST, Palette.mastBar)
        // Bilge reads backwards on purpose: a full bar is a sinking ship.
        meter(
            "WATER", f.bilge / Config.MAX_BILGE, Palette.bilgeBar,
            readout = if (f.leaks > 0) "${f.leaks}x" else null
        )
        meter("AETHER", f.aether / Config.MAX_AETHER, Palette.aetherBar)
    }

    private fun drawVoyage(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        val u = art.unit
        val inset = u * 1.8f
        val pw = art.px(360f)
        val segH = art.px(11f)
        val rowH = art.hud.lineHeight
        val ph = inset * 2f + rowH + u * 0.9f + segH
        val x = (sw - pw) * 0.5f
        val y = sh - ph - u * 1.8f
        w.panel(g, x, y, pw, ph, tint = Palette.accent)

        val progress = MathX.clamp01(f.shipX / Config.COURSE_LENGTH)
        val league = MathX.clampI((progress * Config.LEAGUES).toInt() + 1, 1, Config.LEAGUES)

        val rowY = y + ph - inset - rowH
        val mid = rowY + rowH * 0.5f
        g.text(
            art.small, "LEAGUE %d OF %d".format(league, Config.LEAGUES),
            x + inset, mid + art.small.capHeight * 0.5f, Palette.textDim
        )
        val remaining = maxOf(0f, Config.MATCH_TIME_LIMIT - f.time)
        val urgent = remaining < 45f
        c.set(if (urgent) Palette.danger else Palette.textBright)
        if (urgent) c.a = 0.7f + 0.3f * kotlin.math.sin(time * 6f)
        g.textRight(
            art.hud, "%d:%02d".format((remaining / 60f).toInt(), (remaining % 60f).toInt()),
            x + pw - inset, mid + art.hud.capHeight * 0.5f, c
        )

        w.segments(g, x + inset, y + inset, pw - inset * 2f, segH, Config.LEAGUES, progress, Palette.accent)
    }

    private fun drawConditions(
        g: Painter, f: SnapshotFrame, session: Session, sw: Float, sh: Float, s: Float
    ) {
        val u = art.unit
        val inset = u * 1.7f
        val pw = art.px(230f)
        val pitch = art.hud.lineHeight * 1.02f
        val statusH = art.small.lineHeight
        val ph = inset * 2f + pitch * 3f + u * 0.9f + statusH
        val x = sw - pw - u * 1.8f
        val y = sh - ph - u * 1.8f
        w.panel(g, x, y, pw, ph, tint = Palette.accent)

        var top = y + ph - inset
        fun row(label: String, value: String, tint: Color) {
            val mid = top - pitch * 0.5f
            g.text(art.small, label, x + inset, mid + art.small.capHeight * 0.5f, Palette.textDim)
            g.textRight(art.hud, value, x + pw - inset, mid + art.hud.capHeight * 0.5f, tint)
            top -= pitch
        }

        c.set(Palette.textBright).lerp(Palette.danger, MathX.smoothstep(0.5f, 1f, f.seaState))
        row("SEA", beaufortName(f.seaState), c)

        // Wind gets a drawn arrowhead rather than a typed one. The faces here are Latin and
        // have no arrow glyphs -- asking for one is how the action buttons ended up blank.
        val following = f.windDir >= 0f
        val windTint = if (following) Palette.good else Palette.danger
        run {
            val mid = top - pitch * 0.5f
            g.text(art.small, "WIND", x + inset, mid + art.small.capHeight * 0.5f, Palette.textDim)
            val value = "%.0f".format(f.windSpeed)
            g.textRight(art.hud, value, x + pw - inset, mid + art.hud.capHeight * 0.5f, windTint)
            val ax = x + pw - inset - g.textWidth(art.hud, value) - u * 1.4f
            val ah = art.hud.capHeight * 0.42f
            val dir = if (following) 1f else -1f
            c.set(windTint)
            g.tri(ax - ah * dir, mid + ah, c, ax - ah * dir, mid - ah, c, ax + ah * dir, mid, c)
            top -= pitch
        }

        row("SWELL", "%.1f m".format(session.world.ocean.significantHeight()), Palette.textBright)

        top -= u * 0.4f
        w.rule(g, x + inset, top, pw - inset * 2f, Palette.accent, 0.25f)
        g.textRight(
            art.small, session.statusText, x + pw - inset,
            y + inset + art.small.capHeight, Palette.textFaint
        )
    }

    private fun drawHelm(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float, playing: Boolean) {
        val zoneW = sw * 0.36f
        val zoneH = sh * 0.72f
        if (playing) {
            val r = w.pad(g, Id.HELM, 0f, 0f, zoneW, zoneH, 105f * s, Palette.accent)
            if (r.active) {
                // Up is more sail, down is reefed. Right is weight forward, left is weight aft.
                trim = MathX.clamp01(0.5f + r.y * 0.62f)
                lean = MathX.clamp(r.x, -1f, 1f)
            } else {
                // Released: hands come off the sheet, she settles to working trim.
                trim = MathX.approach(trim, 0.72f, 1.6f, 1f / 60f)
                lean = MathX.approach(lean, 0f, 3.5f, 1f / 60f)
            }
        }

        // A permanent readout of the helm, bottom left, so the state stays legible when the
        // thumb is lifted off the pad.
        //
        // Laid out from the panel's top edge downward by measured row height. The previous
        // version mixed two conventions -- some rows measured from the top, the caption from
        // the bottom -- and the caption landed on top of the row above it.
        val inset = 12f * s
        val rowH = 26f * s
        val barH = 11f * s
        val labelW = 58f * s
        val bw = 178f * s
        val bh = inset * 2f + rowH * 2f + art.small.lineHeight
        val bx = 20f * s
        val by = 20f * s
        w.panel(g, bx, by, bw, bh, 0.85f)

        val barW = bw - inset * 2 - labelW
        val barX = bx + inset + labelW

        // Row one: sail.
        var barY = by + bh - inset - barH
        g.text(art.small, "SAIL", bx + inset, barY + barH * 0.5f + art.small.capHeight * 0.5f,
            Palette.textDim)
        w.bar(g, barX, barY, barW, barH, trim, Palette.sailCloth)

        // Row two: crew weight, as a centred needle rather than a fill -- it runs from aft
        // through level to forward, so a bar filling from the left would read as a quantity.
        barY -= rowH
        g.text(art.small, "WEIGHT", bx + inset, barY + barH * 0.5f + art.small.capHeight * 0.5f,
            Palette.textDim)
        c.set(Palette.ink); c.a = 0.75f
        g.rect(barX, barY, barW, barH, c)
        c.set(Palette.panelEdge); c.a = 0.5f
        g.line(barX + barW * 0.5f, barY, barX + barW * 0.5f, barY + barH, 1f, c)
        c.set(Palette.hullTrim); c.a = 1f
        g.rect(barX + barW * 0.5f + lean * barW * 0.45f - 3f * s, barY - 2f * s, 6f * s,
            barH + 4f * s, c)

        // Caption, on its own line under both rows.
        c.set(Palette.textFaint)
        g.text(
            art.small,
            if (lean > 0.15f) "WEIGHT FORWARD" else if (lean < -0.15f) "WEIGHT AFT" else "TRIMMED LEVEL",
            bx + inset, by + inset + art.small.lineHeight * 0.85f, c
        )
    }

    private fun drawWorkings(
        g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float, playing: Boolean
    ) {
        val r = 47f * s
        val gap = 22f * s
        val total = SpellKind.base.size * (r * 2) + (SpellKind.base.size - 1) * gap
        var cx = sw - 26f * s - total + r
        val cy = 108f * s

        for (kind in SpellKind.base) {
            val def = Spells[kind]
            val cd = f.spellCooldown[kind.id]
            val cdFrac = MathX.clamp01(cd / def.cooldown)
            val snared = f.snaredSpell == kind.id
            val fired = w.actionButton(
                g, Id.SPELL0 + kind.id, cx, cy, r,
                Icons.spell(kind), def.title, Palette.spell(kind.id),
                cdFrac, f.aether >= def.cost, selectedSpell == kind, snared,
                cost = "%.0f".format(def.cost)
            )
            if (fired && playing) selectedSpell = kind
            cx += r * 2 + gap
        }

        // Brace and pump sit above the workings, smaller: they are modifiers, not attacks.
        val sr = 34f * s
        val sy = cy + r + sr + 18f * s
        val braceCd = MathX.clamp01(f.braceCooldown / Config.BRACE_COOLDOWN)
        if (w.actionButton(
                g, Id.BRACE, sw - 26f * s - sr, sy, sr, Icons.BRACE, "BRACE",
                Palette.hullTrim, braceCd, f.aether >= Config.BRACE_COST, f.braced
            ) && playing
        ) braceRequested = true

        if (w.actionButton(
                g, Id.PUMP, sw - 26f * s - sr * 3f - 18f * s, sy, sr, Icons.PUMP, "PUMP",
                Palette.bilgeBar, 0f, true, pump
            ) && playing
        ) pump = !pump
        // Stop pumping automatically once she is dry; it costs aether to run.
        if (f.bilge < 30f) pump = false
    }

    private fun drawWarnings(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        var y = sh * 0.62f
        fun warn(text: String, tint: Color, urgency: Float) {
            c.set(tint)
            c.a = 0.65f + 0.35f * kotlin.math.sin(time * (4f + urgency * 6f))
            g.textCentered(art.hud, text, sw * 0.5f, y, c)
            y -= art.hud.lineHeight * 1.1f
        }

        if (f.snaredSpell in 0..3) {
            warn("${Spells[SpellKind.of(f.snaredSpell)].title} BOUND  ${"%.0f".format(f.snareTimer)}s",
                Palette.singularity, 1f)
        }
        if (f.bilge > Config.MAX_BILGE * 0.6f) warn("TAKING WATER - PUMP", Palette.bilgeBar, 1f)
        if (f.mast <= 1f) warn("MAST GONE", Palette.danger, 0.6f)
        if (f.knockedDown) warn("KNOCKED DOWN", Palette.danger, 1.4f)
        if (f.krakenActive) warn("SOMETHING IS UNDER US", Palette.furyBar, 1.2f)
        if (f.hull < 25f) warn("HULL FAILING", Palette.danger, 1.6f)
    }

    /** A red wash at the edges when she is badly hurt, and a punch on each hit. */
    private fun drawDamageVignette(g: Painter, f: SnapshotFrame, sw: Float, sh: Float) {
        val hurt = MathX.clamp01(1f - f.hull / 42f)
        val a = MathX.clamp01(hurt * 0.5f + damageFlash * 0.55f)
        if (a < 0.01f) return
        c.set(Palette.danger)
        val band = sh * 0.22f
        c.a = 0f
        val edge = Color(Palette.danger)
        edge.a = a * 0.55f
        g.rectV(0f, 0f, sw, band, edge, c)
        g.rectV(0f, sh - band, sw, band, c, edge)
    }

    private fun beaufortName(seaState: Float): String = when {
        seaState < 0.26f -> "CALM"
        seaState < 0.38f -> "SLIGHT"
        seaState < 0.5f -> "MODERATE"
        seaState < 0.62f -> "ROUGH"
        seaState < 0.75f -> "VERY ROUGH"
        seaState < 0.88f -> "HIGH"
        else -> "PHENOMENAL"
    }
}
