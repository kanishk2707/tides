package com.polariz.aethertides.desktop

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import java.io.File

/**
 * Development screenshot harness.
 *
 * Wraps the game, counts frames, and writes a PNG at each requested frame before exiting. It
 * exists so the look of the sea can be checked without an install cycle, and so a rendering
 * regression shows up as a picture rather than as a description.
 *
 * It can also *drive* the game. Watching a bot sail tells you nothing about whether the helm
 * answers or whether a button under your thumb does what it says, so the harness replays a
 * script of pointer events into the same input processor a phone would feed. Coordinates are
 * in window pixels with a top-left origin, exactly as the platform delivers them.
 */
class ShotHarness(
    private val inner: ApplicationListener,
    private val outDir: File,
    private val shots: IntArray,
    private val label: String,
    private val script: List<InputEvent> = emptyList()
) : ApplicationListener {

    /** One scripted pointer event: what to do, when, and where. */
    class InputEvent(
        @JvmField val frame: Int,
        @JvmField val type: Char,      // 'd' down, 'm' move/drag, 'u' up
        @JvmField val x: Int,
        @JvmField val y: Int,
        @JvmField val pointer: Int
    )

    private var frame = 0
    private var cursor = 0
    private val ordered = script.sortedBy { it.frame }

    override fun create() {
        inner.create()
        outDir.mkdirs()
    }

    override fun resize(width: Int, height: Int) = inner.resize(width, height)

    override fun render() {
        // Input goes in before the frame that is supposed to see it, the same order the
        // platform would deliver it in.
        while (cursor < ordered.size && ordered[cursor].frame <= frame) {
            dispatch(ordered[cursor])
            cursor++
        }
        inner.render()
        frame++
        if (shots.contains(frame)) capture(frame)
        if (frame >= (shots.maxOrNull() ?: 0)) Gdx.app.exit()
    }

    private fun dispatch(e: InputEvent) {
        val ip = Gdx.input.inputProcessor ?: return
        when (e.type) {
            'd' -> ip.touchDown(e.x, e.y, e.pointer, 0)
            'm' -> ip.touchDragged(e.x, e.y, e.pointer)
            'u' -> ip.touchUp(e.x, e.y, e.pointer, 0)
        }
        println("input f=$frame ${e.type} ${e.x},${e.y} p${e.pointer}")
    }

    private fun capture(n: Int) {
        val w = Gdx.graphics.backBufferWidth
        val h = Gdx.graphics.backBufferHeight
        val raw = ScreenUtils.getFrameBufferPixmap(0, 0, w, h)
        // The framebuffer comes back bottom-up; flip it so the PNG is the right way round.
        val flipped = Pixmap(w, h, Pixmap.Format.RGBA8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                flipped.drawPixel(x, h - 1 - y, raw.getPixel(x, y))
            }
        }
        val file = File(outDir, "$label-$n.png")
        PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), flipped)
        raw.dispose()
        flipped.dispose()
        println("captured ${file.absolutePath}")
    }

    override fun pause() = inner.pause()
    override fun resume() = inner.resume()
    override fun dispose() = inner.dispose()

    companion object {
        /**
         * Parse `--input "300 d 900 420; 306 u 900 420; 400 h 200 300 460 0"`.
         *
         *   FRAME d|m|u X Y [POINTER]     one event
         *   FRAME h X Y HOLDFRAMES [P]    down at X,Y, up HOLDFRAMES later
         *   FRAME g X Y X2 Y2 FRAMES [P]  down at X,Y, dragged to X2,Y2 over FRAMES, then up
         */
        fun parse(spec: String): List<InputEvent> {
            val out = ArrayList<InputEvent>()
            for (raw in spec.split(";")) {
                val t = raw.trim()
                if (t.isEmpty()) continue
                val f = t.split(Regex("\\s+"))
                val at = f[0].toIntOrNull() ?: continue
                when (f[1].lowercase()) {
                    "d", "m", "u" -> out.add(
                        InputEvent(at, f[1][0], f[2].toInt(), f[3].toInt(), f.getOrNull(4)?.toIntOrNull() ?: 0)
                    )
                    "h" -> {
                        val x = f[2].toInt(); val y = f[3].toInt()
                        val hold = f[4].toInt()
                        val p = f.getOrNull(5)?.toIntOrNull() ?: 0
                        out.add(InputEvent(at, 'd', x, y, p))
                        out.add(InputEvent(at + hold, 'u', x, y, p))
                    }
                    "g" -> {
                        val x = f[2].toInt(); val y = f[3].toInt()
                        val x2 = f[4].toInt(); val y2 = f[5].toInt()
                        val span = maxOf(1, f[6].toInt())
                        val p = f.getOrNull(7)?.toIntOrNull() ?: 0
                        out.add(InputEvent(at, 'd', x, y, p))
                        for (i in 1..span) {
                            val k = i / span.toFloat()
                            out.add(
                                InputEvent(
                                    at + i, 'm',
                                    (x + (x2 - x) * k).toInt(), (y + (y2 - y) * k).toInt(), p
                                )
                            )
                        }
                        out.add(InputEvent(at + span + 1, 'u', x2, y2, p))
                    }
                }
            }
            return out
        }
    }
}
