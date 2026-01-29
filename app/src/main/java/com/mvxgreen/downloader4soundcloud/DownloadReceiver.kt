package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadReceiver : BroadcastReceiver() {
    private val TAG = "DownloadReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

            // Only react to the M3U (Playlist) download finishing
            if (id == SoundLoader.playlistDownloadId) {
                Log.d(TAG, "M3U Download Complete. Starting processing...")

                val m3uPath = SoundLoader.absPathDocsTemp + SoundLoader.currentM3uFilename

                // Launch a coroutine to do the work (Protected by your Foreground Service)
                CoroutineScope(Dispatchers.IO).launch {
                    val urls = SoundLoader.extractMp3Urls(m3uPath)

                    if (urls.isNotEmpty()) {
                        SoundLoader.mMp3Urls = urls.toMutableList()
                        Log.d(TAG, "M3U Parsed. Downloading ${urls.size} chunks directly...")

                        var failures = false

                        // --- DIRECT DOWNLOAD LOOP ---
                        // This replaces the complex "Enqueue -> Wait" logic
                        urls.forEachIndexed { i, url ->
                            // Update Notification (Optional, but nice for users)
                            SoundLoader.updateNotification(context, "Downloading part ${i+1}/${urls.size}", i+1, urls.size, false)

                            val dest = "${SoundLoader.absPathDocsTemp}s$i.mp3"
                            val success = SoundLoader.downloadFile(url, dest)
                            if (!success) failures = true
                        }

                        if (!failures) {
                            finishTrack(context)
                        } else {
                            Log.e(TAG, "Failed to download some chunks.")
                            // Handle failure or skip track
                        }
                    }
                }
            }
            // We ignore all other IDs because we aren't using DM for chunks anymore!
        }
    }

    private fun finishTrack(context: Context) {
        val appContext = context.applicationContext

        // This runs on the same IO scope, so it keeps flowing
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

            // 5. Next Track Logic (Keep your existing batch logic)
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                kotlinx.coroutines.delay(1000) // Small delay for safety

                SoundLoader.resetVarsForNext()
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)

                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: "Track"
                    SoundLoader.mArtist = tag["artist"] ?: "Unknown"
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                    SoundLoader.mThumbnailFilename = if (SoundLoader.mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"
                }

                // Restart Service for the next M3U
                val nextIntent = Intent(appContext, DownloadService::class.java)
                nextIntent.action = "START_DOWNLOAD"
                appContext.startService(nextIntent)

            } else {
                Log.d(TAG, "Batch Finished.")
                SoundLoader.cancelNotification(appContext)
                val stopIntent = Intent(appContext, DownloadService::class.java)
                appContext.stopService(stopIntent)

                // Broadcast to UI
                val i = Intent("DOWNLOAD_FINISHED")
                i.setPackage(appContext.packageName)
                appContext.sendBroadcast(i)
            }
        }
    }
}