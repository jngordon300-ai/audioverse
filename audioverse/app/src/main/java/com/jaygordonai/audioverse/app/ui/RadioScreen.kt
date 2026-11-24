package com.jaygordonai.audioverse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaygordonai.audioverse.app.library.PlaylistOps
import com.jaygordonai.audioverse.app.player.PlayerUi
import com.jaygordonai.audioverse.app.player.PlayerViewModel
import com.jaygordonai.audioverse.app.radio.Station
import com.jaygordonai.audioverse.app.radio.StationCatalog
import com.jaygordonai.audioverse.app.theme.AV

/** Transmission Across the Diaspora: live stations play in AudioVerse's engine (DSP, lock screen, Buds). */
@Composable
fun RadioScreen(s: PlayerUi, vm: PlayerViewModel) {
    val context = LocalContext.current
    var cat by rememberSaveable { mutableStateOf(StationCatalog.FEATURED) }
    var query by rememberSaveable { mutableStateOf("") }
    val data = remember(s.stations, s.featured) { StationCatalog.Data(s.stations, s.featured) }
    val view = remember(data, cat, query) { StationCatalog.filter(data, cat, query) }
    val live = s.stations.count { it.playable }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Radio", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AV.Text)
                Text("Transmission Across the Diaspora · $live live stations", color = AV.TextMuted, fontSize = 13.sp)
            }
            TextButton(onClick = { vm.shuffleRadio(view) }, enabled = view.any { it.playable }) { Text("Shuffle", color = AV.Amber) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(60) }, singleLine = true,
            placeholder = { Text("Search stations, cities, genres") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AV.Amber, unfocusedBorderColor = AV.Line,
                focusedTextColor = AV.Text, unfocusedTextColor = AV.Text, cursorColor = AV.Amber
            )
        )
        Spacer(Modifier.height(8.dp))
        ChipRow(StationCatalog.categories.map { it.key to it.label }, cat) { cat = it }
        Spacer(Modifier.height(6.dp))
        if (s.stations.isEmpty()) {
            Text("Loading stations…", color = AV.TextMuted)
        } else if (view.isEmpty()) {
            Text("No stations match.", color = AV.TextMuted, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(view, key = { i, st -> "$i:${st.url}" }) { _, st ->
                StationRow(
                    st,
                    playing = s.nowPlaying?.id == st.id,
                    favorite = PlaylistOps.contains(s.playlists, PlaylistOps.FAVORITES_ID, st.id),
                    onClick = { if (st.playable) vm.playStation(view, st) else openExternal(context, st.url) },
                    onFavorite = { vm.toggleFavorite(st.toTrack()) },
                    onAdd = { vm.askAddToPlaylist(st.toTrack()) }
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun StationRow(st: Station, playing: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) AV.SurfaceHigh else AV.Ground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(st.name, color = if (playing) AV.Amber else AV.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(st.city, st.tags).filter { it.isNotBlank() }.joinToString(" · "),
                color = AV.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (st.playable) {
            Text("LIVE", color = AV.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SmallText(if (favorite) "♥" else "♡", onFavorite)
            SmallText("+", onAdd)
        } else {
            Text(if (st.type == "spotify") "Spotify ↗" else "Open ↗", color = AV.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
        }
    }
}
