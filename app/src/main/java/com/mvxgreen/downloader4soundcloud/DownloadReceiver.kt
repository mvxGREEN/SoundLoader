package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mvxgreen.downloader4soundcloud.SoundLoader.isCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class DownloadReceiver : BroadcastReceiver() {
    private val TAG = "DownloadReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

            // Only react to the M3U (Playlist) download finishing
            if (id == SoundLoader.playlistDownloadId) {
                Log.d(TAG, "M3U Download Complete. Starting processing...")

                val m3uPath = SoundLoader.absPathDocsTemp + SoundLoader.currentM3uFilename

                // Launch a coroutine to do the work
                CoroutineScope(Dispatchers.IO).launch {
                    val urls = SoundLoader.extractMp3Urls(m3uPath)

                    if (urls.isNotEmpty()) {
                        SoundLoader.mMp3Urls = urls.toMutableList()
                        Log.d(TAG, "M3U Parsed. Downloading ${urls.size} chunks directly...")

                        var failures = false

                        // --- DIRECT DOWNLOAD LOOP ---
                        urls.forEachIndexed { i, url ->
                            val currentChunk = i + 1
                            val totalChunks = urls.size
                            val percent = (currentChunk.toDouble() / totalChunks.toDouble()) * 100.0
                            val percentStr = percent.toInt().toString()
                            val progressText = "$percentStr%"

                            // 1. Update Notification
                            SoundLoader.updateNotification(context, progressText, currentChunk, totalChunks, false)

                            // 2. Update Progress Bar (UI)
                            val progressIntent = Intent("ACTION_PROGRESS_UPDATE")
                            progressIntent.putExtra("text", progressText)
                            progressIntent.putExtra("indeterminate", false)
                            progressIntent.putExtra("current", currentChunk)
                            progressIntent.putExtra("total", totalChunks)
                            progressIntent.setPackage(context.packageName)
                            context.sendBroadcast(progressIntent)

                            // 3. Perform Download
                            val dest = "${SoundLoader.absPathDocsTemp}s$i.mp3"
                            val success = SoundLoader.downloadFile(url, dest)
                            if (!success) failures = true
                        }

                        if (!failures && !isCancelled) {
                            // Optional: Update UI to show processing state before stitching
                            val processingIntent = Intent("ACTION_PROGRESS_UPDATE")
                            processingIntent.putExtra("text", "Processing…")
                            processingIntent.putExtra("indeterminate", true)
                            processingIntent.setPackage(context.packageName)
                            context.sendBroadcast(processingIntent)

                            finishTrack(context)
                        } else {
                            // --- FIX START ---
                            Log.e(TAG, "Critical Failure. Stopping Service.")
                            SoundLoader.cancelNotification(context)

                            // Stop the service so the WakeLock is released
                            val stopIntent = Intent(context, DownloadService::class.java)
                            context.stopService(stopIntent)

                            // Update UI to show failure
                            val failIntent = Intent("DOWNLOAD_FINISHED") // Or a new ACTION_DOWNLOAD_FAILED
                            failIntent.setPackage(context.packageName)
                            context.sendBroadcast(failIntent)
                            // --- FIX END ---

                            // TODO log failure or cancel event
                            if (failures) {
                                Log.e(TAG, "Download failed. Stopping loop.")
                            }

                        }
                    }
                }
            }
        }
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Stitch
                val tempPath = SoundLoader.concatMp3(SoundLoader.mMp3Urls.size)

                // 2. Tag
                SoundLoader.setTags(tempPath)

                // 3. Save
                SoundLoader.moveFileToMusic(tempPath)

            } catch (e: Exception) {
                Log.e(TAG, "Finish failed: ${e.message}")
            }

            // 4. Cleanup
            SoundLoader.deleteTempFiles()

            // 5. Next Track Logic
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                kotlinx.coroutines.delay(333)

                if (SoundLoader.isCancelled) {
                    Log.d(TAG, "Download Cancelled by User. Stopping loop.")
                    return@launch // Stop this coroutine immediately
                }

                SoundLoader.resetVarsForNext()
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)

                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: "Track"
                    SoundLoader.mArtist = tag["artist"] ?: "Unknown"
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                    SoundLoader.mThumbnailFilename = if (SoundLoader.mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                }

                val nextIntent = Intent(appContext, DownloadService::class.java)
                nextIntent.action = "START_DOWNLOAD"
                appContext.startService(nextIntent)

            } else {
                Log.d(TAG, "Batch Finished.")
                SoundLoader.cancelNotification(appContext)
                val stopIntent = Intent(appContext, DownloadService::class.java)
                appContext.stopService(stopIntent)

                val i = Intent("DOWNLOAD_FINISHED")
                i.setPackage(appContext.packageName)
                appContext.sendBroadcast(i)
            }
        }
    }
}