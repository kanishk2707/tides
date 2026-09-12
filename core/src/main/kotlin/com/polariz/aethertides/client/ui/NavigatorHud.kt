package com.polariz.aethertides.client.ui

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.client.Art
import com.polariz.aethertides.client.Cam
import com.polariz.aethertides.client.Painter
import com.polariz.aethertides.client.Palette
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

        val p = touch.pressedIn(0f, 0f, screenW, screenH) ?: return
        // Do not steal taps that belong to the helm zone or the button strip.
        if (p.x < helmZoneW) return
        if (p.y < controlsBottom && p.x > screenW - 520f * s) return
        if (!touch.claim(p, Id.CAST)) return

        val wx = cam.unprojectX(p.x)
        val wy = cam.unprojectY(screenH - p.y)
        castHintX = wx
        castHintY = wy

        val def = Spells[selectedSpell]
        val inRange = abs(wx - f.shipX) <= 120f
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
    fun drawCastMarker(g: Painter, f: SnapshotFrame, cam: Cam) {
        val def = Spells[selectedSpell]
        // The reach ring: where the aether can actually be shaped from here.
        c.set(Palette.aetherBar)
        c.a = 0.10f + 0.04f * kotlin.math.sin(time * 2.2f)
        g.line(f.shipX - 120f, f.shipY + 0.2f, f.shipX + 120f, f.shipY + 0.2f, 0.12f, c)

        if (warnTimer > 0f) {
            c.set(Palette.danger)
            c.a = MathX.clamp01(warnTimer) * 0.8f
            g.ringLine(castHintX, castHintY, def.radius * 0.5f, 0.35f, 20, c)
            g.line(castHintX - 2f, castHintY - 2f, castHintX + 2f, castHintY + 2f, 0.3f, c)
            g.line(castHintX - 2f, castHintY + 2f, castHintX + 2f, castHintY - 2f, 0.3f, c)
        }
    }

    // --- panels ------------------------------------------------------------

    /**
     * Row helper.
     *
     * Every readout in this HUD is a label on the left and a meter on the right, sharing one
     * baseline. Laying them out through one function is what stops the panels drifting out of
     * alignment as numbers are added -- which is exactly what happened the first time these
     * were placed by hand.
     */
    private fun meterRow(
        g: Painter, label: String, x: Float, barY: Float, labelW: Float, barW: Float,
        barH: Float, value: Float, tint: Color, warn: Float = -1f, suffix: String? = null,
        suffixTint: Color = Palette.textFaint
    ) {
        val textY = barY + barH * 0.5f + art.small.capHeight * 0.5f
        g.text(art.small, label, x, textY, Palette.textDim)
        w.bar(g, x + labelW, barY, barW, barH, value, tint, null, warn, time)
        if (suffix != null) {
            g.text(art.small, suffix, x + labelW + barW + 10f * art.uiScale, textY, suffixTint)
        }
    }

    private fun drawVitals(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        val pw = 320f * s
        val ph = 140f * s
        val x = 16f * s
        val y = sh - ph - 14f * s
        w.panel(g, x, y, pw, ph)

        val inset = 14f * s
        val labelW = 78f * s
        val barW = pw - inset * 2 - labelW
        val barH = 15f * s

        meterRow(g, "HULL", x + inset, y + ph - 30f * s, labelW, barW, barH,
            f.hull / Config.MAX_HULL, Palette.hullBar, warn = 0.3f)
        meterRow(g, "RIG", x + inset, y + ph - 56f * s, labelW, barW * 0.68f, barH * 0.8f,
            f.mast / Config.MAX_MAST, Palette.mastBar)
        // Bilge reads backwards on purpose: a full bar is a sinking ship.
        meterRow(g, "WATER", x + inset, y + ph - 80f * s, labelW, barW * 0.68f, barH * 0.8f,
            f.bilge / Config.MAX_BILGE, Palette.bilgeBar,
            suffix = if (f.leaks > 0) "${f.leaks}x" else null,
            suffixTint = Palette.danger)
        meterRow(g, "AETHER", x + inset, y + ph - 104f * s, labelW, barW, barH,
            f.aether / Config.MAX_AETHER, Palette.aetherBar)

        // Speed, large: it is the number that matters most, so it gets its own line.
        val knots = abs(f.shipVx) / 0.5144f
        g.text(art.hudLarge, "%.0f".format(knots), x + inset, y + 30f * s, Palette.textBright)
        g.text(art.small, "KNOTS",
            x + inset + g.textWidth(art.hudLarge, "%.0f".format(knots)) + 6f * s,
            y + 20f * s, Palette.textDim)

        if (f.surf > 0.35f) {
            c.set(Palette.tsunami)
            c.a = MathX.clamp01(f.surf)
            g.textRight(art.hud, "SURFING", x + pw - inset, y + 28f * s, c)
        } else if (f.airborne) {
            g.textRight(art.hud, "AIRBORNE", x + pw - inset, y + 28f * s, Palette.aetherBar)
        }
    }

    private fun drawVoyage(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        val pw = 400f * s
        val ph = 66f * s
        val x = (sw - pw) * 0.5f
        val y = sh - ph - 14f * s
        w.panel(g, x, y, pw, ph)

        val inset = 16f * s
        val progress = MathX.clamp01(f.shipX / Config.COURSE_LENGTH)

        val league = MathX.clampI((progress * Config.LEAGUES).toInt() + 1, 1, Config.LEAGUES)
        g.text(art.small, "LEAGUE %d OF %d".format(league, Config.LEAGUES),
            x + inset, y + ph - 12f * s, Palette.textDim)

        val remaining = maxOf(0f, Config.MATCH_TIME_LIMIT - f.time)
        c.set(if (remaining < 45f) Palette.danger else Palette.textBright)
        g.textRight(art.hud, "%d:%02d".format((remaining / 60f).toInt(), (remaining % 60f).toInt()),
            x + pw - inset, y + ph - 8f * s, c)

        w.segments(g, x + inset, y + 14f * s, pw - inset * 2, 13f * s,
            Config.LEAGUES, progress, Palette.accent)
    }

    private fun drawConditions(
        g: Painter, f: SnapshotFrame, session: Session, sw: Float, sh: Float, s: Float
    ) {
        val pw = 250f * s
        val ph = 92f * s
        val x = sw - pw - 16f * s
        val y = sh - ph - 14f * s
        w.panel(g, x, y, pw, ph)

        val inset = 14f * s
        val rowH = 26f * s

        g.text(art.small, "SEA", x + inset, y + ph - 12f * s, Palette.textDim)
        c.set(Palette.textBright).lerp(Palette.danger, MathX.smoothstep(0.5f, 1f, f.seaState))
        g.textRight(art.hud, beaufortName(f.seaState), x + pw - inset, y + ph - 8f * s, c)

        g.text(art.small, "WIND", x + inset, y + ph - 12f * s - rowH, Palette.textDim)
        val windArrow = if (f.windDir >= 0f) "→" else "←"
        g.textRight(art.hud, "%s %.0f".format(windArrow, f.windSpeed),
            x + pw - inset, y + ph - 8f * s - rowH,
            if (f.windDir >= 0f) Palette.good else Palette.danger)

        g.text(art.small, "SWELL", x + inset, y + ph - 12f * s - rowH * 2, Palette.textDim)
        g.textRight(art.hud, "%.1f m".format(session.world.ocean.significantHeight()),
            x + pw - inset, y + ph - 8f * s - rowH * 2, Palette.textBright)

        g.textRight(art.small, session.statusText, x + pw - inset, y - 14f * s, Palette.textFaint)
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
        val bx = 20f * s
        val by = 20f * s
        val bw = 172f * s
        val bh = 78f * s
        w.panel(g, bx, by, bw, bh, 0.85f)

        val inset = 12f * s
        val labelW = 58f * s
        val barW = bw - inset * 2 - labelW

        var ty = by + bh - 26f * s
        g.text(art.small, "SAIL", bx + inset, ty + 11f * s, Palette.textDim)
        w.bar(g, bx + inset + labelW, ty, barW, 11f * s, trim, Palette.sailCloth)

        ty = by + bh - 52f * s
        g.text(art.small, "WEIGHT", bx + inset, ty + 11f * s, Palette.textDim)
        // Weight is a centred needle, not a fill: it runs from aft through level to forward.
        c.set(Palette.ink); c.a = 0.75f
        g.rect(bx + inset + labelW, ty, barW, 11f * s, c)
        c.set(Palette.panelEdge); c.a = 0.5f
        g.line(bx + inset + labelW + barW * 0.5f, ty,
            bx + inset + labelW + barW * 0.5f, ty + 11f * s, 1f, c)
        c.set(Palette.hullTrim); c.a = 1f
        g.rect(bx + inset + labelW + barW * 0.5f + lean * barW * 0.45f - 3f * s, ty - 2f * s,
            6f * s, 15f * s, c)

        c.set(Palette.textFaint)
        g.text(art.small, if (lean > 0.15f) "FORWARD" else if (lean < -0.15f) "AFT" else "LEVEL",
            bx + inset, by + 18f * s, c)
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
                def.glyph, def.title, Palette.spell(kind.id),
                cdFrac, f.aether >= def.cost, selectedSpell == kind, snared
            )
            if (fired && playing) selectedSpell = kind

            // Cost pip under the glyph.
            g.textCentered(art.small, "%.0f".format(def.cost), cx, cy - r * 0.62f, Palette.textFaint)
            cx += r * 2 + gap
        }

        // Brace and pump sit above the workings, smaller: they are modifiers, not attacks.
        val sr = 34f * s
        val sy = cy + r + sr + 18f * s
        val braceCd = MathX.clamp01(f.braceCooldown / Config.BRACE_COOLDOWN)
        if (w.actionButton(
                g, Id.BRACE, sw - 26f * s - sr, sy, sr, "◘", "BRACE",
                Palette.hullTrim, braceCd, f.aether >= Config.BRACE_COST, f.braced
            ) && playing
        ) braceRequested = true

        if (w.actionButton(
                g, Id.PUMP, sw - 26f * s - sr * 3f - 18f * s, sy, sr, "≡", "PUMP",
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
