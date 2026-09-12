package com.mythron.aethertides.client

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.mythron.aethertides.client.net.Account
import com.mythron.aethertides.client.screens.ConsentScreen
import com.mythron.aethertides.client.screens.MenuScreen
import com.mythron.aethertides.client.ui.Touch
import com.mythron.aethertides.client.ui.Widgets
import com.mythron.aethertides.shared.net.QueueMode
import com.mythron.aethertides.shared.sim.Role

/**
 * Saved settings and the local half of the account.
 *
 * What lives here is deliberately small: the session tokens, the display name the player
 * asked for, and a few preferences. The record of who they are -- rating, wins, history --
 * lives on the server and is only mirrored here for display. The client cannot edit it.
 */
class Prefs(private val p: Preferences) {

    // --- identity -----------------------------------------------------------
    var accessToken: String
        get() = p.getString("at", "")
        set(v) { p.putString("at", v); p.flush() }

    var refreshToken: String
        get() = p.getString("rt", "")
        set(v) { p.putString("rt", v); p.flush() }

    var userId: String
        get() = p.getString("uid", "")
        set(v) { p.putString("uid", v); p.flush() }

    var tokenExpiresAt: Long
        get() = p.getLong("exp", 0L)
        set(v) { p.putLong("exp", v); p.flush() }

    var isAnonymous: Boolean
        get() = p.getBoolean("anon", true)
        set(v) { p.putBoolean("anon", v); p.flush() }

    /** The name the player wants. The server sanitises it and tells us what it will use. */
    var playerName: String
        get() = p.getString("name", "").ifBlank { "Sailor" }
        set(v) { p.putString("name", v.take(18)); p.flush() }

    // --- profile mirror (server-owned; written only from WELCOME / PROFILE) ---
    var rating: Int
        get() = p.getInteger("rating", 1200)
        set(v) { p.putInteger("rating", v.coerceIn(100, 4000)); p.flush() }

    var matchesPlayed: Int
        get() = p.getInteger("played", 0)
        set(v) { p.putInteger("played", v); p.flush() }

    var matchesWon: Int
        get() = p.getInteger("won", 0)
        set(v) { p.putInteger("won", v); p.flush() }

    var bestDistance: Float
        get() = p.getFloat("bestDistance", 0f)
        set(v) { p.putFloat("bestDistance", v); p.flush() }

    // --- legal ----------------------------------------------------------------
    /** The legal version the player accepted, or 0. Compared against Publish.LEGAL_VERSION. */
    var acceptedLegalVersion: Int
        get() = p.getInteger("legal", 0)
        set(v) { p.putInteger("legal", v); p.flush() }

    // --- preferences ----------------------------------------------------------
    var serverUrl: String
        get() = p.getString("server", "")
        set(v) { p.putString("server", v); p.flush() }

    var difficulty: Float
        get() = p.getFloat("difficulty", 0.6f)
        set(v) { p.putFloat("difficulty", v.coerceIn(0.2f, 1f)); p.flush() }

    var preferredRole: Int
        get() = p.getInteger("role", -1)
        set(v) { p.putInteger("role", v); p.flush() }

    var queueMode: Int
        get() = p.getInteger("queue", QueueMode.QUICK)
        set(v) { p.putInteger("queue", v); p.flush() }

    var showStats: Boolean
        get() = p.getBoolean("stats", false)
        set(v) { p.putBoolean("stats", v); p.flush() }

    var privateCode: String
        get() = p.getString("code", "")
        set(v) { p.putString("code", v.take(12).uppercase()); p.flush() }

    /** Practice results are tracked locally only; they never touch the rating. */
    fun recordPractice(won: Boolean, distance: Float) {
        bestDistance = maxOf(bestDistance, distance)
    }

    /** Wipe everything that identifies this install. Used after account deletion. */
    fun clearIdentity() {
        accessToken = ""; refreshToken = ""; userId = ""; tokenExpiresAt = 0L
        rating = 1200; matchesPlayed = 0; matchesWon = 0
        p.remove("name"); p.flush()
    }
}

/**
 * The application.
 *
 * Owns the things that outlive a screen -- the generated art, the painter, touch state, the
 * account -- and a HUD camera in real pixels so UI code can work in the units it is designed in.
 */
class AetherTides(
    val platform: Platform = Platform.Desktop,
    /** Skip the harbour and drop straight into a practice match. Used by the dev harness. */
    private val bootRole: Role? = null,
    private val bootDifficulty: Float = 0.6f,
    /** Skip the harbour and queue on this server instead. Also dev harness only. */
    private val bootServer: String? = null,
    private val bootName: String = "Harness"
) : Game() {

    lateinit var art: Art
        private set
    lateinit var painter: Painter
        private set
    lateinit var touch: Touch
        private set
    lateinit var widgets: Widgets
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var account: Account
        private set

    /** Pixel-space camera for the HUD: origin bottom-left, one unit per physical pixel. */
    val hudCamera = OrthographicCamera()

    /** The match server this build talks to. Debug builds may be pointed at a dev box. */
    val serverUrl: String
        get() {
            val custom = prefs.serverUrl
            if (custom.isNotBlank() && (platform.isDebug || custom.startsWith("wss://"))) return custom
            return if (platform.isDebug) Publish.DEBUG_SERVER_URL else Publish.SERVER_URL
        }

    override fun create() {
        art = Art()
        art.load()
        painter = Painter(art)
        touch = Touch()
        widgets = Widgets(art, touch)
        prefs = Prefs(Gdx.app.getPreferences("aether-tides"))
        account = Account(prefs, Publish.SUPABASE_URL, Publish.SUPABASE_PUBLISHABLE_KEY)

        Gdx.input.inputProcessor = touch
        Gdx.input.isCatchBackKey = true
        resizeHud(Gdx.graphics.width, Gdx.graphics.height)
        platform.telemetry.screen("launch")

        when {
            bootServer != null ->
                setScreen(com.mythron.aethertides.client.screens.OnlineBootScreen(this, bootServer, bootName))
            bootRole != null -> {
                prefs.difficulty = bootDifficulty
                setScreen(
                    com.mythron.aethertides.client.screens.PlayScreen(
                        this,
                        com.mythron.aethertides.client.net.LocalSession(20260911L, bootRole, bootDifficulty)
                    )
                )
            }
            prefs.acceptedLegalVersion < Publish.LEGAL_VERSION -> setScreen(ConsentScreen(this))
            else -> setScreen(MenuScreen(this))
        }
    }

    private fun resizeHud(width: Int, height: Int) {
        hudCamera.setToOrtho(false, width.toFloat(), height.toFloat())
        hudCamera.update()
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        resizeHud(width, height)
        super.resize(width, height)
    }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f) {
        Gdx.gl.glClearColor(r, g, b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    }

    val screenW: Float get() = Gdx.graphics.width.toFloat()
    val screenH: Float get() = Gdx.graphics.height.toFloat()

    override fun dispose() {
        screen?.dispose()
        painter.dispose()
        art.dispose()
    }
}

/** Shared helper so both screens name roles the same way. */
fun roleName(role: Role): String = if (role == Role.NAVIGATOR) "NAVIGATOR" else "TEMPEST"
