package com.mvxgreen.downloader4soundcloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
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
import com.google.android.gms.ads.*
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.mvxgreen.downloader4soundcloud.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // Coroutine Job for scraping
    private var fetchJob: Job? = null

    // --- UPDATED REGEX ---
    // 1. Validation: Checks if the final result looks like a SoundCloud link
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:on\\.|m\\.|www\\.)?soundcloud\\.com\\/)", Pattern.CASE_INSENSITIVE)

    // 2. Extraction: Scans messy text to find the http/https URL starting with soundcloud domains
    // Matches: https:// + optional subdomain + soundcloud.com + / + non-whitespace characters
    private val EXTRACT_URL_REGEX = Pattern.compile(
        "(https?://(?:on\\.|www\\.|m\\.)?soundcloud\\.com/[^\\s]*)",
        Pattern.CASE_INSENSITIVE
    )

    private val inputHandler = Handler(Looper.getMainLooper())
    private var lastLoadedUrl = ""

    // Debounce Runnable: Waits 1 second after typing stops
    private val inputRunnable = Runnable {
        val text = binding.etMainInput.text.toString()
        if (VALID_INPUT_REGEX.matcher(text).find() && text.isNotEmpty()) {
            handleInput(text)
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            // Show/Hide Clear Button (if you have one, reusing paste_button logic for now or custom)
            // binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE

            // 1. Cancel previous pending search
            inputHandler.removeCallbacks(inputRunnable)

            if (s.isNullOrEmpty()) {
                updateUI(UIState.EMPTY)
            } else {
                // 2. Schedule load in 1 second
                inputHandler.postDelayed(inputRunnable, 1000)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    enum class UIState {
        EMPTY, LOADING, PREVIEW, DOWNLOADING, FINISHED
    }

    // Receivers
    private val downloadReceiver = DownloadReceiver()
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI(UIState.FINISHED)
            Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()

            // If shared, auto-close after a delay
            if (SoundLoader.isShared) {
                SoundLoader.isShared = false
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize State
        SoundLoader.resetVars()
        SoundLoader.isShared = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init Services
        FirebaseApp.initializeApp(this)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        SoundLoader.prepareFileDirs()

        setupAds()
        setupListeners()
        setupWebView()

        // Register Receivers
        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, finishReceiver, IntentFilter("DOWNLOAD_FINISHED"), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Check Intent (Sharing)
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
                Log.d("MainActivity", "Received Shared Intent: $sharedText")
                SoundLoader.isShared = true

                // Temporarily disable watcher to prevent double trigger
                binding.etMainInput.removeTextChangedListener(textWatcher)
                binding.etMainInput.setText(sharedText)
                binding.etMainInput.addTextChangedListener(textWatcher)

                handleInput(sharedText)
            }
        }
    }

    private fun setupListeners() {
        // 1. Editor Action (Enter Key)
        binding.etMainInput.setOnEditorActionListener { v, _, _ ->
            handleInput(v.text.toString())
            true
        }

        // 2. Text Watcher (Debounce)
        binding.etMainInput.addTextChangedListener(textWatcher)

        // 3. Paste Button
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()

                binding.etMainInput.setText(text)

                // Cancel debounce and force immediate load
                inputHandler.removeCallbacks(inputRunnable)
                handleInput(text)
            }
        }

        // 4. Download Button
        binding.dlBtn.setOnClickListener {
            startDownloadService()
        }
    }

    private fun handleInput(rawInput: String) {
        // 1. Cancel pending jobs & debounce
        inputHandler.removeCallbacksAndMessages(null)
        fetchJob?.cancel()

        // 2. Hide Keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMainInput.windowToken, 0)
        binding.etMainInput.clearFocus()

        var input = rawInput

        // 3. EXTRACTION LOGIC (New)
        // Scan the input for a valid URL substring (e.g., extracting from "Listen to... https://...")
        val matcher = EXTRACT_URL_REGEX.matcher(rawInput)
        if (matcher.find()) {
            input = matcher.group(1) // We found a URL, replace input with just the URL
            Log.d("MainActivity", "Extracted URL from text: $input")
        } else {
            // Fallback: Trim whitespace for manual inputs like "soundcloud.com/artist/track"
            input = input.trim()
        }

        // 4. Protocol Cleanup (for manual inputs missing https://)
        if (!input.startsWith("http")) {
            if (input.startsWith("soundcloud.com") || input.startsWith("on.soundcloud.com")) {
                input = "https://$input"
            }
        }

        // 5. Final Validation
        if (!VALID_INPUT_REGEX.matcher(input).find()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            return
        }

        // 6. Instant Feedback
        updateUI(UIState.LOADING)

        // 7. Delayed Processing
        val finalUrl = input // Capture for closure
        Handler(Looper.getMainLooper()).postDelayed({
            if (finalUrl.contains("on.soundcloud.com")) {
                Log.d("MainActivity", "Shortlink detected: $finalUrl")
                binding.previewWebview.loadUrl(finalUrl)
            } else {
                processStandardUrl(finalUrl)
            }
        }, 300)
    }

    // Called when we have a resolved, standard SoundCloud URL
    private fun processStandardUrl(url: String) {
        Log.d("MainActivity", "Processing Standard URL: $url")

        if (url.contains("/sets/")) {
            // It's a Playlist or Album
            Log.d("MainActivity", "Detected Playlist/Album")
            SoundLoader.isPlaylist = true
            loadMediaData(url)
        } else {
            // It's a Track
            Log.d("MainActivity", "Detected Track")
            SoundLoader.isPlaylist = false
            loadMediaData(url)
        }
    }

    // Resolves logic for scraping (Equivalent to loadHtml in previous steps)
    private fun loadMediaData(url: String) {
        fetchJob = CoroutineScope(Dispatchers.Main).launch {
            val success = SoundLoader.loadHtml(url) // Takes care of scraping title/thumb/stream

            if (success) {
                // Populate UI
                binding.previewTitle.text = SoundLoader.mTitle
                binding.previewArtist.text = SoundLoader.mArtist

                if (!isDestroyed && !isFinishing) {
                    Glide.with(this@MainActivity)
                        .load(SoundLoader.mThumbnailUrl)
                        .centerCrop()
                        .into(binding.previewImg)
                }

                // Logic for "Client ID" interception if needed
                // If the scraper didn't find the stream directly, we might trigger the WebView here
                // binding.previewWebview.loadUrl(SoundLoader.mStreamUrl + "?client_id=...")

                // If shared, auto-start download
                if (SoundLoader.isShared) {
                    startDownloadService()
                } else {
                    updateUI(UIState.PREVIEW)
                }
            } else {
                Toast.makeText(this@MainActivity, "Could not load track data", Toast.LENGTH_SHORT).show()
                updateUI(UIState.EMPTY)
            }
        }
    }

    private fun setupWebView() {
        val settings = binding.previewWebview.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        binding.previewWebview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Handle Shortlink Resolution
                if (url != null && url != lastLoadedUrl) {
                    // Check if we resolved a shortlink to a real soundcloud link
                    if (lastLoadedUrl.contains("on.soundcloud.com") && url.contains("soundcloud.com")) {
                        Log.d("MainActivity", "Shortlink resolved to: $url")
                        lastLoadedUrl = url
                        processStandardUrl(url)
                    }
                    lastLoadedUrl = url
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // Use this if you need to intercept client_id or JSON APIs
                // (Existing logic from previous steps can go here)
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun startDownloadService() {
        updateUI(UIState.DOWNLOADING)
        val intent = Intent(this, DownloadService::class.java)
        intent.action = "START_DOWNLOAD"
        startService(intent)
    }

    private fun updateUI(state: UIState) {
        // Enable inputs by default
        binding.etMainInput.isEnabled = true
        binding.btnPaste.isEnabled = true
        binding.btnPaste.alpha = 1.0f

        when (state) {
            UIState.EMPTY -> {
                binding.loadingLayout.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.INVISIBLE
                binding.downloaderCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.GONE
            }
            UIState.LOADING -> {
                binding.loadingLayout.alpha = 1.0f
                binding.loadingLayout.visibility = View.VISIBLE

                binding.previewCard.visibility = View.INVISIBLE
                binding.downloaderCard.visibility = View.INVISIBLE
                binding.overlayDownloading.visibility = View.GONE

                // Disable Inputs
                binding.etMainInput.isEnabled = false
                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.5f
            }
            UIState.PREVIEW -> {
                binding.loadingLayout.visibility = View.INVISIBLE

                binding.previewCard.alpha = 1.0f
                binding.previewCard.visibility = View.VISIBLE

                binding.downloaderCard.alpha = 1.0f
                binding.downloaderCard.visibility = View.VISIBLE

                binding.overlayDownloading.visibility = View.GONE

                // Show Download Button
                binding.dlBtn.visibility = View.VISIBLE
                binding.finishBtn.visibility = View.GONE
            }
            UIState.DOWNLOADING -> {
                binding.loadingLayout.visibility = View.INVISIBLE

                binding.previewCard.visibility = View.VISIBLE
                binding.downloaderCard.visibility = View.VISIBLE

                binding.overlayDownloading.visibility = View.VISIBLE // Show overlay

                binding.dlBtn.visibility = View.INVISIBLE

                // Disable Inputs
                binding.etMainInput.isEnabled = false
                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.5f
            }
            UIState.FINISHED -> {
                binding.overlayDownloading.visibility = View.GONE
                binding.finishBtn.visibility = View.VISIBLE
                binding.finishBtn.animate().alpha(1.0f)
            }
        }
    }

    private fun setupAds() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
        binding.bannerAdContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
        unregisterReceiver(finishReceiver)
    }
}