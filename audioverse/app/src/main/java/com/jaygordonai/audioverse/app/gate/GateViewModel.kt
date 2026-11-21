package com.jaygordonai.audioverse.app.gate

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.jaygordonai.audioverse.app.PlayerHolder
import com.jaygordonai.audioverse.network.AuthConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GateTest(val title: String) {
    GAPLESS("1. Gapless playback"),
    HIRES("2. 24-bit / float through DSP"),
    JELLYFIN("3. Jellyfin + Cloudflare Access"),
    ICY("4. Radio now-playing (ICY)"),
    BITPERFECT("5. Bit-perfect USB query"),
    LOCKED("6. Screen-locked playback")
}

enum class Status { NOT_RUN, RUNNING, PASS, FAIL, INFO }

data class TestResult(val status: Status = Status.NOT_RUN, val detail: String = "")

data class GateUiState(
    val processingSink: Boolean = true,
    val dspInChain: Boolean = true,
    val busy: Boolean = false,
    val hasSavedServer: Boolean = false,
    val results: Map<GateTest, TestResult> = GateTest.entries.associateWith { TestResult() }
)

@OptIn(UnstableApi::class)
class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val _ui = MutableStateFlow(GateUiState())
    val ui: StateFlow<GateUiState> = _ui.asStateFlow()
    private var job: Job? = null

    // Captured from analytics during a run
    @Volatile private var sinkEncoding = C.ENCODING_INVALID
    @Volatile private var sinkRate = 0
    @Volatile private var decoderName = "unknown"
    @Volatile private var lastError: String? = null
    @Volatile private var icyTitle: String? = null
    @Volatile private var inputFormat = "unknown"

    private val analytics = object : AnalyticsListener {
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            sinkEncoding = audioTrackConfig.encoding
            sinkRate = audioTrackConfig.sampleRate
        }
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            inputFormat = "${format.sampleMimeType ?: "?"} ${format.sampleRate / 1000.0} kHz ${format.channelCount} ch" +
                if (format.pcmEncoding != androidx.media3.common.Format.NO_VALUE) " ${enc(format.pcmEncoding)}" else ""
        }
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long
        ) { this@GateViewModel.decoderName = decoderName }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { lastError = describe(error) }
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo && !entry.title.isNullOrBlank()) icyTitle = entry.title
            }
        }
    }

    init {
        PlayerHolder.loadAuth(ctx)
        _ui.update { it.copy(processingSink = PlayerHolder.processingSink, dspInChain = PlayerHolder.dspInChain, hasSavedServer = PlayerHolder.auth?.hasJellyfin == true) }
        attach()
    }

    private fun engine() = PlayerHolder.get(ctx)

    private fun attach() {
        val p = engine().backend.player
        p.removeListener(playerListener); p.addListener(playerListener)
        p.removeAnalyticsListener(analytics); p.addAnalyticsListener(analytics)
    }

    fun setProcessingSink(enabled: Boolean) {
        job?.cancel()
        PlayerHolder.rebuild(ctx, enabled, PlayerHolder.dspInChain)
        attach()
        _ui.update { it.copy(processingSink = enabled, busy = false) }
    }

    fun setDspInChain(enabled: Boolean) {
        job?.cancel()
        PlayerHolder.rebuild(ctx, PlayerHolder.processingSink, enabled)
        attach()
        _ui.update { it.copy(dspInChain = enabled, busy = false) }
    }

    /**
     * Error code plus the cause chain (exception types and messages), so failures
     * like AUDIO_TRACK_INIT_FAILED show the AudioTrack state and format requested.
     * Anything that looks like a URL or content URI is masked.
     */
    private fun describe(error: PlaybackException): String {
        val causes = generateSequence(error.cause) { it.cause }.take(4).joinToString(" <- ") { t ->
            t.javaClass.simpleName + (t.message?.let { ": " + it.take(220) } ?: "")
        }
        val masked = causes.replace(Regex("(content|file|https?)://\\S+"), "[uri]")
        return error.errorCodeName + if (masked.isNotBlank()) " | $masked" else ""
    }

    private fun sinkLabel() = if (_ui.value.processingSink) "AudioVerse sink" else "Stock Media3 sink"

    fun stop() {
        job?.cancel()
        engine().backend.pause()
        _ui.update { it.copy(busy = false) }
    }

    // ---------------- Test 1: gapless ----------------
    fun runGapless(uris: List<Uri>) = launchTest(GateTest.GAPLESS) {
        if (uris.size < 2) return@launchTest TestResult(Status.FAIL, "Pick at least 2 consecutive tracks from a gapless album.")
        val e = engine(); val p = e.backend.player
        e.backend.setQueue(uris)
        if (!waitUntil(20_000) { p.playbackState == Player.STATE_READY }) return@launchTest openFailure()
        val dur = p.duration
        if (dur == C.TIME_UNSET) return@launchTest TestResult(Status.FAIL, "Track 1 length unknown.")
        p.seekTo(0, (dur - 6_000).coerceAtLeast(0))
        waitUntil(10_000) { p.playbackState == Player.STATE_READY }
        val framesBefore = e.probe.snapshot().framesSeen
        e.probe.arm()
        p.play()
        val crossed = waitUntil(40_000) { p.currentMediaItemIndex >= 1 && p.currentPosition >= 4_000 }
        p.pause(); e.probe.disarm()
        val snap = e.probe.snapshot()
        when {
            lastError != null -> TestResult(Status.FAIL, "Playback error: $lastError")
            !crossed -> TestResult(Status.FAIL, "Did not reach track 2 in time.")
            snap.framesSeen == framesBefore -> TestResult(
                Status.INFO,
                "Our processors did not run (stock sink with hi-res input), so silence could not be measured. " +
                    "Switch to the AudioVerse sink and run again."
            )
            else -> {
                val ms = snap.longestSilenceMs
                val pass = ms < GAPLESS_MAX_SILENCE_MS
                TestResult(
                    if (pass) Status.PASS else Status.FAIL,
                    "Longest digital silence around the track change: ${fmt(ms)} ms " +
                        "(provisional pass under ${GAPLESS_MAX_SILENCE_MS.toInt()} ms; threshold pending Council). " +
                        "Also judge by ear: any gap or click? Tracks that end in real silence will fail this check."
                )
            }
        }
    }

    // ---------------- Test 2: 24-bit / float ----------------
    fun runHiRes(uri: Uri?) = launchTest(GateTest.HIRES) {
        if (uri == null) return@launchTest TestResult(Status.FAIL, "Pick a 24-bit FLAC file.")
        val e = engine(); val p = e.backend.player
        sinkEncoding = C.ENCODING_INVALID; sinkRate = 0; decoderName = "unknown"; inputFormat = "unknown"
        val framesBefore = e.probe.snapshot().framesSeen
        e.backend.setQueue(listOf(uri))
        if (!waitUntil(20_000) { p.playbackState == Player.STATE_READY }) return@launchTest openFailure()
        p.play(); delay(4_000); p.pause()
        val snap = e.probe.snapshot()
        val ran = snap.framesSeen > framesBefore
        val base = "${sinkLabel()}, DSP ${if (_ui.value.dspInChain) "IN" else "OUT"}. " +
            "File: $inputFormat. Decoder: $decoderName. " +
            "DSP input: ${if (ran) "${enc(snap.encoding)} at ${snap.sampleRate / 1000.0} kHz" else "none"}. " +
            "Output to Android: ${enc(sinkEncoding)} at ${sinkRate / 1000.0} kHz. "
        when {
            lastError != null -> TestResult(Status.FAIL, base + "Playback error: $lastError")
            !ran -> TestResult(
                Status.FAIL,
                base + "Our processors did not run on this file, so EQ and limiter would be bypassed. " +
                    "Expected with the stock sink; with the AudioVerse sink this is a bug."
            )
            snap.encoding == C.ENCODING_PCM_16BIT -> TestResult(
                Status.FAIL,
                base + "The decoder delivered 16-bit, so a 24-bit file lost detail before the DSP. " +
                    "(Pass is expected only for a real 24-bit file.)"
            )
            else -> TestResult(
                Status.PASS,
                base + "Hi-res reaches the DSP (${enc(snap.encoding)}) and leaves as float: no truncation."
            )
        }
    }

    // ---------------- Test 3: Jellyfin + Cloudflare Access ----------------
    fun runJellyfin(streamUrl: String, token: String, cfId: String, cfSecret: String) = launchTest(GateTest.JELLYFIN) {
        val uri = Uri.parse(streamUrl.trim())
        val host = uri.host
        if (uri.scheme != "https" || host.isNullOrBlank()) {
            return@launchTest TestResult(Status.FAIL, "Use an https:// stream URL. Credentials are never sent over plain HTTP.")
        }
        if (uri.queryParameterNames.any { it.equals("api_key", true) || it.equals("apikey", true) }) {
            return@launchTest TestResult(Status.FAIL, "Remove api_key from the URL. AudioVerse sends the token as a header instead.")
        }
        val saved = PlayerHolder.auth
        val config = AuthConfig(
            jellyfinHost = host,
            jellyfinToken = token.ifBlank { saved?.jellyfinToken },
            tunnelHost = if (cfId.isBlank() && saved?.cfClientId.isNullOrBlank()) null else host,
            cfClientId = cfId.ifBlank { saved?.cfClientId },
            cfClientSecret = cfSecret.ifBlank { saved?.cfClientSecret }
        )
        if (!config.hasJellyfin) return@launchTest TestResult(Status.FAIL, "Enter your Jellyfin access token.")
        PlayerHolder.setAuth(ctx, config)
        _ui.update { it.copy(hasSavedServer = true) }

        val e = engine(); val p = e.backend.player
        e.backend.httpRecorder.clear()
        e.backend.setQueue(listOf(uri))
        if (!waitUntil(20_000) { p.playbackState == Player.STATE_READY }) return@launchTest serverSummary(false, "Could not open the stream.")
        p.play(); delay(3_000)
        val before = e.backend.httpRecorder.snapshot().size
        val dur = p.duration
        val target = if (dur != C.TIME_UNSET && dur < 70_000) dur / 2 else 60_000L
        p.seekTo(target)
        val seeked = waitUntil(15_000) { p.playbackState == Player.STATE_READY && p.currentPosition >= target - 2_000 }
        delay(2_000); p.pause()
        val afterSeek = e.backend.httpRecorder.snapshot().drop(before)
        val rangeOk = afterSeek.any { it.toConfiguredServer && it.status == 206 && it.rangeRequested }
        serverSummary(seeked && rangeOk, if (!seeked) "Seek did not complete." else null)
    }

    private fun serverSummary(seekOk: Boolean, note: String?): TestResult {
        val events = engine().backend.httpRecorder.snapshot().filter { it.toConfiguredServer }
        val statuses = events.groupingBy { it.status }.eachCount().entries.joinToString { "${it.key}×${it.value}" }
        val authFail = events.any { it.status == 401 || it.status == 403 }
        val tokenInUrl = events.any { it.tokenInUrl }
        val credsOnHttp = events.any { it.credentialsAttached && !it.https }
        val pass = seekOk && !authFail && !tokenInUrl && !credsOnHttp && lastError == null && events.isNotEmpty()
        val detail = buildString {
            append("${events.size} request(s) to your server; status codes: ${statuses.ifBlank { "none" }}. ")
            append("Seek used HTTP range (206): ${if (seekOk) "yes" else "no"}. ")
            append("Token in URL: ${if (tokenInUrl) "YES (fix)" else "no"}. ")
            append("Credentials only over HTTPS: ${if (credsOnHttp) "NO (fix)" else "yes"}. ")
            if (authFail) append("Login rejected (401/403): check the token and the Cloudflare Access policy (must be Service Auth). ")
            lastError?.let { append("Playback error: $it. ") }
            note?.let { append(it) }
        }
        return TestResult(if (pass) Status.PASS else Status.FAIL, detail.trim())
    }

    fun forgetServer() {
        PlayerHolder.setAuth(ctx, null)
        _ui.update { it.copy(hasSavedServer = false) }
    }

    // ---------------- Test 4: ICY ----------------
    fun runIcy(url: String) = launchTest(GateTest.ICY) {
        val uri = Uri.parse(url.trim())
        if (uri.scheme != "http" && uri.scheme != "https") return@launchTest TestResult(Status.FAIL, "Enter a stream URL starting with http:// or https://.")
        icyTitle = null
        val e = engine(); val p = e.backend.player
        e.backend.setQueue(listOf(uri))
        p.play()
        val got = waitUntil(30_000) { icyTitle != null || lastError != null }
        val playing = p.isPlaying
        p.pause()
        when {
            lastError != null -> TestResult(Status.FAIL, "Playback error: $lastError")
            got && icyTitle != null -> TestResult(Status.PASS, "Now playing received: \"$icyTitle\". Stream over ${uri.scheme?.uppercase()}.")
            playing -> TestResult(Status.INFO, "Stream played but sent no now-playing title in 30 s. Try another station.")
            else -> TestResult(Status.FAIL, "Stream did not start.")
        }
    }

    // ---------------- Test 5: bit-perfect ----------------
    fun runBitPerfect() = launchTest(GateTest.BITPERFECT) {
        val r = BitPerfectProbe.run(ctx)
        when {
            !r.supportedOs -> TestResult(Status.FAIL, r.summary)
            !r.dacAttached -> TestResult(Status.INFO, r.summary)
            r.bitPerfectFormats > 0 -> TestResult(Status.PASS, r.summary + "\nQuery only: bit-perfect was not switched on.")
            else -> TestResult(Status.FAIL, r.summary + "\nThis phone's audio system offers no bit-perfect mode for this DAC.")
        }
    }

    // ---------------- Test 6: manual ----------------
    fun markLocked(pass: Boolean) = setResult(
        GateTest.LOCKED,
        TestResult(if (pass) Status.PASS else Status.FAIL, if (pass) "Audio continued for 60 s with the screen locked." else "Audio stopped when the screen locked.")
    )

    fun resultsText(): String = buildString {
        appendLine("AudioVerse Phase 1 gate results")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Engine: Media3 1.5.1, ${sinkLabel()}, DSP ${if (_ui.value.dspInChain) "in chain" else "out of chain"}")
        appendLine()
        _ui.value.results.forEach { (t, r) -> appendLine("${t.title}: ${r.status}\n  ${r.detail}") }
    }

    // ---------------- helpers ----------------
    private fun launchTest(test: GateTest, body: suspend () -> TestResult) {
        job?.cancel()
        lastError = null
        attach()
        setResult(test, TestResult(Status.RUNNING, "Running…"))
        _ui.update { it.copy(busy = true) }
        job = viewModelScope.launch {
            val result = try { body() } catch (c: kotlinx.coroutines.CancellationException) {
                TestResult(Status.NOT_RUN, "Stopped.")
            } catch (e: Exception) {
                TestResult(Status.FAIL, "Test crashed: ${e.javaClass.simpleName}")
            }
            setResult(test, result)
            _ui.update { it.copy(busy = false) }
        }
    }

    private fun setResult(test: GateTest, r: TestResult) =
        _ui.update { it.copy(results = it.results + (test to r)) }

    private fun openFailure() = TestResult(Status.FAIL, "Could not open the file or stream" + (lastError?.let { ": $it" } ?: "."))

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (lastError != null) return false // fail fast instead of waiting out the timeout
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun enc(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        C.ENCODING_PCM_24BIT -> "24-bit PCM"
        C.ENCODING_PCM_32BIT -> "32-bit PCM"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        C.ENCODING_INVALID -> "unknown"
        else -> "encoding $e"
    }

    override fun onCleared() {
        val p = engine().backend.player
        p.removeListener(playerListener)
        p.removeAnalyticsListener(analytics)
    }

    private companion object {
        const val GAPLESS_MAX_SILENCE_MS = 5.0
    }
}
