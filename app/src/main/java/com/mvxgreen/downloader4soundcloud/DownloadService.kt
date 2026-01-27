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
        Log.d("DownloadService", "onStartCommand Action: ${intent?.action}")
        if (intent?.action == "START_DOWNLOAD") {
            startDownload()
        }
        return START_NOT_STICKY
    }

    private fun startDownload() {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        SoundLoader.deleteTempFiles()

        Log.d("DownloadService", "Starting Download. M3U URL: ${SoundLoader.mM3uUrl}")

        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setTitle("Downloading Track Info")
            request.setDestinationInExternalFilesDir(this, "temp", "playlist.m3u")

            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
            Log.d("DownloadService", "Enqueued M3U. ID: ${SoundLoader.playlistDownloadId}")
        } else {
            Log.e("DownloadService", "ERROR: M3U URL is empty. Download cannot start.")
        }

        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            request.setDestinationInExternalFilesDir(this, "temp", SoundLoader.mThumbnailFilename)
            downloadManager.enqueue(request)
        }
    }
}