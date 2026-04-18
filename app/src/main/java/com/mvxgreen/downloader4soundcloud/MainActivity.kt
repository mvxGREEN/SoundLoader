package com.mvxgreen.downloader4soundcloud

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.*
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.mvxgreen.downloader4soundcloud.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern
import kotlin.toString
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var fetchJob: Job? = null
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:on\\.|m\\.|www\\.)?soundcloud\\.com\\/)", Pattern.CASE_INSENSITIVE)
    private val EXTRACT_URL_REGEX = Pattern.compile("(https?://(?:on\\.|www\\.|m\\.)?soundcloud\\.com/[^\\s]*)", Pattern.CASE_INSENSITIVE)

    private var downloadedFileUri: Uri? = null

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>

    private val inputHandler = Handler(Looper.getMainLooper())
    private val inputRunnable = Runnable {
        val text = binding.etMainInput.text.toString()
        if (VALID_INPUT_REGEX.matcher(text).find() && text.isNotEmpty()) handleInput(text)
    }

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            inputHandler.removeCallbacks(inputRunnable)
            if (s.isNullOrEmpty()) updateUI(UIState.EMPTY)
            else inputHandler.postDelayed(inputRunnable, 100)
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    enum class UIState { EMPTY, LOADING, PREVIEW, DOWNLOADING, FINISHED }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            var text = intent?.getStringExtra("text") ?: "Downloading…"
            val isIndeterminate = intent?.getBooleanExtra("indeterminate", true) ?: true
            val current = intent?.getIntExtra("current", 0) ?: 0
            val total = intent?.getIntExtra("total", 0) ?: 100

            // Update TextView
            // Note: We need to bind this view if not in binding (it is in binding now due to XML change)
            // Since we added ID dl_progress_text to XML, binding.dlProgressText is available
            binding.dlProgressText.text = text

            // Update ProgressBar
            binding.progressRingDlr.isIndeterminate = isIndeterminate
            if (!isIndeterminate) {
                binding.progressRingDlr.max = total
                binding.progressRingDlr.progress = current
            }
        }
    }
    //private val downloadReceiver = DownloadReceiver()
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Grab the URI passed from the Service/Receiver
            val uriString = intent?.getStringExtra("DOWNLOADED_URI")
            if (!uriString.isNullOrEmpty()) {
                downloadedFileUri = Uri.parse(uriString)
            }

            updateUI(UIState.FINISHED)
            Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
            if (SoundLoader.isShared) {
                SoundLoader.isShared = false
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 700)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundLoader.resetVars()
        SoundLoader.isShared = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SoundLoader.prepareFileDirs()

        requestNotificationLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) Toast.makeText(this, "Notifications are recommended", Toast.LENGTH_SHORT).show()
            requestBatteryOptimization()
        }
        startBackgroundPermissionChain()

        setupToolbarMenu()
        setupListeners()
        setupWebView()

        ContextCompat.registerReceiver(this, progressReceiver, IntentFilter("ACTION_PROGRESS_UPDATE"), ContextCompat.RECEIVER_NOT_EXPORTED)
        //ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, finishReceiver, IntentFilter("DOWNLOAD_FINISHED"), ContextCompat.RECEIVER_NOT_EXPORTED)

        updateUI(UIState.EMPTY)
        checkIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                SoundLoader.isShared = true
                binding.etMainInput.removeTextChangedListener(textWatcher)
                binding.etMainInput.setText(sharedText)
                binding.etMainInput.addTextChangedListener(textWatcher)
                handleInput(sharedText)
            }
        }
    }

    private fun setupListeners() {
        binding.etMainInput.setOnEditorActionListener { v, _, _ -> handleInput(v.text.toString()); true }
        binding.etMainInput.addTextChangedListener(textWatcher)

        binding.btnClear.setOnClickListener {
            fetchJob?.cancel()
            inputHandler.removeCallbacks(inputRunnable)
            binding.previewWebview.stopLoading()
            binding.previewWebview.loadUrl("about:blank")
            binding.etMainInput.setText("")
            SoundLoader.resetVars()
            updateUI(UIState.EMPTY)
            SoundLoader.cancelNotification(this)
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val clipText = clip?.getItemAt(0)?.text
            if (clip != null && clip.itemCount > 0 && clipText != null) {
                val text = clipText.toString()
                binding.etMainInput.setText(text)
                inputHandler.removeCallbacks(inputRunnable)
                handleInput(text)
            }
        }

        binding.dlBtn.setOnClickListener { startDownload() }

        binding.shareBtn.setOnClickListener {
            shareDownloadedFile()
        }
    }

    private fun startBackgroundPermissionChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestBatteryOptimization()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_privacy -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/privacy-policy"))); true }
                R.id.action_about -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/"))); true }
                R.id.action_enable_background -> { startBackgroundPermissionChain(); true }
                else -> false
            }
        }
    }

    private fun handleInput(rawInput: String) {
        if (SoundLoader.isBatchActive) {
            Toast.makeText(this, "Please wait for the current download to finish", Toast.LENGTH_LONG).show()
            // TODO log event
            return
        }

        inputHandler.removeCallbacksAndMessages(null)
        fetchJob?.cancel()

        binding.previewWebview.stopLoading()

        // 2. Clear the View state
        binding.previewWebview.loadUrl("about:blank")
        binding.previewWebview.clearCache(true)
        binding.previewWebview.clearHistory()

        // 3. Clear System Web Storage (Cookies & DOM)
        // This ensures SoundCloud sees us as a fresh "Guest" every time
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()

        SoundLoader.resetVars()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMainInput.windowToken, 0)
        binding.etMainInput.clearFocus()

        var input = rawInput
        val matcher = EXTRACT_URL_REGEX.matcher(rawInput)
        if (matcher.find()) input = matcher.group(1) ?: rawInput else input = input.trim()

        if (!input.startsWith("http")) {
            if (input.startsWith("soundcloud.com") || input.startsWith("on.soundcloud.com")) input = "https://$input"
        }

        if (!VALID_INPUT_REGEX.matcher(input).find()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            return
        }

        updateUI(UIState.LOADING)

        val finalUrl = input
        Handler(Looper.getMainLooper()).postDelayed({
            if (finalUrl.contains("on.soundcloud.com")) binding.previewWebview.loadUrl(finalUrl)
            else processStandardUrl(finalUrl)
        }, 300)
    }

    private fun processStandardUrl(url: String) {
        Log.d("MainActivity", "processStandardUrl: url=$url")

        if (url.contains("/sets/") || url.contains("/albums/")) {
            SoundLoader.isPlaylist = true
        } else {
            SoundLoader.isPlaylist = false
        }
        Log.d("MainActivity", "isPlaylist=$SoundLoader.isPlaylist")
        loadMediaData(url)
    }

    private fun loadMediaData(url: String) {
        Log.d("MainActivity", "loadMediaData: url=$url")

        SoundLoader.mStreamUrl = ""

        lifecycleScope.launch {
            val success = SoundLoader.loadHtml(url)
            if (success) {
                if (!isDestroyed && !isFinishing) {
                    Glide.with(this@MainActivity).load(SoundLoader.mThumbnailUrl).centerCrop().into(binding.previewImg)
                }

                // This ensures we ALWAYS get a Client ID, even if the "twitter:player" tag is missing.
                if (SoundLoader.mClientId.isEmpty()) {
                    val targetUrl = if (SoundLoader.mPlayerUrl.isNotEmpty()) SoundLoader.mPlayerUrl else url
                    binding.previewWebview.loadUrl(targetUrl)
                }

                if (SoundLoader.isPlaylist) {
                    // if | present, trim title to everything prior
                    if (SoundLoader.mTitle.contains(" | "))
                        SoundLoader.mTitle = SoundLoader.mTitle.substringBefore(" | ")

                    binding.previewTitle.text = "Playlist: ${SoundLoader.mTitle}"
                    binding.previewArtist.text = "Loading tracks..."
                    binding.dlBtn.setImageResource(R.drawable.ic_download)

                    if (SoundLoader.mClientId.isNotEmpty()) {
                        val success = SoundLoader.processPlaylistWithKey(SoundLoader.mClientId) { count ->
                            // Update UI on the Main Thread
                            runOnUiThread {
                                // Ensure binding is accessible and we aren't destroyed
                                if (!isDestroyed && !isFinishing) {
                                    binding.progressLabel.text = "$count Tracks Found..."
                                }
                            }
                        }
                        if (success) {
                            binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks Ready"
                            updateUI(UIState.PREVIEW)
                            binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks"
                            if (SoundLoader.isShared) startDownload() else updateUI(UIState.PREVIEW)
                        } else {
                            if (SoundLoader.mPlayerUrl.isNotEmpty()) binding.previewWebview.loadUrl(SoundLoader.mPlayerUrl)
                        }
                    }
                } else {
                    binding.previewTitle.text = SoundLoader.mTitle
                    binding.previewArtist.text = SoundLoader.mArtist
                    updateUI(UIState.PREVIEW)
                }
            } else {
                updateUI(UIState.EMPTY)
                Toast.makeText(this@MainActivity, "Load Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownload() {
        updateUI(UIState.DOWNLOADING)

        // Reset Progress UI defaults
        binding.dlProgressText.text = getString(R.string.downloading)
        binding.progressRingDlr.isIndeterminate = true

        if (SoundLoader.isPlaylist) {
            SoundLoader.isBatchActive = true
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                // Prepare variables for the first track
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)
                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: ""
                    SoundLoader.mArtist = tag["artist"] ?: ""
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                }
                // Start the Service (The Service will handle generating the Unique ID)
                val intent = Intent(this, DownloadService::class.java)
                intent.action = "START_DOWNLOAD"
                startService(intent)
            } else {
                Toast.makeText(this, "No downloadable tracks found!", Toast.LENGTH_LONG).show()
                updateUI(UIState.PREVIEW)
                SoundLoader.isBatchActive = false
            }
        } else {
            // Single File Logic
            CoroutineScope(Dispatchers.Main).launch {
                if (SoundLoader.mStreamUrl.isNotEmpty() && SoundLoader.mM3uUrl.isEmpty()) {
                    val url = "${SoundLoader.mStreamUrl}?client_id=${SoundLoader.mClientId}"
                    SoundLoader.loadJson(url) // This is suspend, so we need the CoroutineScope wrapper
                }
                val intent = Intent(this@MainActivity, DownloadService::class.java)
                intent.action = "START_DOWNLOAD"
                startService(intent)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.previewWebview.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

        binding.previewWebview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()

                if (url.contains("client_id=") && SoundLoader.mClientId.isEmpty()) {
                    val id = url.substringAfter("client_id=").substringBefore("&")
                    SoundLoader.mClientId = id
                    Log.d("MainActivity", "Intercepted Key: $id")

                    CoroutineScope(Dispatchers.Main).launch {
                        if (SoundLoader.isPlaylist) {
                            Log.d("MainActivity", "sl_playlist_fetch_start")

                            val success = SoundLoader.processPlaylistWithKey(id) { count ->
                                runOnUiThread {
                                    if (!isDestroyed && !isFinishing) {
                                        binding.progressLabel.text = "$count Tracks Found…"
                                    }
                                }
                            }
                            if (success) {
                                Log.d("MainActivity", "sl_playlist_fetch_success")
                                var input_url = binding.etMainInput.text.toString()
                                if (input_url.contains("http")) {
                                    input_url = input_url.substringAfter("http")
                                }
                                binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks"
                                updateUI(UIState.PREVIEW)
                                // if | present, trim title to everything prior
                                if (SoundLoader.mTitle.contains(" | "))
                                    SoundLoader.mTitle = SoundLoader.mTitle.substringBefore(" | ")

                                binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks"
                                if (SoundLoader.isShared) startDownload() else updateUI(UIState.PREVIEW)
                            } else {
                                Log.d("MainActivity", "sl_playlist_fetch_fail")
                                var input_url = binding.etMainInput.text.toString()
                                if (input_url.contains("http")) {
                                    input_url = input_url.substringAfter("http")
                                }
                                Toast.makeText(this@MainActivity, "Playlist fetch failed", Toast.LENGTH_SHORT).show()
                                updateUI(UIState.EMPTY)
                            }
                        } else if (SoundLoader.mStreamUrl.isNotEmpty()) {
                            val fullUrl = "${SoundLoader.mStreamUrl}?client_id=$id"
                            Log.d("MainActivity", "fullUrl: $fullUrl")
                            SoundLoader.loadJson(fullUrl)
                            runOnUiThread { updateUI(UIState.PREVIEW) }
                        } else {
                            // We have the Key, but no metadata (likely came from a Shortened URL).
                            // Grab the fully resolved URL from the WebView and process it now.
                            val currentUrl = view?.url
                            if (!currentUrl.isNullOrEmpty() && currentUrl.contains("soundcloud.com")) {
                                Log.d("MainActivity", "Shortened URL resolved to: $currentUrl")
                                // Trigger the standard parsing logic now that we have the full URL
                                processStandardUrl(currentUrl)
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun updateUI(state: UIState) {
        binding.etMainInput.isEnabled = true
        binding.btnPaste.isEnabled = true
        binding.btnPaste.alpha = 1.0f

        // get input_url for analytics event
        var url = binding.etMainInput.text.toString()
        if (url.contains("https://")) {
            url = url.substringAfter("https://"+8)
        }

        when (state) {
            UIState.EMPTY -> {
                Log.d("MainActivity", "sl_ui_empty")
                //logEvent("sl_ui_empty")
                binding.loadingLayout.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.INVISIBLE
                binding.downloaderCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.INVISIBLE

                binding.finishBtn.visibility = View.GONE
                binding.finishBtn.alpha = 0.0f
                binding.shareBtn.visibility = View.INVISIBLE
                binding.shareBtn.alpha = 0.0f

                binding.progressLabel.text = ""
            }
            UIState.LOADING -> {
                Log.d("MainActivity", "sl_ui_loading")
                binding.progressLabel.text = getString(R.string.loading)
                binding.loadingLayout.alpha = 1.0f
                binding.loadingLayout.visibility = View.VISIBLE
                binding.previewCard.visibility = View.INVISIBLE
                binding.downloaderCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.INVISIBLE // Was GONE
                binding.etMainInput.isEnabled = false

                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.5f
                binding.shareBtn.visibility = View.INVISIBLE
                binding.shareBtn.alpha = 0.0f
            }
            UIState.PREVIEW -> {
                Log.d("MainActivity", "sl_ui_preview")

                binding.loadingLayout.visibility = View.INVISIBLE
                binding.previewCard.alpha = 1.0f
                binding.previewCard.visibility = View.VISIBLE
                binding.downloaderCard.alpha = 1.0f
                binding.downloaderCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.INVISIBLE // Was GONE
                binding.dlBtn.visibility = View.VISIBLE

                binding.finishBtn.visibility = View.GONE
                binding.finishBtn.alpha = 0.0f
                binding.shareBtn.visibility = View.INVISIBLE
                binding.shareBtn.alpha = 0.0f
            }
            UIState.DOWNLOADING -> {
                Log.d("MainActivity", "sl_ui_downloading")
                binding.loadingLayout.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.VISIBLE
                binding.downloaderCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.VISIBLE
                binding.dlBtn.visibility = View.INVISIBLE
                binding.etMainInput.isEnabled = false

                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.5f
                binding.shareBtn.visibility = View.INVISIBLE
                binding.shareBtn.alpha = 0.0f
            }
            UIState.FINISHED -> {
                Log.d("MainActivity", "sl_ui_finished")
                binding.overlayDownloading.visibility = View.INVISIBLE // Was GONE
                binding.finishBtn.visibility = View.VISIBLE
                binding.finishBtn.animate().alpha(0.5f)

                // show share if not playlist
                if (!SoundLoader.isPlaylist) {
                    binding.shareBtn.visibility = View.VISIBLE
                    binding.shareBtn.animate().alpha(1.0f)
                }
            }
        }
    }

    private fun shareDownloadedFile() {
        downloadedFileUri?.let { uri ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Audio"))
        } ?: run {
            Toast.makeText(this, "File not found to share.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy();
        unregisterReceiver(progressReceiver)
        unregisterReceiver(finishReceiver) }
}