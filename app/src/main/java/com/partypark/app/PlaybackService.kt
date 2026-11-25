package com.partypark.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** Holds playback after Home, with previous, pause, and next for the whole room. */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopInForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_PREV || action == ACTION_NEXT || action == ACTION_TOGGLE) {
            if (action == ACTION_TOGGLE) playing = !playing
            onAction?.invoke(action)
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) this.title = title
        if (intent?.hasExtra(EXTRA_PLAYING) == true) playing = intent.getBooleanExtra(EXTRA_PLAYING, true)
        startForeground(NOTIFICATION_ID, notification())
        return START_NOT_STICKY
    }

    private fun stopInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Watch Together playback",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RoomActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playLabel = if (playing) "Pause" else "Play"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "Partypark" })
            .setContentText(if (playing) "Playing" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pending(ACTION_PREV))
            .addAction(playIcon, playLabel, pending(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "Next", pending(ACTION_NEXT))
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun pending(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "partypark_playback"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"
        const val ACTION_STOP = "com.partypark.app.STOP_PLAYBACK"
        const val ACTION_PREV = "com.partypark.app.PREV"
        const val ACTION_NEXT = "com.partypark.app.NEXT"
        const val ACTION_TOGGLE = "com.partypark.app.TOGGLE"

        var onAction: ((String) -> Unit)? = null
        private var title: String = "Partypark"
        private var playing: Boolean = true

        fun start(context: Context, title: String, playing: Boolean) {
            this.title = title
            this.playing = playing
            val intent = Intent(context, PlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PLAYING, playing)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
