package com.mvxgreen.downloader4soundcloud

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

object SoundLoader {
    private const val TAG = "SoundLoader"

    lateinit var appContext: Context

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

    // FIX 1: Track Thumbnail ID to prevent it from being counted as a chunk
    var thumbnailDownloadId: Long = -1L

    var isShared = false
    var isPlaylist = false

    private const val FLAG_BEGIN_STREAM_ID = "media/soundcloud:tracks:"
    private const val FLAG_END_STREAM_ID = "/stream"
    private const val STREAM_URL_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:"
    private const val STREAM_URL_END = "/stream/hls"

    val absPathDocs: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/"

    val absPathDocsTemp: String
        get() = appContext.getExternalFilesDir("temp")?.absolutePath + "/"

    fun prepareFileDirs() {
        File(absPathDocs).mkdirs()
        File(absPathDocsTemp).mkdirs()
    }

    fun resetVars() {
        Log.d(TAG, "Full Reset of Variables")
        mStreamUrl = ""
        mM3uUrl = ""
        mTitle = ""
        mArtist = ""
        mThumbnailUrl = ""
        mThumbnailFilename = ""
        mPlayerUrl = ""
        mMp3Urls.clear()

        playlistDownloadId = -1L
        thumbnailDownloadId = -1L // Reset thumbnail ID

        isPlaylist = false
        deleteTempFiles()
    }

    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Scraping URL: $url")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .get()
            val html = doc.html()

            // 1. Title/Artist
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

            // 3. Player URL Extraction
            var playerMeta = doc.select("meta[property=twitter:player]").attr("content")
            if (playerMeta.isEmpty()) playerMeta = doc.select("meta[name=twitter:player]").attr("content")

            if (playerMeta.isEmpty()) {
                val token = "twitter:player"
                val startIdx = html.indexOf(token)
                if (startIdx != -1) {
                    val contentIdx = html.indexOf("content=\"", startIdx)
                    if (contentIdx != -1) {
                        val urlStart = contentIdx + 9
                        val urlEnd = html.indexOf("\"", urlStart)
                        if (urlEnd > urlStart) {
                            playerMeta = html.substring(urlStart, urlEnd)
                        }
                    }
                }
            }
            if (playerMeta.isNotEmpty()) mPlayerUrl = playerMeta

            // 4. Stream Extraction (Regex)
            val regex = Pattern.compile("\\{\"url\":\"(https?:\\\\?/\\\\?/api-v2\\.soundcloud\\.com\\\\?/media\\\\?/soundcloud:tracks:[^\"]+)\",[^}]*\"mime_type\":\"audio\\\\?/mpeg\"")
            val matcher = regex.matcher(html)

            if (matcher.find()) {
                var foundUrl = matcher.group(1)
                foundUrl = foundUrl?.replace("\\/", "/")

                if (!foundUrl.isNullOrEmpty()) {
                    mStreamUrl = foundUrl
                    Log.d(TAG, "Found Progressive MP3 Stream: $mStreamUrl")
                    return@withContext true
                }
            }

            Log.w(TAG, "MP3 Stream not found. Trying fallback...")

            // Fallback (HLS)
            if (html.contains(FLAG_BEGIN_STREAM_ID) && html.contains(FLAG_END_STREAM_ID)) {
                val startIdx = html.lastIndexOf(FLAG_BEGIN_STREAM_ID) + FLAG_BEGIN_STREAM_ID.length
                val endIdx = html.indexOf(FLAG_END_STREAM_ID, startIdx)

                if (startIdx > FLAG_BEGIN_STREAM_ID.length && endIdx > startIdx) {
                    val id = html.substring(startIdx, endIdx)
                    mStreamUrl = "$STREAM_URL_BASE$id$STREAM_URL_END"
                    Log.d(TAG, "Fallback to HLS Stream: $mStreamUrl")
                    return@withContext true
                }
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading HTML", e)
            return@withContext false
        }
    }

    suspend fun loadJson(urlStr: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.connect()

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(json)
                if (jsonObj.has("url")) {
                    mM3uUrl = jsonObj.getString("url")
                    Log.d(TAG, "M3U URL Found: $mM3uUrl")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in loadJson", e)
        }
    }

    suspend fun extractMp3Urls(m3uPath: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val file = File(m3uPath)
        if (file.exists()) {
            file.forEachLine { line -> if (!line.startsWith("#")) urls.add(line) }
        }
        return@withContext urls
    }

    suspend fun concatMp3(count: Int): String = withContext(Dispatchers.IO) {
        val destPath = "${absPathDocsTemp}temp_build.mp3"
        val outFile = File(destPath)
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val outStream = FileOutputStream(outFile)
        val tempDir = File(absPathDocsTemp)

        Log.d(TAG, "Concatenating $count chunks...")

        for (i in 0 until count) {
            val chunkFile = tempDir.listFiles { _, name -> name.startsWith("s$i.") }?.firstOrNull()
            if (chunkFile != null && chunkFile.exists() && chunkFile.length() > 0) {
                try { outStream.write(chunkFile.readBytes()) } catch (e: Exception) {}
            }
        }
        outStream.flush()
        outStream.close()
        return@withContext destPath
    }

    suspend fun setTags(filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() < 100) return@withContext

        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, mTitle)
            tag.setField(FieldKey.ARTIST, mArtist)

            val thumbFile = File(absPathDocsTemp + mThumbnailFilename)
            if (thumbFile.exists()) {
                val artwork = ArtworkFactory.createArtworkFromFile(thumbFile)
                tag.setField(artwork)
            }
            audioFile.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Tagging failed: ${e.message}")
        }
    }

    suspend fun moveFileToDocuments(privatePath: String): String = withContext(Dispatchers.IO) {
        val source = File(privatePath)
        if (!source.exists() || source.length() < 100) return@withContext ""

        val docsDir = File(absPathDocs)
        if (!docsDir.exists()) docsDir.mkdirs()

        var safeTitle = mTitle.replace("[^a-zA-Z0-9 .\\-_]".toRegex(), "_")
        if (safeTitle.isEmpty()) safeTitle = "track"

        var finalName = "$safeTitle.mp3"
        var destFile = File(docsDir, finalName)
        var i = 1
        while (destFile.exists()) {
            finalName = "$safeTitle ($i).mp3"
            destFile = File(docsDir, finalName)
            i++
        }

        try {
            source.copyTo(destFile, overwrite = true)
            return@withContext destFile.absolutePath
        } catch (e: Exception) {
            return@withContext ""
        }
    }

    fun deleteTempFiles() {
        try {
            val dir = File(absPathDocsTemp)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            dir.mkdirs()
        } catch (e: Exception) {}
    }
}