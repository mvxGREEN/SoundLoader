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
    private val TAG = "DownloadReceiver"
    private var hasFailures = false
    private var chunksDownloaded = 0
    private var totalChunks = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.d(TAG, "Download Complete ID: $id. TotalChunks: $totalChunks")

            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    Log.d(TAG, "M3U Download Complete. Parsing...")
                    chunksDownloaded = 0

                    // CORRECTION: Use the unique filename from SoundLoader
                    val m3uPath = SoundLoader.absPathDocsTemp + SoundLoader.currentM3uFilename
                    Log.d(TAG, "Reading M3U from: $m3uPath")

                    CoroutineScope(Dispatchers.IO).launch {
                        val urls = SoundLoader.extractMp3Urls(m3uPath)
                        if (urls.isNotEmpty()) {
                            totalChunks = urls.size
                            SoundLoader.mMp3Urls = urls.toMutableList()
                            Log.d(TAG, "M3U Parsed. Found $totalChunks chunks. Enqueueing...")

                            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                            // Enqueue all chunks (IO is fine for this loop)
                            urls.forEachIndexed { i, url ->
                                val req = DownloadManager.Request(Uri.parse(url))
                                req.setDestinationInExternalFilesDir(context, "temp", "s$i.mp3")
                                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                                dm.enqueue(req)
                            }
                        } else {
                            Log.e(TAG, "M3U was empty or failed to parse.")
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
                        Log.e(TAG, "Chunk $id FAILED. Marking track as failure.")
                    }

                    chunksDownloaded++
                    // Log.d(TAG, "Chunk $chunksDownloaded / $totalChunks downloaded") // Optional verbose

                    if (chunksDownloaded >= totalChunks) {
                        Log.d(TAG, "All chunks finished. Proceeding to stitch.")
                        finishTrack(context)
                    }
                }
            }
        }
    }

    private fun isDownloadSuccessful(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)

        // .use block automatically calls close() when the block exits (even on return)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0) {
                    return cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
                }
            }
        }
        return false
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext
        Log.d(TAG, "finishTrack() called")

        CoroutineScope(Dispatchers.IO).launch {

            // 1. Validate & Stitch (Prevents corrupted files)
            if (!hasFailures && totalChunks > 0) {
                Log.d(TAG, "No failures detected. Stitching MP3...")
                val path = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)
                SoundLoader.setTags(path)
                val finalPath = SoundLoader.moveFileToDocuments(path)

                if (finalPath.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
                    Log.d(TAG, "Track saved and scanned: $finalPath")
                }
            } else {
                Log.e(TAG, "Track skipped: Failures=$hasFailures, TotalChunks=$totalChunks")
            }

            // 2. Cleanup
            SoundLoader.deleteTempFiles()
            totalChunks = 0
            chunksDownloaded = 0
            hasFailures = false

            // 3. Process Next Track (With Delay)
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                val remaining = SoundLoader.playlistM3uUrls.size
                Log.d(TAG, "Batch active. $remaining tracks remaining.")

                Log.d(TAG, "Waiting 1-3s for rate-limit cooldown...")
                kotlinx.coroutines.delay(kotlin.random.Random.nextLong(1000, 3000))

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
                Log.d(TAG, "Starting service for next track: ${SoundLoader.mTitle}")
                val nextIntent = Intent(appContext, DownloadService::class.java)
                nextIntent.action = "START_DOWNLOAD"
                appContext.startService(nextIntent)

            } else {
                Log.d(TAG, "Batch Finished. Broadcasting DONE.")
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