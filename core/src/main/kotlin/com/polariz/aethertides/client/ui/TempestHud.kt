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
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.Deployables
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.Spells
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Tempest's chart table.
 *
 * This player is not driving anything, they are *authoring the water ahead*. So the whole HUD
 * is about the lane: where the bow will be in a few seconds, which stretch of sea is legal to
 * seed right now, and what each thing costs. The placement band is drawn on the water itself
 * rather than described in text, because the decision is spatial.
 *
 * They also get the storm dial, which is the only control in the game that changes the
 * conditions for both players -- and which costs upkeep, so holding a gale is a commitment.
 */
class TempestHud(
    private val art: Art,
    private val w: Widgets,
    private val touch: Touch
) {

    private object Id {
        const val CARD0 = 200
        const val STORM = 230
        const val FURY = 231
        const val PLACE = 232
        const val PAN = 233
        const val SNARE0 = 240
    }

    var selected: DeployKind = DeployKind.DRIFT_MINE
        private set

    private var stormWanted = Config.SEA_STATE_FLOOR
    private var snarePicking = false
    private var time = 0f
    private var warnTimer = 0f
    private var placedFlash = 0f
    private var lastPlacedX = 0f

    /** Ghost placement position, in world metres. */
    var ghostX = 0f
        private set
    var ghostValid = false
        private set

    private val c = Color()

    fun update(dt: Float, f: SnapshotFrame) {
        time += dt
        if (warnTimer > 0f) warnTimer -= dt
        placedFlash = MathX.approach(placedFlash, 0f, 3f, dt)
    }

    // -----------------------------------------------------------------------

    fun draw(
        g: Painter, f: SnapshotFrame, session: Session, cam: Cam,
        screenW: Float, screenH: Float
    ) {
        val s = art.uiScale
        val playing = f.phase == MatchPhase.SAILING

        handlePlacement(f, session, cam, screenW, screenH, playing)
        handlePan(cam, screenW, screenH, s)

        drawResources(g, f, session, screenW, screenH, s)
        drawTarget(g, f, screenW, screenH, s)
        drawStormDial(g, f, session, screenW, screenH, s, playing)
        drawCards(g, f, screenW, screenH, s, playing)
        if (snarePicking) drawSnarePicker(g, f, session, screenW, screenH, s)
    }

    // --- placement ---------------------------------------------------------

    private fun bowX(f: SnapshotFrame): Float =
        f.shipX + Config.HULL_LENGTH * 0.5f * kotlin.math.cos(f.shipAngle)

    private fun legal(f: SnapshotFrame, kind: DeployKind, x: Float): Boolean {
        val def = Deployables[kind]
        if (f.malice < def.cost) return false
        if (f.deployCooldown[kind.id] > 0.001f) return false
        val lead = x - bowX(f)
        return lead >= def.minLead && lead <= def.maxLead
    }

    private fun handlePlacement(
        f: SnapshotFrame, session: Session, cam: Cam,
        screenW: Float, screenH: Float, playing: Boolean
    ) {
        ghostValid = false
        if (!playing || snarePicking) return
        val s = art.uiScale
        val cardsTop = 150f * s

        val p = touch.pressedIn(0f, cardsTop, screenW, screenH - cardsTop) ?: return
        if (p.x < 130f * s) return                        // storm dial column
        if (!touch.claim(p, Id.PLACE)) return

        val wx = cam.unprojectX(p.x)
        ghostX = wx
        if (legal(f, selected, wx)) {
            ghostValid = true
            // y is unused for most kinds; the snare packs its target spell into it.
            session.sendCommand(Command.DEPLOY, selected.id, wx, 0f)
            placedFlash = 1f
            lastPlacedX = wx
        } else {
            warnTimer = 1.1f
        }
    }

    private fun handlePan(cam: Cam, screenW: Float, screenH: Float, s: Float) {
        // A second finger anywhere on the chart drags the view forward and back.
        val p = touch.pressedIn(0f, 150f * s, screenW, screenH - 150f * s)
        if (p != null && touch.claim(p, Id.PAN)) { /* claimed on the way down */ }
        touch.ownedBy(Id.PAN)?.let {
            cam.pan = MathX.clamp(cam.pan - it.dx * (cam.viewWidth / screenW), -60f, 220f)
        }
        if (touch.ownedBy(Id.PAN) == null) {
            cam.pan = MathX.approach(cam.pan, 0f, 1.4f, 1f / 60f)
        }
    }

    /**
     * The lane overlay, drawn on the water.
     *
     * Three things: the band where placement is legal for the selected tool, a predicted track
     * for the bow, and the ghost of the thing about to be dropped.
     */
    fun drawLane(g: Painter, f: SnapshotFrame, cam: Cam, sea: SeaRenderer) {
        val def = Deployables[selected]
        val bow = bowX(f)
        val nearX = bow + def.minLead
        val farX = bow + def.maxLead

        // Legal band.
        c.set(Palette.maliceBar)
        c.a = 0.06f
        val top = cam.top
        val bottomY = cam.bottom
        g.rect(nearX, bottomY, farX - nearX, top - bottomY, c)
        c.a = 0.4f
        g.line(nearX, bottomY, nearX, top, 0.22f, c)
        c.a = 0.16f
        g.line(farX, bottomY, farX, top, 0.18f, c)

        // Predicted track of the bow over the next few seconds, dotted along the real water.
        //
        // Deliberately no text out here. The HUD fonts are rasterised in pixels, so drawing
        // one under the world camera scales a single character to several metres across the
        // sea -- which is exactly what it did. Whole seconds are marked with a tick instead.
        c.set(Palette.accent)
        var t = 0.5f
        while (t < 9f) {
            val tx = bow + f.shipVx * t
            val ty = sea.surfaceYAt(tx)
            c.a = MathX.clamp01(1f - t / 9f) * 0.55f
            if (t - t.toInt() < 0.01f) {
                g.line(tx, ty + 0.2f, tx, ty + 1.9f, 0.16f, c)
                g.circle(tx, ty + 2.3f, 0.32f, 8, c)
            } else {
                g.circle(tx, ty + 0.7f, 0.22f, 8, c)
            }
            t += 0.5f
        }

        // Ghost of the pending placement.
        if (warnTimer > 0f || placedFlash > 0f) {
            val gx = if (placedFlash > 0f) lastPlacedX else ghostX
            val gy = sea.surfaceYAt(gx)
            val ok = placedFlash > 0f
            c.set(if (ok) Palette.good else Palette.danger)
            c.a = MathX.clamp01(if (ok) placedFlash else warnTimer) * 0.9f
            g.ringLine(gx, gy, 3.5f + (1f - (if (ok) placedFlash else warnTimer)) * 4f, 0.3f, 20, c)
            if (!ok) {
                // A cross, not a caption: the reason is spelled out on the card bar instead.
                g.line(gx - 2.2f, gy - 2.2f, gx + 2.2f, gy + 2.2f, 0.28f, c)
                g.line(gx - 2.2f, gy + 2.2f, gx + 2.2f, gy - 2.2f, 0.28f, c)
            }
        }
    }

    private fun reasonText(f: SnapshotFrame): String {
        val def = Deployables[selected]
        val lead = ghostX - bowX(f)
        return when {
            f.malice < def.cost -> "NOT ENOUGH MALICE"
            f.deployCooldown[selected.id] > 0.001f -> "NOT READY"
            lead < def.minLead -> "TOO CLOSE"
            else -> "TOO FAR AHEAD"
        }
    }

    // --- panels ------------------------------------------------------------

    private fun drawResources(
        g: Painter, f: SnapshotFrame, session: Session, sw: Float, sh: Float, s: Float
    ) {
        val pw = 340f * s
        val ph = 104f * s
        val x = 16f * s
        val y = sh - ph - 14f * s
        w.panel(g, x, y, pw, ph)

        val inset = 14f * s
        val labelW = 76f * s
        val barW = pw - inset * 2 - labelW - 52f * s

        w.meterRow(g, art.small, "MALICE", x + inset, y + ph - 32f * s, labelW, barW, 17f * s,
            f.malice / Config.MAX_MALICE, Palette.maliceBar, pulse = time,
            readout = "%.0f".format(f.malice))
        w.meterRow(g, art.small, "FURY", x + inset, y + ph - 60f * s, labelW, barW, 13f * s,
            f.fury / Config.MAX_FURY, Palette.furyBar, pulse = time,
            readout = if (f.fury >= Config.MAX_FURY - 0.5f) "READY" else null,
            readoutTint = Palette.furyBar)

        g.text(art.small, "SEA  " + beaufortName(f.seaState), x + inset, y + 24f * s, Palette.textDim)
        g.textRight(art.small, session.statusText, x + pw - inset, y + 24f * s, Palette.textFaint)
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

    private fun drawTarget(g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float) {
        val pw = 330f * s
        val ph = 84f * s
        val x = (sw - pw) * 0.5f
        val y = sh - ph - 14f * s
        w.panel(g, x, y, pw, ph)

        val inset = 14f * s
        val labelW = 64f * s
        val barW = pw - inset * 2 - labelW

        // What the Tempest is actually working on: their hull, and how far they have left.
        w.meterRow(g, art.small, "HULL", x + inset, y + ph - 32f * s, labelW, barW, 16f * s,
            f.hull / Config.MAX_HULL, Palette.hullBar, warn = 0.3f, pulse = time)

        val progress = MathX.clamp01(f.shipX / Config.COURSE_LENGTH)
        w.segments(g, x + inset, y + ph - 58f * s, pw - inset * 2, 10f * s,
            Config.LEAGUES, progress, Palette.accent)

        val remaining = maxOf(0f, Config.MATCH_TIME_LIMIT - f.time)
        g.text(art.small, "%.0f%% TO SHORE".format(progress * 100f),
            x + inset, y + 22f * s, Palette.textDim)
        g.textRight(art.small, "%d:%02d".format((remaining / 60).toInt(), (remaining % 60).toInt()),
            x + pw - inset, y + 22f * s, if (remaining < 45f) Palette.danger else Palette.textDim)
    }

    private fun drawStormDial(
        g: Painter, f: SnapshotFrame, session: Session, sw: Float, sh: Float, s: Float, playing: Boolean
    ) {
        val x = 30f * s
        val h = sh * 0.30f
        val y = sh * 0.32f
        val ww = 26f * s

        val newVal = w.vSlider(g, Id.STORM, x, y, ww, h, stormWanted, Palette.maliceBar, "STORM")
        if (playing && abs(newVal - stormWanted) > 0.004f) {
            stormWanted = newVal
            session.sendCommand(Command.STORM_DIAL, 0, stormWanted, 0f)
        }

        // The sea state actually achieved, marked against what is being paid for.
        val actual = y + h * f.seaState
        c.set(Palette.foam); c.a = 0.85f
        g.line(x - 7f * s, actual, x + ww + 7f * s, actual, 2f, c)
        g.text(art.small, "NOW", x + ww + 11f * s, actual + art.small.capHeight * 0.5f, Palette.textDim)

        // Upkeep warning: a storm you cannot pay for slides straight back down.
        if (stormWanted > f.seaState + 0.05f && f.malice < 25f) {
            c.set(Palette.danger); c.a = 0.6f + 0.4f * sin(time * 6f)
            g.text(art.small, "CANNOT HOLD", x - 6f * s, y + h + 34f * s, c)
        }

        // Fury sits under the dial, clear of the resource panel above it.
        val furyReady = f.fury >= Config.MAX_FURY - 0.5f && !f.krakenActive
        if (w.actionButton(
                g, Id.FURY, x + ww * 0.5f, y - 60f * s, 38f * s,
                Icons.deploy(DeployKind.KRAKEN), "FURY", Palette.furyBar,
                if (furyReady) 0f else 1f - f.fury / Config.MAX_FURY, furyReady, false
            ) && playing && furyReady
        ) {
            session.sendCommand(Command.FURY, 0, 0f, 0f)
        }
    }

    private fun drawCards(
        g: Painter, f: SnapshotFrame, sw: Float, sh: Float, s: Float, playing: Boolean
    ) {
        val bar = Deployables.bar
        val r = 36f * s
        val gap = 14f * s
        val total = bar.size * r * 2 + (bar.size - 1) * gap
        val panelX = (sw - total) * 0.5f - 20f * s
        val panelW = total + 40f * s
        val panelH = 168f * s
        w.panel(g, panelX, 8f * s, panelW, panelH, 0.92f)

        // The full name and blurb of whatever is selected, inside the panel. Nine tools need
        // explaining in the moment, not in a menu the player has to leave the match to read.
        val def = Deployables[selected]
        g.textCentered(art.hud, def.title, sw * 0.5f, 8f * s + panelH - 22f * s, Palette.textBright)
        g.textCentered(art.small, def.blurb, sw * 0.5f, 8f * s + panelH - 50f * s, Palette.textDim)

        var cx = (sw - total) * 0.5f + r
        val cy = 8f * s + 56f * s
        for (kind in bar) {
            val d = Deployables[kind]
            val cd = MathX.clamp01(f.deployCooldown[kind.id] / d.cooldown)
            val afford = f.malice >= d.cost
            val fired = w.actionButton(
                g, Id.CARD0 + kind.id, cx, cy, r,
                Icons.deploy(kind), d.short, Palette.maliceBar, cd, afford, selected == kind,
                cost = "%.0f".format(d.cost)
            )
            if (fired && playing) {
                selected = kind
                snarePicking = kind == DeployKind.AETHER_SNARE
            }
            g.textCentered(art.small, "%.0f".format(d.cost), cx, cy - r * 0.62f, Palette.textFaint)
            cx += r * 2 + gap
        }

        // Why the last attempt was refused, if it was.
        if (warnTimer > 0f) {
            c.set(Palette.danger)
            c.a = MathX.clamp01(warnTimer) * 0.9f
            g.textCentered(art.hud, reasonText(f), sw * 0.5f, 8f * s + panelH + 26f * s, c)
        }
    }

    /** The snare needs a second choice: which of their four workings to bind. */
    private fun drawSnarePicker(
        g: Painter, f: SnapshotFrame, session: Session, sw: Float, sh: Float, s: Float
    ) {
        val pw = 460f * s
        val ph = 150f * s
        val x = (sw - pw) * 0.5f
        val y = (sh - ph) * 0.5f
        w.panel(g, x, y, pw, ph)
        g.textCentered(art.hud, "BIND WHICH WORKING?", sw * 0.5f, y + ph - 34f * s, Palette.textBright)

        val r = 38f * s
        val gap = 26f * s
        val total = 4 * r * 2 + 3 * gap
        var cx = (sw - total) * 0.5f + r
        for (kind in SpellKind.base) {
            val def = Spells[kind]
            if (w.actionButton(
                    g, Id.SNARE0 + kind.id, cx, y + 54f * s, r,
                    Icons.spell(kind), def.title, Palette.spell(kind.id), 0f, true, false
                )
            ) {
                session.sendCommand(Command.SNARE_PICK, kind.id, 0f, 0f)
                snarePicking = false
                selected = DeployKind.DRIFT_MINE
            }
            cx += r * 2 + gap
        }
    }

    fun cancelModal() {
        snarePicking = false
    }

    val modalOpen: Boolean get() = snarePicking
}
