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

        // 1. Download M3U
        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            Log.d("DownloadService", "Enqueuing Playlist Download: ${SoundLoader.mM3uUrl}")
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setTitle("Downloading Track Info")
            request.setDestinationInExternalFilesDir(this, "temp", "playlist.m3u")

            // FIX: Store the specific ID so Receiver knows which file is the M3U
            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        } else {
            Log.e("DownloadService", "M3U URL is empty!")
        }

        // 2. Download Thumbnail
        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            request.setDestinationInExternalFilesDir(this, "temp", SoundLoader.mThumbnailFilename)
            downloadManager.enqueue(request)
        }
    }
}