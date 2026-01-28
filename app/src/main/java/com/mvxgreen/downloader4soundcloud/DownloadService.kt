package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
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

            // Ensure Channel Exists
            SoundLoader.createNotificationChannel(this)

            // Calculate Progress
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

            // 1. Update Notification
            SoundLoader.updateNotification(this, progressText, current, total, isIndeterminate)

            // 2. Broadcast to MainActivity
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
        return START_NOT_STICKY
    }

    private suspend fun startDownload() {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // 1. Generate Unique Name (Crucial for the "Duplicate Audio" fix)
        val uniqueName = "playlist_${System.currentTimeMillis()}.m3u"
        SoundLoader.currentM3uFilename = uniqueName
        Log.d(TAG, "Generated Unique M3U Name: $uniqueName")

        // 2. Clear temp files
        SoundLoader.deleteTempFiles()

        // 3. Enqueue...
        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            Log.d(TAG, "Enqueueing M3U Download: ${SoundLoader.mM3uUrl}")
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            request.setDestinationInExternalFilesDir(this, "temp", uniqueName) // Use Unique Name
            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        } else {
            Log.e(TAG, "mM3uUrl is empty! Cannot start download.")
        }

        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val thumbReq = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            thumbReq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            thumbReq.setDestinationInExternalFilesDir(this, "temp", SoundLoader.mThumbnailFilename)
            SoundLoader.thumbnailDownloadId = downloadManager.enqueue(thumbReq)
        }
    }
}