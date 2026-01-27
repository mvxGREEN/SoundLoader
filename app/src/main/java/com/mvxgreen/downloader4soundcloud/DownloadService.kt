package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log

class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_DOWNLOAD") {
            startDownload()
        }
        return START_NOT_STICKY
    }

    private fun startDownload() {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        SoundLoader.deleteTempFiles()

        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setTitle("Downloading Track Info")
            request.setDestinationInExternalFilesDir(this, "temp", "playlist.m3u")

            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        }

        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            request.setDestinationInExternalFilesDir(this, "temp", SoundLoader.mThumbnailFilename)

            // FIX 2: Store the ID
            SoundLoader.thumbnailDownloadId = downloadManager.enqueue(request)
        }
    }
}