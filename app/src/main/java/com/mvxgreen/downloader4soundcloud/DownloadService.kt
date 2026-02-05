package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay // Import added

class DownloadService : Service() {

    private val TAG = "DownloadService"

    private var wakeLock: PowerManager.WakeLock? = null
    private val downloadReceiver = DownloadReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLoader::ServiceBatchLock")
        wakeLock?.setReferenceCounted(false)

        Log.d(TAG, "Registering DownloadReceiver in Service")
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) { }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "Batch Finished. WakeLock Released.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_DOWNLOAD") {
            Log.d(TAG, "onStartCommand: START_DOWNLOAD received")

            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = "CANCEL_DOWNLOAD"
            }
            val cancelPendingIntent = android.app.PendingIntent.getService(
                this, 0, cancelIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
                Log.d(TAG, "WakeLock Acquired for Batch")
            }

            SoundLoader.createNotificationChannel(this)

            var progressText = ""
            var isIndeterminate = true
            var current = 0
            var total = 0

            if (SoundLoader.isBatchActive && SoundLoader.batchTotal > 0) {
                current = SoundLoader.batchTotal - SoundLoader.playlistM3uUrls.size
                total = SoundLoader.batchTotal
                progressText = "Downloading $current of $total"
                isIndeterminate = false
            }

            val builder = androidx.core.app.NotificationCompat.Builder(this, SoundLoader.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SoundLoader")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

            if (!progressText.isEmpty())
                builder.setContentText(progressText)

            if (isIndeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(total, current, false)
            }

            val notification = builder.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SoundLoader.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(SoundLoader.NOTIFICATION_ID, notification)
            }

            val progressIntent = Intent("ACTION_PROGRESS_UPDATE")
            progressIntent.putExtra("text", progressText)
            progressIntent.putExtra("indeterminate", isIndeterminate)
            progressIntent.putExtra("current", current)
            progressIntent.putExtra("total", total)
            progressIntent.setPackage(packageName)
            sendBroadcast(progressIntent)

            CoroutineScope(Dispatchers.IO).launch {
                startDownload()
            }
        }
        else if (intent?.action == "CANCEL_DOWNLOAD") {
            Log.d(TAG, "User requested cancellation")

            SoundLoader.isCancelled = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            CoroutineScope(Dispatchers.IO).launch {
                SoundLoader.deleteTempFiles()
            }

            val i = Intent("DOWNLOAD_FINISHED")
            i.setPackage(packageName)
            sendBroadcast(i)
        }

        return START_STICKY
    }

    private suspend fun startDownload() {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        SoundLoader.prepareFileDirs()

        // M3U Download
        val uniqueName = "playlist_${System.currentTimeMillis()}.m3u"
        SoundLoader.currentM3uFilename = uniqueName

        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            request.setDestinationInExternalFilesDir(this, "temp", uniqueName)

            delay(34) // Added delay before enqueueing download
            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        }

        // Thumbnail Download
        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val thumbExt = if (SoundLoader.mThumbnailUrl.contains(".png")) "png" else "jpg"
            val uniqueThumb = "thumb_${System.currentTimeMillis()}.$thumbExt"
            SoundLoader.mThumbnailFilename = uniqueThumb

            val thumbReq = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            thumbReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            thumbReq.setDestinationInExternalFilesDir(this, "temp", uniqueThumb)

            delay(34) // Added delay before enqueueing download
            SoundLoader.thumbnailDownloadId = downloadManager.enqueue(thumbReq)
        }
    }
}