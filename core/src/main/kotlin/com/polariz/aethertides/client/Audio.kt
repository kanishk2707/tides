package com.polariz.aethertides.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import com.polariz.aethertides.shared.math.MathX
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound of the game, synthesised.
 *
 * There is not one audio file in this repository and there is not going to be: the rest of the
 * project generates its art at launch, and sound is no different. Every cue here is a handful
 * of numbers describing one voice -- an oscillator and a noise source crossfaded, run through a
 * one-pole lowpass whose cutoff moves, shaped by an envelope, optionally driven into soft
 * clipping. That single voice covers the whole bank: a splash is noise whose cutoff falls, a
 * hull strike is a low sine falling under a noise transient, lightning is bright noise that
 * darkens fast.
 *
 * Two voices never stop. The sea is filtered noise whose level follows the sea state and how
 * hard the hull is being driven through it; the wind is brighter noise following wind speed.
 * Between them the game has a floor of sound at all times, which is what stops the cues from
 * sounding like beeps in a vacuum.
 *
 * Mixing runs on its own daemon thread and paces itself on the device -- [AudioDevice.writeSamples]
 * blocks until the hardware has taken the block, so the loop needs no clock of its own. The
 * render thread only ever appends a trigger or writes a bed parameter.
 *
 * Two development properties:
 *   -Daether.audio.silent=1        run the mixer but open no device (screenshot runs)
 *   -Daether.audio.capture=FILE    write everything mixed to a 16-bit WAV
 */
object Audio {

    private const val RATE = 44100
    private const val BLOCK = 512          // frames per write: ~12 ms of latency
    private const val VOICES = 24
    private const val TAU = 6.2831853f

    // --- cue bank ----------------------------------------------------------

    /**
     * One synthesised sound.
     *
     * @param f0        starting frequency, Hz
     * @param f1        frequency at the end of the sound
     * @param noise     0 pure tone, 1 pure noise
     * @param cut0      lowpass cutoff at the start, Hz
     * @param cut1      lowpass cutoff at the end, Hz
     * @param attack    seconds to full level
     * @param dur       total length, seconds
     * @param curve     decay shape: 1 linear, higher is snappier
     * @param gain      voice level before distance attenuation
     * @param drive     soft-clip amount; gives weight to impacts
     */
    class Cue(
        @JvmField val f0: Float,
        @JvmField val f1: Float,
        @JvmField val noise: Float,
        @JvmField val cut0: Float,
        @JvmField val cut1: Float,
        @JvmField val attack: Float,
        @JvmField val dur: Float,
        @JvmField val curve: Float = 2f,
        @JvmField val gain: Float = 0.6f,
        @JvmField val drive: Float = 0f
    )

    // Water. Bright noise collapsing to a dull wash is what a splash actually is.
    val SPLASH_SMALL = Cue(0f, 0f, 1f, 5200f, 900f, 0.004f, 0.26f, 2.6f, 0.30f)
    val SPLASH = Cue(0f, 0f, 1f, 4200f, 500f, 0.006f, 0.55f, 2.2f, 0.46f)
    val SPLASH_BIG = Cue(0f, 0f, 1f, 3400f, 320f, 0.010f, 0.95f, 1.8f, 0.62f)
    val BUBBLES = Cue(320f, 140f, 0.72f, 1400f, 400f, 0.02f, 0.70f, 1.7f, 0.34f)

    // Weight. A low sine falling under the noise is what makes a hit feel heavy rather than loud.
    val THUMP = Cue(96f, 42f, 0.30f, 1500f, 260f, 0.003f, 0.42f, 2.4f, 0.70f, drive = 0.45f)
    val BOOM = Cue(128f, 28f, 0.46f, 2200f, 220f, 0.004f, 1.05f, 1.7f, 0.85f, drive = 0.7f)
    val KNOCK = Cue(180f, 90f, 0.35f, 1800f, 500f, 0.002f, 0.20f, 3.2f, 0.42f, drive = 0.3f)
    val TIMBER = Cue(150f, 52f, 0.55f, 1300f, 220f, 0.004f, 1.20f, 1.5f, 0.75f, drive = 0.5f)

    // Aether. Rising tones for the player's own workings, falling ones for things done to them.
    val CAST_GALE = Cue(210f, 720f, 0.34f, 5000f, 2600f, 0.012f, 0.52f, 2.0f, 0.40f)
    val CAST_MIST = Cue(430f, 190f, 0.52f, 2400f, 700f, 0.030f, 0.80f, 1.6f, 0.36f)
    val CAST_VOID = Cue(300f, 62f, 0.20f, 1700f, 320f, 0.015f, 0.85f, 1.8f, 0.52f, drive = 0.3f)
    val CAST_BOLT = Cue(900f, 300f, 0.55f, 9000f, 1400f, 0.002f, 0.38f, 3.0f, 0.50f, drive = 0.4f)
    val FUSION = Cue(260f, 1180f, 0.18f, 7000f, 3000f, 0.020f, 1.30f, 1.4f, 0.66f)
    val THUNDER = Cue(0f, 0f, 1f, 9500f, 700f, 0.001f, 0.85f, 2.6f, 0.72f, drive = 0.35f)

    // Voice of the other side.
    val RISE = Cue(56f, 34f, 0.16f, 700f, 180f, 0.55f, 2.60f, 1.2f, 0.90f, drive = 0.25f)
    val SNARE_BIND = Cue(680f, 150f, 0.30f, 4200f, 800f, 0.010f, 0.90f, 1.9f, 0.48f)
    val SWELL = Cue(0f, 0f, 1f, 300f, 1500f, 0.90f, 1.60f, 1.0f, 0.70f)

    // Reward and information.
    val PICKUP = Cue(760f, 1240f, 0.06f, 8000f, 6000f, 0.004f, 0.28f, 2.4f, 0.34f)
    val CHIME = Cue(520f, 780f, 0.05f, 8000f, 5000f, 0.006f, 0.75f, 1.7f, 0.32f)
    val GOOD = Cue(440f, 660f, 0.08f, 7000f, 4000f, 0.005f, 0.40f, 2.0f, 0.30f)
    val TICK = Cue(1250f, 900f, 0.28f, 8000f, 3000f, 0.001f, 0.11f, 3.4f, 0.26f)
    val WHOOSH = Cue(0f, 0f, 1f, 900f, 4200f, 0.10f, 0.40f, 1.6f, 0.26f)
    val PUMP_GULP = Cue(240f, 110f, 0.62f, 1100f, 330f, 0.008f, 0.24f, 2.6f, 0.28f)
    val CORSAIR_DIE = Cue(620f, 130f, 0.60f, 4000f, 500f, 0.004f, 0.45f, 2.2f, 0.44f, drive = 0.3f)

    // --- state -------------------------------------------------------------

    /** Master level. Zero silences everything without stopping the mixer. */
    /** The level the master sits at when sound is on. */
    const val FULL_VOLUME = 0.85f

    /** Master level. Zero is silence; the mute toggle in Settings writes this. */
    @Volatile var volume = FULL_VOLUME

    @Volatile private var running = false
    private var thread: Thread? = null
    private var device: AudioDevice? = null

    /** How many cue voices have been started. Used by tests and by the dev harness. */
    @Volatile var triggered = 0
    /** Cues asked for, and cues thrown away because they were too far off-screen or the
     *  queue was full. A large gap between requested and triggered is a bug, not a mix. */
    @Volatile var requested = 0
    @Volatile var droppedFar = 0
    @Volatile var droppedFull = 0
        private set

    /** Peak absolute sample seen since the last read. Proof that sound is actually produced. */
    @Volatile var peak = 0f

    // Listener, written by the renderer, read by the mixer.
    @Volatile private var listenX = 0f
    @Volatile private var listenSpan = 60f

    // Bed targets, written by the renderer.
    @Volatile private var seaTarget = 0.10f
    @Volatile private var seaCut = 380f
    @Volatile private var windTarget = 0.05f
    @Volatile private var windCut = 1100f

    private class Voice {
        @JvmField var active = false
        @JvmField var phase = 0f
        @JvmField var f0 = 0f
        @JvmField var f1 = 0f
        @JvmField var noise = 0f
        @JvmField var cut0 = 0f
        @JvmField var cut1 = 0f
        @JvmField var attack = 0f
        @JvmField var dur = 1f
        @JvmField var curve = 2f
        @JvmField var gain = 0f
        @JvmField var drive = 0f
        @JvmField var t = 0f
        @JvmField var panL = 0.7f
        @JvmField var panR = 0.7f
        @JvmField var lp = 0f
        @JvmField var rng = 1
    }

    private val voices = Array(VOICES) { Voice() }

    /** Pending triggers. Written by the render thread, drained by the mixer. */
    private class Trigger {
        @JvmField var cue: Cue? = null
        @JvmField var gain = 1f
        @JvmField var pitch = 1f
        @JvmField var pan = 0f
    }

    private val queue = Array(64) { Trigger() }
    private var qWrite = 0
    private var qRead = 0
    private val qLock = Any()

    // Bed voices live outside the pool: they never stop, so they never compete for one.
    private var seaLevel = 0f
    private var seaLp = 0f
    private var seaRng = 12345
    private var windLevel = 0f
    private var windLp = 0f
    private var windRng = 6789

    private var capture: ByteArrayOutputStream? = null
    private var capturePath: String? = null

    // -----------------------------------------------------------------------

    /** Open the device and start mixing. Safe to call repeatedly. */
    fun start() {
        if (running) return
        val silent = System.getProperty("aether.audio.silent") != null
        capturePath = System.getProperty("aether.audio.capture")
        if (capturePath != null) capture = ByteArrayOutputStream(1 shl 20)

        if (!silent) {
            device = try {
                Gdx.audio?.newAudioDevice(RATE, false)
            } catch (e: Throwable) {
                // A device that will not open is not a reason to lose the game. Emulators,
                // headless CI and phones with audio policy restrictions all land here.
                Gdx.app?.log("AetherTides", "audio unavailable: ${e.message}")
                null
            }
            if (device == null && capture == null) return
        }

        running = true
        val t = Thread({ run() }, "aether-audio")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY + 1
        thread = t
        t.start()
    }

    fun stop() {
        if (!running) return
        // Worth a line: "0 cues" is the signature of an event bus that never got wired up,
        // and a peak at 1.0 is the signature of a mix that is clipping.
        Gdx.app?.log(
            "AetherTides",
            "audio: %d requested, %d fired, %d dropped far, %d dropped full, peak %.2f"
                .format(requested, triggered, droppedFar, droppedFull, peak)
        )
        running = false
        thread?.join(500)
        thread = null
        device?.dispose()
        device = null
        flushCapture()
        for (v in voices) v.active = false
        seaLevel = 0f
        windLevel = 0f
    }

    /**
     * Where the ear is and how wide the frame is, in world metres. Panning and distance
     * attenuation both come off this, so a mine going up off the starboard bow is heard there.
     */
    fun listener(centreX: Float, viewWidth: Float) {
        listenX = centreX
        listenSpan = viewWidth.coerceAtLeast(8f) * 0.5f
    }

    /**
     * The continuous bed.
     *
     * Sea level rises with the state and with how hard she is being pushed through it; the
     * cutoff opens as it gets rough, so heavy weather is brighter and hissier as well as
     * louder. Going under lifts the level and shuts the cutoff right down -- the muffled
     * moment when green water comes over the rail is worth having.
     */
    fun bed(seaState: Float, windSpeed: Float, speed: Float, submerged: Float) {
        val rough = MathX.clamp01(seaState)
        val drive = MathX.clamp01(abs(speed) / 18f)
        val sub = MathX.clamp01(submerged)
        // Levels are set so the bed idles near -21 dBFS RMS and peaks near -14 in a full
        // storm. That is quiet enough to sit under everything and still be there -- measured,
        // not guessed: the first version ran at -5 dBFS RMS, which is brickwall loud and left
        // the cues nothing to punch through.
        seaTarget = (0.015f + rough * 0.055f + drive * 0.028f) * (1f + sub * 0.5f)
        seaCut = MathX.lerp(300f, 1500f, rough * 0.7f + drive * 0.3f) * (1f - sub * 0.72f)
        windTarget = MathX.clamp01(windSpeed / 34f) * 0.035f
        windCut = MathX.lerp(700f, 2600f, MathX.clamp01(windSpeed / 30f))
    }

    /** Fire a cue at a world position. */
    fun play(cue: Cue, worldX: Float, gain: Float = 1f, pitch: Float = 1f) {
        if (!running) return
        requested++
        val d = (worldX - listenX) / listenSpan
        // Beyond the frame a sound is still there, just small. A hard cut-off at the screen
        // edge makes the world feel like it stops where the camera does.
        val atten = 1f / (1f + d * d * 1.25f)
        if (atten < 0.02f) { droppedFar++; return }
        val pan = MathX.clamp(d, -1f, 1f)
        synchronized(qLock) {
            val next = (qWrite + 1) % queue.size
            if (next == qRead) { droppedFull++; return }   // never block the renderer
            val t = queue[qWrite]
            t.cue = cue
            t.gain = gain * atten
            t.pitch = pitch
            t.pan = pan
            qWrite = next
        }
    }

    /** Fire a cue with no position: banners, the countdown, anything happening to *you*. */
    fun playHere(cue: Cue, gain: Float = 1f, pitch: Float = 1f) = play(cue, listenX, gain, pitch)

    // -----------------------------------------------------------------------
    // Mixer
    // -----------------------------------------------------------------------

    private fun run() {
        val buf = FloatArray(BLOCK * 2)
        val dev = device
        val blockNanos = BLOCK * 1_000_000_000L / RATE
        try {
            while (running) {
                drain()
                fill(buf)
                if (dev != null) dev.writeSamples(buf, 0, buf.size)
                else Thread.sleep(blockNanos / 1_000_000L)
                capture?.let { appendCapture(it, buf) }
            }
        } catch (e: Throwable) {
            Gdx.app?.log("AetherTides", "audio thread stopped: $e")
        }
    }

    private fun drain() {
        while (true) {
            var cue: Cue? = null
            var gain = 1f; var pitch = 1f; var pan = 0f
            synchronized(qLock) {
                if (qRead != qWrite) {
                    val t = queue[qRead]
                    cue = t.cue; gain = t.gain; pitch = t.pitch; pan = t.pan
                    t.cue = null
                    qRead = (qRead + 1) % queue.size
                }
            }
            val c = cue ?: return
            startVoice(c, gain, pitch, pan)
        }
    }

    private fun startVoice(c: Cue, gain: Float, pitch: Float, pan: Float) {
        // Steal the voice with the least life left rather than the oldest slot: a long tail
        // that is nearly silent is always the right thing to lose.
        var best = -1
        var bestRemaining = Float.MAX_VALUE
        for (i in voices.indices) {
            val v = voices[i]
            if (!v.active) { best = i; bestRemaining = -1f; break }
            val remaining = (v.dur - v.t) * v.gain
            if (remaining < bestRemaining) { bestRemaining = remaining; best = i }
        }
        val v = voices[best]
        v.active = true
        v.phase = 0f
        v.f0 = c.f0 * pitch
        v.f1 = c.f1 * pitch
        v.noise = c.noise
        v.cut0 = c.cut0 * pitch
        v.cut1 = c.cut1 * pitch
        v.attack = c.attack.coerceAtLeast(0.0005f)
        v.dur = c.dur
        v.curve = c.curve
        v.gain = c.gain * gain
        v.drive = c.drive
        v.t = 0f
        v.lp = 0f
        v.rng = (triggered * 2654435761L).toInt() or 1
        // Equal-power panning, so a sound crossing the bow does not dip in the middle.
        val a = (pan * 0.5f + 0.5f).coerceIn(0f, 1f)
        v.panL = sqrt(1f - a)
        v.panR = sqrt(a)
        triggered++
    }

    private fun fill(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        val master = volume
        val dt = 1f / RATE

        // --- bed ---------------------------------------------------------
        val seaK = coef(seaCut)
        val windK = coef(windCut)
        // 40 ms smoothing on the levels: the sea does not switch volume, it swells.
        val lvlK = 1f - exp(-dt / 0.25f)
        var sl = seaLevel
        var wl = windLevel
        var slp = seaLp
        var wlp = windLp
        var srng = seaRng
        var wrng = windRng
        val sTgt = seaTarget
        val wTgt = windTarget

        var i = 0
        while (i < out.size) {
            sl += (sTgt - sl) * lvlK
            wl += (wTgt - wl) * lvlK
            srng = srng * 1103515245 + 12345
            wrng = wrng * 1103515245 + 12345
            val sn = (srng shr 9).toFloat() * (1f / 4194304f) - 1f
            val wn = (wrng shr 9).toFloat() * (1f / 4194304f) - 1f
            slp += seaK * (sn - slp)
            wlp += windK * (wn - wlp)
            // The sea gets a second pass so its floor is genuinely dull, not just quiet.
            val s = slp * sl * 2.2f + wlp * wl * 1.4f
            out[i] += s
            out[i + 1] += s
            i += 2
        }
        seaLevel = sl; windLevel = wl; seaLp = slp; windLp = wlp
        seaRng = srng; windRng = wrng

        // --- cue voices ---------------------------------------------------
        for (v in voices) {
            if (!v.active) continue
            var j = 0
            while (j < out.size) {
                val u = v.t / v.dur
                if (u >= 1f) { v.active = false; break }

                val env = if (v.t < v.attack) v.t / v.attack
                else {
                    val k = ((v.t - v.attack) / (v.dur - v.attack)).coerceIn(0f, 1f)
                    val inv = 1f - k
                    if (v.curve <= 1.01f) inv else inv * inv * (if (v.curve > 2.5f) inv else 1f)
                }

                v.rng = v.rng * 1103515245 + 12345
                val n = (v.rng shr 9).toFloat() * (1f / 4194304f) - 1f

                var s: Float
                if (v.noise >= 0.999f) {
                    s = n
                } else {
                    val f = v.f0 + (v.f1 - v.f0) * u
                    v.phase += TAU * f / RATE
                    if (v.phase > TAU) v.phase -= TAU
                    s = sin(v.phase) * (1f - v.noise) + n * v.noise
                }

                val k = coef(v.cut0 + (v.cut1 - v.cut0) * u)
                v.lp += k * (s - v.lp)
                s = v.lp

                if (v.drive > 0f) {
                    val g = 1f + v.drive * 6f
                    val x = s * g
                    s = x / (1f + abs(x))
                }

                s *= v.gain * env
                out[j] += s * v.panL
                out[j + 1] += s * v.panR
                v.t += dt
                j += 2
            }
        }

        // --- master -------------------------------------------------------
        var pk = 0f
        var m = 0
        while (m < out.size) {
            // Soft limiter: the bank is loud on purpose and a Kraken over a storm would clip.
            var s = out[m] * master
            if (s > 0.72f || s < -0.72f) s = 0.72f * (if (s > 0f) 1f else -1f) +
                    (s - 0.72f * (if (s > 0f) 1f else -1f)) * 0.25f
            s = s.coerceIn(-1f, 1f)
            out[m] = s
            val a = abs(s)
            if (a > pk) pk = a
            m++
        }
        if (pk > peak) peak = pk
    }

    /** One-pole lowpass coefficient for a cutoff in Hz. */
    private fun coef(hz: Float): Float {
        val f = hz.coerceIn(20f, RATE * 0.45f)
        return (1f - exp(-TAU * f / RATE)).coerceIn(0f, 1f)
    }

    // --- development capture ------------------------------------------------

    private fun appendCapture(out: ByteArrayOutputStream, buf: FloatArray) {
        if (out.size() > 200 * 1024 * 1024) return
        for (s in buf) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    private fun flushCapture() {
        val out = capture ?: return
        val path = capturePath ?: return
        capture = null
        val pcm = out.toByteArray()
        val f = File(path)
        f.parentFile?.mkdirs()
        f.outputStream().use { s ->
            fun i32(v: Int) { s.write(v and 0xFF); s.write((v shr 8) and 0xFF); s.write((v shr 16) and 0xFF); s.write((v shr 24) and 0xFF) }
            fun i16(v: Int) { s.write(v and 0xFF); s.write((v shr 8) and 0xFF) }
            s.write("RIFF".toByteArray()); i32(36 + pcm.size); s.write("WAVE".toByteArray())
            s.write("fmt ".toByteArray()); i32(16); i16(1); i16(2)
            i32(RATE); i32(RATE * 4); i16(4); i16(16)
            s.write("data".toByteArray()); i32(pcm.size)
            s.write(pcm)
        }
        Gdx.app?.log("AetherTides", "audio capture written: $path (${pcm.size / 4} frames)")
    }
}
