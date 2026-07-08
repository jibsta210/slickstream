package com.slickstream.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.slickstream.core.model.Download
import com.slickstream.core.model.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground service that keeps the process alive while OFFLINE downloads run, so "download a season
 * then lock the phone" actually finishes instead of the OS killing the app. Holds no download state —
 * [DownloadManager] (a @Singleton) owns the queue; this service just observes it, shows a live progress
 * notification, and SELF-STOPS the moment nothing is QUEUED/DOWNLOADING.
 *
 * Declared in the manifest with `foregroundServiceType="dataSync"`.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var downloadManager: DownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground within ~5s of startForegroundService — do it synchronously with a
        // placeholder, then the collector below keeps it current.
        startInForeground(buildNotification(active = emptyList()))
        observeQueue()
        // Not sticky: we self-stop when the queue drains, and a restart with no work would just spin up
        // an empty foreground service. DownloadManager re-launches us when new work is queued.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeQueue() {
        downloadManager.downloads
            .onEach { all ->
                val active = all.filter {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
                }
                if (active.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    notify(buildNotification(active))
                }
            }
            .launchIn(scope)
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(active: List<Download>): Notification {
        val downloading = active.filter { it.status == DownloadStatus.DOWNLOADING }
        val title: String
        val text: String
        var indeterminate = true
        var percent = 0
        when {
            active.isEmpty() -> {
                title = "SlickStream"; text = "Preparing…"
            }
            active.size == 1 -> {
                val d = active.first()
                title = "Downloading ${d.title}"
                text = d.subtitle.ifBlank { d.quality.ifBlank { "Starting…" } }
                // A finite bar only once we know the size; torrents report totalBytes early.
                if (d.totalBytes > 0) { indeterminate = false; percent = (d.progress * 100).toInt() }
            }
            else -> {
                title = "Downloading ${active.size} items"
                val done = active.count { it.isComplete }
                text = if (downloading.isNotEmpty())
                    "${downloading.first().title} · ${downloading.size} in progress" else "$done of ${active.size} done"
                // Aggregate progress across items whose size is known.
                val known = active.filter { it.totalBytes > 0 }
                val totalAll = known.sumOf { it.totalBytes }
                if (totalAll > 0) {
                    indeterminate = false
                    percent = (known.sumOf { it.downloadedBytes }.toDouble() / totalAll * 100).toInt()
                }
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .apply { launch?.let { setContentIntent(it) } }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Offline download progress"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "slickstream_downloads"
        private const val NOTIFICATION_ID = 4202
    }
}
