package com.jaygordonai.audioverse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.PlayerViewModel
import com.jaygordonai.audioverse.app.player.SoundSettings
import com.jaygordonai.audioverse.app.theme.AV
import com.jaygordonai.audioverse.dsp.ReplayGainMode
import com.jaygordonai.audioverse.dsp.VocalStage
import java.util.Locale

@Composable
fun SoundScreen(s: PlayerUi, vm: PlayerViewModel) {
    val snd = s.sound
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Sound", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text, modifier = Modifier.padding(top = 16.dp))
            Text("Decode → Gain → Vocal → EQ → Mixer → Limiter → Buds", color = AV.TextMuted, fontSize = 12.sp)
        }

        item {
            Panel("ReplayGain") {
                Segments(
                    options = listOf(ReplayGainMode.OFF to "Off", ReplayGainMode.TRACK to "Track", ReplayGainMode.ALBUM to "Album"),
                    selected = snd.replayGain, onSelect = vm::setReplayGain
                )
            }
        }

        item {
            Panel("Preamp", value = db(snd.preampDb)) {
                AmberSlider(snd.preampDb, -12f..12f, vm::setPreamp)
                if (snd.preampDb > 0f) {
                    Text("Boost above 0 dB. Keep volume moderate on earbuds.", color = AV.Warn, fontSize = 12.sp)
                }
            }
        }

        item {
            Panel("10-band EQ", trailing = {
                TextButton(onClick = vm::flatEq) { Text("Flat", color = AV.Amber) }
                AmberSwitch(snd.eqEnabled, vm::setEqEnabled)
            }) {
                SoundSettings.EQ_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = AV.TextMuted, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                        AmberSlider(snd.eqDb[i], -12f..12f, { vm.setEq(i, it) }, Modifier.weight(1f), enabled = snd.eqEnabled)
                        Text(db(snd.eqDb[i]), color = AV.Text, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                }
            }
        }

        item {
            Panel("Vocals") {
                Segments(
                    options = listOf(VocalStage.Mode.OFF to "Off", VocalStage.Mode.CUT to "Vocal cut", VocalStage.Mode.CENTRE_FOCUS to "Centre focus"),
                    selected = snd.vocalMode, onSelect = vm::setVocalMode
                )
                if (snd.vocalMode != VocalStage.Mode.OFF) {
                    Text("Strength ${(snd.vocalStrength * 100).toInt()}%", color = AV.TextMuted, fontSize = 12.sp)
                    AmberSlider(snd.vocalStrength, 0f..1f, vm::setVocalStrength)
                    if (s.nearMono) Text("This track is mono or near-mono, so vocal removal won't work on it.", color = AV.Warn, fontSize = 12.sp)
                    Text("Karaoke quality: reverb and stereo backing vocals remain.", color = AV.TextMuted, fontSize = 12.sp)
                }
            }
        }

        item {
            Panel("Channel mixer", trailing = {
                Text("Mono", color = AV.TextMuted, fontSize = 13.sp)
                AmberSwitch(snd.mono, vm::setMono)
            }) {
                Text("Balance ${balanceText(snd.balance)}", color = AV.TextMuted, fontSize = 12.sp)
                AmberSlider(snd.balance, -1f..1f, vm::setBalance)
                if (!snd.mono) {
                    Text("Stereo width ${(snd.width * 100).toInt()}%", color = AV.TextMuted, fontSize = 12.sp)
                    AmberSlider(snd.width, 0f..2f, vm::setWidth)
                }
            }
        }

        item {
            Panel("Limiter", value = if (snd.limiterEnabled) "−1 dBFS ceiling" else "Off", trailing = { AmberSwitch(snd.limiterEnabled, vm::setLimiter) }) {
                Text(
                    "Catches peaks so boost never clips. Now: " +
                        if (s.limiterGrDb < -0.1f) "reducing ${db(s.limiterGrDb)}" else "no reduction",
                    color = AV.TextMuted, fontSize = 12.sp
                )
            }
        }

        item {
            Text(
                "Stereo correlation ${String.format(Locale.US, "%.2f", s.correlation)} · settings are saved on this phone",
                color = AV.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun Panel(
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(AV.Surface, RoundedCornerShape(16.dp)).border(1.dp, AV.Line, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = AV.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            value?.let { Text(it, color = AV.Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = AV.SurfaceHigh, activeContentColor = AV.Text,
                    inactiveContainerColor = AV.Surface, inactiveContentColor = AV.TextMuted,
                    activeBorderColor = AV.Line, inactiveBorderColor = AV.Line
                )
            ) { Text(label, maxLines = 1, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun AmberSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = AV.Text, activeTrackColor = AV.Amber, inactiveTrackColor = AV.Line)
    )
}

@Composable
private fun AmberSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedThumbColor = AV.OnAmber, checkedTrackColor = AV.Amber, uncheckedTrackColor = AV.Line)
    )
}

private fun db(v: Float) = String.format(Locale.US, "%+.1f dB", v)

private fun balanceText(b: Float) = when {
    b < -0.02f -> "L ${(-b * 100).toInt()}%"
    b > 0.02f -> "R ${(b * 100).toInt()}%"
    else -> "centre"
}
