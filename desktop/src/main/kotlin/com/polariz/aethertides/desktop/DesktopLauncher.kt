package com.polariz.aethertides.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.polariz.aethertides.client.AetherTides
import com.polariz.aethertides.client.Platform
import com.polariz.aethertides.shared.sim.Role
import java.io.File

/**
 * Development harness.
 *
 * Not a shipping target -- it exists so the sea, the hull solver and the HUD can be iterated on
 * in seconds instead of through an install cycle. The window defaults to a phone aspect ratio
 * so the HUD is laid out against the shape it will actually run on.
 *
 * Flags:
 *   --role nav|tempest   boot straight into a practice match
 *   --shot DIR           write PNGs and exit (implies a fixed frame budget)
 *   --frames a,b,c       which frames to capture
 *   --size WxH           window size
 *   --input SCRIPT       replay pointer events, so the controls can actually be tried
 */
private const val MIN_W = 900
private const val MIN_H = 450

fun main(args: Array<String>) {
    var role: Role? = null
    var shotDir: String? = null
    var frames = intArrayOf(240, 600, 1100)
    var width = 1440
    var height = 720
    var label = "shot"
    var difficulty = 0.65f
    var server: String? = null
    var name = "Harness"
    var input: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--role" -> {
                role = if (args.getOrNull(i + 1) == "tempest") Role.TEMPEST else Role.NAVIGATOR
                i++
            }
            "--shot" -> { shotDir = args.getOrNull(i + 1); i++ }
            "--frames" -> {
                frames = args.getOrNull(i + 1)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toIntArray() ?: frames
                i++
            }
            "--size" -> {
                val parts = args.getOrNull(i + 1)?.split("x")
                width = parts?.getOrNull(0)?.toIntOrNull() ?: width
                height = parts?.getOrNull(1)?.toIntOrNull() ?: height
                i++
            }
            "--label" -> { label = args.getOrNull(i + 1) ?: label; i++ }
            "--difficulty" -> { difficulty = args.getOrNull(i + 1)?.toFloatOrNull() ?: difficulty; i++ }
            "--online" -> { server = args.getOrNull(i + 1); i++ }
            "--name" -> { name = args.getOrNull(i + 1) ?: name; i++ }
            "--input" -> { input = args.getOrNull(i + 1); i++ }
        }
        i++
    }

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Aether Tides")
        // The minimum has to be honoured here rather than left to the window manager: asking
        // for a window smaller than the limit gets a clamped window but an unclamped
        // backbuffer, and the game then renders into a corner of it with black on two sides.
        setWindowedMode(maxOf(width, MIN_W), maxOf(height, MIN_H))
        setWindowSizeLimits(MIN_W, MIN_H, 4096, 2160)
        useVsync(shotDir == null)
        // Capture runs at a real 60 Hz too: the game is driven by wall-clock delta, so letting
        // it free-run would make "frame 600" mean a different moment every time.
        setForegroundFPS(60)
        setBackBufferConfig(8, 8, 8, 8, 0, 0, 2)
        if (shotDir != null) setInitialVisible(false)
    }

    val game = AetherTides(Platform.Desktop, role, difficulty, server, name)
    if (shotDir != null) {
        val script = input?.let { ShotHarness.parse(it) } ?: emptyList()
        Lwjgl3Application(ShotHarness(game, File(shotDir), frames, label, script), config)
    } else {
        Lwjgl3Application(game, config)
    }
}
