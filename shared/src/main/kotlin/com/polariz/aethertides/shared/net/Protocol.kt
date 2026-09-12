package com.polariz.aethertides.shared.net

import com.polariz.aethertides.shared.sim.Config

/**
 * Message ids. Every frame on the socket is binary and starts with one of these.
 *
 * Clients may only send inputs and intents. Every rule -- cost, cooldown, range, legality --
 * is checked on the server, so a tampered client can ask for a free Kraken and simply be
 * ignored. Identity works the same way: the client presents a token it got from the auth
 * provider, and the server decides who that is. Names and ratings are never taken from the
 * client's word.
 */
object Msg {
    // client -> server
    const val HELLO = 1
    const val QUEUE = 2
    const val CANCEL_QUEUE = 3
    const val NAV_INPUT = 4
    const val COMMAND = 5
    const val PING = 6
    const val LEAVE = 7
    const val REMATCH = 8
    const val DELETE_ACCOUNT = 9

    // server -> client
    const val WELCOME = 20
    const val MATCH_FOUND = 21
    const val SNAPSHOT = 22
    const val MATCH_END = 23
    const val PONG = 24
    const val OPPONENT_LEFT = 25
    const val QUEUE_STATUS = 26
    const val DENIED = 27
    const val OPPONENT_REMATCH = 28
    const val PROFILE = 29
    const val ACCOUNT_DELETED = 30
}

/** Queue modes. Ranked pairs by rating; the others are for friends and practice. */
object QueueMode {
    const val QUICK = 0
    const val RANKED = 1
    const val PRIVATE = 2
}

object Denied {
    const val VERSION_MISMATCH = 1
    const val ROOM_FULL = 2
    const val NOT_IN_MATCH = 3
    const val ILLEGAL_ACTION = 4
    const val RATE_LIMITED = 5
    const val AUTH_REQUIRED = 6
    const val AUTH_FAILED = 7
    const val SERVER_FULL = 8
    const val BAD_NAME = 9
    const val CODE_TOO_SHORT = 10
}

/** Limits shared by both ends so a client can pre-validate what a server will refuse. */
object Limits {
    const val NAME_MIN = 2
    const val NAME_MAX = 18
    const val PRIVATE_CODE_MIN = 6
    const val PRIVATE_CODE_MAX = 12
    /** The largest legitimate client frame is a HELLO carrying a token. */
    const val MAX_CLIENT_FRAME = 4096
    /** Snapshots stay well under this; it exists so a hostile peer cannot allocate at will. */
    const val MAX_SERVER_FRAME = 16384

    /**
     * Display names are printable, single-line, and cannot impersonate the system. The same
     * function runs on both ends so the client can show what will actually be accepted.
     */
    fun sanitizeName(raw: String): String {
        val cleaned = StringBuilder()
        for (ch in raw) {
            // Letters, digits, space and a small set of punctuation. No control characters,
            // no bidi overrides, no zero-width tricks, no ANSI escapes reaching the logs.
            val ok = ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' ||
                    ch == '.' || ch == '\''
            if (ok && ch.code >= 0x20 && ch.code != 0x7F && ch.code !in 0x2000..0x206F) {
                cleaned.append(ch)
            }
        }
        val collapsed = cleaned.toString().trim().replace(Regex(" {2,}"), " ")
        return collapsed.take(NAME_MAX)
    }

    fun nameIsValid(name: String): Boolean =
        name.length in NAME_MIN..NAME_MAX && sanitizeName(name) == name

    fun sanitizeCode(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.take(PRIVATE_CODE_MAX)
}

/** Convenience builders for the small control messages. */
object Packets {

    /**
     * First frame on every connection. The token is whatever the auth provider issued; an
     * empty token means "anonymous guest", which a server may or may not allow. The name is a
     * request, not a fact -- the server sanitises it and echoes back what it will use.
     */
    fun hello(token: String, name: String): ByteArray =
        ByteWriter(1500).u8(Msg.HELLO).i32(Config.PROTOCOL_VERSION).lstr(token).str(name).bytes()

    fun queue(mode: Int, preferredRole: Int, code: String): ByteArray =
        ByteWriter(64).u8(Msg.QUEUE).u8(mode).i8(preferredRole).str(code).bytes()

    fun cancelQueue(): ByteArray = ByteWriter(4).u8(Msg.CANCEL_QUEUE).bytes()

    fun leave(): ByteArray = ByteWriter(4).u8(Msg.LEAVE).bytes()

    fun rematch(): ByteArray = ByteWriter(4).u8(Msg.REMATCH).bytes()

    fun deleteAccount(): ByteArray = ByteWriter(4).u8(Msg.DELETE_ACCOUNT).bytes()

    fun ping(clientTime: Long): ByteArray =
        ByteWriter(16).u8(Msg.PING).i64(clientTime).bytes()

    fun pong(clientTime: Long, serverTime: Long): ByteArray =
        ByteWriter(24).u8(Msg.PONG).i64(clientTime).i64(serverTime).bytes()

    /**
     * Helm state. Sent every client tick but tiny: trim and lean quantised to a byte each,
     * flags packed into a third. Under 12 bytes including the websocket frame header.
     */
    fun navInput(seq: Int, trim: Float, lean: Float, pump: Boolean, brace: Boolean): ByteArray {
        var flags = 0
        if (pump) flags = flags or 1
        if (brace) flags = flags or 2
        return ByteWriter(16)
            .u8(Msg.NAV_INPUT)
            .i32(seq)
            .q8(trim)
            .q8((lean + 1f) * 0.5f)
            .u8(flags)
            .bytes()
    }

    fun command(type: Int, a: Int, x: Float, y: Float): ByteArray =
        ByteWriter(24).u8(Msg.COMMAND).u8(type).i32(a).f32(x).f32(y).bytes()

    fun denied(code: Int): ByteArray = ByteWriter(4).u8(Msg.DENIED).u8(code).bytes()

    /** Who the server decided you are. Everything here came from the profile store, not the client. */
    fun welcome(
        playerId: Int, serverTime: Long, userId: String, name: String,
        rating: Int, played: Int, won: Int, guest: Boolean
    ): ByteArray =
        ByteWriter(128)
            .u8(Msg.WELCOME)
            .i32(playerId)
            .i64(serverTime)
            .str(userId)
            .str(name)
            .i32(rating)
            .i32(played)
            .i32(won)
            .bool(guest)
            .bytes()

    /** Pushed whenever the stored profile changes, e.g. after a rated series. */
    fun profile(name: String, rating: Int, played: Int, won: Int, ratingDelta: Int): ByteArray =
        ByteWriter(64).u8(Msg.PROFILE).str(name).i32(rating).i32(played).i32(won).i32(ratingDelta).bytes()

    fun accountDeleted(): ByteArray = ByteWriter(4).u8(Msg.ACCOUNT_DELETED).bytes()

    fun queueStatus(waiting: Int, etaSeconds: Int): ByteArray =
        ByteWriter(16).u8(Msg.QUEUE_STATUS).i32(waiting).i32(etaSeconds).bytes()

    fun matchFound(matchId: Int, role: Int, seed: Long, opponent: String, oppRating: Int, bot: Boolean): ByteArray =
        ByteWriter(96)
            .u8(Msg.MATCH_FOUND)
            .i32(matchId)
            .u8(role)
            .i64(seed)
            .str(opponent)
            .i32(oppRating)
            .bool(bot)
            .bytes()

    fun opponentLeft(): ByteArray = ByteWriter(4).u8(Msg.OPPONENT_LEFT).bytes()

    fun opponentRematch(): ByteArray = ByteWriter(4).u8(Msg.OPPONENT_REMATCH).bytes()
}
