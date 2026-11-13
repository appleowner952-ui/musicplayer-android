package com.spook.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private lateinit var engine: PlayerEngine

    override fun onCreate() {
        super.onCreate()
        engine = (application as MusicApp).engine
        createChannel()

        observeJob = scope.launch {
            engine.state.collectLatest { state ->
                if (state.currentId == null) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    startForegroundInternal(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlayPause()
            ACTION_NEXT -> engine.step(1)
            ACTION_PREV -> engine.step(-1)
            ACTION_STOP -> {
                engine.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val state = engine.state.value
        if (state.currentId != null) {
            startForegroundInternal(state)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Çalma",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundInternal(state: PlayerUiState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(state: PlayerUiState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(code: Int, action: String): PendingIntent = PendingIntent.getService(
            this,
            code,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(state.currentTitle.ifEmpty { "Music" })
            .setContentText(state.currentGroup)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .addAction(0, "Önceki", action(1, ACTION_PREV))
            .addAction(0, if (state.isPlaying) "Duraklat" else "Çal", action(2, ACTION_TOGGLE))
            .addAction(0, "Sonraki", action(3, ACTION_NEXT))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_TOGGLE = "com.spook.musicplayer.TOGGLE"
        const val ACTION_NEXT = "com.spook.musicplayer.NEXT"
        const val ACTION_PREV = "com.spook.musicplayer.PREV"
        const val ACTION_STOP = "com.spook.musicplayer.STOP"
    }
}
