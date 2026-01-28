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
    private var hasFailures = false
    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val m3uPath = SoundLoader.absPathDocsTemp + "playlist.m3u"

            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    chunksDownloaded = 0

                    // CORRECTION: Use the unique filename from SoundLoader
                    val m3uPath = SoundLoader.absPathDocsTemp + SoundLoader.currentM3uFilename

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
                                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                                dm.enqueue(req)
                            }
                        } else {
                            finishTrack(context) // Skip empty/failed M3Us
                        }
                    }
                }
            }
            else {
                if (id != SoundLoader.playlistDownloadId && id != SoundLoader.thumbnailDownloadId) {

                    // CORRECTION: Check for success!
                    if (!isDownloadSuccessful(context, id)) {
                        hasFailures = true
                        Log.e("DownloadReceiver", "Chunk $id failed")
                    }

                    chunksDownloaded++
                    if (chunksDownloaded >= totalChunks) {
                        finishTrack(context) // finishTrack checks !hasFailures, so we are safe
                    }
                }
            }
        }
    }

    private fun isDownloadSuccessful(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query)
        if (cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex >= 0) {
                return cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
            }
        }
        cursor.close()
        return false
    }

    // In DownloadReceiver.kt

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {

            // 1. Validate & Stitch (Prevents corrupted files)
            if (!hasFailures && totalChunks > 0) {
                val path = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)
                SoundLoader.setTags(path)
                val finalPath = SoundLoader.moveFileToDocuments(path)

                if (finalPath.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
                }
            } else {
                Log.e("DownloadReceiver", "Track skipped: Download failures detected.")
            }

            // 2. Cleanup
            SoundLoader.deleteTempFiles()
            totalChunks = 0
            chunksDownloaded = 0
            hasFailures = false

            // 3. Process Next Track (With Delay)
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {

                // --- NEW: Add "Human" Delay ---
                // Waits 3 to 6 seconds before requesting the next song.
                // This allows the server's rate-limit bucket to cool down.
                Log.d("DownloadReceiver", "Waiting for rate-limit cooldown...")
                kotlinx.coroutines.delay(kotlin.random.Random.nextLong(3000, 6000))

                // 4. Setup Next Track
                SoundLoader.resetVarsForNext()
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)

                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: "Track"
                    SoundLoader.mArtist = tag["artist"] ?: "Unknown"
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                    SoundLoader.mThumbnailFilename = if (SoundLoader.mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                }

                // 5. Start Service
                val nextIntent = Intent(appContext, DownloadService::class.java)
                nextIntent.action = "START_DOWNLOAD"
                appContext.startService(nextIntent)

            } else {
                // Batch Finished
                SoundLoader.cancelNotification(appContext)
                withContext(Dispatchers.Main) {
                    val i = Intent("DOWNLOAD_FINISHED")
                    i.setPackage(appContext.packageName)
                    appContext.sendBroadcast(i)
                }
            }
        }
    }
}