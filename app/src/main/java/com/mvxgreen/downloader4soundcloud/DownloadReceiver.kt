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

class DownloadReceiver : BroadcastReceiver() {

    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {

            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val realM3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            // PHASE 1: M3U Download Handling
            if (totalChunks == 0) {
                // FIX: Check if THIS download is actually the playlist
                if (id == SoundLoader.playlistDownloadId) {
                    Log.d("DownloadReceiver", "Playlist Download Complete (ID: $id). Processing...")

                    CoroutineScope(Dispatchers.Main).launch {
                        val urls = SoundLoader.extractMp3Urls(realM3uPath)

                        if (urls.isNotEmpty()) {
                            totalChunks = urls.size
                            SoundLoader.mMp3Urls = urls.toMutableList()
                            Log.d("DownloadReceiver", "Found $totalChunks chunks. Starting download.")

                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            urls.forEachIndexed { index, url ->
                                val req = DownloadManager.Request(Uri.parse(url))
                                req.setMimeType("audio/mpeg")
                                req.setDestinationInExternalFilesDir(context, "temp", "s$index.mp3")
                                dm.enqueue(req)
                            }
                        } else {
                            Log.e("DownloadReceiver", "Extracted 0 URLs from M3U")
                        }
                    }
                } else {
                    Log.d("DownloadReceiver", "Ignoring completion of ID $id (Not the playlist).")
                }
            }
            // PHASE 2: Chunk Download Handling
            else {
                // For chunks, we can assume any completion is a chunk since M3U is already done.
                // In a stricter app, you'd track chunk IDs too, but this is usually sufficient.
                chunksDownloaded++

                if (chunksDownloaded >= totalChunks) {
                    Log.d("DownloadReceiver", "All chunks downloaded. Finishing track.")
                    finishTrack(context)
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            val finalPath = SoundLoader.concatMp3(totalChunks)
            SoundLoader.setTags(finalPath)
            MediaScannerConnection.scanFile(context, arrayOf(finalPath), null, null)
            SoundLoader.deleteTempFiles()

            val i = Intent("DOWNLOAD_FINISHED")
            context.sendBroadcast(i)
        }
    }
}