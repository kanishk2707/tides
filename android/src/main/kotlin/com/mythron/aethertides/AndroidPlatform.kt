package com.mythron.aethertides

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mythron.aethertides.client.Platform
import com.mythron.aethertides.client.Telemetry

/**
 * The Android half of [Platform].
 *
 * Telemetry is resolved by name at runtime: when the project has a `google-services.json`, the
 * build includes `FirebaseTelemetry` and it is picked up here; when it does not, the class is
 * absent and telemetry is a no-op. That keeps the core game free of any vendor SDK and lets a
 * clone build and run with nothing configured.
 */
class AndroidPlatform(private val context: Context) : Platform {

    override val isDebug: Boolean = BuildConfig.DEBUG
    override val versionName: String = BuildConfig.VERSION_NAME

    override val telemetry: Telemetry = try {
        Class.forName("com.mythron.aethertides.FirebaseTelemetry")
            .getConstructor(Context::class.java)
            .newInstance(context.applicationContext) as Telemetry
    } catch (_: Throwable) {
        Telemetry.None
    }

    override fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // No browser. Nothing sensible to do; the URL is also printed in Settings.
        }
    }
}
