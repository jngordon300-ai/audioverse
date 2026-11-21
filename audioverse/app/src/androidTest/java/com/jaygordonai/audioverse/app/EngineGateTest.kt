package com.jaygordonai.audioverse.app

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jaygordonai.audioverse.app.gate.ProbeAudioProcessor
import com.jaygordonai.audioverse.dsp.AudiophileDspProcessor
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.playback.Media3Backend
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator gate: plays bundled test tones through the real Media3 engine.
 * Every outcome is logged under the AV_GATE tag; CI turns those lines into a
 * build annotation so results are readable without downloading artifacts.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EngineGateTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val files = listOf("tone_16_44k.mp3", "tone_16_44k.flac", "tone_24_96k.flac", "tone_24_48k.wav")

    private class Engine(val backend: Media3Backend, val probe: ProbeAudioProcessor, val probeOut: ProbeAudioProcessor? = null) {
        @Volatile var error: String? = null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Test tones are packaged in the TEST apk, but the player runs in the app's
     * context, so asset:/// would look in the wrong apk. Copy each tone from the
     * instrumentation context into the app's cache once and play it as a file.
     */
    private fun asset(name: String): Uri {
        val out = java.io.File(context.cacheDir, "gate_$name")
        if (!out.exists() || out.length() == 0L) {
            instrumentation.context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(out)
    }

    private fun build(processingSink: Boolean, dsp: Boolean, outputProbe: Boolean = false): Engine = onMain {
        val probe = ProbeAudioProcessor()
        val probeOut = if (outputProbe) ProbeAudioProcessor() else null
        val processor = AudiophileDspProcessor().apply {
            setReplayGainMode(ReplayGainMode.OFF)
            limiterEnabled = false
        }
        val backend = Media3Backend(
            context = context,
            useProcessingSink = processingSink,
            processors = (listOf(probe) + (if (dsp) listOf(processor) else emptyList()) + listOfNotNull(probeOut))
                .toTypedArray<androidx.media3.common.audio.AudioProcessor>(),
            authProvider = { null }
        )
        Engine(backend, probe, probeOut).also { e ->
            backend.player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    e.error = error.errorCodeName + " | " +
                        generateSequence(error.cause) { it.cause }.take(3)
                            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(160)}" }
                }
            })
        }
    }

    private fun waitFor(e: Engine, timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (e.error != null) return false
            if (onMain(cond)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /** Plays one file for ~1.5 s. Returns null on success or a failure description. */
    private fun playOnce(processingSink: Boolean, dsp: Boolean, file: String): String? {
        val e = build(processingSink, dsp)
        try {
            onMain { e.backend.setQueue(listOf(asset(file))); e.backend.play() }
            val ok = waitFor(e, 15_000) { e.backend.player.currentPosition >= 1_500 }
            val snap = e.probe.snapshot()
            val line = "sink=${if (processingSink) "AudioVerse" else "stock"} dsp=${if (dsp) "in" else "out"} " +
                "file=$file -> ${if (ok) "PLAYS" else "FAILS"} probeRan=${snap.framesSeen > 0} " +
                "dspInput=${encName(snap.encoding)}@${snap.sampleRate}" + (e.error?.let { " error=$it" } ?: "")
            Log.i(TAG, line)
            return if (ok) null else line
        } finally {
            onMain { e.backend.release() }
        }
    }

    @Test
    fun audioVerseSinkPlaysEveryFormatWithAndWithoutDsp() {
        val failures = mutableListOf<String>()
        for (dsp in listOf(true, false)) for (f in files) playOnce(true, dsp, f)?.let { failures += it }
        if (failures.isNotEmpty()) fail("AudioVerse sink failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun audioVerseSinkRunsDspOnHiResFile() {
        val e = build(processingSink = true, dsp = true)
        try {
            onMain { e.backend.setQueue(listOf(asset("tone_24_96k.flac"))); e.backend.play() }
            val ok = waitFor(e, 15_000) { e.backend.player.currentPosition >= 1_500 }
            val snap = e.probe.snapshot()
            Log.i(TAG, "hires: plays=$ok dspInput=${encName(snap.encoding)}@${snap.sampleRate} frames=${snap.framesSeen}" +
                (e.error?.let { " error=$it" } ?: ""))
            assertTrue("hi-res file did not play: ${e.error}", ok)
            assertTrue("DSP did not run on hi-res input", snap.framesSeen > 0)
            assertTrue("sample rate not preserved: ${snap.sampleRate}", snap.sampleRate == 96_000)
        } finally {
            onMain { e.backend.release() }
        }
    }

    @Test
    fun audioVerseSinkIsGapless() {
        val e = build(processingSink = true, dsp = true)
        try {
            onMain { e.backend.setQueue(listOf(asset("gapless_part1.flac"), asset("gapless_part2.flac"))) }
            assertTrue("did not prepare: ${e.error}", waitFor(e, 15_000) { e.backend.player.playbackState == Player.STATE_READY })
            val dur = onMain { e.backend.player.duration }
            assertTrue(dur != C.TIME_UNSET)
            onMain { e.backend.player.seekTo(0, (dur - 1_500).coerceAtLeast(0)) }
            waitFor(e, 10_000) { e.backend.player.playbackState == Player.STATE_READY }
            e.probe.arm()
            onMain { e.backend.play() }
            val crossed = waitFor(e, 20_000) {
                e.backend.player.currentMediaItemIndex == 1 && e.backend.player.currentPosition >= 1_000
            }
            e.probe.disarm()
            val ms = e.probe.snapshot().longestSilenceMs
            Log.i(TAG, "gapless: crossed=$crossed longestSilenceMs=${"%.3f".format(ms)}" + (e.error?.let { " error=$it" } ?: ""))
            assertTrue("did not reach track 2: ${e.error}", crossed)
            assertTrue("gap of $ms ms at the track change", ms < 5.0)
        } finally {
            onMain { e.backend.release() }
        }
    }

    /**
     * Guards against byte-order or format mistakes: with a neutral DSP the level
     * coming out must match the level going in (tones peak at about 0.088).
     * Garbage from misread samples would be far louder.
     */
    @Test
    fun dspOutputMatchesInputLevel() {
        val problems = mutableListOf<String>()
        for (f in files) {
            val e = build(processingSink = true, dsp = true, outputProbe = true)
            try {
                onMain { e.backend.setQueue(listOf(asset(f))); e.backend.play() }
                val ok = waitFor(e, 15_000) { e.backend.player.currentPosition >= 1_500 }
                val inPeak = e.probe.snapshot().peak
                val outPeak = e.probeOut!!.snapshot().peak
                val line = "levels file=$f plays=$ok inPeak=${"%.4f".format(inPeak)} outPeak=${"%.4f".format(outPeak)}" +
                    (e.error?.let { " error=$it" } ?: "")
                Log.i(TAG, line)
                val sane = ok && inPeak in 0.05f..0.15f && kotlin.math.abs(outPeak - inPeak) < 0.01f
                if (!sane) problems += line
            } finally {
                onMain { e.backend.release() }
            }
        }
        if (problems.isNotEmpty()) fail("Level check failed:\n" + problems.joinToString("\n"))
    }

    /** Documents the stock Media3 sink for the Council; logs only, never fails. */
    @Test
    fun stockSinkComparisonReport() {
        for (dsp in listOf(true, false)) for (f in files) playOnce(false, dsp, f)
    }

    private fun encName(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> "16bit"
        C.ENCODING_PCM_24BIT -> "24bit"
        C.ENCODING_PCM_32BIT -> "32bit"
        C.ENCODING_PCM_FLOAT -> "float"
        else -> "none"
    }

    private companion object { const val TAG = "AV_GATE" }
}
