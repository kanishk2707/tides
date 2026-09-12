package com.polariz.aethertides.shared.sim

/** Global tuning. Kept flat and boring on purpose: this is the file you balance from. */
object Config {

    // --- timing -----------------------------------------------------------
    /** Fixed simulation step. Everything is integrated at exactly this rate, everywhere. */
    const val DT = 1f / 60f
    const val TICK_RATE = 60

    /** Snapshots per second pushed to clients. Clients interpolate between them. */
    const val SNAPSHOT_RATE = 20
    const val TICKS_PER_SNAPSHOT = TICK_RATE / SNAPSHOT_RATE

    /** How far behind the newest snapshot the client renders remote entities, seconds. */
    const val INTERP_DELAY = 0.10f

    const val COUNTDOWN = 3.6f

    // --- course -----------------------------------------------------------
    /** Full voyage length in metres. Split into five leagues for pacing and the HUD. */
    const val COURSE_LENGTH = 2600f
    const val LEAGUES = 5
    const val LEAGUE_LENGTH = COURSE_LENGTH / LEAGUES

    /** Hard time limit. If the navigator is still afloat at zero, they have held out and win. */
    const val MATCH_TIME_LIMIT = 300f

    // --- ship -------------------------------------------------------------
    const val HULL_LENGTH = 15.0f
    const val HULL_BEAM = 4.2f          // draught-relevant cross section
    const val HULL_DEPTH = 2.6f         // keel to gunwale
    const val FREEBOARD = 1.35f         // gunwale above the waterline when trimmed
    const val MAST_HEIGHT = 12.5f
    const val DRY_MASS = 16000f          // kg, hull + rig + cargo
    const val MAX_BILGE = 9000f         // kg of water aboard before she founders

    const val HULL_POINTS = 11          // buoyancy sample stations along the keel

    const val MAX_HULL = 100f
    const val MAX_MAST = 100f
    const val MAX_AETHER = 100f

    /** Aether regenerated per second at rest. */
    const val AETHER_REGEN = 5.4f
    /** Extra per second while carving a wave face -- the reward for sailing well. */
    const val AETHER_SURF_BONUS = 8.0f
    /** Extra per second while airborne off a crest. */
    const val AETHER_AIR_BONUS = 14f

    /** Bilge pump throughput, kg/s, and what it costs to run. */
    const val PUMP_RATE = 520f
    const val PUMP_AETHER_PER_SEC = 6.5f

    /** Brace: a short window of halved impact damage. */
    const val BRACE_DURATION = 1.1f
    const val BRACE_COOLDOWN = 7f
    const val BRACE_COST = 14f
    const val BRACE_DAMAGE_MULT = 0.38f

    /**
     * The ship's carpenter. Hull points come back once she has been left alone long enough,
     * which forces the Tempest to sustain pressure instead of landing one hit a minute, and
     * rewards a navigator for sailing a clean league.
     */
    const val HULL_REPAIR_DELAY = 11f
    const val HULL_REPAIR_RATE = 0.34f

    /** Mast self-repair once out of trouble, points/second. */
    const val MAST_REPAIR = 2.4f

    /** Sail thrust coefficient: area times lift factor, lumped. */
    const val SAIL_AREA = 145f
    const val SAIL_EFFICIENCY = 0.80f
    /** Above this apparent wind on a full sail, the rig starts to fail. */
    const val MAST_STRESS_WIND = 26f
    const val MAST_STRESS_RATE = 8.5f

    /** Hydrodynamic drag along the hull and across it. */
    const val DRAG_LONG = 24f
    const val DRAG_VERT = 1800f
    const val ANGULAR_DRAG = 0.62f

    /**
     * The hull is aether-driven, not a museum piece: she carries enough drive to hold about
     * twice the classical displacement speed before wave-making resistance takes over. Beyond
     * that, only a wave face will move her.
     */
    const val HULL_SPEED_MULT = 2.1f

    /** Vertical impact speed above which a landing hurts, m/s. */
    const val SLAM_THRESHOLD = 5.2f
    const val SLAM_DAMAGE_PER_MS = 3.05f

    /** Pitch beyond this is a knockdown; beyond the second value she does not come back. */
    const val KNOCKDOWN_ANGLE = 1.15f       // ~66 deg
    const val CAPSIZE_ANGLE = 2.0f          // ~115 deg
    const val CAPSIZE_GRACE = 2.4f          // seconds inverted before it is over

    // --- damage -----------------------------------------------------------
    const val DMG_MINE = 9f
    const val DMG_REEF = 11f
    const val DMG_FLOE = 7f
    const val DMG_BOMB = 5f
    const val DMG_SEEKER = 4f
    const val DMG_SKIMMER = 4f
    const val DMG_TENTACLE_PER_SEC = 3.0f
    const val DMG_KRAKEN = 14f

    /** Chance a heavy hit opens a leak, and how fast one leak floods, kg/s. */
    const val BREACH_CHANCE = 0.42f
    const val BREACH_THRESHOLD = 9f
    const val LEAK_FLOW = 45f
    const val MAX_LEAKS = 6

    /** Water shipped per second per metre of deck below the surface. */
    const val GREEN_WATER_FLOW = 260f

    // --- tempest economy --------------------------------------------------
    const val MAX_MALICE = 120f
    const val MALICE_REGEN = 5.0f
    // Balance note: the outcome is very sensitive to this number. Bot-vs-bot over ten seeds
    // at equal skill runs roughly 8-2 to the navigator at 4.7 and 1-9 at 5.4, so the playable
    // band is narrow and 5.0 sits in the middle of it. The residual asymmetry is absorbed by
    // the match format: both players sail the same course as Navigator and the better run wins.

    /** Malice earned per point of hull damage dealt -- aggression pays for itself. */
    const val MALICE_PER_DAMAGE = 0.35f
    /** And per second the navigator is held below half speed. */
    const val MALICE_STALL_BONUS = 1.6f

    const val MAX_FURY = 100f
    const val FURY_PER_DAMAGE = 0.75f
    const val FURY_PER_SEC = 0.9f
    const val KRAKEN_DURATION = 14f

    /** Storm dial: how fast the Tempest can drive the sea state, and the upkeep it costs. */
    const val STORM_SLEW = 0.085f
    const val STORM_UPKEEP = 5.2f
    const val STORM_DECAY = 0.035f
    const val SEA_STATE_FLOOR = 0.22f

    const val SNARE_DURATION = 8f

    // --- corsairs ---------------------------------------------------------
    const val CORSAIR_FLIGHT_MIN = 3
    const val CORSAIR_FLIGHT_MAX = 4
    const val BOMB_FUSE = 6f

    // --- pickups ----------------------------------------------------------
    /** Aether motes drift down the course for the navigator to line up and collect. */
    const val MOTE_INTERVAL = 6.5f
    const val MOTE_VALUE = 18f
    const val CRATE_HULL_REPAIR = 14f

    // --- world ------------------------------------------------------------
    /** Entities behind the stern by more than this are culled. */
    const val CULL_BEHIND = 90f
    const val MAX_ENTITIES = 96
    const val MAX_SPELLS = 10

    // --- net --------------------------------------------------------------
    const val PROTOCOL_VERSION = 4
    const val DEFAULT_PORT = 7788
    const val HEARTBEAT = 4f
    const val TIMEOUT = 18f
}
