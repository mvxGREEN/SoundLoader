package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadReceiver : BroadcastReceiver() {

    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val m3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            // PHASE 1: Parse M3U
            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    chunksDownloaded = 0

                    // FIX: Launch on IO directly, skipping Main thread for parsing
                    CoroutineScope(Dispatchers.IO).launch {
                        val urls = SoundLoader.extractMp3Urls(m3uPath)
                        if (urls.isNotEmpty()) {
                            totalChunks = urls.size
                            SoundLoader.mMp3Urls = urls.toMutableList()

                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                            // Enqueue all chunks (IO is fine for this loop)
                            urls.forEachIndexed { i, url ->
                                val req = DownloadManager.Request(Uri.parse(url))
                                req.setDestinationInExternalFilesDir(context, "temp", "s$i.mp3")
                                dm.enqueue(req)
                            }
                        } else {
                            finishTrack(context) // Skip empty/failed M3Us
                        }
                    }
                }
            }
            // PHASE 2: Check Chunks
            else {
                if (id != SoundLoader.playlistDownloadId && id != SoundLoader.thumbnailDownloadId) {
                    chunksDownloaded++
                    if (chunksDownloaded >= totalChunks) {
                        totalChunks = 0
                        chunksDownloaded = 0
                        finishTrack(context)
                    }
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            // Build File (Heavy CPU/IO)
            val path = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)
            SoundLoader.setTags(path)
            val finalPath = SoundLoader.moveFileToDocuments(path)

            if (finalPath.isNotEmpty()) {
                MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
            }

            // Clean up old files (Heavy IO)
            SoundLoader.deleteTempFiles()

            // --- C# STYLE BATCH LOOP ---
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                // 1. Pop Next
                SoundLoader.resetVarsForNext()
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)

                // 2. Set Metadata
                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: "Track"
                    SoundLoader.mArtist = tag["artist"] ?: "Unknown"
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                    SoundLoader.mThumbnailFilename = if (SoundLoader.mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                }

                // 3. Start Download Service
                val nextIntent = Intent(appContext, DownloadService::class.java)
                nextIntent.action = "START_DOWNLOAD"
                // Starting service is fast, can be done from IO context
                appContext.startService(nextIntent)

            } else {
                // Done - Notify UI on Main Thread
                withContext(Dispatchers.Main) {
                    val i = Intent("DOWNLOAD_FINISHED")
                    i.setPackage(appContext.packageName)
                    appContext.sendBroadcast(i)
                }
            }
        }
    }
}