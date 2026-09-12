package com.mythron.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.mythron.aethertides.shared.math.MathX

/**
 * The colour language of the game.
 *
 * Two ideas hold the look together. First, the sea is never one colour: it runs from a deep
 * cold body, through a translucent backlit crest, to white foam, and the whole ramp shifts
 * with the weather. Second, everything is colour-coded by who authored it -- the navigator's
 * aether is cool and luminous, the Tempest's works are warm-shadowed and bruised -- so a
 * glance at the water tells you whose doing it was.
 */
object Palette {

    /** Hex helper. Takes 0xRRGGBBAA as a Long so the alpha byte never overflows an Int. */
    fun rgb(hex: Long): Color = Color(hex.toInt())

    // --- sky ---------------------------------------------------------------
    val skyHighCalm = rgb(0x1d3f6eff)
    val skyLowCalm = rgb(0x8cbcd8ff)
    val skyHighStorm = rgb(0x0d1017ff)
    val skyLowStorm = rgb(0x484d5aff)
    val sunCore = rgb(0xfff6d2ff)
    val sunGlow = rgb(0xffb95eff)
    val cloudLit = rgb(0xd7dde8ff)
    val cloudDark = rgb(0x2b3040ff)

    // --- water -------------------------------------------------------------
    val abyss = rgb(0x03101cff)
    val deepCalm = rgb(0x082c46ff)
    val deepStorm = rgb(0x081d2cff)
    val shallowCalm = rgb(0x176c90ff)
    val shallowStorm = rgb(0x1a5162ff)
    /** Light coming through the back of a crest. This is what sells a wave. */
    val translucentCalm = rgb(0x53c8bbff)
    val translucentStorm = rgb(0x6bb8b2ff)
    val foam = rgb(0xf4fcffff)
    val foamShadow = rgb(0xb2d4e2ff)

    // --- the ship ----------------------------------------------------------
    val hullDark = rgb(0x2c1a11ff)
    val hullMid = rgb(0x6a4425ff)
    val hullLight = rgb(0x9d6c3bff)
    val hullTrim = rgb(0xd9a55bff)
    val deckPlank = rgb(0x8b6b43ff)
    val sailCloth = rgb(0xf1e7d3ff)
    val sailShade = rgb(0xbeaf97ff)
    val mastWood = rgb(0x4b301bff)
    val rigging = rgb(0x2a2118ff)

    // --- the navigator's aether -------------------------------------------
    val gale = rgb(0x8ef0ffff)
    val mist = rgb(0x6fa8ffff)
    val voidWell = rgb(0xb583ffff)
    val bolt = rgb(0xffe066ff)
    val voltaic = rgb(0xd8fbffff)
    val singularity = rgb(0x7b3cffff)
    val tsunami = rgb(0x35e0e8ff)
    val aetherBar = rgb(0x6fe9ffff)

    // --- the Tempest's works ----------------------------------------------
    val mineShell = rgb(0x23262bff)
    val mineHorn = rgb(0xc04a3aff)
    val reefStone = rgb(0x4a3a4fff)
    val reefEdge = rgb(0x8d6f93ff)
    val ice = rgb(0xbfe6f2ff)
    val iceDeep = rgb(0x5d93a8ff)
    val maelstrom = rgb(0x23103aff)
    val tentacle = rgb(0x5c2352ff)
    val tentacleSkin = rgb(0x8e3a72ff)
    val corsair = rgb(0xd8452fff)
    val corsairSkimmer = rgb(0x2fbf8aff)
    val corsairBomber = rgb(0x59626eff)
    val kraken = rgb(0x3a1230ff)
    val maliceBar = rgb(0xff5a4aff)
    val furyBar = rgb(0xff9d2eff)

    // --- pickups -----------------------------------------------------------
    val mote = rgb(0x8ef7d6ff)
    val crate = rgb(0xb98b4eff)

    // --- ui ----------------------------------------------------------------
    val ink = rgb(0x05080dff)
    val panel = rgb(0x0d1420e6)
    val panelEdge = rgb(0x3c5a78ff)
    val textBright = rgb(0xecf4ffff)
    val textDim = rgb(0x8ea4bcff)
    val textFaint = rgb(0x53657bff)
    val hullBar = rgb(0x4ad07aff)
    val mastBar = rgb(0xd9a55bff)
    val bilgeBar = rgb(0x2e7fb8ff)
    val accent = rgb(0x5fd7ffff)
    val danger = rgb(0xff4d4dff)
    val good = rgb(0x5ee08aff)

    private val tmp = Color()

    /** Blend two colours without allocating. Callers must use the result immediately. */
    fun mix(a: Color, b: Color, t: Float): Color {
        val k = MathX.clamp01(t)
        tmp.r = a.r + (b.r - a.r) * k
        tmp.g = a.g + (b.g - a.g) * k
        tmp.b = a.b + (b.b - a.b) * k
        tmp.a = a.a + (b.a - a.a) * k
        return tmp
    }

    private val tmp2 = Color()

    fun alpha(c: Color, a: Float): Color {
        tmp2.set(c)
        tmp2.a = MathX.clamp01(a)
        return tmp2
    }

    /** Colour for a spell, so the HUD button and the zone on the water always agree. */
    fun spell(id: Int): Color = when (id) {
        0 -> gale
        1 -> mist
        2 -> voidWell
        3 -> bolt
        4 -> voltaic
        5 -> singularity
        else -> tsunami
    }
}
