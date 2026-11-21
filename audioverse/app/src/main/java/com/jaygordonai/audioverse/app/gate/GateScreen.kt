package com.jaygordonai.audioverse.app.gate

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaygordonai.audioverse.app.theme.Values

@Composable
fun GateScreen(vm: GateViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Secrets live only in memory (remember, not rememberSaveable) so they are never
    // written into saved-instance state.
    var gaplessUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var hiResUri by remember { mutableStateOf<Uri?>(null) }
    var streamUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var cfId by remember { mutableStateOf("") }
    var cfSecret by remember { mutableStateOf("") }
    var radioUrl by remember { mutableStateOf("") }

    val pickTracks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { gaplessUris = it }
    val pickHiRes = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { hiResUri = it }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Masthead() }
        item {
            Row(
                Modifier.fillMaxWidth().background(Values.Card).border(1.dp, Values.Rule).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Float output", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Run tests 1 and 2 with this ON and OFF to see how Media3 treats hi-res audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Values.InkBody
                    )
                }
                Switch(
                    checked = s.floatOutput, onCheckedChange = vm::setFloatOutput, enabled = !s.busy,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Values.Card,
                        checkedTrackColor = Values.GoldText,
                        uncheckedThumbColor = Values.GoldText,
                        uncheckedTrackColor = Values.Paper,
                        uncheckedBorderColor = Values.Gold
                    )
                )
            }
        }

        item {
            TestCard(GateTest.GAPLESS, s.results.getValue(GateTest.GAPLESS)) {
                Body("Pick 2+ consecutive tracks from an album that plays without gaps (live album or DJ mix).")
                ButtonRow {
                    OutlineAction("Pick tracks (${gaplessUris.size})") { pickTracks.launch(arrayOf("audio/*")) }
                    InkAction("Run", enabled = !s.busy) { vm.runGapless(gaplessUris) }
                }
            }
        }
        item {
            TestCard(GateTest.HIRES, s.results.getValue(GateTest.HIRES)) {
                Body("Pick one 24-bit FLAC file.")
                ButtonRow {
                    OutlineAction(if (hiResUri == null) "Pick file" else "File picked") { pickHiRes.launch(arrayOf("audio/*")) }
                    InkAction("Run", enabled = !s.busy) { vm.runHiRes(hiResUri) }
                }
            }
        }
        item {
            TestCard(GateTest.JELLYFIN, s.results.getValue(GateTest.JELLYFIN)) {
                Body(
                    "Paste an https stream URL for one track through your Cloudflare Tunnel, without api_key. " +
                        "Token and Cloudflare service token are stored encrypted on this phone only."
                )
                OutlinedTextField(streamUrl, { streamUrl = it }, label = { Text("Stream URL (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                SecretField(token, { token = it }, if (s.hasSavedServer) "Jellyfin token (saved; leave blank)" else "Jellyfin token")
                SecretField(cfId, { cfId = it }, "CF-Access-Client-Id (optional)")
                SecretField(cfSecret, { cfSecret = it }, "CF-Access-Client-Secret (optional)")
                ButtonRow {
                    InkAction("Run", enabled = !s.busy) {
                        vm.runJellyfin(streamUrl, token, cfId, cfSecret)
                        token = ""; cfId = ""; cfSecret = "" // clear from screen memory once saved
                    }
                    if (s.hasSavedServer) {
                        TextButton(onClick = vm::forgetServer, shape = RectangleShape) {
                            Text("Forget saved login", color = Values.GoldText)
                        }
                    }
                }
            }
        }
        item {
            TestCard(GateTest.ICY, s.results.getValue(GateTest.ICY)) {
                OutlinedTextField(radioUrl, { radioUrl = it }, label = { Text("Radio stream URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                InkAction("Run", enabled = !s.busy) { vm.runIcy(radioUrl) }
            }
        }
        item {
            TestCard(GateTest.BITPERFECT, s.results.getValue(GateTest.BITPERFECT)) {
                Body("Attach a USB DAC first. This only asks the phone; it never switches bit-perfect on.")
                InkAction("Run", enabled = !s.busy, onClick = vm::runBitPerfect)
            }
        }
        item {
            TestCard(GateTest.LOCKED, s.results.getValue(GateTest.LOCKED)) {
                Body("Start any test that plays audio, press Play in the notification if needed, lock the phone for 60 seconds, then report.")
                ButtonRow {
                    InkAction("Kept playing") { vm.markLocked(true) }
                    OutlineAction("Stopped") { vm.markLocked(false) }
                }
            }
        }
        item {
            // Espresso band, like the closing bar on the VALUES contents page.
            Column(
                Modifier.padding(bottom = 32.dp).fillMaxWidth().background(Values.Ink).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("For the AiVerse Council", fontFamily = Values.Script, fontSize = 28.sp, color = Values.GoldOnInk)
                Text(
                    "Copy results puts a text report on the clipboard. It contains no URLs, hostnames or tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Values.PaperOnInk
                )
                ButtonRow {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(vm.resultsText())) },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Values.GoldOnInk, contentColor = Values.Ink)
                    ) { Text("COPY RESULTS") }
                    OutlinedButton(
                        onClick = vm::stop,
                        shape = RectangleShape,
                        border = BorderStroke(1.dp, Values.GoldOnInk),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Values.Paper)
                    ) { Text("STOP AUDIO") }
                }
            }
        }
    }
}

@Composable
private fun Masthead() {
    Column(Modifier.padding(top = 24.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PHASE 1 · ENGINE GATE", style = Values.Kicker, color = Values.InkMuted)
            Text("V0.1.0", style = Values.Kicker, color = Values.InkMuted)
        }
        Text(
            "AudioVerse",
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldRule(Modifier.weight(1f))
            Text("Media3 engine tests", style = MaterialTheme.typography.headlineSmall)
            GoldRule(Modifier.weight(1f))
        }
        Text(
            "Six tests that answer the Phase 1 questions on real hardware.",
            style = MaterialTheme.typography.bodyLarge,
            color = Values.InkBody
        )
    }
}

@Composable
private fun GoldRule(modifier: Modifier) {
    Box(modifier.height(2.dp).background(Values.Gold))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Values.InkBody)
}

@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun InkAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RectangleShape) { Text(label.uppercase()) }
}

@Composable
private fun OutlineAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RectangleShape,
        border = BorderStroke(1.dp, Values.Gold),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Values.Ink)
    ) { Text(label.uppercase()) }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TestCard(test: GateTest, result: TestResult, content: @Composable () -> Unit) {
    // Titles are "1. Gapless playback"; show the number as a gold "01" like the contents page.
    val number = test.title.substringBefore(". ").padStart(2, '0')
    val name = test.title.substringAfter(". ")
    Column(
        Modifier.fillMaxWidth().background(Values.Card).border(1.dp, Values.Rule).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, style = MaterialTheme.typography.titleLarge, color = Values.GoldText)
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            StatusTag(result.status)
        }
        content()
        if (result.detail.isNotBlank()) {
            Text(
                result.detail,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (result.status == Status.PASS || result.status == Status.FAIL) FontWeight.SemiBold else FontWeight.Normal,
                color = statusColor(result.status),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun StatusTag(status: Status) {
    val color = statusColor(status)
    Text(
        status.name.replace('_', ' '),
        style = Values.Kicker.copy(fontSize = 10.sp, letterSpacing = 2.sp),
        color = color,
        modifier = Modifier.border(1.dp, if (status == Status.NOT_RUN) Values.Rule else color).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private fun statusColor(s: Status): Color = when (s) {
    Status.PASS -> Values.Pass
    Status.FAIL -> Values.Fail
    Status.RUNNING -> Values.GoldText
    else -> Values.InkMuted
}
