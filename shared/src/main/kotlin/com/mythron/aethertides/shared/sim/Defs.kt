package com.mythron.aethertides.shared.sim

/**
 * The rulebook.
 *
 * Every cost, cooldown, radius and duration in the game lives here. The server balances from
 * it, the bots plan from it, and both HUDs render from it, so a number can never drift out of
 * sync between what a player is shown and what actually happens.
 */

enum class Role(val id: Int) {
    NAVIGATOR(0),   // sails the ship, wields the aether
    TEMPEST(1);     // seeds the sea against them

    fun other(): Role = if (this == NAVIGATOR) TEMPEST else NAVIGATOR

    companion object {
        fun of(id: Int) = if (id == 1) TEMPEST else NAVIGATOR
    }
}

enum class EntityKind(val id: Int) {
    DRIFT_MINE(0),
    REEF_SPIKE(1),
    ICE_FLOE(2),
    MAELSTROM(3),
    TENTACLE(4),
    SEEKER(5),
    BOMBER(6),
    SKIMMER(7),
    BOMB(8),
    AETHER_MOTE(9),
    SALVAGE_CRATE(10),
    KRAKEN(11),
    DEBRIS(12);

    val isCorsair: Boolean get() = this == SEEKER || this == BOMBER || this == SKIMMER
    val isPickup: Boolean get() = this == AETHER_MOTE || this == SALVAGE_CRATE

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): EntityKind = byId[id] ?: DEBRIS
    }
}

enum class SpellKind(val id: Int) {
    GALE(0), MIST(1), VOID(2), BOLT(3),
    // Synergies -- never cast directly, only born from two overlapping zones.
    VOLTAIC_MIST(4), SINGULARITY(5), TSUNAMI(6);

    val isBase: Boolean get() = id <= 3

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): SpellKind = byId[id] ?: GALE
        val base = listOf(GALE, MIST, VOID, BOLT)
    }
}

enum class DeployKind(val id: Int) {
    REEF_SPIKE(0),
    DRIFT_MINE(1),
    ICE_FLOE(2),
    MAELSTROM(3),
    TENTACLE(4),
    CORSAIRS(5),
    ROGUE_WAVE(6),
    SQUALL(7),
    AETHER_SNARE(8),
    KRAKEN(9);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): DeployKind = byId[id] ?: DRIFT_MINE
    }
}

/** One-shot notifications pushed to clients for audio and visual effects. */
enum class EventKind(val id: Int) {
    SPELL_CAST(0),
    SYNERGY(1),
    EXPLOSION(2),
    HULL_IMPACT(3),
    SPLASH(4),
    LIGHTNING(5),
    MAST_BREAK(6),
    MAST_REPAIR(7),
    PUMP(8),
    CAPSIZE(9),
    RIGHTED(10),
    DEPLOY(11),
    PICKUP(12),
    CORSAIR_DOWN(13),
    TENTACLE_GRAB(14),
    TENTACLE_BREAK(15),
    KRAKEN_RISE(16),
    ROGUE_WAVE(17),
    SNARE(18),
    BREACH(19),
    LEAGUE(20),
    AIRBORNE(21),
    LANDING(22),
    BRACE(23);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): EventKind = byId[id] ?: SPLASH
    }
}

enum class MatchPhase(val id: Int) {
    WAITING(0),     // lobby, waiting for an opponent
    COUNTDOWN(1),   // 3..2..1, both players see the sea before it starts
    SAILING(2),
    ENDED(3);

    companion object {
        fun of(id: Int): MatchPhase = entries.firstOrNull { it.id == id } ?: WAITING
    }
}

// ---------------------------------------------------------------------------
// Spells
// ---------------------------------------------------------------------------

class SpellDef(
    @JvmField val kind: SpellKind,
    @JvmField val title: String,
    @JvmField val glyph: String,
    @JvmField val cost: Float,
    @JvmField val cooldown: Float,
    @JvmField val radius: Float,
    @JvmField val duration: Float,
    @JvmField val force: Float,
    @JvmField val blurb: String
)

object Spells {
    val defs: Map<SpellKind, SpellDef> = listOf(
        SpellDef(
            SpellKind.GALE, "GALE", "⟳", 16f, 2.2f, 17f, 2.0f, 42f,
            "A vortex of hard air. Throws loose hazards clear and, cast astern, punches the sail full."
        ),
        SpellDef(
            SpellKind.MIST, "MIST", "≈", 22f, 4.6f, 21f, 5.0f, 0f,
            "Cold fog. Flattens the sea beneath it, drowns skimmers and slows everything it touches."
        ),
        SpellDef(
            SpellKind.VOID, "VOID", "●", 28f, 6.5f, 15f, 2.6f, 78f,
            "A collapsing well. Swallows mines and shot; everything else it takes hold of and shears."
        ),
        SpellDef(
            SpellKind.BOLT, "BOLT", "☇", 24f, 2.8f, 13f, 0.55f, 0f,
            "Chained lightning. Kills corsairs outright, cooks tentacles off the hull, sets off mines at range."
        ),
        SpellDef(
            SpellKind.VOLTAIC_MIST, "VOLTAIC MIST", "⚡", 0f, 0f, 30f, 5.0f, 0f,
            "SYNERGY - charged fog. Everything inside it is held, stunned and slowly cooked."
        ),
        SpellDef(
            SpellKind.SINGULARITY, "SINGULARITY", "✵", 0f, 0f, 19f, 3.4f, 150f,
            "SYNERGY - a hungry point driven downrange. It eats what it passes through."
        ),
        SpellDef(
            SpellKind.TSUNAMI, "TSUNAMI", "≋", 0f, 0f, 34f, 4.0f, 90f,
            "SYNERGY - a swell of your own making. Clears the surface, and if you catch the face, it is the fastest water in the game."
        )
    ).associateBy { it.kind }

    operator fun get(k: SpellKind): SpellDef = defs.getValue(k)

    /** Which pairs fuse, and into what. Order independent. */
    fun synergy(a: SpellKind, b: SpellKind): SpellKind? {
        val lo = if (a.id <= b.id) a else b
        val hi = if (a.id <= b.id) b else a
        return when {
            lo == SpellKind.MIST && hi == SpellKind.BOLT -> SpellKind.VOLTAIC_MIST
            lo == SpellKind.GALE && hi == SpellKind.VOID -> SpellKind.SINGULARITY
            lo == SpellKind.GALE && hi == SpellKind.MIST -> SpellKind.TSUNAMI
            else -> null
        }
    }
}

// ---------------------------------------------------------------------------
// Tempest deployables
// ---------------------------------------------------------------------------

class DeployDef(
    @JvmField val kind: DeployKind,
    @JvmField val title: String,
    /** Four to six characters. The card bar has nine slots across a phone; long names collide. */
    @JvmField val short: String,
    @JvmField val glyph: String,
    @JvmField val cost: Float,
    @JvmField val cooldown: Float,
    /** How far ahead of the bow it must be placed. No point blank ambushes. */
    @JvmField val minLead: Float,
    /** How far ahead it may be placed. Keeps the Tempest playing the near field. */
    @JvmField val maxLead: Float,
    /** Where on the water column it lives: -1 submerged, 0 surface, 1 airborne. */
    @JvmField val layer: Int,
    @JvmField val blurb: String
)

object Deployables {
    val defs: Map<DeployKind, DeployDef> = listOf(
        DeployDef(
            DeployKind.REEF_SPIKE, "REEF", "REEF", "▲", 24f, 5.2f, 42f, 240f, -1,
            "Coral teeth just under the surface. Invisible from a crest, fatal from a trough."
        ),
        DeployDef(
            DeployKind.DRIFT_MINE, "MINE", "MINE", "✹", 22f, 2.6f, 30f, 260f, 0,
            "Floats on the swell and rides it. Detonates on the hull, or on its neighbour."
        ),
        DeployDef(
            DeployKind.ICE_FLOE, "FLOE", "FLOE", "⬟", 26f, 5.0f, 34f, 240f, 0,
            "A slab of black ice. Takes two good hits to break and stops a bow cold."
        ),
        DeployDef(
            DeployKind.MAELSTROM, "MAELSTROM", "WHIRL", "◌", 42f, 11f, 40f, 230f, 0,
            "A standing whirlpool. Drags the ship back off the wave it was riding."
        ),
        DeployDef(
            DeployKind.TENTACLE, "TENTACLE", "GRASP", "❦", 52f, 14f, 34f, 200f, 0,
            "It takes hold of the hull and does not let go until lightning finds it."
        ),
        DeployDef(
            DeployKind.CORSAIRS, "CORSAIRS", "FLIGHT", "➤", 34f, 7.5f, 60f, 300f, 1,
            "A flight of raiders: seekers dive, bombers loiter above, skimmers hug the water."
        ),
        DeployDef(
            DeployKind.ROGUE_WAVE, "ROGUE", "ROGUE", "≋", 40f, 9.5f, 90f, 340f, 0,
            "A wall of water sent upcourse. Meet it bow high or it ends the voyage."
        ),
        DeployDef(
            DeployKind.SQUALL, "SQUALL", "SQUALL", "☁", 32f, 9f, 70f, 320f, 1,
            "Reverses the airflow and blinds the deck. A full sail in a squall is a broken mast."
        ),
        DeployDef(
            DeployKind.AETHER_SNARE, "SNARE", "SNARE", "☢", 46f, 13f, 0f, 400f, 0,
            "Bind one of their four spells for eight seconds. Choose the one they are leaning on."
        ),
        DeployDef(
            DeployKind.KRAKEN, "KRAKEN", "FURY", "☠", 0f, 0f, 50f, 260f, 0,
            "FURY - it surfaces once. For twenty seconds the sea belongs to it."
        )
    ).associateBy { it.kind }

    operator fun get(k: DeployKind): DeployDef = defs.getValue(k)

    /** The loadout order shown on the Tempest deck. */
    val bar = listOf(
        DeployKind.REEF_SPIKE, DeployKind.DRIFT_MINE, DeployKind.ICE_FLOE,
        DeployKind.CORSAIRS, DeployKind.MAELSTROM, DeployKind.TENTACLE,
        DeployKind.ROGUE_WAVE, DeployKind.SQUALL, DeployKind.AETHER_SNARE
    )
}
