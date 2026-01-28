package com.mvxgreen.downloader4soundcloud

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object SoundLoader {
    private const val TAG = "SoundLoader"

    lateinit var appContext: Context

    // --- TRACK STATE ---
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
    private const val FALLBACK_CLIENT_ID = "a3e059563d7fd3372b49b37f00a00bcf"

    val absPathDocs: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/"

    val absPathDocsTemp: String
        get() = appContext.getExternalFilesDir("temp")?.absolutePath + "/"

    fun prepareFileDirs() {
        File(absPathDocs).mkdirs()
        File(absPathDocsTemp).mkdirs()
    }

    fun resetVars() {
        Log.d(TAG, "Full Reset")
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
        deleteTempFiles()
    }

    fun resetVarsForNext() {
        mM3uUrl = ""
        mMp3Urls.clear()
    }

    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Scraping URL: $url")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .get()
            val html = doc.html()

            // 1. Basic Metadata
            val titleText = doc.title().replace("Stream ", "").replace(" | Listen online for free on SoundCloud", "")
            val split = titleText.split(" by ")
            if (split.size > 1) {
                mTitle = split[0].replace("/", "-").trim()
                mArtist = split[1].trim()
            } else {
                mTitle = titleText.replace("/", "-").trim()
                mArtist = "Unknown"
            }

            // 2. Thumbnail
            val ogImage = doc.select("meta[property=og:image]").attr("content")
            mThumbnailUrl = ogImage.replace("-large.", "-t500x500.")
            mThumbnailFilename = if (mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"

            // 3. Player URL
            mPlayerUrl = doc.select("meta[property=twitter:player]").attr("content")
            if (mPlayerUrl.isEmpty()) {
                mPlayerUrl = doc.select("meta[name=twitter:player]").attr("content")
            }

            // --- PLAYLIST LOGIC (DEFERRED) ---
            if (url.contains("/sets/") || url.contains("/albums/")) {
                isPlaylist = true
                pendingPlaylistId = extractPlaylistId(html)
                Log.d(TAG, "Playlist Detected. ID: $pendingPlaylistId. Waiting for Client ID from WebView...")
                return@withContext true
            }

            // --- SINGLE TRACK LOGIC (IMMEDIATE) ---
            val regex = Pattern.compile("\\{\"url\":\"(https?:\\\\?/\\\\?/api-v2\\.soundcloud\\.com\\\\?/media\\\\?/soundcloud:tracks:[^\"]+)\",[^}]*\"mime_type\":\"audio\\\\?/mpeg\"")
            val matcher = regex.matcher(html)
            if (matcher.find()) {
                var foundUrl = matcher.group(1)
                foundUrl = foundUrl?.replace("\\/", "/")
                if (!foundUrl.isNullOrEmpty()) {
                    mStreamUrl = foundUrl
                    return@withContext true
                }
            }

            if (html.contains(FLAG_BEGIN_STREAM_ID) && html.contains(FLAG_END_STREAM_ID)) {
                val startIdx = html.lastIndexOf(FLAG_BEGIN_STREAM_ID) + FLAG_BEGIN_STREAM_ID.length
                val endIdx = html.indexOf(FLAG_END_STREAM_ID, startIdx)
                if (startIdx > FLAG_BEGIN_STREAM_ID.length && endIdx > startIdx) {
                    val id = html.substring(startIdx, endIdx)
                    mStreamUrl = "$STREAM_URL_BASE$id$STREAM_URL_END"
                    return@withContext true
                }
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading HTML", e)
            return@withContext false
        }
    }

    // --- BATCH PROCESSOR ---
    suspend fun processPlaylistWithKey(clientId: String): Boolean = withContext(Dispatchers.IO) {
        if (pendingPlaylistId.isEmpty()) return@withContext false
        mClientId = clientId
        Log.d(TAG, "Starting API Fetch with Key: $mClientId")

        // 1. Get IDs
        val trackIds = fetchPlaylistTrackIds(pendingPlaylistId, mClientId)
        if (trackIds.isEmpty()) {
            Log.e(TAG, "Failed to fetch Track IDs")
            return@withContext false
        }

        Log.d(TAG, "Found ${trackIds.size} tracks. Fetching details...")

        // 2. Batch Fetch
        val idChunks = trackIds.chunked(50)
        for (chunk in idChunks) {
            val idsParam = chunk.joinToString(",")
            val batchUrl = "https://api-v2.soundcloud.com/tracks?ids=$idsParam&client_id=$mClientId"
            Log.d(TAG, "Batch URL: $batchUrl")
            val jsonStr = loadNetworkResponse(batchUrl)
            Log.d(TAG, "Batch Response: $jsonStr")
            if (jsonStr.isNotEmpty()) parseBatchResponse(jsonStr)
        }

        batchTotal = playlistM3uUrls.size
        Log.d(TAG, "Batch Prepared: $batchTotal tracks")
        return@withContext batchTotal > 0
    }

    private suspend fun fetchPlaylistTrackIds(playlistId: String, clientId: String): List<String> {
        val ids = mutableListOf<String>()
        val url = "https://api-v2.soundcloud.com/playlists/$playlistId?client_id=$clientId"
        val jsonStr = loadNetworkResponse(url)
        if (jsonStr.isEmpty()) return ids

        try {
            val jsonObj = JSONObject(jsonStr)
            val tracks = jsonObj.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    if (track.has("id")) ids.add(track.getString("id"))
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Playlist ID parse error", e) }
        return ids
    }

    // FIX: Relaxed Parsing Logic to accept ANY valid stream
    private fun parseBatchResponse(jsonStr: String) {
        try {
            // Note: Batch 'tracks' endpoint returns a JSON ARRAY, not an object with 'collection'
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val trackObj = jsonArray.getJSONObject(i)

                val artwork = trackObj.optString("artwork_url").replace("-large.", "-t500x500.")
                val title = trackObj.optString("title")
                val artist = trackObj.optJSONObject("user")?.optString("username") ?: "Unknown"

                // Select Best Stream URL
                var streamUrl = ""
                val media = trackObj.optJSONObject("media")
                val transcodings = media?.optJSONArray("transcodings")

                if (transcodings != null && transcodings.length() > 0) {
                    // Priority 1: HLS MP3 (Standard)
                    for (j in 0 until transcodings.length()) {
                        val trans = transcodings.getJSONObject(j)
                        val format = trans.optJSONObject("format")
                        val protocol = trans.optString("protocol")
                        val mime = format?.optString("mime_type")

                        if (mime == "audio/mpeg" && protocol == "hls") {
                            streamUrl = trans.optString("url")
                            break
                        }
                    }

                    // Priority 2: Progressive MP3
                    if (streamUrl.isEmpty()) {
                        for (j in 0 until transcodings.length()) {
                            val trans = transcodings.getJSONObject(j)
                            val format = trans.optJSONObject("format")
                            if (format?.optString("mime_type") == "audio/mpeg") {
                                streamUrl = trans.optString("url")
                                break
                            }
                        }
                    }

                    // Priority 3: Fallback to ANY available stream (Matches C# behavior)
                    if (streamUrl.isEmpty()) {
                        val trans = transcodings.getJSONObject(0)
                        streamUrl = trans.optString("url")
                        Log.w(TAG, "Fallback stream used for $title")
                    }
                }

                if (streamUrl.isNotEmpty()) {
                    val m3u = resolveM3uUrl(streamUrl)
                    if (m3u.isNotEmpty()) {
                        Log.d(TAG, "Resolved M3U for $title: $m3u")

                        playlistM3uUrls.add(m3u)
                        playlistTags.add(mapOf("title" to title, "artist" to artist, "artwork_url" to artwork))
                    } else {
                        Log.w(TAG, "Failed to resolve M3U for $title")
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Batch Parse Error", e) }
    }

    private fun resolveM3uUrl(streamBaseUrl: String): String {
        try {
            val authUrl = "$streamBaseUrl?client_id=$mClientId"
            val jsonStr = URL(authUrl).readText()
            val jsonObj = JSONObject(jsonStr)
            return jsonObj.optString("url", "")
        } catch (e: Exception) {
            Log.e(TAG, "Resolve URL Error: ${e.message}")
            return ""
        }
    }

    private fun extractPlaylistId(html: String): String {
        val regex = Pattern.compile("soundcloud://playlists:(\\d+)")
        val matcher = regex.matcher(html)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    private suspend fun loadNetworkResponse(urlStr: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.connect()
            if (conn.responseCode == 200) {
                sb.append(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: Exception) { Log.e(TAG, "Network Error: $urlStr", e) }
        return@withContext sb.toString()
    }

    // --- STANDARD UTILS ---
    suspend fun loadJson(urlStr: String) = withContext(Dispatchers.IO) {
        try {
            val json = URL(urlStr).readText()
            val jsonObj = JSONObject(json)
            if (jsonObj.has("url")) mM3uUrl = jsonObj.getString("url")
        } catch (e: Exception) {}
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

    suspend fun setTags(filePath: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                TagOptionSingleton.getInstance().isAndroid = true
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.TITLE, mTitle)
                tag.setField(FieldKey.ARTIST, mArtist)
                val thumb = File(absPathDocsTemp + mThumbnailFilename)
                if (thumb.exists()) tag.setField(ArtworkFactory.createArtworkFromFile(thumb))
                audioFile.commit()
            }
        } catch (e: Exception) {}
    }

    suspend fun moveFileToDocuments(privatePath: String): String = withContext(Dispatchers.IO) {
        val source = File(privatePath)
        if (!source.exists()) return@withContext ""
        val docsDir = File(absPathDocs)
        docsDir.mkdirs()

        var cleanTitle = mTitle.replace("[^a-zA-Z0-9 .\\-_]".toRegex(), "_")
        if (cleanTitle.isEmpty()) cleanTitle = "track"

        var finalName = "$cleanTitle.mp3"
        var dest = File(docsDir, finalName)
        var i = 1
        while (dest.exists()) {
            finalName = "$cleanTitle ($i).mp3"
            dest = File(docsDir, finalName)
            i++
        }
        source.copyTo(dest, true)
        return@withContext dest.absolutePath
    }

    fun deleteTempFiles() { try { File(absPathDocsTemp).deleteRecursively(); File(absPathDocsTemp).mkdirs() } catch (e: Exception) {} }
}