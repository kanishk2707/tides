package com.polariz.aethertides

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.polariz.aethertides.client.Telemetry

/**
 * Firebase-backed telemetry. Compiled in only when `android/google-services.json` exists.
 *
 * Analytics gets screen views and a handful of aggregate match events. Crashlytics gets
 * crashes and the non-fatal errors the game chooses to report. Neither ever receives a
 * player id, a name, or anything typed by the player; the privacy policy says so, and this
 * class is where that promise is kept.
 */
class FirebaseTelemetry(context: Context) : Telemetry {

    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()

    init {
        crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        analytics.setAnalyticsCollectionEnabled(true)
    }

    override fun screen(name: String) {
        val b = Bundle()
        b.putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
        b.putString(FirebaseAnalytics.Param.SCREEN_CLASS, "libgdx")
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, b)
    }

    override fun event(name: String, vararg params: Pair<String, Any>) {
        val b = Bundle()
        for ((k, v) in params) {
            when (v) {
                is Int -> b.putInt(k, v)
                is Long -> b.putLong(k, v)
                is Float -> b.putDouble(k, v.toDouble())
                is Double -> b.putDouble(k, v)
                is Boolean -> b.putInt(k, if (v) 1 else 0)
                else -> b.putString(k, v.toString().take(100))
            }
        }
        analytics.logEvent(name.take(40), b)
    }

    override fun error(where: String, t: Throwable) {
        crashlytics.log(where)
        crashlytics.recordException(t)
    }
}
