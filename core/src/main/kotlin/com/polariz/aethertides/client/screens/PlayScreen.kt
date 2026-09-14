package com.polariz.aethertides.client.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.client.AetherTides
import com.polariz.aethertides.client.Audio
import com.polariz.aethertides.client.Cam
import com.polariz.aethertides.client.EntityRenderer
import com.polariz.aethertides.client.Fx
import com.polariz.aethertides.client.Palette
import com.polariz.aethertides.client.SeaRenderer
import com.polariz.aethertides.client.ShipRenderer
import com.polariz.aethertides.client.SkyRenderer
import com.polariz.aethertides.client.net.LocalSession
import com.polariz.aethertides.client.net.OnlineSession
import com.polariz.aethertides.client.net.RoundResult
import com.polariz.aethertides.client.net.Session
import com.polariz.aethertides.client.roleName
import com.polariz.aethertides.client.ui.NavigatorHud
import com.polariz.aethertides.client.ui.TempestHud
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.net.EventRec
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.EndReason
import com.polariz.aethertides.shared.sim.EventKind
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.Role
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.Spells
import kotlin.math.abs

/**
 * The match.
 *
 * Draw order is the whole trick to making a 2D sea look like water with things in it:
 *
 *   sky -> distant swell -> light shafts -> water body -> submerged hazards -> waterline foam
 *   -> floating hazards -> the ship -> workings -> particles -> near water -> lane overlay
 *   -> HUD
 *
 * Submerged things go *under* the foam line and above the body, so they are visibly inside the
 * water column. The near-water band goes over everything in the world, so the camera is
 * clearly down among the waves rather than hovering above them.
 */
class PlayScreen(
    private val app: AetherTides,
    private val session: Session
) : ScreenAdapter() {

    private val cam = Cam()
    private val sky = SkyRenderer(app.art, session.seed)
    private val sea = SeaRenderer(app.art, session.seed)
    private val ship = ShipRenderer(app.art)
    private val entities = EntityRenderer(app.art)
    private val fx = Fx(app.art)

    private val navHud = NavigatorHud(app.art, app.widgets, app.touch)
    private val tempestHud = TempestHud(app.art, app.widgets, app.touch)

    private val events = ArrayList<EventRec>(48)

    private var bannerText: String? = null
    private var bannerSub: String? = null
    private var bannerTimer = 0f
    private var bannerTint: Color = Palette.textBright

    private var resultShown = false
    private var resultAlpha = 0f
    private var time = 0f
    private var started = false

    // --- performance sampling ------------------------------------------
    private var statTimer = 0f
    private var statFrames = 0
    private var statWorstMs = 0f
    private var statFps = 0f
    private var statWorst = 0f

    private val c = Color()

    override fun show() {
        Gdx.input.inputProcessor = app.touch
        cam.resize(Gdx.graphics.width, Gdx.graphics.height)
        cam.snapTo(session.render.shipX, 0f)
    }

    override fun resize(width: Int, height: Int) {
        cam.resize(width, height)
    }

    // -----------------------------------------------------------------------

    override fun render(delta: Float) {
        val dt = delta.coerceAtMost(0.05f)
        time += dt

        app.touch.beginFrame(dt)
        app.widgets.update(dt)

        update(dt)
        draw()

        app.touch.endFrame()
        sampleFrame(delta)
    }

    /**
     * Rolling frame statistics.
     *
     * Averaged over a second, and the *worst* frame in that second is tracked separately --
     * a mean of 60 with an occasional 90 ms hitch is a stutter the player feels and an average
     * will never show you.
     */
    private fun sampleFrame(delta: Float) {
        if (!app.prefs.showStats) return
        statFrames++
        statTimer += delta
        statWorstMs = maxOf(statWorstMs, delta * 1000f)
        if (statTimer >= 1f) {
            statFps = statFrames / statTimer
            statWorst = statWorstMs
            Gdx.app.log(
                "AetherTides",
                "fps=%.1f worst=%.1fms entities=%d fx=%d ping=%dms".format(
                    statFps, statWorst, session.render.entityCount, fx.count(), session.pingMs
                )
            )
            statTimer = 0f
            statFrames = 0
            statWorstMs = 0f
        }
    }

    private fun drawStats(g: com.polariz.aethertides.client.Painter, sw: Float, sh: Float) {
        val s = app.art.uiScale
        val text = "%.0f fps   worst %.0f ms   ent %d   fx %d%s".format(
            statFps, statWorst, session.render.entityCount, fx.count(),
            if (session.pingMs > 0) "   %d ms".format(session.pingMs) else ""
        )
        val tint = when {
            statFps < 1f -> Palette.textFaint
            statWorst > 34f -> Palette.danger
            statFps < 50f -> Palette.mastBar
            else -> Palette.good
        }
        g.textCentered(app.art.small, text, sw * 0.5f, sh - 172f * s, tint)
    }

    private fun update(dt: Float) {
        // Helm goes out before the session steps, so prediction uses this frame's input.
        if (session.role == Role.NAVIGATOR) {
            session.setHelm(
                navHud.helmTrim(), navHud.helmLean(), navHud.helmPump(), navHud.takeBrace()
            )
        }

        session.update(dt)
        val f = session.render

        if (!started && f.phase != MatchPhase.WAITING) {
            started = true
            cam.snapTo(f.shipX, f.shipY)
        }

        session.drainEvents(events)
        for (e in events) handleEvent(e, f.seaState)
        events.clear()

        cam.update(
            dt, session.role, f.shipX, f.shipY, f.shipVx, f.seaState,
            // Metres across the screen. This number is the single biggest lever on how the
            // sea reads: at 96 m a four-metre swell is a ripple, at 58 m it is a wall.
            baseWidth = if (session.role == Role.NAVIGATOR) 58f else 124f
        )
        // The ear sits with the camera, and the sea and wind beds follow the same numbers
        // the renderer draws from, so what you hear is what is on screen.
        Audio.listener(cam.camera.position.x, cam.viewWidth)
        Audio.bed(f.seaState, f.windSpeed, abs(f.shipVx), f.submerged)

        sky.update(dt, f.windSpeed)
        sea.sample(cam, session.world.ocean)
        sea.emit(dt, cam, fx, f.seaState)
        ship.update(dt, f, fx, sea)
        entities.update(dt)
        fx.update(dt, session.world.ocean)

        // Rain, spawned along the top of the frame wherever the weather warrants it.
        val rain = session.world.wind.rainAt(f.shipX, f.seaState)
        if (rain > 0.05f) {
            val drops = (rain * 26f * dt * 60f).toInt()
            repeat(drops.coerceAtMost(14)) {
                val x = cam.left + (it * 137.13f + time * 53f) % cam.viewWidth
                fx.rain(x, cam.top + 2f, -f.windSpeed * 0.35f, 1)
            }
        }

        navHud.update(dt, f)
        tempestHud.update(dt, f)

        if (bannerTimer > 0f) bannerTimer -= dt

        // Countdown and phase banners.
        if (f.phase == MatchPhase.COUNTDOWN) {
            val n = kotlin.math.ceil(f.phaseTimer).toInt()
            bannerText = if (n <= 0) "SAIL" else n.toString()
            bannerSub = if (session.role == Role.NAVIGATOR) "MAKE THE SHORE" else "SEE THAT THEY DO NOT"
            bannerTimer = 0.3f
            bannerTint = Palette.textBright
        }

        val res = session.result
        if (res != null && !resultShown) {
            resultShown = true
            // Rating and record are the server's to change; the PROFILE message carries the
            // new values after a rated series. Locally we only remember how far she got.
            app.prefs.recordPractice(res.won, res.distance)
            (session as? OnlineSession)?.let { s ->
                if (s.profileUpdated) {
                    s.profileUpdated = false
                    app.prefs.rating = s.profile.rating
                    app.prefs.matchesPlayed = s.profile.played
                    app.prefs.matchesWon = s.profile.won
                }
            }
            app.platform.telemetry.event(
                "match_end", "role" to session.role.name, "won" to res.won,
                "reason" to res.reason, "online" to (session is OnlineSession)
            )
        }
        resultAlpha = MathX.approach(resultAlpha, if (resultShown) 1f else 0f, 4f, dt)
        app.touch.blocked = resultShown || tempestHud.modalOpen

        if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.BACK) ||
            Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.ESCAPE)
        ) {
            quit()
        }
    }

    // -----------------------------------------------------------------------

    private fun draw() {
        val f = session.render
        val ambient = sky.ambient(f.seaState)
        app.clear()

        val g = app.painter
        g.begin(cam.camera)

        sky.draw(g, cam, f.seaState, session.world.wind, f.shipX)
        sea.drawBackdrop(g, cam, session.world.ocean, f.seaState, ambient)
        sea.drawShore(g, cam, ambient)
        sea.drawLightShafts(g, cam, f.seaState, ambient)
        sea.drawBody(g, cam, f.seaState, ambient, sky)

        entities.drawSubmerged(g, f, sea, ambient)
        sea.drawSurfaceDetail(g, cam, ambient)

        if (session.role == Role.TEMPEST) tempestHud.drawLane(g, f, cam, sea)

        entities.drawSurface(g, f, sea, fx, ambient)
        ship.draw(g, f, sea, ambient, f.seaState)
        entities.drawSpells(g, f, sea)

        if (session.role == Role.NAVIGATOR) navHud.drawCastMarker(g, f, cam)

        fx.draw(g, cam.left, cam.right)
        sea.drawForeground(g, cam, f.seaState, ambient)
        g.end()

        // --- HUD, in pixels ------------------------------------------------
        g.begin(app.hudCamera)
        val sw = app.screenW
        val sh = app.screenH

        if (session.role == Role.NAVIGATOR) navHud.draw(g, f, session, cam, sw, sh)
        else tempestHud.draw(g, f, session, cam, sw, sh)

        if (bannerTimer > 0f) {
            app.widgets.banner(
                g, bannerText ?: "", bannerSub, sw * 0.5f, sh * 0.58f,
                bannerTint, MathX.clamp01(bannerTimer * 2.6f)
            )
        }

        if (app.prefs.showStats) drawStats(g, sw, sh)
        if (resultAlpha > 0.01f) drawResultCard(g, sw, sh)

        g.end()
    }

    // -----------------------------------------------------------------------

    /**
     * The round card.
     *
     * Laid out with a text cursor stepped by each font's real line height, and with values
     * right-aligned inside their column. Hand-picked pixel offsets look fine at one font size
     * and collide at the next, which is exactly what happened when this was first written --
     * the title landed on top of its own subtitle.
     */
    private fun drawResultCard(g: com.polariz.aethertides.client.Painter, sw: Float, sh: Float) {
        val res = session.result ?: return
        val s = app.art.uiScale
        val a = resultAlpha

        c.set(Palette.ink); c.a = 0.8f * a
        g.rect(0f, 0f, sw, sh, c)

        val pw = minOf(740f * s, sw - 60f * s)
        val ph = 470f * s
        val x = (sw - pw) * 0.5f
        val y = (sh - ph) * 0.5f
        app.widgets.panel(g, x, y, pw, ph, a)

        val won = res.won
        val title = when {
            !res.seriesOver && session.role == Role.NAVIGATOR &&
                    res.reason == EndReason.SHORE_REACHED.id -> "SHORE REACHED"
            !res.seriesOver -> reasonTitle(res)
            won -> "THE VOYAGE IS YOURS"
            else -> "THE SEA TOOK IT"
        }

        var ty = y + ph - 26f * s
        // Drop to the smaller face rather than letting a long headline run off the card.
        val titleFont =
            if (g.textWidth(app.art.title, title) > pw - 56f * s) app.art.hudLarge else app.art.title
        c.set(if (won) Palette.good else Palette.danger); c.a = a
        g.textCentered(titleFont, title, sw * 0.5f, ty, c)
        ty -= titleFont.lineHeight * 0.95f

        c.set(Palette.textDim); c.a = a
        g.textCentered(
            app.art.small,
            if (res.seriesOver) "BEST RUN TAKES THE MATCH"
            else "ROUND ${res.round + 1} OF 2  ·  NOW SWAP SIDES",
            sw * 0.5f, ty, c
        )
        ty -= app.art.small.lineHeight * 1.9f

        // Two columns, values right-aligned against the column edge.
        val lh = app.art.hud.lineHeight * 1.12f
        val colLeftX = x + 42f * s
        val colLeftEnd = x + pw * 0.46f
        val colRightX = x + pw * 0.54f
        val colRightEnd = x + pw - 42f * s

        fun stat(label: String, value: String, lx: Float, rx: Float, yy: Float, tint: Color) {
            c.set(Palette.textDim); c.a = a
            g.text(app.art.small, label, lx, yy - (app.art.hud.capHeight - app.art.small.capHeight), c)
            c.set(tint); c.a = a
            g.textRight(app.art.hud, value, rx, yy, c)
        }

        stat("DISTANCE", "%.0f m".format(res.distance), colLeftX, colLeftEnd, ty, Palette.textBright)
        stat("TIME", "%.0f s".format(res.duration), colRightX, colRightEnd, ty, Palette.textBright)
        ty -= lh
        stat("DAMAGE TAKEN", "%.0f".format(res.damageTaken), colLeftX, colLeftEnd, ty, Palette.textBright)
        stat("TOP SPEED", "%.0f kn".format(res.topSpeed / 0.5144f), colRightX, colRightEnd, ty, Palette.textBright)
        ty -= lh
        stat("CORSAIRS DOWNED", "${res.corsairsDowned}", colLeftX, colLeftEnd, ty, Palette.textBright)
        stat("HAZARDS SOWN", "${res.hazardsDeployed}", colRightX, colRightEnd, ty, Palette.textBright)
        ty -= lh
        stat("WORKINGS CAST", "${res.spellsCast}", colLeftX, colLeftEnd, ty, Palette.textBright)
        stat("FUSIONS", "${res.fusions}", colRightX, colRightEnd, ty, Palette.singularity)
        ty -= lh
        stat("BEST AIR", "%.1f s".format(res.bestAir), colLeftX, colLeftEnd, ty, Palette.textBright)
        stat("TIME SURFING", "%.0f s".format(res.timeSurfing), colRightX, colRightEnd, ty, Palette.tsunami)
        ty -= lh * 1.15f

        if (res.seriesOver && res.round0 >= 0f && res.round1 >= 0f) {
            c.set(Palette.textFaint); c.a = a
            g.textCentered(
                app.art.small,
                "RUN ONE  %.0f      RUN TWO  %.0f".format(res.round0, res.round1),
                sw * 0.5f, ty, c
            )
        }

        val bw = 260f * s
        val bh = 62f * s
        val by = y + 24f * s
        if (!res.seriesOver) {
            if (app.widgets.button(
                    g, 900, sw * 0.5f - bw * 0.5f, by, bw, bh,
                    "SAIL ROUND 2", app.art.hud, Palette.accent
                )
            ) nextRound()
        } else {
            if (app.widgets.button(
                    g, 901, sw * 0.5f - bw - 10f * s, by, bw, bh,
                    "AGAIN", app.art.hud, Palette.accent
                )
            ) rematch()
            if (app.widgets.button(
                    g, 902, sw * 0.5f + 10f * s, by, bw, bh,
                    "HARBOUR", app.art.hud, Palette.panelEdge
                )
            ) quit()
        }
    }

    private fun reasonTitle(res: RoundResult): String = when (EndReason.of(res.reason)) {
        EndReason.SHORE_REACHED -> "THEY MADE THE SHORE"
        EndReason.HULL_LOST -> "THE HULL GAVE OUT"
        EndReason.CAPSIZED -> "ROLLED AND LOST"
        EndReason.FOUNDERED -> "SHE FOUNDERED"
        EndReason.TIME_UP -> "THE WEATHER BLEW OUT"
        EndReason.FORFEIT -> "ABANDONED"
        EndReason.NONE -> "ROUND OVER"
    }

    private fun nextRound() {
        val local = session as? LocalSession ?: return
        local.nextRound()
        resultShown = false
        resultAlpha = 0f
        started = false
        fx.clear()
        banner("ROUND 2", "YOU ARE NOW THE ${roleName(session.role.other())}", Palette.accent, 2.6f)
    }

    private fun rematch() {
        when (val s = session) {
            is LocalSession -> {
                val fresh = LocalSession(System.nanoTime(), s.role, s.difficulty)
                app.setScreen(PlayScreen(app, fresh))
                dispose()
            }
            is OnlineSession -> {
                s.sendCommand(com.polariz.aethertides.shared.sim.Command.REMATCH, 0, 0f, 0f)
                s.clearResult()
                resultShown = false
                resultAlpha = 0f
                fx.clear()
            }
            else -> quit()
        }
    }

    private fun quit() {
        session.leave()
        app.setScreen(MenuScreen(app))
        dispose()
    }

    // -----------------------------------------------------------------------

    private fun banner(text: String, sub: String?, tint: Color, seconds: Float) {
        bannerText = text
        bannerSub = sub
        bannerTint = tint
        bannerTimer = seconds
    }

    /**
     * Turn one simulation event into sound, light and shake.
     *
     * The rule followed here: anything that costs the player hull shakes the camera in
     * proportion to what it cost, and anything that emits light also lights the sky. Effects
     * that are merely decorative do neither, so the player can trust the feedback.
     */
    private fun handleEvent(e: EventRec, seaState: Float) {
        when (e.kind) {
            EventKind.EXPLOSION -> {
                val m = maxOf(0.4f, e.magnitude)
                Audio.play(Audio.BOOM, e.x, gain = 0.7f + 0.5f * m, pitch = 1.15f - 0.25f * m)
                fx.embers(e.x, e.y, 16f * m, (10 * m).toInt().coerceIn(6, 26), Palette.mineHorn)
                fx.smoke(e.x, e.y, (4 * m).toInt().coerceIn(3, 10), Palette.ink, 3f)
                fx.shock(e.x, e.y, 9f * m, Palette.mineHorn, 0.45f)
                fx.spray(e.x, e.y, 0f, 1f, 11f * m, (7 * m).toInt().coerceIn(5, 18))
                cam.addShake(0.5f * m)
                sky.lightning(0.14f * m)
            }

            EventKind.HULL_IMPACT -> {
                Audio.play(Audio.THUMP, e.x, gain = 0.8f + e.magnitude * 0.03f)
                fx.spray(e.x, e.y, -0.4f, 0.8f, 9f, 8)
                fx.debris(e.x, e.y, 7f, 4, Palette.hullMid)
                fx.text(e.x, e.y + 4f, "-%.0f".format(e.magnitude), Palette.danger)
                cam.addShake(0.28f + e.magnitude * 0.02f)
            }

            EventKind.SPLASH -> {
                Audio.play(Audio.SPLASH, e.x, gain = 0.6f + e.magnitude * 0.5f)
                fx.spray(e.x, e.y, 0f, 1f, 5f + e.magnitude * 5f, 5)
                fx.foam(e.x, e.y, 0f, 2)
            }

            EventKind.LIGHTNING -> {
                Audio.play(Audio.THUNDER, e.x, gain = 0.85f)
                if (e.hasLine) fx.arc(e.x, e.y, e.x2, e.y2, Palette.bolt)
                else fx.shock(e.x, e.y, 5f, Palette.bolt, 0.3f)
                sky.lightning(0.5f)
                cam.addShake(0.22f)
            }

            EventKind.SPELL_CAST -> {
                val kind = SpellKind.of(e.magnitude.toInt())
                Audio.play(
                    when (kind) {
                        SpellKind.GALE -> Audio.CAST_GALE
                        SpellKind.MIST -> Audio.CAST_MIST
                        SpellKind.VOID -> Audio.CAST_VOID
                        else -> Audio.CAST_BOLT
                    },
                    e.x, gain = 0.9f
                )
                fx.shock(e.x, e.y, Spells[kind].radius, Palette.spell(kind.id), 0.4f)
            }

            EventKind.SYNERGY -> {
                val kind = SpellKind.of(e.magnitude.toInt())
                Audio.play(Audio.FUSION, e.x, gain = 1f)
                fx.shock(e.x, e.y, 26f, Palette.spell(kind.id), 0.8f)
                fx.embers(e.x, e.y, 24f, 26, Palette.spell(kind.id))
                sky.lightning(0.35f)
                cam.addShake(0.55f)
                banner(Spells[kind].title, "FUSION", Palette.spell(kind.id), 2.0f)
            }

            EventKind.MAST_BREAK -> {
                Audio.play(Audio.TIMBER, e.x, gain = 1f)
                fx.debris(e.x, e.y, 12f, 12, Palette.mastWood)
                banner("MAST GONE", "CARRY LESS SAIL", Palette.danger, 2.2f)
                cam.addShake(0.7f)
            }

            EventKind.MAST_REPAIR -> {
                Audio.play(Audio.GOOD, e.x, gain = 0.5f)
                fx.text(e.x, e.y + 6f, "RIG RESTORED", Palette.good)
            }

            EventKind.BREACH -> {
                Audio.play(Audio.BUBBLES, e.x, gain = 0.9f)
                fx.bubbles(e.x, e.y, 10)
                fx.text(e.x, e.y + 5f, "BREACH", Palette.bilgeBar)
                cam.addShake(0.4f)
            }

            EventKind.CAPSIZE -> {
                Audio.play(Audio.SPLASH_BIG, e.x, gain = 1f, pitch = 0.85f)
                banner("KNOCKED DOWN", "GET HER BACK UP", Palette.danger, 2.2f)
                fx.spray(e.x, e.y, 0f, 1f, 16f, 24)
                cam.addShake(1.1f)
            }

            EventKind.RIGHTED -> {
                Audio.play(Audio.GOOD, e.x, gain = 0.6f)
                fx.text(e.x, e.y + 6f, "RIGHTED", Palette.good)
            }

            EventKind.DEPLOY -> {
                Audio.play(Audio.KNOCK, e.x, gain = 0.55f, pitch = 0.9f)
                fx.shock(e.x, e.y, 6f, Palette.maliceBar, 0.5f)
                if (session.role == Role.NAVIGATOR) {
                    fx.text(e.x, e.y + 5f, "!", Palette.maliceBar, 0.9f)
                }
            }

            EventKind.PICKUP -> {
                Audio.play(Audio.PICKUP, e.x, gain = 0.7f)
                fx.embers(e.x, e.y, 9f, 14, Palette.mote)
                fx.text(e.x, e.y + 4f, "+%.0f".format(e.magnitude), Palette.mote)
            }

            EventKind.CORSAIR_DOWN -> {
                Audio.play(Audio.CORSAIR_DIE, e.x, gain = 0.65f)
                fx.embers(e.x, e.y, 11f, 10, Palette.corsair)
                fx.smoke(e.x, e.y, 3, Palette.ink, 2f)
                fx.debris(e.x, e.y, 6f, 3, Palette.corsairBomber)
            }

            EventKind.TENTACLE_GRAB -> {
                Audio.play(Audio.RISE, e.x, gain = 0.8f, pitch = 1.3f)
                banner("IT HAS US", "BURN IT OFF", Palette.tentacleSkin, 1.8f)
                cam.addShake(0.6f)
            }

            EventKind.TENTACLE_BREAK -> {
                Audio.play(Audio.CORSAIR_DIE, e.x, gain = 0.8f, pitch = 0.7f)
                fx.embers(e.x, e.y, 12f, 14, Palette.tentacleSkin)
                fx.text(e.x, e.y + 5f, "CUT FREE", Palette.good)
            }

            EventKind.KRAKEN_RISE -> {
                Audio.play(Audio.RISE, e.x, gain = 1f)
                banner("IT RISES", "TWENTY SECONDS", Palette.furyBar, 3f)
                cam.addShake(1.6f)
                sky.lightning(0.4f)
            }

            EventKind.ROGUE_WAVE -> {
                val mine = e.magnitude > 0f
                Audio.play(Audio.SWELL, e.x, gain = 1f, pitch = if (mine) 1.1f else 0.9f)
                banner(
                    if (mine) "TSUNAMI" else "ROGUE WAVE",
                    if (mine) "RIDE IT" else "MEET IT BOW HIGH",
                    if (mine) Palette.tsunami else Palette.danger, 2.4f
                )
                cam.addShake(0.5f)
            }

            EventKind.SNARE -> {
                val kind = SpellKind.of(e.magnitude.toInt())
                Audio.playHere(Audio.SNARE_BIND, gain = 0.9f)
                banner("${Spells[kind].title} BOUND", "EIGHT SECONDS", Palette.singularity, 2.2f)
            }

            EventKind.LEAGUE -> {
                val n = e.magnitude.toInt()
                Audio.playHere(Audio.CHIME, gain = 0.7f)
                banner("LEAGUE $n", "${Config.LEAGUES - n + 1} TO RUN", Palette.accent, 1.7f)
            }

            EventKind.AIRBORNE -> {
                Audio.play(Audio.WHOOSH, e.x, gain = 0.5f)
                fx.foam(e.x, e.y, 0f, 4, 1.4f)
            }

            EventKind.LANDING -> {
                val m = MathX.clamp(e.magnitude / 8f, 0.2f, 2.2f)
                Audio.play(
                    if (m > 1.2f) Audio.SPLASH_BIG else Audio.SPLASH,
                    e.x, gain = 0.5f + 0.5f * m
                )
                fx.spray(e.x, e.y, 0f, 1f, 10f * m, (14 * m).toInt().coerceIn(6, 30))
                fx.foam(e.x, e.y, 0f, (6 * m).toInt(), 1.5f)
                cam.addShake(0.35f * m)
                if (m > 1.2f) fx.text(e.x, e.y + 6f, "HARD LANDING", Palette.danger)
            }

            EventKind.BRACE -> {
                Audio.play(Audio.KNOCK, e.x, gain = 0.5f, pitch = 1.4f)
                fx.shock(e.x, e.y, 11f, Palette.hullTrim, 0.5f)
            }

            EventKind.PUMP -> Audio.play(Audio.PUMP_GULP, e.x, gain = 0.45f)
        }
    }

    override fun dispose() {
        // Art and painter belong to the app, not the screen. The session does not.
        session.leave()
    }
}
