package com.mvxgreen.downloader4soundcloud

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
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
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object SoundLoader {
    private const val TAG = "SoundLoader"

    var totalItems = 0
    var completedItems = 0
    var isShared = false
    //var isAlbum = false
    var isPlaylist = false  // use for playlist and album
    var isCancelled = false


    // State variables
    var mClientId = ""
    var mStreamUrl = ""
    var mM3uUrl = ""
    var mMp3Urls = mutableListOf<String>()
    var mThumbnailFilename = ""
    var mTitle = ""
    var mArtist = ""
    var mThumbnailUrl = ""
    var mM3uFileName = "audio_playlist"
    var mFilePath = ""

    // Receiver State
    var mCountChunks = 0
    var mCountChunksFinal = 0

    var mMediaUrls = mutableListOf<String>()
    var mChunkUrls = mutableListOf<String>()

    // Constants
    private const val STREAM_URL_BASE = "https://api-v2.soundcloud.com/media/soundcloud:tracks:"
    private const val STREAM_URL_END = "/stream/hls"

    // Directories
    val absPathDocs: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath + "/"
    val absPathDocsTemp: String
        get() = absPathDocs + "temp/"

    fun prepareFileDirs() {
        File(absPathDocs).mkdirs()
        File(absPathDocsTemp).mkdirs()
    }

    fun resetVars() {
        // When we start fresh, ensure we are NOT cancelled
        isCancelled = false

        mMediaUrls.clear()
        mChunkUrls.clear()
        mTitle = ""
        isPlaylist = false
        mM3uUrl = ""

        // Reset counters
        totalItems = 0
        completedItems = 0

        // TODO implement ?
        //DownloadReceiver.reset()
    }

    // Coroutine to load HTML and parse logic
    suspend fun loadHtml(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
            val html = doc.html()

            // Extract Title/Artist from <title> or <h1>
            val titleText = doc.title().replace("Stream ", "").replace(" | Listen online for free on SoundCloud", "")
            val split = titleText.split(" by ")
            if (split.size > 1) {
                mTitle = split[0]
                mArtist = split[1]
            } else {
                mTitle = titleText
                mArtist = "Unknown"
            }

            // Extract Thumbnail
            val ogImage = doc.select("meta[property=og:image]").attr("content")
            mThumbnailUrl = ogImage.replace("-large.", "-t500x500.")
            mThumbnailFilename = if (mThumbnailUrl.contains(".jpg")) "thumbnail.jpg" else "thumbnail.png"

            // Extract Stream URL ID
            // NOTE: This logic mimics the C# string parsing.
            // Ideally, you'd use a regex for "media/soundcloud:tracks:(\d+)"
            val regex = Regex("media/soundcloud:tracks:(\\d+)")
            val match = regex.find(html)
            if (match != null) {
                val id = match.groupValues[1]
                mStreamUrl = "$STREAM_URL_BASE$id$STREAM_URL_END"
                return@withContext true
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
            val json = conn.inputStream.bufferedReader().use { it.readText() }

            val jsonObj = JSONObject(json)

            // Handling M3U extraction from JSON
            if (jsonObj.has("url")) {
                mM3uUrl = jsonObj.getString("url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading JSON", e)
        }
    }

    suspend fun extractMp3Urls(m3uPath: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val file = File(m3uPath)
        if (file.exists()) {
            file.forEachLine { line ->
                if (!line.startsWith("#")) {
                    urls.add(line)
                }
            }
        }
        return@withContext urls
    }

    suspend fun concatMp3(count: Int): String = withContext(Dispatchers.IO) {
        val destPath = "$absPathDocs$mTitle.mp3" // Simple naming for example
        val outFile = File(destPath)
        val outStream = FileOutputStream(outFile)

        for (i in 0 until count) {
            val chunkFile = File("${absPathDocsTemp}s$i.mp3")
            if (chunkFile.exists()) {
                outStream.write(chunkFile.readBytes())
            }
        }
        outStream.close()
        return@withContext destPath
    }

    suspend fun setTags(filePath: String) = withContext(Dispatchers.IO) {
        try {
            // Using JAudiotagger
            TagOptionSingleton.getInstance().isAndroid = true
            val f = AudioFileIO.read(File(filePath))
            val tag = f.tag
            tag.setField(FieldKey.TITLE, mTitle)
            tag.setField(FieldKey.ARTIST, mArtist)

            // Set Artwork
            val thumbFile = File(absPathDocsTemp + mThumbnailFilename)
            if(thumbFile.exists()){
                val artwork = ArtworkFactory.createArtworkFromFile(thumbFile)
                tag.setField(artwork)
            }
            f.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Tagging failed", e)
        }
    }

    fun deleteTempFiles() {
        File(absPathDocsTemp).deleteRecursively()
    }
}