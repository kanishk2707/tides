package com.polariz.aethertides.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Noise
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Every pixel in the game is generated at launch.
 *
 * There is not a single painted sprite in the build: glows, sparks, foam puffs and cloud
 * bodies are all written into pixmaps here at startup. That keeps the download tiny, keeps the
 * art consistent with the palette by construction, and means every effect is authored at
 * whatever resolution the device actually has rather than at whatever an artist exported.
 *
 * Fonts are the exception -- typography is not worth faking -- and are rasterised from bundled
 * open-licence faces at a size chosen from the real screen height.
 */
class Art : Disposable {

    lateinit var white: TextureRegion; private set
    /** Radial falloff, for additive light. */
    lateinit var glow: TextureRegion; private set
    /** Soft disc with a solid core, for foam and smoke. */
    lateinit var puff: TextureRegion; private set
    /** Tiny bright point for spray and embers. */
    lateinit var spark: TextureRegion; private set
    /** Thin annulus, for shockwaves and spell boundaries. */
    lateinit var ring: TextureRegion; private set
    /** A vertical soft-edged shaft, for god rays and rain. */
    lateinit var shaft: TextureRegion; private set
    /** Irregular cloud body. */
    lateinit var cloud: TextureRegion; private set

    lateinit var title: BitmapFont; private set
    lateinit var hudLarge: BitmapFont; private set
    lateinit var hud: BitmapFont; private set
    lateinit var small: BitmapFont; private set

    val layout = GlyphLayout()

    private val textures = ArrayList<Texture>()
    private val fonts = ArrayList<BitmapFont>()

    /** UI scale factor derived from the real screen, so a tablet is not a giant phone. */
    var uiScale = 1f
        private set

    fun load() {
        val h = Gdx.graphics.height.toFloat()
        val w = Gdx.graphics.width.toFloat()
        val shortEdge = min(w, h)
        // Tuned against a 1080p phone in landscape; clamped so very small and very large
        // panels both stay usable.
        uiScale = MathX.clamp(shortEdge / 1080f, 0.55f, 2.0f)

        white = region(Pixmap(2, 2, Pixmap.Format.RGBA8888).apply {
            setColor(Color.WHITE); fill()
        })
        glow = region(radial(256) { d -> (1f - d).pow(2.4f) })
        puff = region(radial(128) { d -> MathX.smoothstep(1f, 0.25f, d) })
        spark = region(radial(64) { d -> (1f - d).pow(4.5f) })
        ring = region(annulus(256, 0.80f, 0.055f))
        shaft = region(verticalShaft(64, 256))
        cloud = region(cloudBody(256, 128))

        loadFonts(shortEdge)
    }

    // -----------------------------------------------------------------------
    // Fonts
    // -----------------------------------------------------------------------

    private fun loadFonts(shortEdge: Float) {
        // Rasterise at the size the screen will actually use, so glyphs stay crisp instead of
        // being a scaled-up atlas.
        val base = MathX.clamp(shortEdge / 26f, 16f, 64f)

        title = gen("fonts/title.ttf", (base * 1.55f).toInt(), borderWidth = base * 0.055f,
            borderColor = Palette.ink, shadow = 3)
        hudLarge = gen("fonts/hud.ttf", (base * 1.15f).toInt(), borderWidth = base * 0.035f,
            borderColor = Palette.ink, shadow = 2)
        hud = gen("fonts/hud.ttf", (base * 0.78f).toInt(), borderWidth = base * 0.028f,
            borderColor = Palette.ink, shadow = 2)
        small = gen("fonts/body.ttf", (base * 0.60f).toInt(), borderWidth = base * 0.022f,
            borderColor = Palette.ink, shadow = 1)
    }

    private fun gen(
        path: String, size: Int, borderWidth: Float, borderColor: Color, shadow: Int
    ): BitmapFont {
        val generator = FreeTypeFontGenerator(Gdx.files.internal(path))
        val p = FreeTypeFontGenerator.FreeTypeFontParameter()
        p.size = size
        p.color = Color.WHITE
        // A dark outline plus an offset shadow is what keeps a HUD readable over bright foam
        // and dark storm water without putting a panel behind every label.
        p.borderWidth = borderWidth
        p.borderColor = borderColor
        p.borderStraight = false
        p.shadowOffsetX = shadow
        p.shadowOffsetY = shadow
        p.shadowColor = Color(0f, 0f, 0f, 0.55f)
        p.minFilter = Texture.TextureFilter.Linear
        p.magFilter = Texture.TextureFilter.Linear
        p.characters = FreeTypeFontGenerator.DEFAULT_CHARS + "·•←→↑↓×÷≈≋⟳✵☇⚡●▲✹⬟◌❦➤☁☢☠√°"
        val font = generator.generateFont(p)
        generator.dispose()
        font.setUseIntegerPositions(false)
        fonts.add(font)
        return font
    }

    fun width(font: BitmapFont, text: CharSequence): Float {
        layout.setText(font, text)
        return layout.width
    }

    // -----------------------------------------------------------------------
    // Procedural pixmaps
    // -----------------------------------------------------------------------

    private fun region(p: Pixmap): TextureRegion {
        val t = Texture(p, true)
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        p.dispose()
        textures.add(t)
        return TextureRegion(t)
    }

    /** Radial map: `curve` receives 0 at the centre and 1 at the rim. */
    private inline fun radial(size: Int, curve: (Float) -> Float): Pixmap {
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val c = size * 0.5f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x + 0.5f - c) / c
                val dy = (y + 0.5f - c) / c
                val d = sqrt(dx * dx + dy * dy)
                val a = if (d >= 1f) 0f else MathX.clamp01(curve(d))
                p.setColor(1f, 1f, 1f, a)
                p.drawPixel(x, y)
            }
        }
        return p
    }

    private fun annulus(size: Int, radius: Float, thickness: Float): Pixmap {
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val c = size * 0.5f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x + 0.5f - c) / c
                val dy = (y + 0.5f - c) / c
                val d = sqrt(dx * dx + dy * dy)
                val a = MathX.smoothstep(thickness, 0f, abs(d - radius))
                p.setColor(1f, 1f, 1f, a)
                p.drawPixel(x, y)
            }
        }
        return p
    }

    private fun verticalShaft(w: Int, h: Int): Pixmap {
        val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
        for (y in 0 until h) {
            // Bright at the top, fading out downward: a shaft of light entering water.
            val vy = 1f - y / (h - 1f)
            for (x in 0 until w) {
                val vx = 1f - abs((x + 0.5f) / w * 2f - 1f)
                val a = MathX.clamp01(vx.pow(1.6f) * vy.pow(1.3f))
                p.setColor(1f, 1f, 1f, a)
                p.drawPixel(x, y)
            }
        }
        return p
    }

    /** A cloud: several overlapping soft lobes carved by noise, denser at the bottom. */
    private fun cloudBody(w: Int, h: Int): Pixmap {
        val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = x / (w - 1f)
                val v = y / (h - 1f)
                // Base lobes.
                var d = 0f
                d += lobe(u, v, 0.28f, 0.55f, 0.26f)
                d += lobe(u, v, 0.52f, 0.62f, 0.33f)
                d += lobe(u, v, 0.74f, 0.52f, 0.24f)
                d += lobe(u, v, 0.42f, 0.40f, 0.30f)
                var a = MathX.clamp01(d)
                // Carve with noise so the silhouette is not a row of circles.
                a *= MathX.clamp01(0.55f + 0.75f * (Noise.fbm(u * 6.5f, v * 6.5f, 4, 91) * 0.5f + 0.5f))
                a = MathX.smoothstep(0.22f, 0.7f, a)
                p.setColor(1f, 1f, 1f, a)
                p.drawPixel(x, y)
            }
        }
        return p
    }

    private fun lobe(u: Float, v: Float, cx: Float, cy: Float, r: Float): Float {
        val dx = (u - cx) / r
        val dy = (v - cy) / (r * 0.72f)
        val d = sqrt(dx * dx + dy * dy)
        return MathX.smoothstep(1f, 0.1f, d)
    }

    override fun dispose() {
        textures.forEach { it.dispose() }
        fonts.forEach { it.dispose() }
    }
}
