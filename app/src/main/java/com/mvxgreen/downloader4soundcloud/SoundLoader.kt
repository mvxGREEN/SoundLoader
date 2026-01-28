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
import java.io.File
import java.io.FileOutputStream
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
        // Run cleanup in background if called from coroutine, otherwise just fire and forget in main logic
        // For resetVars usually called at start, we can leave as is or launch scope.
        // We will make deleteTempFiles suspend to be safe.
    }

    fun resetVarsForNext() {
        mM3uUrl = ""
        mMp3Urls.clear()
    }

    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        // ... (Keep existing scraping logic exactly as is) ...
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
            return@withContext false
        } catch (e: Exception) { return@withContext false }
    }

    suspend fun processPlaylistWithKey(clientId: String): Boolean = withContext(Dispatchers.IO) {
        if (pendingPlaylistId.isEmpty()) return@withContext false
        mClientId = clientId
        val trackIds = fetchPlaylistTrackIds(pendingPlaylistId, mClientId)
        if (trackIds.isEmpty()) return@withContext false

        val idChunks = trackIds.chunked(50)
        for (chunk in idChunks) {
            val batchUrl = "https://api-v2.soundcloud.com/tracks?ids=${chunk.joinToString(",")}&client_id=$mClientId"
            val jsonStr = loadNetworkResponse(batchUrl)
            if (jsonStr.isNotEmpty()) parseBatchResponse(jsonStr)
        }
        batchTotal = playlistM3uUrls.size
        return@withContext batchTotal > 0
    }

    private suspend fun fetchPlaylistTrackIds(playlistId: String, clientId: String): List<String> {
        val ids = mutableListOf<String>()
        val jsonStr = loadNetworkResponse("https://api-v2.soundcloud.com/playlists/$playlistId?client_id=$clientId")
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
        } catch (e: Exception) {}
        return ids
    }

    private fun parseBatchResponse(jsonStr: String) {
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val trackObj = jsonArray.getJSONObject(i)
                val artwork = trackObj.optString("artwork_url").replace("-large.", "-t500x500.")
                val title = trackObj.optString("title")
                val artist = trackObj.optJSONObject("user")?.optString("username") ?: "Unknown"

                var streamUrl = ""
                val transcodings = trackObj.optJSONObject("media")?.optJSONArray("transcodings")

                if (transcodings != null && transcodings.length() > 0) {
                    for (j in 0 until transcodings.length()) {
                        val trans = transcodings.getJSONObject(j)
                        val mime = trans.optJSONObject("format")?.optString("mime_type")
                        if (mime == "audio/mpeg" && trans.optString("protocol") == "hls") {
                            streamUrl = trans.optString("url")
                            break
                        }
                    }
                    if (streamUrl.isEmpty()) streamUrl = transcodings.getJSONObject(0).optString("url")
                }

                if (streamUrl.isNotEmpty()) {
                    val m3u = resolveM3uUrl(streamUrl)
                    if (m3u.isNotEmpty()) {
                        playlistM3uUrls.add(m3u)
                        playlistTags.add(mapOf("title" to title, "artist" to artist, "artwork_url" to artwork))
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun resolveM3uUrl(streamBaseUrl: String): String {
        try {
            val authUrl = if (streamBaseUrl.contains("client_id")) streamBaseUrl else "$streamBaseUrl?client_id=$mClientId"
            val jsonStr = URL(authUrl).readText()
            return JSONObject(jsonStr).optString("url", "")
        } catch (e: Exception) { return "" }
    }

    private fun extractPlaylistId(html: String): String {
        val matcher = Pattern.compile("soundcloud://playlists:(\\d+)").matcher(html)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    private suspend fun loadNetworkResponse(urlStr: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.connect()
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
        } catch (e: Exception) { "" }
    }

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
        // ... (Keep existing tag logic) ...
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
        // ... (Keep existing move logic) ...
        val source = File(privatePath)
        if (!source.exists()) return@withContext ""
        val docsDir = File(absPathDocs)
        docsDir.mkdirs()
        var name = mTitle.replace("[^a-zA-Z0-9 .\\-_]".toRegex(), "_") + ".mp3"
        if(name == ".mp3") name = "track.mp3"
        var dest = File(docsDir, name)
        var i = 1
        while(dest.exists()) { dest = File(docsDir, name.replace(".mp3", " ($i).mp3")); i++ }
        source.copyTo(dest, true)
        return@withContext dest.absolutePath
    }

    // FIX: Make this suspend and run on IO
    suspend fun deleteTempFiles() = withContext(Dispatchers.IO) {
        try {
            val dir = File(absPathDocsTemp)
            dir.deleteRecursively()
            dir.mkdirs()
        } catch (e: Exception) {}
    }
}