package com.spook.musicplayer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object Palette {
    val canvas = Color(0xFF0F0F11)
    val surface = Color(0xFF17171B)
    val hover = Color(0xFF23232A)
    val track = Color(0xFF33333D)
    val text = Color(0xFFEEEEF2)
    val muted = Color(0xFF8C8C98)
    val accent = Color(0xFF7CAAFF)
    val accentDeep = Color(0xFF5888E8)
    val accentInk = Color(0xFF11141E)
    val stroke = Color(0x17FFFFFF)
}

@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.accentInk,
            background = Palette.canvas,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text
        ),
        content = content
    )
}

private data class Section(val title: String, val songs: List<PakEntry>)

private fun buildSections(entries: List<PakEntry>, query: String): List<Section> {
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) {
        entries
    } else {
        entries.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                it.group.contains(trimmed, ignoreCase = true)
        }
    }

    val grouped = filtered.groupBy { it.group }
    val keys = grouped.keys.sortedWith(
        compareBy<String> { it.equals("Added", ignoreCase = true) }
            .thenBy { it.lowercase() }
    )

    return keys.map { key ->
        Section(key, grouped[key].orEmpty().sortedBy { it.title.lowercase() })
    }
}

@Composable
fun MusicScreen(engine: PlayerEngine, state: PlayerUiState) {
    var query by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    val pakPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) engine.importPak(uri)
    }
    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) engine.addSongs(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.canvas)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(
            query = query,
            onQueryChange = { query = it },
            showMenu = showMenu,
            onMenuChange = { showMenu = it },
            onImportPak = {
                showMenu = false
                pakPicker.launch(arrayOf("*/*"))
            },
            onAddSongs = {
                showMenu = false
                songPicker.launch(arrayOf("audio/*"))
            }
        )

        val sections = remember(state.entries, query) { buildSections(state.entries, query) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
        ) {
            if (sections.isEmpty()) {
                Text(
                    text = if (state.hasLibrary) {
                        "Şarkı bulunamadı"
                    } else {
                        "Henüz müzik yok.\n+ → muzik.pak içe aktar"
                    },
                    color = Palette.muted,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    sections.forEach { section ->
                        item(key = "header_${section.title}") {
                            SectionHeader(section.title)
                        }
                        items(section.songs, key = { "song_${it.id}" }) { entry ->
                            SongRow(
                                entry = entry,
                                isCurrent = state.currentId == entry.id,
                                isPlaying = state.isPlaying,
                                onClick = { engine.play(entry) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        NowPlaying(
            state = state,
            onToggle = engine::togglePlayPause,
            onNext = { engine.step(1) },
            onPrev = { engine.step(-1) },
            onSeek = engine::seekTo,
            onVolume = engine::setVolume
        )
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = engine::clearError,
            confirmButton = {
                TextButton(onClick = engine::clearError) { Text("Tamam") }
            },
            title = { Text("Hata") },
            text = { Text(state.error) }
        )
    }
}

@Composable
private fun Header(
    query: String,
    onQueryChange: (String) -> Unit,
    showMenu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onImportPak: () -> Unit,
    onAddSongs: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.surface)
                .border(1.dp, Palette.stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Palette.muted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Ara...", color = Palette.muted, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Palette.muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Box {
            TextButton(
                onClick = { onMenuChange(true) },
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Palette.track)
            ) {
                Text(
                    "+  Ekle",
                    color = Palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { onMenuChange(false) }) {
                DropdownMenuItem(
                    text = { Text("muzik.pak içe aktar") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    onClick = onImportPak
                )
                DropdownMenuItem(
                    text = { Text("MP3 ekle") },
                    leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                    onClick = onAddSongs
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = Palette.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun SongRow(
    entry: PakEntry,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (isCurrent) Palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = if (isCurrent) Palette.accent else Palette.text,
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.group,
                color = Palette.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NowPlaying(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Float) -> Unit,
    onVolume: (Float) -> Unit
) {
    var seekValue by remember { mutableStateOf<Float?>(null) }
    val fraction = seekValue
        ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.currentId != null) state.currentTitle else "Şarkı seçilmedi",
                    color = Palette.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.currentId != null) {
                    Text(
                        text = state.currentGroup,
                        color = Palette.muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(state.libraryInfo, color = Palette.muted, fontSize = 11.sp)
        }

        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                seekValue?.let(onSeek)
                seekValue = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.track
            )
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatTime(
                    if (seekValue != null) (seekValue!! * state.durationMs).toLong() else state.positionMs
                ),
                color = Palette.muted,
                fontSize = 11.sp
            )
            Text(formatTime(state.durationMs), color = Palette.muted, fontSize = 11.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportButton(Icons.Filled.SkipPrevious, onPrev)
            Spacer(Modifier.width(26.dp))

            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Palette.accent)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Palette.accentInk,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(Modifier.width(26.dp))
            TransportButton(Icons.Filled.SkipNext, onNext)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.VolumeMute,
                contentDescription = null,
                tint = Palette.muted,
                modifier = Modifier.size(14.dp)
            )
            Slider(
                value = state.volume,
                onValueChange = onVolume,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Palette.accent,
                    activeTrackColor = Palette.accent,
                    inactiveTrackColor = Palette.track
                )
            )
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = Palette.muted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun TransportButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Palette.track)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Palette.text, modifier = Modifier.size(26.dp))
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
