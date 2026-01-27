package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadReceiver : BroadcastReceiver() {

    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

            // NOTE: In a real app, you must check if THIS downloadId belongs to us.
            // Simplified logic for migration context:

            val m3uFile = context.getExternalFilesDir(null)?.resolve("playlist.m3u")
            // In the Service we saved to Documents/temp/playlist.m3u
            val realM3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            if (totalChunks == 0) {
                // Assume M3U just finished
                CoroutineScope(Dispatchers.Main).launch {
                    val urls = SoundLoader.extractMp3Urls(realM3uPath)
                    totalChunks = urls.size
                    SoundLoader.mMp3Urls = urls.toMutableList()

                    // Start downloading chunks
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    urls.forEachIndexed { index, url ->
                        val req = DownloadManager.Request(Uri.parse(url))
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "temp/s$index.mp3")
                        dm.enqueue(req)
                    }
                }
            } else {
                chunksDownloaded++
                // Update UI (Progress) via Intent or EventBus

                if (chunksDownloaded >= totalChunks) {
                    // All chunks done
                    finishTrack(context)
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            val finalPath = SoundLoader.concatMp3(totalChunks)
            SoundLoader.setTags(finalPath)

            // Scan File
            MediaScannerConnection.scanFile(context, arrayOf(finalPath), null, null)

            // Clean up
            SoundLoader.deleteTempFiles()

            // Notify UI
            val i = Intent("DOWNLOAD_FINISHED")
            context.sendBroadcast(i)
        }
    }
}