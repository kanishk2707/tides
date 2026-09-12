package com.polariz.aethertides.client.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.client.AetherTides
import com.polariz.aethertides.client.Palette
import com.polariz.aethertides.client.net.OnlineSession
import com.polariz.aethertides.shared.net.QueueMode

/**
 * Development harness screen: queue on a server immediately and hand off to the match as soon
 * as one is found.
 *
 * It exists so the full networked path -- connect, queue, pair, snapshot, interpolate, render
 * -- can be exercised without a human tapping through the menu, which is the only way to
 * verify netcode changes cheaply.
 */
class OnlineBootScreen(
    private val app: AetherTides,
    private val url: String,
    private val name: String
) : ScreenAdapter() {

    private var session: OnlineSession? = null
    private val c = Color()
    private var elapsed = 0f

    override fun show() {
        Gdx.input.inputProcessor = app.touch
        app.account.ensureSignedIn()
    }

    override fun render(delta: Float) {
        elapsed += delta

        // Without an identity provider reachable, fall back to a guest HELLO -- the dev server
        // accepts those. A production server will refuse it, which is the point.
        var sess = session
        if (sess == null) {
            val acct = app.account
            val token = acct.freshToken()
            val giveUp = acct.state == com.polariz.aethertides.client.net.Account.State.FAILED || elapsed > 6f
            if (token != null || giveUp) {
                sess = OnlineSession(url, token ?: "", name, QueueMode.QUICK, -1, "", System.nanoTime())
                sess.connect()
                session = sess
            } else {
                acct.ensureSignedIn()
            }
        }

        sess?.poll(delta)
        if (sess != null && sess.state == OnlineSession.State.IN_MATCH) {
            app.setScreen(PlayScreen(app, sess))
            return
        }

        app.clear(0.02f, 0.05f, 0.09f)
        val g = app.painter
        g.begin(app.hudCamera)
        c.set(Palette.textBright)
        g.textCentered(app.art.hud, sess?.statusText ?: "SIGNING IN", app.screenW * 0.5f, app.screenH * 0.55f, c)
        c.set(Palette.textFaint)
        g.textCentered(app.art.small, url, app.screenW * 0.5f, app.screenH * 0.45f, c)
        g.textCentered(app.art.small, "%.0fs".format(elapsed), app.screenW * 0.5f, app.screenH * 0.40f, c)
        g.end()
    }
}
