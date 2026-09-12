package com.polariz.aethertides.client

import com.badlogic.gdx.graphics.Color
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.net.EntityRec
import com.polariz.aethertides.shared.net.SnapshotFrame
import com.polariz.aethertides.shared.net.SpellRec
import com.polariz.aethertides.shared.sim.EntityKind
import com.polariz.aethertides.shared.sim.SpellKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hazards, corsairs, pickups and workings.
 *
 * Everything the Tempest makes is drawn with hard edges and bruised colour; everything the
 * navigator makes is drawn as light. A player should be able to tell at a glance whose thing
 * is in the water in front of them, because in this game that determines whether you answer it
 * or sail around it.
 *
 * Submerged hazards get depth treatment rather than being drawn flat on top of the sea: a reef
 * head fades toward the water colour the deeper it sits, so it genuinely disappears under a
 * crest and looms out of a trough. That visibility cycle is the reef's entire counterplay.
 */
class EntityRenderer(private val art: Art) {

    private val c = Color()
    private val c2 = Color()
    private val pts = FloatArray(40)
    private var time = 0f

    fun update(dt: Float) {
        time += dt
    }

    // -----------------------------------------------------------------------

    fun drawSubmerged(g: Painter, f: SnapshotFrame, sea: SeaRenderer, ambient: Float) {
        for (i in 0 until f.entityCount) {
            val e = f.entities[i]
            if (e.kind != EntityKind.REEF_SPIKE) continue
            reef(g, e, sea, ambient)
        }
    }

    fun drawSurface(g: Painter, f: SnapshotFrame, sea: SeaRenderer, fx: Fx, ambient: Float) {
        // Two passes so the big field effects sit behind the objects inside them.
        for (i in 0 until f.entityCount) {
            val e = f.entities[i]
            when (e.kind) {
                EntityKind.MAELSTROM -> maelstrom(g, e, sea, ambient)
                EntityKind.KRAKEN -> kraken(g, e, sea, ambient)
                else -> {}
            }
        }
        for (i in 0 until f.entityCount) {
            val e = f.entities[i]
            when (e.kind) {
                EntityKind.DRIFT_MINE -> mine(g, e, ambient)
                EntityKind.ICE_FLOE -> floe(g, e, ambient)
                EntityKind.TENTACLE -> tentacle(g, e, sea, ambient)
                EntityKind.SEEKER -> seeker(g, e, ambient)
                EntityKind.SKIMMER -> skimmer(g, e, ambient)
                EntityKind.BOMBER -> bomber(g, e, ambient)
                EntityKind.BOMB -> bomb(g, e, ambient)
                EntityKind.AETHER_MOTE -> mote(g, e)
                EntityKind.SALVAGE_CRATE -> crate(g, e, ambient)
                EntityKind.DEBRIS -> debris(g, e, ambient)
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // The Tempest's works
    // -----------------------------------------------------------------------

    private fun reef(g: Painter, e: EntityRec, sea: SeaRenderer, ambient: Float) {
        val surface = sea.surfaceYAt(e.x)
        val depth = surface - e.y
        // The deeper it is, the more water is between it and the camera.
        val visibility = MathX.clamp01(1f - depth / 7f)
        if (visibility <= 0.02f) return

        val exposed = depth < 1.2f
        tint(Palette.reefStone, ambient * (0.5f + 0.5f * visibility), c)
        c.a = 0.35f + 0.65f * visibility

        // Three jagged heads of coral.
        var n = 0
        pts[n++] = e.x - 3.2f; pts[n++] = e.y - 2.4f
        pts[n++] = e.x - 2.1f; pts[n++] = e.y + 1.0f
        pts[n++] = e.x - 1.1f; pts[n++] = e.y - 0.6f
        pts[n++] = e.x + 0.1f; pts[n++] = e.y + 2.3f
        pts[n++] = e.x + 1.2f; pts[n++] = e.y - 0.4f
        pts[n++] = e.x + 2.3f; pts[n++] = e.y + 1.3f
        pts[n++] = e.x + 3.3f; pts[n++] = e.y - 2.4f
        g.fan(e.x, e.y - 2.2f, pts, n / 2, c)

        tint(Palette.reefEdge, ambient * (0.6f + 0.4f * visibility), c2)
        c2.a = c.a
        g.outline(pts, n / 2, 0.12f, c2, closed = false)

        // When a trough exposes it, it breaks the surface and gets a warning glint.
        if (exposed) {
            c2.set(Palette.foam)
            c2.a = 0.7f * visibility
            g.line(e.x - 3f, surface, e.x + 3f, surface, 0.3f, c2)
            g.additive(true)
            g.glow(e.x, e.y + 2f, 2.2f, Palette.alpha(Palette.danger, 0.30f * visibility))
            g.additive(false)
        }
    }

    private fun mine(g: Painter, e: EntityRec, ambient: Float) {
        val r = 1.35f
        // Spiked shell riding the swell, tilted to the local slope.
        tint(Palette.mineShell, ambient, c)
        tint(Palette.mineHorn, ambient, c2)
        for (k in 0 until 8) {
            val a = e.angle + MathX.TAU * k / 8f + time * 0.25f
            g.line(
                e.x + cos(a) * r * 0.8f, e.y + sin(a) * r * 0.8f,
                e.x + cos(a) * r * 1.55f, e.y + sin(a) * r * 1.55f,
                0.22f, c2
            )
        }
        g.circle(e.x, e.y, r, 14, c)
        tint(Palette.reefEdge, ambient * 0.8f, c2)
        g.ringLine(e.x, e.y, r * 0.55f, 0.12f, 12, c2)

        // The arming light, pulsing. Faster once it is about to chain.
        val rate = if (e.phase < 0f) 14f else 3.4f
        val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(time * rate))
        g.additive(true)
        g.glow(e.x, e.y, 2.6f, Palette.alpha(Palette.danger, 0.42f * pulse))
        g.additive(false)
    }

    private fun floe(g: Painter, e: EntityRec, ambient: Float) {
        val w = 4.6f
        val h = 1.9f
        tint(Palette.ice, ambient, c)
        c.a = 0.92f
        tint(Palette.iceDeep, ambient * 0.8f, c2)

        var n = 0
        pts[n++] = e.x - w; pts[n++] = e.y - 0.2f
        pts[n++] = e.x - w * 0.55f; pts[n++] = e.y + h
        pts[n++] = e.x + w * 0.1f; pts[n++] = e.y + h * 0.72f
        pts[n++] = e.x + w * 0.6f; pts[n++] = e.y + h * 1.15f
        pts[n++] = e.x + w; pts[n++] = e.y - 0.3f
        pts[n++] = e.x + w * 0.4f; pts[n++] = e.y - h * 1.3f
        pts[n++] = e.x - w * 0.5f; pts[n++] = e.y - h * 1.1f
        g.fan(e.x, e.y, pts, n / 2, c)

        // Cracks, and the darker mass hanging below the waterline.
        c2.a = 0.75f
        g.line(e.x - 1.4f, e.y + h * 0.8f, e.x + 0.3f, e.y - 1.2f, 0.1f, c2)
        g.line(e.x + 1.1f, e.y + h * 0.6f, e.x + 1.9f, e.y - 0.9f, 0.08f, c2)
        // Damage: a floe that has taken a hit is visibly fractured.
        if (e.hp < 0.9f) {
            c2.set(Palette.iceDeep); c2.a = 0.9f
            g.line(e.x - 2.2f, e.y + h, e.x + 1.2f, e.y - h, 0.16f, c2)
        }
    }

    private fun maelstrom(g: Painter, e: EntityRec, sea: SeaRenderer, ambient: Float) {
        val r = 16f
        val surface = sea.surfaceYAt(e.x)
        // A funnel: nested ellipses sinking and rotating, darkening toward the throat.
        for (k in 6 downTo 0) {
            val t = k / 6f
            val rr = r * (0.18f + 0.82f * t)
            val depth = (1f - t) * 6.5f
            c.set(Palette.maelstrom).lerp(Palette.shallowStorm, t * 0.65f)
            c.r *= ambient; c.g *= ambient; c.b *= ambient
            c.a = 0.55f + 0.4f * (1f - t)
            // Flattened, because we are looking at it nearly edge-on.
            drawEllipse(g, e.x, surface - depth, rr, rr * 0.30f, c)
        }
        // Spiralling foam streaks on the rim.
        c.set(Palette.foam); c.a = 0.45f
        for (k in 0 until 7) {
            val a = time * 2.6f + MathX.TAU * k / 7f
            val rr = r * (0.55f + 0.4f * (0.5f + 0.5f * sin(a * 1.3f)))
            g.line(
                e.x + cos(a) * rr, surface - 0.5f + sin(a) * rr * 0.28f,
                e.x + cos(a + 0.5f) * rr * 0.85f, surface - 1.4f + sin(a + 0.5f) * rr * 0.26f,
                0.22f, c
            )
        }
    }

    private fun tentacle(g: Painter, e: EntityRec, sea: SeaRenderer, ambient: Float) {
        val base = sea.surfaceYAt(e.x)
        val height = if (e.grabbing) 11f else 8f
        val segs = 9
        tint(Palette.tentacle, ambient, c)
        tint(Palette.tentacleSkin, ambient, c2)

        var px = e.x
        var py = base - 1f
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            // A curling limb: the sway is phase-shifted up its length so it whips rather than
            // swinging as a rigid bar.
            val sway = sin(time * 2.1f + e.phase + t * 3.4f) * (1.4f + 3.4f * t)
            val x = e.x + sway * (if (e.grabbing) 0.5f else 1f) + (if (e.grabbing) t * 5f else 0f)
            val y = base + height * t * t.let { 0.55f + 0.45f * it }
            val w = MathX.lerp(2.3f, 0.45f, t)
            g.line(px, py, x, y, w, c)
            // Suckers down the underside.
            if (i % 2 == 0) g.circle(px, py, w * 0.28f, 7, c2)
            px = x; py = y
        }
        // The tip curls.
        g.circle(px, py, 0.5f, 8, c2)

        if (e.stunned) {
            g.additive(true)
            g.glow(px, py, 3f, Palette.alpha(Palette.bolt, 0.5f))
            g.additive(false)
        }
        if (e.grabbing) {
            g.additive(true)
            g.glow(e.x, base + 1f, 4.5f, Palette.alpha(Palette.tentacleSkin, 0.3f))
            g.additive(false)
        }
    }

    private fun kraken(g: Painter, e: EntityRec, sea: SeaRenderer, ambient: Float) {
        val surface = sea.surfaceYAt(e.x)
        val rise = MathX.clamp01(e.phase * 0.5f)
        val y = e.y

        // The mass under the water, seen as a huge dark shadow.
        c.set(Palette.kraken); c.a = 0.55f
        drawEllipse(g, e.x, y - 3f, 15f, 7f, c)

        // The head breaking the surface.
        tint(Palette.kraken, ambient * 1.3f, c)
        c.a = 1f
        drawEllipse(g, e.x, y + 1.5f * rise, 9f, 5.5f, c)
        tint(Palette.tentacle, ambient * 1.2f, c2)
        drawEllipse(g, e.x + 1f, y + 2.6f * rise, 6.5f, 3.4f, c2)

        // Eyes. The only warm thing about it.
        g.additive(true)
        val blink = if (sin(time * 0.7f) > 0.94f) 0.15f else 1f
        g.glow(e.x - 2.6f, y + 3.2f * rise, 1.5f, Palette.alpha(Palette.furyBar, 0.9f * blink))
        g.glow(e.x + 2.2f, y + 3.4f * rise, 1.5f, Palette.alpha(Palette.furyBar, 0.9f * blink))
        g.additive(false)

        // Limbs breaking the surface around it.
        tint(Palette.tentacle, ambient, c)
        for (k in 0 until 4) {
            val o = k * 1.7f
            val bx = e.x - 11f + k * 7.5f
            val h = 6f + 4f * (0.5f + 0.5f * sin(time * 1.4f + o))
            var lx = bx
            var ly = surface - 1f
            for (i in 1..5) {
                val t = i / 5f
                val nx = bx + sin(time * 1.8f + o + t * 2.6f) * (2f + 4f * t)
                val ny = surface + h * t
                g.line(lx, ly, nx, ny, MathX.lerp(1.7f, 0.35f, t), c)
                lx = nx; ly = ny
            }
        }
    }

    // -----------------------------------------------------------------------
    // Corsairs
    // -----------------------------------------------------------------------

    private fun corsairBody(
        g: Painter, e: EntityRec, len: Float, span: Float, body: Color, ambient: Float
    ) {
        tint(body, ambient, c)
        val ca = cos(e.angle)
        val sa = sin(e.angle)
        fun px(lx: Float, ly: Float) = e.x + lx * ca - ly * sa
        fun py(lx: Float, ly: Float) = e.y + lx * sa + ly * ca

        var n = 0
        pts[n++] = px(len, 0f); pts[n++] = py(len, 0f)
        pts[n++] = px(-len * 0.35f, span); pts[n++] = py(-len * 0.35f, span)
        pts[n++] = px(-len * 0.75f, span * 0.25f); pts[n++] = py(-len * 0.75f, span * 0.25f)
        pts[n++] = px(-len * 0.75f, -span * 0.25f); pts[n++] = py(-len * 0.75f, -span * 0.25f)
        pts[n++] = px(-len * 0.35f, -span); pts[n++] = py(-len * 0.35f, -span)
        g.fan(e.x, e.y, pts, n / 2, c)

        tint(Palette.ink, 1f, c2)
        c2.a = 0.5f
        g.outline(pts, n / 2, 0.08f, c2)

        if (e.stunned) {
            g.additive(true)
            g.glow(e.x, e.y, 2.6f, Palette.alpha(Palette.bolt, 0.6f))
            g.additive(false)
        }
    }

    private fun seeker(g: Painter, e: EntityRec, ambient: Float) {
        corsairBody(g, e, 1.9f, 1.15f, Palette.corsair, ambient)
        g.additive(true)
        // Engine trail, opposite its heading.
        g.glow(e.x - cos(e.angle) * 1.9f, e.y - sin(e.angle) * 1.9f, 1.5f,
            Palette.alpha(Palette.corsair, 0.55f))
        g.additive(false)
    }

    private fun skimmer(g: Painter, e: EntityRec, ambient: Float) {
        corsairBody(g, e, 1.7f, 0.8f, Palette.corsairSkimmer, ambient)
        g.additive(true)
        g.glow(e.x - cos(e.angle) * 1.7f, e.y - sin(e.angle) * 1.7f, 1.9f,
            Palette.alpha(Palette.corsairSkimmer, 0.6f))
        g.additive(false)
    }

    private fun bomber(g: Painter, e: EntityRec, ambient: Float) {
        tint(Palette.corsairBomber, ambient, c)
        // A slab: heavier, slower, unmistakable from below.
        g.quad(
            e.x - 2.6f, e.y - 1.1f, e.x + 2.6f, e.y - 0.8f,
            e.x + 2.2f, e.y + 1.2f, e.x - 2.2f, e.y + 1.0f,
            c, c, c, c
        )
        tint(Palette.ink, 1f, c2); c2.a = 0.55f
        g.line(e.x - 2.6f, e.y - 1.1f, e.x + 2.6f, e.y - 0.8f, 0.12f, c2)
        // Rotors.
        tint(Palette.textDim, ambient, c2)
        val blur = sin(time * 40f) * 1.6f
        g.line(e.x - 1.6f + blur, e.y + 1.4f, e.x + 1.6f + blur, e.y + 1.4f, 0.1f, c2)
        if (e.stunned) {
            g.additive(true)
            g.glow(e.x, e.y, 3.2f, Palette.alpha(Palette.bolt, 0.6f))
            g.additive(false)
        }
    }

    private fun bomb(g: Painter, e: EntityRec, ambient: Float) {
        tint(Palette.mineShell, ambient, c)
        g.circle(e.x, e.y, 0.55f, 9, c)
        tint(Palette.textDim, ambient, c2)
        g.line(e.x, e.y, e.x - cos(e.angle) * 1.1f, e.y - sin(e.angle) * 1.1f, 0.22f, c2)
        g.additive(true)
        val fuse = 0.55f + 0.45f * sin(time * 11f)
        g.glow(e.x, e.y, 1.5f, Palette.alpha(Palette.danger, 0.5f * fuse))
        g.additive(false)
    }

    // -----------------------------------------------------------------------
    // Gifts and flotsam
    // -----------------------------------------------------------------------

    private fun mote(g: Painter, e: EntityRec) {
        val bob = sin(time * 2.4f + e.x * 0.3f) * 0.3f
        g.additive(true)
        g.glow(e.x, e.y + bob, 4.2f, Palette.alpha(Palette.mote, 0.30f))
        g.glow(e.x, e.y + bob, 1.5f, Palette.alpha(Palette.mote, 0.85f))
        // A slow ring, so it reads as a thing to collect rather than a light source.
        val t = (time * 0.6f) % 1f
        g.ring(e.x, e.y + bob, 1.4f + t * 3.2f, Palette.alpha(Palette.mote, (1f - t) * 0.5f))
        g.additive(false)
    }

    private fun crate(g: Painter, e: EntityRec, ambient: Float) {
        tint(Palette.crate, ambient, c)
        val ca = cos(e.angle); val sa = sin(e.angle)
        val s = 1.25f
        g.quad(
            e.x + (-s * ca - -s * sa), e.y + (-s * sa + -s * ca),
            e.x + (s * ca - -s * sa), e.y + (s * sa + -s * ca),
            e.x + (s * ca - s * sa), e.y + (s * sa + s * ca),
            e.x + (-s * ca - s * sa), e.y + (-s * sa + s * ca),
            c, c, c, c
        )
        tint(Palette.hullDark, ambient, c2)
        g.line(e.x - s, e.y, e.x + s, e.y, 0.16f, c2)
        g.additive(true)
        g.glow(e.x, e.y, 3f, Palette.alpha(Palette.good, 0.26f))
        g.additive(false)
    }

    private fun debris(g: Painter, e: EntityRec, ambient: Float) {
        tint(Palette.hullMid, ambient, c)
        g.sprite(art.white, e.x, e.y, 0.8f, 0.32f, e.angle, c)
    }

    // -----------------------------------------------------------------------
    // Workings
    // -----------------------------------------------------------------------

    fun drawSpells(g: Painter, f: SnapshotFrame, sea: SeaRenderer) {
        for (i in 0 until f.spellCount) {
            val z = f.spellZones[i]
            when (z.kind) {
                SpellKind.GALE -> gale(g, z)
                SpellKind.MIST -> mist(g, z)
                SpellKind.VOID -> well(g, z, Palette.voidWell, 1f)
                SpellKind.BOLT -> boltFlash(g, z)
                SpellKind.VOLTAIC_MIST -> voltaic(g, z)
                SpellKind.SINGULARITY -> well(g, z, Palette.singularity, 1.4f)
                SpellKind.TSUNAMI -> tsunami(g, z, sea)
            }
        }
    }

    private fun gale(g: Painter, z: SpellRec) {
        val fade = MathX.clamp01(z.t * 1.6f)
        g.additive(true)
        g.glow(z.x, z.y, z.radius * 0.8f, Palette.alpha(Palette.gale, 0.12f * fade))
        // Spiral arms, turning.
        c.set(Palette.gale)
        for (arm in 0 until 3) {
            val base = time * 5.5f + MathX.TAU * arm / 3f
            var lx = z.x
            var ly = z.y
            for (k in 1..9) {
                val t = k / 9f
                val a = base + t * 4.2f
                val r = z.radius * t
                val nx = z.x + cos(a) * r
                val ny = z.y + sin(a) * r * 0.85f
                c.a = (1f - t) * 0.55f * fade
                g.line(lx, ly, nx, ny, 0.35f + t * 0.4f, c)
                lx = nx; ly = ny
            }
        }
        g.ring(z.x, z.y, z.radius, Palette.alpha(Palette.gale, 0.35f * fade))
        g.additive(false)
    }

    private fun mist(g: Painter, z: SpellRec) {
        val fade = MathX.clamp01(z.t * 2f)
        // Alpha-blended, not additive: fog takes light away.
        c.set(Palette.mist)
        c.a = 0.30f * fade
        for (k in 0 until 12) {
            val a = time * 0.35f + k * 2.1f
            val r = z.radius * (0.25f + 0.7f * ((k * 37 % 10) / 10f))
            val ox = cos(a) * r * 0.8f
            val oy = sin(a * 0.7f) * r * 0.35f
            g.puff(z.x + ox, z.y + oy, z.radius * 0.42f, c, a)
        }
        c.set(Palette.foam); c.a = 0.16f * fade
        g.puff(z.x, z.y, z.radius * 0.8f, c)
    }

    private fun well(g: Painter, z: SpellRec, tint: Color, intensity: Float) {
        val fade = MathX.clamp01(z.t * 2f)
        g.additive(true)
        // Accretion: rings drawn tighter and brighter toward the throat.
        for (k in 5 downTo 0) {
            val t = k / 5f
            val a = time * (2.5f + k * 1.4f)
            val rr = z.radius * (0.12f + 0.88f * t)
            c.set(tint)
            c.a = (0.10f + 0.3f * (1f - t)) * fade * intensity
            drawEllipse(g, z.x, z.y, rr, rr * (0.35f + 0.25f * sin(a)), c)
        }
        g.additive(false)
        // The throat itself is a hole in the scene, so it is drawn as black, not light.
        c.set(Palette.ink)
        c.a = 0.92f * fade
        g.circle(z.x, z.y, z.radius * 0.2f, 16, c)
        g.additive(true)
        g.ring(z.x, z.y, z.radius * 0.26f, Palette.alpha(tint, 0.9f * fade))
        g.glow(z.x, z.y, z.radius * 1.1f, Palette.alpha(tint, 0.16f * fade * intensity))
        g.additive(false)
    }

    private fun boltFlash(g: Painter, z: SpellRec) {
        g.additive(true)
        g.glow(z.x, z.y, z.radius * 1.1f, Palette.alpha(Palette.bolt, 0.45f * z.t))
        g.ring(z.x, z.y, z.radius * (1.1f - z.t), Palette.alpha(Palette.bolt, 0.7f * z.t))
        g.additive(false)
    }

    private fun voltaic(g: Painter, z: SpellRec) {
        val fade = MathX.clamp01(z.t * 2f)
        c.set(Palette.voltaic); c.a = 0.22f * fade
        for (k in 0 until 10) {
            val a = time * 0.5f + k * 2.4f
            g.puff(z.x + cos(a) * z.radius * 0.5f, z.y + sin(a * 0.8f) * z.radius * 0.28f,
                z.radius * 0.45f, c, a)
        }
        // Discharges crawling through the fog.
        g.additive(true)
        c.set(Palette.bolt)
        for (k in 0 until 5) {
            val seed = (k * 313 + (time * 7f).toInt() * 71)
            val fx0 = z.x + ((seed % 101) / 101f - 0.5f) * z.radius * 1.6f
            val fy0 = z.y + (((seed / 7) % 61) / 61f - 0.5f) * z.radius * 0.9f
            val fx1 = fx0 + (((seed / 13) % 41) / 41f - 0.5f) * 9f
            val fy1 = fy0 + (((seed / 29) % 37) / 37f - 0.5f) * 7f
            c.a = 0.55f * fade
            g.line(fx0, fy0, fx1, fy1, 0.16f, c)
        }
        g.glow(z.x, z.y, z.radius * 0.9f, Palette.alpha(Palette.voltaic, 0.14f * fade))
        g.additive(false)
    }

    private fun tsunami(g: Painter, z: SpellRec, sea: SeaRenderer) {
        val fade = MathX.clamp01(z.t * 2f)
        g.additive(true)
        // A luminous band riding along the face of the swell it created.
        val steps = 16
        val step = z.radius * 2f / steps
        var px = z.x - z.radius
        var py = sea.surfaceYAt(px)
        for (i in 1..steps) {
            val x = z.x - z.radius + step * i
            val y = sea.surfaceYAt(x)
            val t = 1f - abs(x - z.x) / z.radius
            c.set(Palette.tsunami)
            c.a = MathX.clamp01(t) * 0.65f * fade
            g.line(px, py, x, y, 0.5f + t * 1.6f, c)
            px = x; py = y
        }
        g.glow(z.x, sea.surfaceYAt(z.x) + 2f, z.radius * 0.55f,
            Palette.alpha(Palette.tsunami, 0.18f * fade))
        g.additive(false)
    }

    // -----------------------------------------------------------------------

    private fun drawEllipse(g: Painter, cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
        val segs = 20
        var px = cx + rx
        var py = cy
        for (i in 1..segs) {
            val a = MathX.TAU * i / segs
            val nx = cx + cos(a) * rx
            val ny = cy + sin(a) * ry
            g.tri(cx, cy, color, px, py, color, nx, ny, color)
            px = nx; py = ny
        }
    }

    private fun tint(src: Color, k: Float, out: Color) {
        out.set(src)
        out.r *= k; out.g *= k; out.b *= k
        out.a = src.a
    }
}
