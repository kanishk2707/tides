package com.polariz.aethertides.client.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.client.AetherTides
import com.polariz.aethertides.client.Painter
import com.polariz.aethertides.client.Palette
import com.polariz.aethertides.client.Publish

/**
 * First launch.
 *
 * One screen, three facts, one button. Stores and regulators require that a player has been
 * told what the app collects and has agreed to the terms before an account is created; this
 * is where that happens, and it is the only thing between the icon and the harbour. No
 * account exists until the button is pressed.
 *
 * Kept short on purpose. A wall of text nobody reads is not consent, it is theatre.
 */
class ConsentScreen(private val app: AetherTides) : ScreenAdapter() {

    private val c = Color()
    private var ageConfirmed = false

    override fun show() {
        Gdx.input.inputProcessor = app.touch
        app.platform.telemetry.screen("consent")
    }

    override fun render(delta: Float) {
        app.touch.beginFrame(delta)
        app.widgets.update(delta)

        app.clear(0.02f, 0.04f, 0.07f)
        val g: Painter = app.painter
        g.begin(app.hudCamera)

        val s = app.art.uiScale
        val sw = app.screenW
        val sh = app.screenH
        val x = 64f * s
        var y = sh - 70f * s

        c.set(Palette.textBright)
        g.text(app.art.title, "BEFORE YOU SAIL", x, y, c)
        y -= app.art.title.lineHeight * 1.05f

        val lh = app.art.small.lineHeight * 1.25f
        fun line(text: String, tint: Color = Palette.textDim) {
            c.set(tint); g.text(app.art.small, text, x, y, c); y -= lh
        }

        line("Aether Tides creates an anonymous player account so you can play online. No email,")
        line("no password. It stores a display name you choose, your rating, and match results.")
        y -= lh * 0.4f
        line("It does not collect your location, contacts, or anything from other apps.")
        line("Practice mode works entirely offline and sends nothing.")
        y -= lh * 0.4f
        line("You can delete the account, and everything tied to it, from Settings at any time.")
        y -= lh * 0.8f

        line("Aether Tides is for players aged 13 and over.", Palette.textBright)
        y -= lh * 0.6f

        val bw = 300f * s
        val bh = 56f * s
        if (app.widgets.button(
                g, 700, x, y - bh, bw, bh,
                if (ageConfirmed) "I AM 13 OR OLDER  ✓" else "I AM 13 OR OLDER",
                app.art.small, if (ageConfirmed) Palette.good else Palette.panelEdge
            )
        ) ageConfirmed = !ageConfirmed
        y -= bh + lh * 1.2f

        line("By continuing you agree to the Terms of Service and acknowledge the Privacy Policy.",
            Palette.textFaint)
        y -= lh * 0.3f

        val lw = 220f * s
        val lhh = 48f * s
        if (app.widgets.button(g, 701, x, y - lhh, lw, lhh, "PRIVACY POLICY", app.art.small, Palette.panelEdge)) {
            app.platform.openUrl(Publish.PRIVACY_URL)
        }
        if (app.widgets.button(g, 702, x + lw + 16f * s, y - lhh, lw, lhh, "TERMS OF SERVICE", app.art.small, Palette.panelEdge)) {
            app.platform.openUrl(Publish.TERMS_URL)
        }

        // The one button that matters, bottom right, enabled only once age is confirmed.
        val cw = 320f * s
        val ch = 66f * s
        if (app.widgets.button(
                g, 703, sw - cw - 48f * s, 40f * s, cw, ch, "AGREE AND CONTINUE",
                app.art.hud, Palette.accent, enabled = ageConfirmed
            )
        ) {
            app.prefs.acceptedLegalVersion = Publish.LEGAL_VERSION
            app.platform.telemetry.event("consent_accepted", "version" to Publish.LEGAL_VERSION)
            app.setScreen(MenuScreen(app))
        }

        c.set(Palette.textFaint)
        g.text(app.art.small, "v${app.platform.versionName}  ·  ${Publish.SUPPORT_EMAIL}", x, 56f * s, c)

        g.end()
        app.touch.endFrame()
    }
}
