package com.mythron.aethertides.desktop

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
 */
class ShotHarness(
    private val inner: ApplicationListener,
    private val outDir: File,
    private val shots: IntArray,
    private val label: String
) : ApplicationListener {

    private var frame = 0

    override fun create() {
        inner.create()
        outDir.mkdirs()
    }

    override fun resize(width: Int, height: Int) = inner.resize(width, height)

    override fun render() {
        inner.render()
        frame++
        if (shots.contains(frame)) capture(frame)
        if (frame >= (shots.maxOrNull() ?: 0)) Gdx.app.exit()
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
}
