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

    // These should ideally be static or part of a singleton state if the Receiver is recreated,
    // but for standard broadcast flows, this instance often persists during the burst.
    // Ideally, rely on SoundLoader state if you encounter persistence issues.
    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.d("DownloadReceiver", "Received Download Complete. ID: $id. Expected M3U ID: ${SoundLoader.playlistDownloadId}")

            val realM3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            // PHASE 1: Waiting for Playlist (M3U)
            // If we haven't parsed chunks yet (totalChunks == 0), we ONLY care about the M3U download.
            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    Log.d("DownloadReceiver", "M3U Download Finished! Extracting chunks...")

                    // Reset chunk counter for safety
                    chunksDownloaded = 0

                    CoroutineScope(Dispatchers.Main).launch {
                        val urls = SoundLoader.extractMp3Urls(realM3uPath)

                        if (urls.isNotEmpty()) {
                            totalChunks = urls.size
                            SoundLoader.mMp3Urls = urls.toMutableList()
                            Log.d("DownloadReceiver", "Queueing $totalChunks chunk downloads")

                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            urls.forEachIndexed { index, url ->
                                val req = DownloadManager.Request(Uri.parse(url))
                                req.setMimeType("audio/mpeg")
                                req.setDestinationInExternalFilesDir(context, "temp", "s$index.mp3")
                                dm.enqueue(req)
                            }
                        } else {
                            Log.e("DownloadReceiver", "No URLs found in M3U file. Download aborted.")
                        }
                    }
                } else {
                    // CRITICAL FIX: Ignore thumbnail or other downloads while waiting for M3U
                    // This prevents the "0 >= 0" race condition.
                    Log.d("DownloadReceiver", "Ignoring ID $id (Not the M3U we are waiting for).")
                }
            }
            // PHASE 2: Waiting for Chunks
            else {
                // We are strictly waiting for chunks now.
                // We should ignore the M3U ID (already processed) and Thumbnail ID (irrelevant to progress).
                // A robust check would track all chunk IDs, but usually counting hits is sufficient if we ignore the playlist ID.

                if (id != SoundLoader.playlistDownloadId) {
                    chunksDownloaded++
                    Log.d("DownloadReceiver", "Chunk finished. Progress: $chunksDownloaded / $totalChunks")

                    if (chunksDownloaded >= totalChunks) {
                        Log.d("DownloadReceiver", "All chunks done. Finishing track.")
                        finishTrack(context)
                    }
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            Log.d("DownloadReceiver", "Finishing track (Concat -> Tag -> Move)")

            // 1. Concat
            val privatePath = SoundLoader.concatMp3(totalChunks)

            // 2. Tag
            SoundLoader.setTags(privatePath)

            // 3. Move
            val finalPath = SoundLoader.moveFileToDocuments(privatePath)

            withContext(Dispatchers.Main) {
                if (finalPath.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
                    Log.d("DownloadReceiver", "Track finished successfully: $finalPath")
                } else {
                    Log.e("DownloadReceiver", "Track finish failed (Empty or missing file).")
                }

                // Cleanup
                SoundLoader.deleteTempFiles()

                // Notify UI
                val i = Intent("DOWNLOAD_FINISHED")
                i.setPackage(appContext.packageName)
                appContext.sendBroadcast(i)

                // Reset local state for safety
                totalChunks = 0
                chunksDownloaded = 0
            }
        }
    }
}