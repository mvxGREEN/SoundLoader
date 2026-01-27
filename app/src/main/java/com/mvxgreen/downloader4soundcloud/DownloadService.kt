package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // 1. Download M3U
        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
                .setTitle("Downloading Track Info")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "temp/playlist.m3u")

            downloadManager.enqueue(request)
        }

        // 2. Download Thumbnail
        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "temp/" + SoundLoader.mThumbnailFilename)
            downloadManager.enqueue(request)
        }
    }
}