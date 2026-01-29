package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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
            // Log.d(TAG, "Download Complete ID: $id. TotalChunks: $totalChunks")

            if (totalChunks == 0) {
                if (id == SoundLoader.playlistDownloadId) {
                    Log.d(TAG, "M3U Download Complete. Parsing...")
                    chunksDownloaded = 0

                    // [FIX 1] Acquire WakeLock for M3U Parsing
                    // The CPU might sleep between download finish and parsing. We must hold it awake.
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLoader::M3ULock")
                    wakeLock.acquire(30 * 1000L) // 30 second timeout

                    val m3uPath = SoundLoader.absPathDocsTemp + SoundLoader.currentM3uFilename

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val urls = SoundLoader.extractMp3Urls(m3uPath)
                            if (urls.isNotEmpty()) {
                                totalChunks = urls.size
                                SoundLoader.mMp3Urls = urls.toMutableList()
                                Log.d(TAG, "M3U Parsed. Found $totalChunks chunks. Enqueueing...")

                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                                urls.forEachIndexed { i, url ->
                                    val req = DownloadManager.Request(Uri.parse(url))
                                    req.setDestinationInExternalFilesDir(context, "temp", "s$i.mp3")
                                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                                    dm.enqueue(req)
                                }
                            } else {
                                Log.e(TAG, "M3U was empty or failed to parse.")
                                finishTrack(context)
                            }
                        } finally {
                            // Release lock only after chunks are enqueued
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                    }
                }
            }
            else {
                if (id != SoundLoader.playlistDownloadId && id != SoundLoader.thumbnailDownloadId) {
                    if (!isDownloadSuccessful(context, id)) {
                        hasFailures = true
                    }

                    chunksDownloaded++

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

        try {
            dm.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex >= 0) {
                        return cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
                    }
                }
            }
        } catch (e: Exception) {
            // Handle edge case where download might be cancelled/removed
        }
        return false
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLoader::StitchLock")
        // Acquire lock for 10 minutes (covers stitching + delay + next track setup)
        wakeLock.acquire(10 * 60 * 1000L)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // --- STITCHING PHASE ---
                if (!hasFailures && totalChunks > 0) {
                    try {
                        val tempPath = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)
                        SoundLoader.setTags(tempPath)
                        val finalUriString = SoundLoader.moveFileToMusic(tempPath)
                        if (finalUriString.isNotEmpty()) {
                            Log.d(TAG, "Track successfully saved to MediaStore: $finalUriString")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Stitching failed: ${e.message}")
                    }
                }

                // --- CLEANUP PHASE ---
                SoundLoader.deleteTempFiles()
                totalChunks = 0
                chunksDownloaded = 0
                hasFailures = false

                // --- BATCH TRANSITION PHASE ---
                if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                    val remaining = SoundLoader.playlistM3uUrls.size
                    Log.d(TAG, "Batch active. $remaining tracks remaining.")

                    // [FIX 2] The WakeLock is STILL HELD here, so the CPU won't sleep during delay
                    kotlinx.coroutines.delay(kotlin.random.Random.nextLong(1000, 3000))

                    SoundLoader.resetVarsForNext()
                    SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)

                    if (SoundLoader.playlistTags.isNotEmpty()) {
                        val tag = SoundLoader.playlistTags.removeAt(0)
                        SoundLoader.mTitle = tag["title"] ?: "Track"
                        SoundLoader.mArtist = tag["artist"] ?: "Unknown"
                        SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                        SoundLoader.mThumbnailFilename = if (SoundLoader.mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                    }

                    Log.d(TAG, "Starting service for next track: ${SoundLoader.mTitle}")
                    val nextIntent = Intent(appContext, DownloadService::class.java)
                    nextIntent.action = "START_DOWNLOAD"
                    appContext.startService(nextIntent)

                } else {
                    Log.d(TAG, "Batch Finished. Broadcasting DONE.")
                    val stopIntent = Intent(appContext, DownloadService::class.java)
                    appContext.stopService(stopIntent)

                    withContext(Dispatchers.Main) {
                        val i = Intent("DOWNLOAD_FINISHED")
                        i.setPackage(appContext.packageName)
                        appContext.sendBroadcast(i)
                    }
                }
            } finally {
                // [FIX 2] Release lock ONLY after everything (including batch setup) is done
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }
}