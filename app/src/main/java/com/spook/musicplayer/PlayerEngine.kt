package com.spook.musicplayer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayerUiState(
    val entries: List<PakEntry> = emptyList(),
    val hasLibrary: Boolean = false,
    val libraryInfo: String = "muzik.pak bulunamadı",
    val currentId: Int? = null,
    val currentTitle: String = "",
    val currentGroup: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 0.8f,
    val error: String? = null
)

class PlayerEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pakFile = File(context.filesDir, PakLibrary.FILE_NAME)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var library: PakLibrary? = null
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private var currentEntry: PakEntry? = null
    private var volume = 0.8f

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseInternal()
            }
        }
        .build()

    init {
        reload()
    }

    // MARK: - Library

    fun reload() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!pakFile.exists()) {
                    null
                } else {
                    runCatching { PakLibrary(pakFile).apply { load() } }
                }
            }

            when {
                result == null -> {
                    library = null
                    _state.value = _state.value.copy(
                        entries = emptyList(),
                        hasLibrary = false,
                        libraryInfo = "muzik.pak bulunamadı"
                    )
                }

                result.isSuccess -> {
                    val loaded = result.getOrThrow()
                    library = loaded
                    _state.value = _state.value.copy(
                        entries = loaded.entries.toList(),
                        hasLibrary = true,
                        libraryInfo = "${loaded.entries.size} şarkı"
                    )
                }

                else -> {
                    library = null
                    _state.value = _state.value.copy(
                        entries = emptyList(),
                        hasLibrary = false,
                        libraryInfo = "muzik.pak bulunamadı",
                        error = "muzik.pak okunamadı: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    private fun ensureLibrary(): PakLibrary? {
        library?.let { return it }
        return try {
            if (!pakFile.exists()) {
                pakFile.parentFile?.mkdirs()
                pakFile.outputStream().use { output ->
                    output.write("MUSPAK1_".toByteArray(Charsets.US_ASCII))
                    output.write(byteArrayOf(0, 0, 0))
                }
            }
            PakLibrary(pakFile).apply { load() }.also { library = it }
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = "Kütüphane oluşturulamadı: ${t.message}")
            null
        }
    }

    // MARK: - Playback

    fun play(entry: PakEntry) {
        val lib = library ?: return

        scope.launch {
            try {
                val cacheFile = songCacheFile(entry)
                withContext(Dispatchers.IO) {
                    if (!cacheFile.exists() || cacheFile.length() != entry.length) {
                        val temp = File(cacheFile.parentFile, cacheFile.name + ".part")
                        temp.outputStream().use { output -> lib.copyTo(entry, output) }
                        if (cacheFile.exists()) cacheFile.delete()
                        if (!temp.renameTo(cacheFile)) {
                            temp.copyTo(cacheFile, overwrite = true)
                            temp.delete()
                        }
                    }
                }
                startPlayer(entry, cacheFile)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = "Çalınamadı: ${t.message}")
            }
        }
    }

    private fun startPlayer(entry: PakEntry, file: File) {
        releasePlayerOnly()
        requestFocus()

        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { step(1) }
        mp.setOnErrorListener { _, _, _ ->
            _state.value = _state.value.copy(isPlaying = false, error = "Oynatma hatası.")
            true
        }
        mp.prepare()
        mp.setVolume(volume, volume)
        mp.start()

        player = mp
        currentEntry = entry
        _state.value = _state.value.copy(
            currentId = entry.id,
            currentTitle = entry.title,
            currentGroup = entry.group,
            isPlaying = true,
            positionMs = 0L,
            durationMs = mp.duration.toLong(),
            error = null
        )

        startProgress()
        startService()
    }

    fun togglePlayPause() {
        val mp = player
        if (mp == null) {
            orderedEntries().firstOrNull()?.let { play(it) }
            return
        }
        if (mp.isPlaying) pauseInternal() else resumeInternal()
    }

    private fun pauseInternal() {
        player?.let { if (it.isPlaying) it.pause() }
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun resumeInternal() {
        val mp = player ?: return
        requestFocus()
        mp.start()
        _state.value = _state.value.copy(isPlaying = true)
        startProgress()
        startService()
    }

    fun step(direction: Int) {
        val list = orderedEntries()
        if (list.isEmpty()) return

        val current = currentEntry
        val index = if (current != null) list.indexOfFirst { it.id == current.id } else -1
        val next = when {
            index >= 0 -> ((index + direction) % list.size + list.size) % list.size
            direction > 0 -> 0
            else -> list.size - 1
        }
        play(list[next])
    }

    fun seekTo(fraction: Float) {
        val mp = player ?: return
        val value = fraction.coerceIn(0f, 1f)
        val target = (mp.duration * value).toInt()
        mp.seekTo(target)
        _state.value = _state.value.copy(
            positionMs = target.toLong(),
            durationMs = mp.duration.toLong()
        )
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        volume = v
        player?.setVolume(v, v)
        _state.value = _state.value.copy(volume = v)
    }

    fun stop() {
        releasePlayerOnly()
        abandonFocus()
        currentEntry = null
        _state.value = _state.value.copy(
            currentId = null,
            currentTitle = "",
            currentGroup = "",
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // MARK: - Import

    fun importPak(uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File(pakFile.parentFile, "${PakLibrary.FILE_NAME}.tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                    } ?: throw PakException("Dosya açılamadı.")

                    if (pakFile.exists()) pakFile.delete()
                    if (!temp.renameTo(pakFile)) {
                        temp.copyTo(pakFile, overwrite = true)
                        temp.delete()
                    }
                }

                releasePlayerOnly()
                currentEntry = null
                clearSongCache()
                _state.value = _state.value.copy(
                    currentId = null,
                    currentTitle = "",
                    currentGroup = "",
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L
                )
                reload()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = "İçe aktarılamadı: ${t.message}")
            }
        }
    }

    fun addSongs(uris: List<Uri>) {
        scope.launch {
            val lib = ensureLibrary() ?: return@launch
            var added = 0
            var lastError: String? = null

            for (uri in uris) {
                try {
                    val name = displayName(uri) ?: "song.mp3"
                    withContext(Dispatchers.IO) { lib.add(context, uri, name) }
                    added++
                } catch (t: Throwable) {
                    lastError = "Eklenemedi: ${t.message}"
                }
            }

            if (added > 0) reload()
            if (lastError != null) _state.value = _state.value.copy(error = lastError)
        }
    }

    // MARK: - Helpers

    private fun orderedEntries(): List<PakEntry> =
        _state.value.entries.sortedWith(
            compareBy<PakEntry> { it.group.equals("Added", ignoreCase = true) }
                .thenBy { it.group.lowercase() }
                .thenBy { it.title.lowercase() }
        )

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun songCacheFile(entry: PakEntry): File {
        val key = entry.name.hashCode().toUInt().toString(16)
        return File(context.cacheDir, "song_${entry.id}_${entry.length}_$key.mp3")
    }

    private fun clearSongCache() {
        context.cacheDir.listFiles { file -> file.name.startsWith("song_") }?.forEach { it.delete() }
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val mp = player ?: break
                val playing = try { mp.isPlaying } catch (_: Exception) { false }
                if (playing) {
                    val position = try { mp.currentPosition.toLong() } catch (_: Exception) { 0L }
                    val duration = try { mp.duration.toLong() } catch (_: Exception) { 0L }
                    _state.value = _state.value.copy(positionMs = position, durationMs = duration)
                }
                delay(250)
            }
        }
    }

    private fun releasePlayerOnly() {
        progressJob?.cancel()
        progressJob = null
        val mp = player
        player = null
        if (mp != null) {
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) { }
            try { mp.release() } catch (_: Exception) { }
        }
    }

    private fun requestFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }
}
