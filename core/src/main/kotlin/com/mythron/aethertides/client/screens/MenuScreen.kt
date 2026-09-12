package com.mythron.aethertides.client.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.mythron.aethertides.client.AetherTides
import com.mythron.aethertides.client.Cam
import com.mythron.aethertides.client.Painter
import com.mythron.aethertides.client.Palette
import com.mythron.aethertides.client.Publish
import com.mythron.aethertides.client.net.Account
import com.mythron.aethertides.shared.net.Limits
import com.mythron.aethertides.client.SeaRenderer
import com.mythron.aethertides.client.SkyRenderer
import com.mythron.aethertides.client.Fx
import com.mythron.aethertides.client.net.LocalSession
import com.mythron.aethertides.client.net.OnlineSession
import com.mythron.aethertides.shared.math.MathX
import com.mythron.aethertides.shared.net.QueueMode
import com.mythron.aethertides.shared.ocean.Ocean
import com.mythron.aethertides.shared.ocean.Wind
import com.mythron.aethertides.shared.sim.Config
import com.mythron.aethertides.shared.sim.DeployKind
import com.mythron.aethertides.shared.sim.Deployables
import com.mythron.aethertides.shared.sim.Role
import com.mythron.aethertides.shared.sim.SpellKind
import com.mythron.aethertides.shared.sim.Spells
import kotlin.math.sin

/**
 * The harbour.
 *
 * The menu sits on a live sea rather than a painted backdrop: the same Gerstner ocean, the same
 * sky, the same foam, running in front of the player while they choose. It costs almost
 * nothing and it tells them what the game is before they press anything.
 */
class MenuScreen(private val app: AetherTides) : ScreenAdapter() {

    private enum class Page { MAIN, PRACTICE, ONLINE, GUIDE, SETTINGS, LEGAL, DELETE }

    private val seed = System.nanoTime()
    private val ocean = Ocean(seed, Config.COURSE_LENGTH)
    private val wind = Wind(seed)
    private val sky = SkyRenderer(app.art, seed)
    private val sea = SeaRenderer(app.art, seed)
    private val fx = Fx(app.art)
    private val cam = Cam()

    private var page = Page.MAIN
    private var time = 0f
    private var drift = 0f
    private var guideTab = 0
    private var pendingOnline: OnlineSession? = null
    /** Queue parameters held while sign-in completes, then handed to the session. */
    private var pendingMode = -1
    private var pendingCode = ""
    private var deleting: OnlineSession? = null
    private var deleteConfirmed = false

    private val c = Color()

    init {
        ocean.seaState = 0.42f
        wind.baseSpeed = 12f
    }

    override fun show() {
        Gdx.input.inputProcessor = app.touch
        cam.resize(Gdx.graphics.width, Gdx.graphics.height)
        cam.snapTo(0f, 2f)
    }

    override fun resize(width: Int, height: Int) {
        cam.resize(width, height)
    }

    override fun render(delta: Float) {
        val dt = delta.coerceAtMost(0.05f)
        time += dt
        drift += dt * 7f

        app.touch.beginFrame(dt)
        app.widgets.update(dt)

        // A slow breathing sea: the state wanders so the backdrop is never static.
        ocean.seaState = 0.34f + 0.26f * (0.5f + 0.5f * sin(time * 0.09f))
        ocean.step(dt)
        wind.step(dt)
        wind.baseSpeed = 8f + 14f * ocean.seaState
        sky.update(dt, wind.baseSpeed)
        cam.update(dt, Role.NAVIGATOR, drift, 0f, 6f, ocean.seaState, 66f)
        sea.sample(cam, ocean)
        sea.emit(dt, cam, fx, ocean.seaState)
        fx.update(dt, ocean)

        app.clear()
        val g = app.painter
        g.begin(cam.camera)
        val ambient = sky.ambient(ocean.seaState)
        sky.draw(g, cam, ocean.seaState, wind, drift)
        sea.drawBackdrop(g, cam, ocean, ocean.seaState, ambient)
        sea.drawLightShafts(g, cam, ocean.seaState, ambient)
        sea.drawBody(g, cam, ocean.seaState, ambient, sky)
        sea.drawSurfaceDetail(g, cam, ambient)
        fx.draw(g, cam.left, cam.right)
        sea.drawForeground(g, cam, ocean.seaState, ambient)
        g.end()

        g.begin(app.hudCamera)
        drawScrim(g)
        when (page) {
            Page.MAIN -> drawMain(g)
            Page.PRACTICE -> drawPractice(g)
            Page.ONLINE -> drawOnline(g)
            Page.GUIDE -> drawGuide(g)
            Page.SETTINGS -> drawSettings(g)
            Page.LEGAL -> drawLegal(g)
            Page.DELETE -> drawDelete(g)
        }
        g.end()

        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (page == Page.MAIN) Gdx.app.exit() else page = Page.MAIN
        }

        app.touch.endFrame()
    }

    // -----------------------------------------------------------------------

    private fun drawScrim(g: Painter) {
        // Darken the left third so text stays readable over bright foam, without a hard panel.
        c.set(Palette.ink); c.a = 0.72f
        val edge = Color(Palette.ink); edge.a = 0f
        g.rect(0f, 0f, app.screenW * 0.52f, app.screenH, c, edge, edge, c)
    }

    private fun drawTitle(g: Painter) {
        val s = app.art.uiScale
        val x = 56f * s
        // BitmapFont.draw takes the *top* of the line, so each subsequent line has to step down
        // by the previous line's real height. Hand-picked offsets collide the moment the font
        // size changes with the screen, which is exactly what happened here.
        var y = app.screenH - 64f * s
        c.set(Palette.textBright)
        g.text(app.art.title, "AETHER TIDES", x, y, c)
        y -= app.art.title.lineHeight * 0.92f

        c.set(Palette.accent)
        g.text(app.art.small, "ONE SAILS.  ONE DROWNS THEM.", x + 4f * s, y, c)
        y -= app.art.small.lineHeight * 1.15f

        c.set(Palette.textFaint)
        g.text(
            app.art.small,
            "${app.prefs.playerName}   ·   rating ${app.prefs.rating}   ·   " +
                    "${app.prefs.matchesWon}/${app.prefs.matchesPlayed} won",
            x + 4f * s, y, c
        )
    }

    private fun drawMain(g: Painter) {
        val s = app.art.uiScale
        drawTitle(g)
        val bw = 380f * s
        val bh = 66f * s
        val x = 56f * s
        var y = app.screenH * 0.52f

        if (app.widgets.button(g, 1, x, y, bw, bh, "FIND A MATCH", app.art.hud, Palette.accent)) {
            page = Page.ONLINE
        }
        y -= bh + 16f * s
        if (app.widgets.button(g, 2, x, y, bw, bh, "PRACTICE", app.art.hud, Palette.panelEdge)) {
            page = Page.PRACTICE
        }
        y -= bh + 16f * s
        if (app.widgets.button(g, 3, x, y, bw, bh, "HOW IT WORKS", app.art.hud, Palette.panelEdge)) {
            page = Page.GUIDE
        }
        y -= bh + 16f * s
        if (app.widgets.button(g, 4, x, y, bw * 0.48f, bh * 0.8f, "SETTINGS", app.art.small, Palette.panelEdge)) {
            page = Page.SETTINGS
        }

        if (app.prefs.bestDistance > 1f) {
            c.set(Palette.textFaint)
            g.text(app.art.small, "furthest run  %.0f m".format(app.prefs.bestDistance),
                x, y - 34f * s, c)
        }
    }

    // -----------------------------------------------------------------------

    private fun drawPractice(g: Painter) {
        val s = app.art.uiScale
        drawHeader(g, "PRACTICE", "Play either side against the ship's own crew. Two rounds, roles swapped.")

        val x = 56f * s
        val bw = 400f * s
        val bh = 62f * s
        var y = app.screenH * 0.56f

        if (app.widgets.button(g, 10, x, y, bw, bh, "SAIL AS NAVIGATOR", app.art.hud, Palette.aetherBar)) {
            startLocal(Role.NAVIGATOR)
        }
        y -= bh + 14f * s
        if (app.widgets.button(g, 11, x, y, bw, bh, "PLAY AS THE TEMPEST", app.art.hud, Palette.maliceBar)) {
            startLocal(Role.TEMPEST)
        }

        y -= bh + 30f * s
        c.set(Palette.textDim)
        g.text(app.art.small, "OPPOSITION", x, y + 40f * s, c)
        val labels = arrayOf("DECKHAND", "MATE", "MASTER", "LEVIATHAN")
        val values = floatArrayOf(0.3f, 0.55f, 0.78f, 0.98f)
        val cw = bw / 4f - 8f * s
        for (i in 0 until 4) {
            val selected = kotlin.math.abs(app.prefs.difficulty - values[i]) < 0.06f
            if (app.widgets.button(
                    g, 12 + i, x + i * (cw + 8f * s), y, cw, bh * 0.72f,
                    labels[i], app.art.small,
                    if (selected) Palette.accent else Palette.panelEdge
                )
            ) app.prefs.difficulty = values[i]
        }

        drawBack(g, 19)
    }

    private fun drawOnline(g: Painter) {
        val s = app.art.uiScale
        drawHeader(
            g, "FIND A MATCH",
            "Two rounds on the same sea with the sides swapped. The better run takes the match."
        )

        val x = 56f * s
        val bw = 400f * s
        val bh = 62f * s
        var y = app.screenH * 0.56f

        // A queue request waits here until the account is ready, then becomes a session.
        if (pendingMode >= 0 && pendingOnline == null) {
            val acct = app.account
            when (acct.state) {
                Account.State.SIGNED_IN -> {
                    val token = acct.freshToken()
                    if (token != null) {
                        openSession(token)
                    } else {
                        acct.ensureSignedIn()
                    }
                }
                Account.State.FAILED -> {
                    c.set(Palette.danger)
                    g.text(app.art.hud, "COULD NOT SIGN IN", x, y + bh, c)
                    c.set(Palette.textDim)
                    g.text(app.art.small, acct.lastError.take(80), x, y + bh - 30f * s, c)
                    g.text(app.art.small, "Check your connection. Practice mode works offline.", x, y + bh - 52f * s, c)
                    if (app.widgets.button(g, 29, x, y - bh, bw * 0.5f, bh, "BACK", app.art.hud, Palette.panelEdge)) {
                        pendingMode = -1
                    }
                    return
                }
                else -> {
                    c.set(Palette.textBright)
                    g.text(app.art.hud, "SIGNING IN", x, y + bh, c)
                    c.set(Palette.textFaint)
                    g.text(app.art.small, "Creating your anonymous player account", x, y + bh - 30f * s, c)
                    acct.ensureSignedIn()
                    if (app.widgets.button(g, 29, x, y - bh, bw * 0.5f, bh, "CANCEL", app.art.hud, Palette.panelEdge)) {
                        pendingMode = -1
                    }
                    return
                }
            }
        }

        val online = pendingOnline
        if (online != null) {
            online.poll(Gdx.graphics.deltaTime)
            if (online.profileUpdated) {
                online.profileUpdated = false
                mirrorProfile(online)
            }
            c.set(Palette.textBright)
            g.text(app.art.hud, online.statusText, x, y + bh, c)
            if (online.state == OnlineSession.State.IN_MATCH) {
                app.setScreen(PlayScreen(app, online))
                pendingOnline = null
                pendingMode = -1
                return
            }
            if (online.state == OnlineSession.State.DISCONNECTED ||
                online.state == OnlineSession.State.REJECTED
            ) {
                c.set(Palette.danger)
                g.text(app.art.small, "Could not reach ${app.serverUrl}", x, y + bh - 30f * s, c)
                g.text(app.art.small, "Practice mode works offline.", x, y + bh - 52f * s, c)
            }
            if (app.widgets.button(g, 29, x, y - bh, bw * 0.5f, bh, "CANCEL", app.art.hud, Palette.panelEdge)) {
                online.leave()
                pendingOnline = null
                pendingMode = -1
            }
            return
        }

        if (app.widgets.button(g, 20, x, y, bw, bh, "QUICK MATCH", app.art.hud, Palette.accent)) {
            startOnline(QueueMode.QUICK, "")
        }
        y -= bh + 14f * s
        if (app.widgets.button(g, 21, x, y, bw, bh, "RANKED", app.art.hud, Palette.maliceBar)) {
            startOnline(QueueMode.RANKED, "")
        }
        y -= bh + 14f * s
        val code = app.prefs.privateCode
        val codeOk = code.length >= Limits.PRIVATE_CODE_MIN
        if (app.widgets.button(
                g, 22, x, y, bw * 0.62f, bh,
                if (code.isBlank()) "PRIVATE: SET CODE" else "PRIVATE: $code",
                app.art.small, Palette.panelEdge
            )
        ) {
            promptText("Private match code (${Limits.PRIVATE_CODE_MIN}-${Limits.PRIVATE_CODE_MAX} letters or digits)",
                app.prefs.privateCode) { app.prefs.privateCode = Limits.sanitizeCode(it) }
        }
        if (app.widgets.button(
                g, 23, x + bw * 0.66f, y, bw * 0.34f, bh, "JOIN", app.art.hud, Palette.accent,
                enabled = codeOk
            )
        ) startOnline(QueueMode.PRIVATE, code)
        if (code.isNotBlank() && !codeOk) {
            c.set(Palette.danger)
            g.text(app.art.small, "Code needs at least ${Limits.PRIVATE_CODE_MIN} characters", x, y - 14f * s, c)
        }

        y -= bh + 24f * s
        c.set(Palette.textDim)
        g.text(app.art.small, "PREFERRED SIDE", x, y + 40f * s, c)
        val roles = arrayOf("EITHER", "NAVIGATOR", "TEMPEST")
        val roleVals = intArrayOf(-1, Role.NAVIGATOR.id, Role.TEMPEST.id)
        val cw = bw / 3f - 8f * s
        for (i in 0 until 3) {
            val sel = app.prefs.preferredRole == roleVals[i]
            if (app.widgets.button(
                    g, 24 + i, x + i * (cw + 8f * s), y, cw, bh * 0.72f, roles[i], app.art.small,
                    if (sel) Palette.accent else Palette.panelEdge
                )
            ) app.prefs.preferredRole = roleVals[i]
        }

        c.set(Palette.textFaint)
        val who = if (app.account.isSignedIn) "signed in  ·  ${app.prefs.playerName}  ·  rating ${app.prefs.rating}"
                  else "not signed in yet  ·  an anonymous account is created when you queue"
        g.text(app.art.small, who, x, y - 30f * s, c)
        g.text(app.art.small, "If nobody answers within 12 seconds you get a practice match instead.",
            x, y - 52f * s, c)

        drawBack(g, 28)
    }

    /** Pull the server's copy of the profile into the local mirror. Never the other way. */
    private fun mirrorProfile(online: OnlineSession) {
        val pr = online.profile
        if (pr.userId.isNotBlank()) {
            app.prefs.playerName = pr.name
            app.prefs.rating = pr.rating
            app.prefs.matchesPlayed = pr.played
            app.prefs.matchesWon = pr.won
        }
    }

    private fun drawGuide(g: Painter) {
        val s = app.art.uiScale
        drawHeader(g, "HOW IT WORKS", null)

        val x = 56f * s
        val tabW = 200f * s
        val tabY = app.screenH - 190f * s
        val tabs = arrayOf("THE VOYAGE", "NAVIGATOR", "TEMPEST")
        for (i in tabs.indices) {
            if (app.widgets.button(
                    g, 30 + i, x + i * (tabW + 10f * s), tabY, tabW, 46f * s, tabs[i], app.art.small,
                    if (guideTab == i) Palette.accent else Palette.panelEdge
                )
            ) guideTab = i
        }

        var y = tabY - 44f * s
        val lh = 26f * s
        fun line(text: String, tint: Color = Palette.textDim) {
            c.set(tint)
            g.text(app.art.small, text, x, y, c)
            y -= lh
        }
        fun head(text: String) {
            c.set(Palette.textBright)
            g.text(app.art.hud, text, x, y, c)
            y -= lh * 1.35f
        }

        when (guideTab) {
            0 -> {
                head("TWO ROUNDS, ONE SEA")
                line("Both players sail the same course, from the same seed, against the same opposition.")
                line("Round one you sail; round two you sow. The better run takes the match, so the")
                line("result measures the players and not the balance of the two sides.")
                y -= lh * 0.4f
                head("THE WATER IS REAL")
                line("The sea is a sum of travelling waves obeying deep-water dispersion: long swell")
                line("outruns short chop. The hull floats by displacement at eleven stations along the")
                line("keel. Nothing about her motion is animation -- she pitches because the bow is")
                line("lifted before the stern, and she surfs because buoyancy on a slope pushes forward.")
                y -= lh * 0.4f
                head("WHY THAT MATTERS")
                line("A displacement hull cannot outrun her own bow wave. Roughly %.0f knots is the wall."
                    .format(1.34f * kotlin.math.sqrt(Config.HULL_LENGTH * 3.28084f) * Config.HULL_SPEED_MULT))
                line("The only way past it is to catch a wave face and let the water do the work.")
                line("Which means the Tempest raising the sea helps you, if you can sail it.")
            }

            1 -> {
                head("SAILING HER")
                line("Left thumb is the helm. Up is sail, down is reefed. Left and right move crew weight.")
                line("Weight aft on a rising face launches her off the crest; weight forward lands flat.")
                line("Carrying full sail in too much wind will break the mast. Watch the rig bar.")
                line("Water comes aboard through breaches and over the rail. Pump it, or founder.")
                y -= lh * 0.5f
                head("THE FOUR WORKINGS")
                for (k in SpellKind.base) {
                    val d = Spells[k]
                    c.set(Palette.spell(k.id))
                    g.text(app.art.small, "${d.glyph}  ${d.title}", x, y, c)
                    c.set(Palette.textDim)
                    g.text(app.art.small, d.blurb, x + 150f * s, y, c)
                    y -= lh
                }
                y -= lh * 0.4f
                head("FUSIONS")
                line("Land two workings so their circles overlap and they collapse into something else.")
                line("Mist + Bolt is a holding field. Gale + Void is a lance. Gale + Mist is your own wave.")
            }

            2 -> {
                head("SOWING THE WATER")
                line("You are not chasing them -- you are authoring the sea they have not reached yet.")
                line("Everything must be placed inside the marked band ahead of their bow.")
                line("Malice accrues over time and faster when you are hurting them.")
                line("The storm dial raises the sea for both of you, and costs upkeep to hold.")
                line("Careful: rough water is also the only water worth surfing.")
                y -= lh * 0.5f
                head("THE TOOLS")
                var i = 0
                for (k in Deployables.bar) {
                    if (i >= 7) break
                    val d = Deployables[k]
                    c.set(Palette.maliceBar)
                    g.text(app.art.small, "${d.glyph}  ${d.title}", x, y, c)
                    c.set(Palette.textDim)
                    g.text(app.art.small, d.blurb, x + 150f * s, y, c)
                    y -= lh
                    i++
                }
            }
        }

        drawBack(g, 39)
    }

    private fun drawSettings(g: Painter) {
        val s = app.art.uiScale
        drawHeader(g, "SETTINGS", null)
        val x = 56f * s
        val bw = 400f * s
        val bh = 56f * s
        var y = app.screenH * 0.60f

        if (app.widgets.button(g, 40, x, y, bw, bh, "NAME: ${app.prefs.playerName}", app.art.small, Palette.panelEdge)) {
            promptText("Display name (${Limits.NAME_MIN}-${Limits.NAME_MAX} characters)", app.prefs.playerName) {
                val clean = Limits.sanitizeName(it)
                if (Limits.nameIsValid(clean)) app.prefs.playerName = clean
            }
        }
        y -= bh + 12f * s

        if (app.platform.isDebug) {
            if (app.widgets.button(g, 41, x, y, bw, bh, "DEV SERVER", app.art.small, Palette.panelEdge)) {
                promptText("Match server URL (debug builds only)", app.serverUrl) { app.prefs.serverUrl = it.trim() }
            }
            c.set(Palette.textFaint)
            g.text(app.art.small, app.serverUrl, x + 8f * s, y - 20f * s, c)
            y -= bh + 30f * s
        }

        val statsOn = app.prefs.showStats
        if (app.widgets.button(
                g, 42, x, y, bw, bh,
                if (statsOn) "PERFORMANCE READOUT: ON" else "PERFORMANCE READOUT: OFF",
                app.art.small, if (statsOn) Palette.accent else Palette.panelEdge
            )
        ) app.prefs.showStats = !statsOn
        y -= bh + 12f * s

        if (app.widgets.button(g, 43, x, y, bw, bh, "PRIVACY, TERMS & LICENCES", app.art.small, Palette.panelEdge)) {
            page = Page.LEGAL
        }
        y -= bh + 12f * s

        if (app.widgets.button(
                g, 44, x, y, bw, bh, "DELETE ACCOUNT", app.art.small, Palette.danger,
                enabled = app.account.isSignedIn
            )
        ) {
            deleteConfirmed = false
            page = Page.DELETE
        }
        y -= bh + 24f * s

        c.set(Palette.textFaint)
        val idText = if (app.account.isSignedIn) "player id  ${app.prefs.userId.take(8)}…  (anonymous account)"
                     else "no account yet  ·  one is created the first time you play online"
        g.text(app.art.small, idText, x, y, c)
        g.text(app.art.small, "v${app.platform.versionName}  ·  protocol ${Config.PROTOCOL_VERSION}  ·  sim ${Config.TICK_RATE} Hz",
            x, y - 24f * s, c)

        drawBack(g, 49)
    }

    private fun drawLegal(g: Painter) {
        val s = app.art.uiScale
        drawHeader(g, "LEGAL", "The documents that govern the game, and the open-source work it stands on.")
        val x = 56f * s
        val bw = 400f * s
        val bh = 52f * s
        var y = app.screenH * 0.60f

        if (app.widgets.button(g, 50, x, y, bw, bh, "PRIVACY POLICY", app.art.small, Palette.panelEdge)) {
            app.platform.openUrl(Publish.PRIVACY_URL)
        }
        y -= bh + 10f * s
        if (app.widgets.button(g, 51, x, y, bw, bh, "TERMS OF SERVICE", app.art.small, Palette.panelEdge)) {
            app.platform.openUrl(Publish.TERMS_URL)
        }
        y -= bh + 26f * s

        val lh = app.art.small.lineHeight * 1.2f
        fun line(t: String, tint: Color = Palette.textDim) { c.set(tint); g.text(app.art.small, t, x, y, c); y -= lh }
        line("OPEN-SOURCE SOFTWARE", Palette.textBright)
        line("libGDX — Apache License 2.0 — libgdx.com")
        line("Java-WebSocket (TooTallNate) — MIT License")
        line("Kotlin standard library — Apache License 2.0")
        line("Cinzel Decorative (Natanael Gama) — SIL Open Font License 1.1")
        line("Rajdhani (Indian Type Foundry) — SIL Open Font License 1.1")
        y -= lh * 0.4f
        line("Support: ${Publish.SUPPORT_EMAIL}", Palette.textFaint)

        if (app.widgets.button(g, 59, 56f * s, 40f * s, 200f * s, 56f * s, "BACK", app.art.small, Palette.panelEdge)) {
            page = Page.SETTINGS
        }
    }

    /**
     * Account deletion, as the stores require: in-app, two steps, and complete. The server
     * removes the auth user and every row tied to it, then closes the socket; we wipe the
     * local copy when the confirmation arrives.
     */
    private fun drawDelete(g: Painter) {
        val s = app.art.uiScale
        drawHeader(g, "DELETE ACCOUNT", null)
        val x = 56f * s
        val bw = 400f * s
        val bh = 58f * s
        var y = app.screenH * 0.58f

        val lh = app.art.small.lineHeight * 1.2f
        fun line(t: String, tint: Color = Palette.textDim) { c.set(tint); g.text(app.art.small, t, x, y, c); y -= lh }

        val session = deleting
        if (session != null) {
            session.poll(Gdx.graphics.deltaTime)
            when (session.state) {
                OnlineSession.State.DELETED -> {
                    app.account.signOutLocally()
                    app.prefs.clearIdentity()
                    deleting = null
                    line("Your account and its data have been deleted.", Palette.good)
                    if (app.widgets.button(g, 69, x, y - bh, 200f * s, bh, "DONE", app.art.small, Palette.panelEdge)) {
                        page = Page.MAIN
                    }
                }
                OnlineSession.State.DISCONNECTED, OnlineSession.State.REJECTED -> {
                    line("Could not reach the server to delete the account.", Palette.danger)
                    line("Try again with a connection, or email ${Publish.SUPPORT_EMAIL}.")
                    deleting = null
                    if (app.widgets.button(g, 69, x, y - bh, 200f * s, bh, "BACK", app.art.small, Palette.panelEdge)) {
                        page = Page.SETTINGS
                    }
                }
                OnlineSession.State.QUEUED -> {
                    // Authenticated. Now, and not before, the request can be honoured.
                    session.requestAccountDeletion()
                    line("Deleting…")
                }
                else -> line("Connecting…  ${session.statusText}")
            }
            return
        }

        line("This permanently removes your player account, your rating, your match history,")
        line("and your display name from our servers. It cannot be undone.")
        line("Practice mode will keep working. A new anonymous account is created if you play")
        line("online again.")
        y -= lh * 0.6f

        if (app.widgets.button(
                g, 60, x, y - bh, bw, bh,
                if (deleteConfirmed) "I UNDERSTAND  ✓" else "I UNDERSTAND",
                app.art.small, if (deleteConfirmed) Palette.good else Palette.panelEdge
            )
        ) deleteConfirmed = !deleteConfirmed
        y -= bh + 16f * s

        if (app.widgets.button(
                g, 61, x, y - bh, bw, bh, "DELETE MY ACCOUNT", app.art.hud, Palette.danger,
                enabled = deleteConfirmed
            )
        ) {
            val token = app.account.freshToken()
            if (token == null) {
                app.account.ensureSignedIn()
            } else {
                val sess = OnlineSession(app.serverUrl, token, app.prefs.playerName, QueueMode.QUICK, -1, "", 1L)
                sess.connect()
                deleting = sess
            }
        }

        if (app.widgets.button(g, 69, 56f * s, 40f * s, 200f * s, 56f * s, "BACK", app.art.small, Palette.panelEdge)) {
            page = Page.SETTINGS
        }
    }

    // -----------------------------------------------------------------------

    private fun drawHeader(g: Painter, title: String, sub: String?) {
        val s = app.art.uiScale
        var y = app.screenH - 64f * s
        c.set(Palette.textBright)
        g.text(app.art.title, title, 56f * s, y, c)
        if (sub != null) {
            y -= app.art.title.lineHeight * 0.92f
            c.set(Palette.textDim)
            g.text(app.art.small, sub, 58f * s, y, c)
        }
    }

    private fun drawBack(g: Painter, id: Int) {
        val s = app.art.uiScale
        if (app.widgets.button(g, id, 56f * s, 40f * s, 200f * s, 56f * s, "BACK", app.art.small, Palette.panelEdge)) {
            page = Page.MAIN
        }
    }

    private fun startLocal(role: Role) {
        val session = LocalSession(System.nanoTime(), role, app.prefs.difficulty)
        app.setScreen(PlayScreen(app, session))
    }

    private fun startOnline(mode: Int, code: String) {
        app.prefs.queueMode = mode
        pendingMode = mode
        pendingCode = code
        app.account.ensureSignedIn()
    }

    private fun openSession(token: String) {
        val sess = OnlineSession(
            app.serverUrl, token, app.prefs.playerName,
            pendingMode, app.prefs.preferredRole, pendingCode, System.nanoTime()
        )
        sess.connect()
        pendingOnline = sess
        app.platform.telemetry.event("queue", "mode" to pendingMode)
    }

    /** Platform text entry. On Android this is the system IME; on desktop, a small dialog. */
    private fun promptText(title: String, current: String, onDone: (String) -> Unit) {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) {
                if (text.isNotBlank()) Gdx.app.postRunnable { onDone(text.trim()) }
            }
            override fun canceled() {}
        }, title, current, "")
    }

    override fun dispose() {}
}
