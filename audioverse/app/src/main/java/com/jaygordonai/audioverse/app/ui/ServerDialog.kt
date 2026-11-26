package com.jaygordonai.audioverse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.PlayerViewModel
import com.jaygordonai.audioverse.app.theme.AV

/**
 * Home server (Jellyfin on the Mac Mini). Secrets live only in this dialog's memory until
 * Connect; the password is used once to get a token and is never stored.
 */
@Composable
fun ServerDialog(s: PlayerUi, vm: PlayerViewModel, onClose: () -> Unit) {
    val st = s.server
    var editing by remember { mutableStateOf(!st.configured) }
    var home by remember { mutableStateOf(st.homeAddress) }
    var remote by remember { mutableStateOf(st.remoteAddress) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var cfId by remember { mutableStateOf("") }
    var cfSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val secret = KeyboardOptions(keyboardType = KeyboardType.Password)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Home server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.configured) {
                    Text(
                        when {
                            st.error != null -> st.error
                            st.via != null -> "${st.serverName.ifBlank { "Jellyfin" }} via ${st.via} · ${st.songs} songs · ${st.videos} videos"
                            else -> "Connecting…"
                        },
                        color = if (st.error != null) AV.Warn else AV.Text, fontSize = 13.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Data saver", color = AV.Text)
                            Text("Server converts to MP3 192k / 4 Mbps video. Off = original files (FLAC, full quality).", color = AV.TextMuted, fontSize = 11.sp)
                        }
                        Switch(st.dataSaver, vm::setServerDataSaver, colors = SwitchDefaults.colors(checkedTrackColor = AV.Amber))
                    }
                    Row {
                        TextButton(onClick = vm::rescan) { Text("Reload library", color = AV.Amber) }
                        TextButton(onClick = { editing = !editing }) { Text(if (editing) "Hide settings" else "Change", color = AV.Amber) }
                        TextButton(onClick = { vm.disconnectServer(); onClose() }) { Text("Sign out", color = AV.Warn) }
                    }
                } else {
                    Text(
                        "Stream the music and videos on your Mac Mini's drives through Jellyfin: at home over Wi-Fi, " +
                            "away through your VPN (Tailscale / Meshnet) or Cloudflare.",
                        color = AV.TextMuted, fontSize = 12.sp
                    )
                }
                if (st.configured) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto Data saver on mobile data", color = AV.Text)
                        Text("Original quality on Wi-Fi; smaller streams on cellular.", color = AV.TextMuted, fontSize = 11.sp)
                    }
                    Switch(st.autoSaver, vm::setServerAutoSaver, colors = SwitchDefaults.colors(checkedTrackColor = AV.Amber))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trusted home Wi-Fi", color = AV.Text)
                        Text(
                            "Off (safer): the home http address is used only while your VPN is on. On: also on any Wi-Fi. " +
                                "Only turn on if you use the home address without a VPN, and never on public Wi-Fi.",
                            color = if (st.trustHomeWifi) AV.Warn else AV.TextMuted, fontSize = 11.sp
                        )
                    }
                    Switch(st.trustHomeWifi, vm::setTrustHomeWifi, colors = SwitchDefaults.colors(checkedTrackColor = AV.Amber))
                }
                if (editing) {
                    Text("Addresses (fill one or both)", color = AV.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    OutlinedTextField(home, { home = it.trim() }, singleLine = true, label = { Text("Home / VPN: http://100.x.x.x:8096") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(remote, { remote = it.trim() }, singleLine = true, label = { Text("Cloudflare: https://media.yourdomain.com") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("Jellyfin login (the password is used once and not saved)", color = AV.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret)
                    Text("Cloudflare Access service token (only for the Cloudflare address; blank keeps the saved one)", color = AV.TextMuted, fontSize = 11.sp)
                    OutlinedTextField(cfId, { cfId = it.trim() }, singleLine = true, label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret)
                    OutlinedTextField(cfSecret, { cfSecret = it.trim() }, singleLine = true, label = { Text("Client secret") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = secret)
                    error?.let { Text(it, color = AV.Warn, fontSize = 12.sp) }
                    if (s.serverBusy) Text("Signing in…", color = AV.Amber, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (editing) TextButton(enabled = !s.serverBusy && user.isNotBlank() && (home.isNotBlank() || remote.isNotBlank()), onClick = {
                error = null
                vm.connectServer(home, remote, user, pass, cfId, cfSecret) { err ->
                    pass = ""; cfSecret = ""
                    if (err == null) { editing = false; onClose() } else error = err
                }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
