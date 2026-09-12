package com.polariz.aethertides.shared.sim

/**
 * What a player is asking the world to do this tick.
 *
 * Inputs are the only thing a client is allowed to send. The server owns everything else,
 * so a modified client can ask for impossible things and simply be told no.
 */

/** Continuous helm state. Sampled every tick, sent at input rate, replayed during prediction. */
class NavInput {
    /** 0 = bare poles, 1 = everything she has. */
    @JvmField var trim = 0.65f

    /** Crew weight fore/aft, -1 aft to +1 forward. This is how you control pitch in the air. */
    @JvmField var lean = 0f

    /** Running the bilge pump. Costs aether, sheds water. */
    @JvmField var pump = false

    /** Rising edge triggers a brace. */
    @JvmField var brace = false

    /** Client tick this input was produced for; used for reconciliation. */
    @JvmField var seq = 0

    fun set(o: NavInput) {
        trim = o.trim; lean = o.lean; pump = o.pump; brace = o.brace; seq = o.seq
    }

    fun copy(): NavInput = NavInput().also { it.set(this) }
}

/** A discrete action. Both roles queue these; the server validates cost and legality. */
class Command(
    @JvmField var type: Int = 0,
    @JvmField var a: Int = 0,
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f
) {
    companion object {
        const val CAST_SPELL = 1     // a = SpellKind id
        const val DEPLOY = 2         // a = DeployKind id
        const val STORM_DIAL = 3     // x = target sea state 0..1
        const val SNARE_PICK = 4     // a = SpellKind id to lock
        const val FURY = 5           // spend fury, a unused
        const val EMOTE = 6
        const val REMATCH = 7
    }
}

/** Why a hit landed. Drives the right sound, the right shake, and the scoreboard. */
enum class DamageCause {
    MINE, REEF, FLOE, BOMB, CORSAIR, TENTACLE, KRAKEN, SLAM, FOUNDER, ROGUE
}
