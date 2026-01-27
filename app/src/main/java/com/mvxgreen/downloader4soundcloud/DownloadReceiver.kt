package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
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
            Log.d("DownloadReceiver", "Download Complete. ID: $id")

            val realM3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            // PHASE 1: Waiting for Playlist
            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    chunksDownloaded = 0 // Reset

                    CoroutineScope(Dispatchers.Main).launch {
                        val urls = SoundLoader.extractMp3Urls(realM3uPath)
                        if (urls.isNotEmpty()) {
                            totalChunks = urls.size
                            SoundLoader.mMp3Urls = urls.toMutableList()
                            Log.d("DownloadReceiver", "Starting download of $totalChunks chunks")

                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            urls.forEachIndexed { index, url ->
                                val req = DownloadManager.Request(Uri.parse(url))
                                req.setMimeType("audio/mpeg")
                                req.setDestinationInExternalFilesDir(context, "temp", "s$index.mp3")
                                dm.enqueue(req)
                            }
                        }
                    }
                }
                // Ignore Thumbnail here too
            }
            // PHASE 2: Waiting for Chunks
            else {
                // FIX 3: Strict ignoring of Playlist AND Thumbnail IDs
                if (id != SoundLoader.playlistDownloadId && id != SoundLoader.thumbnailDownloadId) {
                    chunksDownloaded++
                    Log.d("DownloadReceiver", "Chunk processed. Progress: $chunksDownloaded/$totalChunks")

                    if (chunksDownloaded >= totalChunks) {
                        Log.d("DownloadReceiver", "All chunks done. Locking & Finishing.")

                        // FIX 4: Immediate Lock to prevent Double Entry (Race Condition)
                        totalChunks = 0
                        chunksDownloaded = 0

                        finishTrack(context)
                    }
                } else {
                    Log.d("DownloadReceiver", "Ignored non-chunk download (ID: $id)")
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Concat
            val privatePath = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)

            // 2. Tag
            SoundLoader.setTags(privatePath)

            // 3. Move
            val finalPath = SoundLoader.moveFileToDocuments(privatePath)

            withContext(Dispatchers.Main) {
                if (finalPath.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
                    Log.d("DownloadReceiver", "Track finished: $finalPath")
                }

                SoundLoader.deleteTempFiles()

                val i = Intent("DOWNLOAD_FINISHED")
                i.setPackage(appContext.packageName)
                appContext.sendBroadcast(i)
            }
        }
    }
}