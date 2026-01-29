package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val TAG = "DownloadService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_DOWNLOAD") {
            Log.d(TAG, "onStartCommand: START_DOWNLOAD received")

            // 1. Create Notification Channel
            SoundLoader.createNotificationChannel(this)

            // 2. Prepare Notification Data
            var progressText = "Downloading..."
            var isIndeterminate = true
            var current = 0
            var total = 0

            if (SoundLoader.isBatchActive && SoundLoader.batchTotal > 0) {
                current = SoundLoader.batchTotal - SoundLoader.playlistM3uUrls.size
                total = SoundLoader.batchTotal
                progressText = "Downloading $current of $total"
                isIndeterminate = false
            }

            // 3. START FOREGROUND (Fixes Background Pausing)
            // We create the notification immediately so the service is "promoted"
            val notification = androidx.core.app.NotificationCompat.Builder(this, SoundLoader.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SoundLoader")
                .setContentText(progressText)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            // This tells Android: "Do not kill this app even if the screen is off"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SoundLoader.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(SoundLoader.NOTIFICATION_ID, notification)
            }

            // 4. Update UI
            SoundLoader.updateNotification(this, progressText, current, total, isIndeterminate)
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
        return START_STICKY // Restart if killed
    }

    private suspend fun startDownload() {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // 1. Generate Unique Name for M3U
        val uniqueName = "playlist_${System.currentTimeMillis()}.m3u"
        SoundLoader.currentM3uFilename = uniqueName

        // --- CRITICAL FIX: REMOVED deleteTempFiles() ---
        // We do NOT delete files here. The Receiver handles cleanup after success.
        // Deleting here kills batch downloads.
        SoundLoader.prepareFileDirs()

        // 2. Enqueue M3U
        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            request.setDestinationInExternalFilesDir(this, "temp", uniqueName)
            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        }

        // 3. Enqueue Thumbnail with UNIQUE Name (Fixes wrong art / file lock)
        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            // Create a unique name for this specific track's art
            val thumbExt = if (SoundLoader.mThumbnailUrl.contains(".png")) "png" else "jpg"
            val uniqueThumb = "thumb_${System.currentTimeMillis()}.$thumbExt"
            SoundLoader.mThumbnailFilename = uniqueThumb

            val thumbReq = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            thumbReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            thumbReq.setDestinationInExternalFilesDir(this, "temp", uniqueThumb)
            SoundLoader.thumbnailDownloadId = downloadManager.enqueue(thumbReq)
        }
    }
}