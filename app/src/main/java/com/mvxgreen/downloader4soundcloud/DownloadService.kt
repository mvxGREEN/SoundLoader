package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.bumptech.glide.Glide
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
        SoundLoader.deleteTempFiles()

        // 1. Download Playlist (M3U)
        // Note: In batch mode, mM3uUrl is populated by the Receiver from the pre-calculated list
        if (SoundLoader.mM3uUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mM3uUrl))
            request.setTitle("Downloading Track Info")
            request.setDestinationInExternalFilesDir(this, "temp", "playlist.m3u")
            SoundLoader.playlistDownloadId = downloadManager.enqueue(request)
        }

        // 2. Download Thumbnail
        if (SoundLoader.mThumbnailUrl.isNotEmpty()) {
            val request = DownloadManager.Request(Uri.parse(SoundLoader.mThumbnailUrl))
            request.setDestinationInExternalFilesDir(this, "temp", SoundLoader.mThumbnailFilename)
            SoundLoader.thumbnailDownloadId = downloadManager.enqueue(request)
        }
    }
}