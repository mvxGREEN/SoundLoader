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

object SoundLoader {
    private const val TAG = "SoundLoader"

    lateinit var appContext: Context

    // State variables
    var mClientId = ""
    var mStreamUrl = ""
    var mM3uUrl = ""
    var mTitle = ""
    var mArtist = ""
    var mThumbnailUrl = ""
    var mThumbnailFilename = ""
    var mMp3Urls = mutableListOf<String>()

    // FIX: Track the Download ID to distinguish between Thumbnail and Playlist
    var playlistDownloadId: Long = -1L

    var isShared = false
    var isPlaylist = false

    private const val FLAG_BEGIN_STREAM_ID = "media/soundcloud:tracks:"
    private const val FLAG_END_STREAM_ID = "/stream"
    private const val STREAM_URL_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:"
    private const val STREAM_URL_END = "/stream/hls"

    // Directories
    val absPathDocs: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/"

    val absPathDocsTemp: String
        get() = appContext.getExternalFilesDir("temp")?.absolutePath + "/"

    fun prepareFileDirs() {
        File(absPathDocs).mkdirs()
        File(absPathDocsTemp).mkdirs()
    }

    fun resetVars() {
        mStreamUrl = ""
        mM3uUrl = ""
        mTitle = ""
        isPlaylist = false
        mClientId = ""
        mMp3Urls.clear()
        playlistDownloadId = -1L // Reset ID
        deleteTempFiles()
    }

    // ... (loadHtml and loadJson remain unchanged) ...
    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
            val html = doc.html()
            val titleText = doc.title().replace("Stream ", "").replace(" | Listen online for free on SoundCloud", "")
            val split = titleText.split(" by ")
            if (split.size > 1) {
                mTitle = split[0]
                mArtist = split[1]
            } else {
                mTitle = titleText
                mArtist = "Unknown"
            }
            val ogImage = doc.select("meta[property=og:image]").attr("content")
            mThumbnailUrl = ogImage.replace("-large.", "-t500x500.")
            mThumbnailFilename = if (mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"

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

    suspend fun loadJson(urlStr: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connect()
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(json)
                if (jsonObj.has("url")) mM3uUrl = jsonObj.getString("url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in loadJson", e)
        }
    }

    suspend fun extractMp3Urls(m3uPath: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val file = File(m3uPath)
        if (file.exists()) {
            file.forEachLine { line ->
                if (!line.startsWith("#")) urls.add(line)
            }
        } else {
            Log.e(TAG, "M3U File not found at: $m3uPath")
        }
        return@withContext urls
    }

    suspend fun concatMp3(count: Int): String = withContext(Dispatchers.IO) {
        val destPath = "$absPathDocs$mTitle.mp3"
        val outFile = File(destPath)
        val outStream = FileOutputStream(outFile)
        val tempDir = File(absPathDocsTemp)

        Log.d(TAG, "Concatenating $count chunks into $destPath")

        for (i in 0 until count) {
            val chunkFile = tempDir.listFiles { _, name ->
                name.startsWith("s$i.")
            }?.firstOrNull()

            if (chunkFile != null && chunkFile.exists() && chunkFile.length() > 0) {
                outStream.write(chunkFile.readBytes())
            } else {
                Log.e(TAG, "CRITICAL: Missing or empty chunk s$i.")
            }
        }
        outStream.flush()
        outStream.close()
        return@withContext destPath
    }

    suspend fun setTags(filePath: String) = withContext(Dispatchers.IO) {
        try {
            val f = File(filePath)
            if (!f.exists() || f.length() < 100) return@withContext

            TagOptionSingleton.getInstance().isAndroid = true
            val audioFile = AudioFileIO.read(f)
            val tag = audioFile.tag
            tag.setField(FieldKey.TITLE, mTitle)
            tag.setField(FieldKey.ARTIST, mArtist)

            val thumbFile = File(absPathDocsTemp + mThumbnailFilename)
            if(thumbFile.exists()){
                val artwork = ArtworkFactory.createArtworkFromFile(thumbFile)
                tag.setField(artwork)
            }
            audioFile.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Tagging failed: ${e.message}")
        }
    }

    fun deleteTempFiles() {
        try {
            val dir = File(absPathDocsTemp)
            if (dir.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning temp files", e)
        }
    }
}