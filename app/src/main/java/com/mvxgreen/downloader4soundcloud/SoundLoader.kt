package com.mvxgreen.downloader4soundcloud

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay // Import added
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
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.util.Collections

object SoundLoader {
    private const val TAG = "SoundLoader"

    lateinit var appContext: Context

    @Volatile var isCancelled = false

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

    var playlistDownloadId: Long = -1L
    var thumbnailDownloadId: Long = -1L
    var isShared = false
    var isPlaylist = false
    var isBatchActive = false
    var currentM3uFilename = ""
    var playlistM3uUrls = mutableListOf<String>()
    var playlistTags = mutableListOf<Map<String, String>>()
    var batchTotal = 0
    var batchProgress = 0
    var pendingPlaylistId = ""
    private const val FLAG_BEGIN_STREAM_ID = "media/soundcloud:tracks:"
    private const val FLAG_END_STREAM_ID = "/stream"
    private const val STREAM_URL_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:"
    private const val STREAM_URL_END = "/stream/hls"

    val absPathDocsTemp: String
        get() = appContext.getExternalFilesDir("temp")?.absolutePath + "/"

    // --- NEW ANALYTICS HELPER ---
    fun logErrorEvent(eventName: String, errorMessage: String, targetUrl: String = "") {
        try {
            val bundle = Bundle()
            if (mLoadHtmlUrl.isNotEmpty()) {
                bundle.putString("input_url", mLoadHtmlUrl)
            }
            if (targetUrl.isNotEmpty()) {
                bundle.putString("target_url", targetUrl)
            }
            bundle.putString("error_message", errorMessage)

            FirebaseAnalytics.getInstance(appContext).logEvent(eventName, bundle)
            Log.d(TAG, "Logged Analytics Event: $eventName | Error: $errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log analytics: ${e.message}")
        }
    }

    fun prepareFileDirs() {
        val tempDir = File(absPathDocsTemp)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
    }

    fun resetVars() {
        Log.d(TAG, "resetVars() called. Full Reset.")
        mClientId = ""
        isCancelled = false
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
        mThumbnailFilename = ""
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
            val cancelIntent = Intent(context, DownloadService::class.java).apply {
                action = "CANCEL_DOWNLOAD"
            }
            val cancelPendingIntent = android.app.PendingIntent.getService(
                context, 0, cancelIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SoundLoader")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPendingIntent)

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

    suspend fun downloadFile(urlStr: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        delay(34) // Added network delay
        try {
            val url = URL(urlStr)
            val file = File(destPath)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000
            conn.readTimeout = 60000
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
            // logErrorEvent("sl_file_download_exception", e.message ?: "Unknown Error", urlStr)
        }
        return@withContext false
    }

    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        delay(34) // Added network delay
        mLoadHtmlUrl = url

        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
                .timeout(5000)
                .get()
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

            if (html.contains(FLAG_BEGIN_STREAM_ID)) {
                val trim = html.substring(html.indexOf(FLAG_BEGIN_STREAM_ID)+1)
                if (trim.contains(FLAG_BEGIN_STREAM_ID)) {
                    val trim = trim.substring(trim.indexOf(FLAG_BEGIN_STREAM_ID) + 1)
                    if (trim.contains(FLAG_BEGIN_STREAM_ID) && trim.contains(FLAG_END_STREAM_ID)) {
                        val start = trim.indexOf(FLAG_BEGIN_STREAM_ID) + FLAG_BEGIN_STREAM_ID.length
                        val end = trim.indexOf(FLAG_END_STREAM_ID, start)
                        if (start > 0 && end > start) {
                            mStreamUrl =
                                STREAM_URL_BASE + trim.substring(start, end) + STREAM_URL_END
                            return@withContext true
                        }
                    }
                }
            }
            Log.e(TAG, "loadHtml failed: No stream URL found for $url")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "loadHtml Exception: ${e.message}")
            return@withContext false
        }
    }

    suspend fun processPlaylistWithKey(clientId: String, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "processPlaylistWithKey called. ClientID: $clientId")

        if (pendingPlaylistId.isEmpty()) return@withContext false
        mClientId = clientId

        val url = "https://api-v2.soundcloud.com/playlists/$pendingPlaylistId?client_id=$clientId"
        Log.d(TAG, "Fetching Playlist Data: $url")

        val jsonStr = loadNetworkResponse(url)
        if (jsonStr.isEmpty()) return@withContext false

        val synchronizedM3us = Collections.synchronizedList(mutableListOf<String>())
        val synchronizedTags = Collections.synchronizedList(mutableListOf<Map<String, String>>())

        try {
            val jsonObj = JSONObject(jsonStr)
            val tracks = jsonObj.optJSONArray("tracks")

            if (tracks != null && tracks.length() > 0) {
                val trackIds = mutableListOf<Long>()
                for (i in 0 until tracks.length()) {
                    val t = tracks.getJSONObject(i)
                    val id = t.optLong("id", -1L)
                    if (id != -1L) trackIds.add(id)
                }

                Log.d(TAG, "Found ${trackIds.size} tracks. Starting parallel fetch...")

                val batchSize = 5
                val chunks = trackIds.chunked(batchSize)

                for (chunk in chunks) {
                    supervisorScope {
                        val tasks = chunk.map { id ->
                            async {
                                fetchTrackMetadata(id)
                            }
                        }

                        val results = tasks.awaitAll()

                        results.forEach { result ->
                            if (result != null) {
                                synchronizedM3us.add(result.first)
                                synchronizedTags.add(result.second)
                            }
                        }
                    }

                    playlistM3uUrls.addAll(synchronizedM3us)
                    playlistTags.addAll(synchronizedTags)

                    synchronizedM3us.clear()
                    synchronizedTags.clear()

                    onProgress(playlistM3uUrls.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Playlist JSON", e)
        }
        batchTotal = playlistM3uUrls.size
        return@withContext batchTotal > 0
    }

    private suspend fun fetchTrackMetadata(trackId: Long): Pair<String, Map<String, String>>? {
        try {
            val apiUrl = "https://api-v2.soundcloud.com/tracks/$trackId?client_id=$mClientId"
            val jsonStr = loadNetworkResponse(apiUrl)
            if (jsonStr.isEmpty()) return null

            val json = JSONObject(jsonStr)

            val title = json.optString("title", "Unknown Track")
            val user = json.optJSONObject("user")
            val artist = user?.optString("username", "Unknown Artist") ?: "Unknown"
            var artwork = json.optString("artwork_url", "")
            if (artwork.isEmpty()) artwork = user?.optString("avatar_url", "") ?: ""
            artwork = artwork.replace("-large.", "-t500x500.")

            val media = json.optJSONObject("media")
            val transcodings = media?.optJSONArray("transcodings")
            var streamUrl = ""

            if (transcodings != null) {
                for (i in 0 until transcodings.length()) {
                    val t = transcodings.getJSONObject(i)
                    val format = t.optJSONObject("format")
                    if (format?.optString("protocol") == "hls" &&
                        format.optString("mime_type") == "audio/mpeg") {
                        streamUrl = t.optString("url")
                        break
                    }
                }
            }

            if (streamUrl.isNotEmpty()) {
                val m3u = resolveM3uUrl(streamUrl)
                if (m3u.isNotEmpty()) {
                    return Pair(m3u, mapOf("title" to title, "artist" to artist, "artwork_url" to artwork))
                }
            }
        } catch (e: Exception) {
            logErrorEvent("sl_fetch_track_meta_exception", e.message ?: "Unknown Error", "$trackId")
            Log.e(TAG, "Failed to fetch track $trackId: ${e.message}")
        }
        return null
    }

    private suspend fun resolveM3uUrl(streamBaseUrl: String): String {
        delay(34) // Added network delay
        try {
            val authUrl = if (streamBaseUrl.contains("client_id")) streamBaseUrl else "$streamBaseUrl?client_id=$mClientId"
            // Use URL().readText() inside IO context if not using loadNetworkResponse
            // For consistency with other network calls, we can use simple readText here but wrapped in try/catch
            val jsonStr = withContext(Dispatchers.IO) { URL(authUrl).readText() }
            return JSONObject(jsonStr).optString("url", "")
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
        delay(34) // Added network delay
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
                logErrorEvent("sl_network_response_fail", "HTTP Code: $code", urlStr)
                return@withContext ""
            }
        } catch (e: Exception) {
            logErrorEvent("sl_network_exception", e.message ?: "Unknown Error", urlStr)
            return@withContext ""
        }
    }

    suspend fun loadJson(urlStr: String) = withContext(Dispatchers.IO) {
        delay(34) // Added network delay
        try {
            val json = URL(urlStr).readText()
            val jsonObj = JSONObject(json)
            if (jsonObj.has("url")) mM3uUrl = jsonObj.getString("url")
        } catch (e: Exception) {
            logErrorEvent("sl_json_parse_exception", e.message ?: "Unknown Error", urlStr)
        }
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
                TagOptionSingleton.getInstance().isAndroid = true
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.TITLE, mTitle)
                tag.setField(FieldKey.ARTIST, mArtist)

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
            logErrorEvent("sl_tagging_exception", e.message ?: "Unknown Error", tempFilePath)
        }
    }

    suspend fun moveFileToMusic(privatePath: String): String = withContext(Dispatchers.IO) {
        val sourceFile = File(privatePath)
        if (!sourceFile.exists()) return@withContext ""

        var safeName = mTitle.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            .trim { it.isWhitespace() || it == '.' }

        if (safeName.length > 100) {
            safeName = safeName.substring(0, 100).trim()
        }
        if (safeName.isEmpty()) safeName = "track_${System.currentTimeMillis()}"

        val displayName = "$safeName.mp3"
        val resolver = appContext.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.TITLE, mTitle)
            put(MediaStore.Audio.Media.ARTIST, mArtist)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/SoundLoader")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val uri = resolver.insert(collection, contentValues) ?: return@withContext ""
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
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
            logErrorEvent("sl_export_exception", e.message ?: "Unknown Error", privatePath)
            return@withContext ""
        }
    }

    suspend fun deleteTempFiles() = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(absPathDocsTemp)
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs()
            Log.d("SoundLoader", "Temp files cleared.")
        } catch (e: Exception) {
            Log.e("SoundLoader", "Cleanup failed: ${e.message}")
        }
    }
}