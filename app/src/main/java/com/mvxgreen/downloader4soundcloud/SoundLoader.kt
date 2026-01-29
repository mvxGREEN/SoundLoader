package com.mvxgreen.downloader4soundcloud

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object SoundLoader {
    private const val TAG = "SoundLoader"

    lateinit var appContext: Context

    // --- TRACK STATE ---
    var mLoadHtmlUrl = ""
    var mClientId = ""
    var mStreamUrl = ""
    var mM3uUrl = ""
    var mTitle = ""
    var mArtist = ""
    var mThumbnailUrl = ""
    var mThumbnailFilename = ""
    var mPlayerUrl = ""
    var mMp3Urls = mutableListOf<String>()

    // --- DOWNLOAD IDS ---
    var playlistDownloadId: Long = -1L
    var thumbnailDownloadId: Long = -1L

    // --- APP FLOW FLAGS ---
    var isShared = false
    var isPlaylist = false
    var isBatchActive = false

    // --- BATCH STATE ---
    var currentM3uFilename = ""
    var playlistM3uUrls = mutableListOf<String>()
    var playlistTags = mutableListOf<Map<String, String>>()
    var batchTotal = 0
    var batchProgress = 0
    var pendingPlaylistId = ""

    // --- CONSTANTS ---
    private const val FLAG_BEGIN_STREAM_ID = "media/soundcloud:tracks:"
    private const val FLAG_END_STREAM_ID = "/stream"
    private const val STREAM_URL_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:"
    private const val STREAM_URL_END = "/stream/hls"

    val absPathDocsTemp: String
        get() = appContext.getExternalFilesDir("temp")?.absolutePath + "/"

    // Updated to only manage internal temp storage
    fun prepareFileDirs() {
        val tempDir = File(absPathDocsTemp)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
    }

    fun resetVars() {
        Log.d(TAG, "resetVars() called. Full Reset.")
        mStreamUrl = ""
        mM3uUrl = ""
        mTitle = ""
        mArtist = ""
        mThumbnailUrl = ""
        mThumbnailFilename = ""
        mPlayerUrl = ""
        mMp3Urls.clear()
        playlistDownloadId = -1L
        thumbnailDownloadId = -1L
        isShared = false
        isPlaylist = false
        isBatchActive = false
        playlistM3uUrls.clear()
        playlistTags.clear()
        batchTotal = 0
        batchProgress = 0
        pendingPlaylistId = ""

        CoroutineScope(Dispatchers.IO).launch {
            deleteTempFiles()
        }
    }

    fun resetVarsForNext() {
        Log.d(TAG, "resetVarsForNext() called.")
        mM3uUrl = ""
        mMp3Urls.clear()
        mThumbnailFilename = "" // Reset this so we don't reuse old art
    }

    // --- NOTIFICATION CONSTANTS ---
    const val CHANNEL_ID = "sc_downloader_channel"
    const val NOTIFICATION_ID = 777

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val descriptionText = "Show download progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(context: Context, text: String, progressCurrent: Int, progressMax: Int, indeterminate: Boolean) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SoundLoader")
                //.setContentText(text)
                .setContentText("Downloading…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)

            if (indeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(progressMax, progressCurrent, false)
            }

            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_ID, builder.build())
            }
        } catch (e: SecurityException) { }
    }

    fun cancelNotification(context: Context) {
        try {
            with(NotificationManagerCompat.from(context)) {
                cancel(NOTIFICATION_ID)
            }
        } catch (e: SecurityException) {}
    }

    // In SoundLoader.kt, add this function:
    suspend fun downloadFile(urlStr: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val file = File(destPath)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()

            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SoundLoader", "Download failed: ${e.message}")
        }
        return@withContext false
    }

    // The Single-Track Scraper (This works, so we will reuse it in the loop!)
    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        mLoadHtmlUrl = url

        // Log.d(TAG, "loadHtml() called for URL: $url") // Commented out to reduce spam in loops
        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
            val html = doc.html()

            val titleText = doc.title().replace("Stream ", "").replace(" | Listen online for free on SoundCloud", "")
            val split = titleText.split(" by ")
            if (split.size > 1) { mTitle = split[0].trim(); mArtist = split[1].trim() }
            else { mTitle = titleText.trim(); mArtist = "Unknown" }

            val ogImage = doc.select("meta[property=og:image]").attr("content")
            mThumbnailUrl = ogImage.replace("-large.", "-t500x500.")
            mThumbnailFilename = if (mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"

            mPlayerUrl = doc.select("meta[property=twitter:player]").attr("content")
            if (mPlayerUrl.isEmpty()) mPlayerUrl = doc.select("meta[name=twitter:player]").attr("content")

            if (url.contains("/sets/") || url.contains("/albums/")) {
                Log.d(TAG, "Detected Playlist/Set.")
                isPlaylist = true
                pendingPlaylistId = extractPlaylistId(html)
                return@withContext true
            }

            val regex = Pattern.compile("\\{\"url\":\"(https?:\\\\?/\\\\?/api-v2\\.soundcloud\\.com\\\\?/media\\\\?/soundcloud:tracks:[^\"]+)\",[^}]*\"mime_type\":\"audio\\\\?/mpeg\"")
            val matcher = regex.matcher(html)
            if (matcher.find()) {
                mStreamUrl = matcher.group(1)?.replace("\\/", "/") ?: ""
                return@withContext mStreamUrl.isNotEmpty()
            }

            if (html.contains(FLAG_BEGIN_STREAM_ID) && html.contains(FLAG_END_STREAM_ID)) {
                val start = html.lastIndexOf(FLAG_BEGIN_STREAM_ID) + FLAG_BEGIN_STREAM_ID.length
                val end = html.indexOf(FLAG_END_STREAM_ID, start)
                if (start > 0 && end > start) {
                    mStreamUrl = STREAM_URL_BASE + html.substring(start, end) + STREAM_URL_END
                    return@withContext true
                }
            }
            Log.e(TAG, "loadHtml failed: No stream URL found for $url")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "loadHtml Exception: ${e.message}")
            return@withContext false
        }
    }

    // --- LOOP & SCRAPE ---
    suspend fun processPlaylistWithKey(clientId: String, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "processPlaylistWithKey called. ClientID: $clientId")

        if (pendingPlaylistId.isEmpty()) return@withContext false
        mClientId = clientId

        val url = "https://api-v2.soundcloud.com/playlists/$pendingPlaylistId?client_id=$clientId"
        Log.d(TAG, "Fetching Playlist Data: $url")

        val jsonStr = loadNetworkResponse(url)
        if (jsonStr.isEmpty()) return@withContext false

        try {
            val jsonObj = JSONObject(jsonStr)
            val tracks = jsonObj.optJSONArray("tracks")

            if (tracks != null && tracks.length() > 0) {
                Log.d(TAG, "Found ${tracks.length()} tracks. Starting processing...")

                for (i in 0 until tracks.length()) {
                    val trackObj = tracks.getJSONObject(i)
                    var permalink = trackObj.optString("permalink_url")
                    val id = trackObj.optLong("id", -1L)

                    // STEP 1: Handle "Stub" Tracks
                    if (permalink.isEmpty() && id != -1L) {
                        val metaUrl = "https://api-v2.soundcloud.com/tracks/$id?client_id=$mClientId"
                        val metaJson = loadNetworkResponse(metaUrl)
                        if (metaJson.isNotEmpty()) {
                            val fullTrackObj = JSONObject(metaJson)
                            permalink = fullTrackObj.optString("permalink_url")
                        }
                    }

                    // STEP 2: Scrape
                    if (permalink.isNotEmpty()) {
                        mStreamUrl = "" // Clear state

                        val success = loadHtml(permalink)

                        if (success && mStreamUrl.isNotEmpty()) {
                            val m3u = resolveM3uUrl(mStreamUrl)
                            if (m3u.isNotEmpty()) {
                                playlistM3uUrls.add(m3u)
                                playlistTags.add(mapOf(
                                    "title" to mTitle,
                                    "artist" to mArtist,
                                    "artwork_url" to mThumbnailUrl
                                ))

                                // --- UPDATE UI HERE ---
                                onProgress(playlistM3uUrls.size)
                                // ---------------------
                            }
                        }
                        kotlinx.coroutines.delay(150)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Playlist JSON", e)
        }

        batchTotal = playlistM3uUrls.size
        return@withContext batchTotal > 0
    }

    private fun resolveM3uUrl(streamBaseUrl: String): String {
        try {
            val authUrl = if (streamBaseUrl.contains("client_id")) streamBaseUrl else "$streamBaseUrl?client_id=$mClientId"
            val jsonStr = URL(authUrl).readText()
            val m3u = JSONObject(jsonStr).optString("url", "")
            return m3u
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving M3U url", e)
            return ""
        }
    }

    private fun extractPlaylistId(html: String): String {
        val matcher = Pattern.compile("soundcloud://playlists:(\\d+)").matcher(html)
        val id = if (matcher.find()) matcher.group(1) ?: "" else ""
        if (id.isEmpty()) Log.w(TAG, "Could not extract playlist ID from HTML")
        return id
    }

    private suspend fun loadNetworkResponse(urlStr: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
            conn.setRequestProperty("Referer", "https://soundcloud.com/")
            conn.setRequestProperty("Origin", "https://soundcloud.com")
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            conn.connect()

            val code = conn.responseCode
            if (code == 200) {
                return@withContext conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                return@withContext ""
            }
        } catch (e: Exception) {
            return@withContext ""
        }
    }

    // ... (loadJson, extractMp3Urls, concatMp3, setTags, moveFileToDocuments, deleteTempFiles remain unchanged) ...
    suspend fun loadJson(urlStr: String) = withContext(Dispatchers.IO) {
        try {
            val json = URL(urlStr).readText()
            val jsonObj = JSONObject(json)
            if (jsonObj.has("url")) mM3uUrl = jsonObj.getString("url")
        } catch (e: Exception) { }
    }

    suspend fun extractMp3Urls(m3uPath: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val file = File(m3uPath)
        if (file.exists()) file.forEachLine { if (!it.startsWith("#")) urls.add(it) }
        return@withContext urls
    }

    suspend fun concatMp3(count: Int): String = withContext(Dispatchers.IO) {
        val destPath = "${absPathDocsTemp}temp_build.mp3"
        val outFile = File(destPath)
        outFile.delete()
        val outStream = FileOutputStream(outFile)
        for (i in 0 until count) {
            val f = File(absPathDocsTemp, "s$i.mp3")
            if (f.exists()) outStream.write(f.readBytes())
        }
        outStream.close()
        return@withContext destPath
    }

    suspend fun setTags(tempFilePath: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(tempFilePath)
            if (file.exists()) {
                // Tell JAudiotagger we are on Android
                TagOptionSingleton.getInstance().isAndroid = true

                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tagOrCreateAndSetDefault

                tag.setField(FieldKey.TITLE, mTitle)
                tag.setField(FieldKey.ARTIST, mArtist)

                // Handle Artwork
                val thumbPath = absPathDocsTemp + mThumbnailFilename
                val thumbFile = File(thumbPath)
                if (thumbFile.exists()) {
                    tag.setField(ArtworkFactory.createArtworkFromFile(thumbFile))
                }

                audioFile.commit()
                Log.d(TAG, "Tags committed successfully to $tempFilePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting tags: ${e.message}")
        }
    }

    suspend fun moveFileToMusic(privatePath: String): String = withContext(Dispatchers.IO) {
        val sourceFile = File(privatePath)
        if (!sourceFile.exists()) return@withContext ""

        // 1. Sanitize Name
        var safeName = mTitle.replace("[^a-zA-Z0-9 .\\-_]".toRegex(), "_")
            .trim { it.isWhitespace() || it == '.' }

        // 2. SAFETY CAP: Truncate to 100 chars to prevent "File name too long" crash
        if (safeName.length > 100) {
            safeName = safeName.substring(0, 100).trim()
        }
        if (safeName.isEmpty()) safeName = "track_${System.currentTimeMillis()}"

        val displayName = "$safeName.mp3"
        val resolver = appContext.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.TITLE, mTitle) // Metadata keeps FULL title
            put(MediaStore.Audio.Media.ARTIST, mArtist)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/SoundLoader")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        // ... (Keep the rest of your existing try/catch logic exactly the same) ...
        // Just paste the rest of your original function here
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val uri = resolver.insert(collection, contentValues) ?: return@withContext ""

            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            } else {
                MediaScannerConnection.scanFile(appContext, arrayOf(sourceFile.absolutePath), null, null)
            }
            return@withContext uri.toString()
        } catch (e: Exception) {
            Log.e("SoundLoader", "Export failed: ${e.message}")
            return@withContext ""
        }
    }

    // Safer cleanup that ensures the internal folder exists but is empty
    suspend fun deleteTempFiles() = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(absPathDocsTemp)
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs() // Recreate for the next download
            Log.d("SoundLoader", "Temp files cleared.")
        } catch (e: Exception) {
            Log.e("SoundLoader", "Cleanup failed: ${e.message}")
        }
    }
}