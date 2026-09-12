package com.mythron.aethertides.client

import com.badlogic.gdx.Gdx

/**
 * Analytics and crash reporting, behind an interface so `core` never links against a vendor
 * SDK. The Android launcher supplies a Firebase-backed implementation when the project has
 * been configured for it; everywhere else this stays a no-op.
 *
 * Only aggregate, non-identifying events are ever sent: which screen, which role, how long a
 * match ran, how it ended. Never a name, never an id, never a position.
 */
interface Telemetry {
    fun screen(name: String) {}
    fun event(name: String, vararg params: Pair<String, Any>) {}
    fun error(where: String, t: Throwable) {}

    object None : Telemetry
}

/**
 * What the host platform provides to the game.
 *
 * Kept deliberately thin. Anything that needs an Android API (opening a browser, reading the
 * build type, reporting a crash) goes through here so `core` stays a plain JVM module that the
 * desktop harness and the tests can run.
 */
interface Platform {
    /** Debug builds may talk to a cleartext dev server; release builds never do. */
    val isDebug: Boolean
    val versionName: String
    val telemetry: Telemetry

    /** Open a URL in the system browser. Used for the privacy policy and terms. */
    fun openUrl(url: String) {
        Gdx.net.openURI(url)
    }

    object Desktop : Platform {
        override val isDebug = true
        override val versionName = "dev"
        override val telemetry: Telemetry = Telemetry.None
    }
}

/**
 * Build-time identifiers that the client needs and that must match what was published.
 * Edit these before a release; the launch checklist calls them out.
 */
object Publish {
    /** Your Supabase project. The publishable key is designed to ship inside the app. */
    const val SUPABASE_URL = "https://kldficmkpzgkhfwcforh.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_inPnxTy9cs-f5wLmJLVF4g_UOOnwlOS"

    /** The match server, behind TLS. Debug builds fall back to the emulator alias. */
    const val SERVER_URL = "wss://play.aethertides.example/ws"
    const val DEBUG_SERVER_URL = "ws://10.0.2.2:7788"

    /** Where the legal documents are hosted. Play requires these to be reachable URLs. */
    const val PRIVACY_URL = "https://kanishk2707.github.io/tides/privacy.html"
    const val TERMS_URL = "https://kanishk2707.github.io/tides/terms.html"
    const val DELETE_ACCOUNT_URL = "https://kanishk2707.github.io/tides/delete-account.html"
    const val SUPPORT_EMAIL = "polarizenterprises@gmail.com"

    /** Bump when the legal text changes materially; users are asked to accept again. */
    const val LEGAL_VERSION = 1
}
