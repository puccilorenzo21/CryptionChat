package com.lollo1344.cryptionchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.IOException

class AudioPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.lollo1344.cryptionchat.ACTION_PLAY"
        const val ACTION_PAUSE = "com.lollo1344.cryptionchat.ACTION_PAUSE"
        const val ACTION_STOP = "com.lollo1344.cryptionchat.ACTION_STOP"
        const val ACTION_SEEK = "com.lollo1344.cryptionchat.ACTION_SEEK"

        const val ACTION_PROGRESS_UPDATE = "com.lollo1344.cryptionchat.ACTION_PROGRESS_UPDATE"
        const val ACTION_COMPLETE = "com.lollo1344.cryptionchat.ACTION_COMPLETE"
        const val ACTION_PAUSED_BROADCAST = "com.lollo1344.cryptionchat.ACTION_PAUSED_BROADCAST"

        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_CURRENT_POSITION = "current_position"
        const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "audio_player_channel"
        private const val NOTIFICATION_ID = 777

        // FIX CRITICO: Variabili globali per sincronizzare la UI al rientro dal background
        @Volatile var currentPlayingMessageId: Int = -1
        @Volatile var isPlayingAudio: Boolean = false
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var currentUsername: String = "CryptionChat"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressRunnable: Runnable
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "AudioPlayerService")

        updateNotification(false)

        progressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        broadcastProgress(mp.currentPosition, mp.duration)
                        handler.postDelayed(this, 100)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_USERNAME)?.let { currentUsername = it }

        updateNotification(mediaPlayer?.isPlaying == true)

        when (intent?.action) {
            ACTION_PLAY -> {
                val path = intent.getStringExtra(EXTRA_FILE_PATH)
                val msgId = intent.getIntExtra(EXTRA_MESSAGE_ID, -1)

                if (path != null && msgId != -1) {
                    playAudio(path, msgId, currentUsername)
                } else if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                    mediaPlayer?.start()
                    isPlayingAudio = true
                    updateNotification(true)
                    handler.post(progressRunnable)
                }
            }
            ACTION_PAUSE -> pauseAudio()
            ACTION_STOP -> stopAudio(reset = true, stopService = true)
            ACTION_SEEK -> {
                val pos = intent.getIntExtra(EXTRA_SEEK_POSITION, 0)
                mediaPlayer?.seekTo(pos)
                if (mediaPlayer?.isPlaying == false) {
                    broadcastProgress(pos, mediaPlayer?.duration ?: 0)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun playAudio(path: String, messageId: Int, username: String) {
        // Se stiamo già riproducendo o mettendo in pausa questo stesso messaggio
        if (currentPlayingMessageId == messageId) {
            if (mediaPlayer?.isPlaying == true) return
            if (mediaPlayer != null) {
                try {
                    mediaPlayer?.start()
                    isPlayingAudio = true
                    updateNotification(true)
                    handler.post(progressRunnable)
                } catch (e: Exception) {
                    Log.e("AudioPlayerService", "Error resuming audio", e)
                    stopAudio(reset = true, stopService = true)
                }
                return
            }
        }

        stopAudio(reset = false, stopService = false)

        currentPlayingMessageId = messageId
        currentFilePath = path
        currentUsername = username

        mediaPlayer = MediaPlayer().apply {
            try {
                // Imposta gli attributi audio (Fondamentale su Android 10+ per non farlo mutare dal sistema)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes)

                // Metodo più sicuro per leggere file in Android rispetto a setDataSource(String)
                val file = java.io.File(path)
                val fis = java.io.FileInputStream(file)
                setDataSource(fis.fd)
                fis.close()

                setOnCompletionListener {
                    stopAudio(reset = true, stopService = true)
                }

                // Impedisce al player di bloccarsi e "frizzare" l'UI in caso di errori del file
                setOnErrorListener { _, what, extra ->
                    Log.e("AudioPlayerService", "MediaPlayer error: what=$what extra=$extra")
                    stopAudio(reset = true, stopService = true)
                    true
                }

                prepare()
                start()

                isPlayingAudio = true
                updateNotification(true)
                handler.post(progressRunnable)
            } catch (e: Exception) {
                Log.e("AudioPlayerService", "Error playing file", e)
                stopAudio(reset = true, stopService = true)
            }
        }
    }

    private fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlayingAudio = false
                updateNotification(false)
                handler.removeCallbacks(progressRunnable)
                broadcastPaused()
            }
        }
    }

    private fun stopAudio(reset: Boolean = true, stopService: Boolean = true) {
        isPlayingAudio = false
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        mediaPlayer = null
        handler.removeCallbacks(progressRunnable)

        broadcastComplete()

        if (reset) {
            stopForeground(true)
            currentPlayingMessageId = -1
            currentFilePath = null
        }

        if (stopService) {
            stopSelf()
        }
    }

    private fun updateNotification(isPlaying: Boolean) {
        val playPauseIntent = if (isPlaying) {
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_PAUSE)
        } else {
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_PLAY)
        }
        val playPausePending = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, AudioPlayerService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val label = if (isPlaying) getString(R.string.audio_action_pause) else getString(R.string.audio_action_resume)

        val contentIntent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(currentUsername)
            .setContentText(if (isPlaying) getString(R.string.audio_status_playing) else getString(R.string.audio_status_paused))
            .setOngoing(isPlaying)
            .setContentIntent(pendingContentIntent)
            .addAction(icon, label, playPausePending)
            .addAction(R.drawable.ic_stop, getString(R.string.audio_action_stop), stopPending)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1)
                .setMediaSession(mediaSession?.sessionToken))
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerService", "Eccezione in startForeground: ${e.message}")
        }
    }

    private fun broadcastProgress(current: Int, total: Int) {
        val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
            putExtra(EXTRA_MESSAGE_ID, currentPlayingMessageId)
            putExtra(EXTRA_CURRENT_POSITION, current)
            putExtra(EXTRA_DURATION, total)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastPaused() {
        val intent = Intent(ACTION_PAUSED_BROADCAST).apply {
            putExtra(EXTRA_MESSAGE_ID, currentPlayingMessageId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastComplete() {
        val intent = Intent(ACTION_COMPLETE).apply {
            putExtra(EXTRA_MESSAGE_ID, currentPlayingMessageId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.audio_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.audio_channel_desc)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio(reset = true, stopService = false)
        mediaSession?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}