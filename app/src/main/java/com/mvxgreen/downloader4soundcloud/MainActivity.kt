package com.mvxgreen.downloader4soundcloud

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.mvxgreen.downloader4soundcloud.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val downloadReceiver = DownloadReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAds()
        setupUI()
        setupWebView()
        SoundLoader.prepareFileDirs()
    }

    private fun setupAds() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
        adView.setAdSize(com.google.android.gms.ads.AdSize.BANNER)
        binding.bannerAdContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun setupUI() {
        // Toolbar
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_upgrade -> {
                    // Handle Upgrade
                    true
                }
                else -> false
            }
        }

        // Paste Button
        binding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                binding.mainTextfield.setText(clip.getItemAt(0).text)
            }
        }

        // Text Change Listener
        binding.mainTextfield.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val url = s.toString()
                if (url.contains("soundcloud.com")) {
                    loadUrl(url)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Download Button
        binding.dlBtn.setOnClickListener {
            showDownloadingUI()
            val intent = Intent(this, DownloadService::class.java)
            intent.action = "START_DOWNLOAD"
            startService(intent)
        }
    }

    private fun loadUrl(url: String) {
        showPreparingUI()

        CoroutineScope(Dispatchers.Main).launch {
            val success = SoundLoader.loadHtml(url)
            if (success) {
                // Update UI with Meta
                binding.previewTitle.text = SoundLoader.mTitle
                binding.previewArtist.text = SoundLoader.mArtist

                Glide.with(this@MainActivity)
                    .load(SoundLoader.mThumbnailUrl)
                    .into(binding.previewImg)

                // Load Player URL in hidden WebView to get Client ID
                // Note: In native code, you might trigger the WebView here
                // to hit the stream URL and intercept the client_id via WebViewClient
                binding.previewWebview.loadUrl(SoundLoader.mStreamUrl + "?client_id=YOUR_FALLBACK_ID")
            }
        }
    }

    // WebView Client to intercept Client ID (Logic from MWebViewClient in MAUI)
    private fun setupWebView() {
        binding.previewWebview.settings.javaScriptEnabled = true
        binding.previewWebview.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url.toString()
                if (url.contains("client_id=")) {
                    val clientId = url.substringAfter("client_id=").substringBefore("&")
                    SoundLoader.mClientId = clientId

                    // We found the ID, now we can fetch the JSON
                    CoroutineScope(Dispatchers.Main).launch {
                        val fullStreamUrl = SoundLoader.mStreamUrl + "?client_id=" + clientId
                        SoundLoader.loadJson(fullStreamUrl)
                        showPreviewUI()
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun showPreparingUI() {
        binding.previewCard.animate().alpha(1.0f)
        binding.previewImg.animate().alpha(0.5f)
        binding.loadingLayout.animate().alpha(1.0f)
    }

    private fun showPreviewUI() {
        binding.loadingLayout.animate().alpha(0.0f)
        binding.previewImg.animate().alpha(1.0f)
        binding.downloaderCard.visibility = View.VISIBLE
        binding.downloaderCard.animate().alpha(1.0f)
    }

    private fun showDownloadingUI() {
        binding.dlBtn.visibility = View.INVISIBLE
        // Instead of just showing the ring, show the overlay container
        binding.overlayDownloading.visibility = View.VISIBLE
        binding.progressRingDlr.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 1. Register DownloadManager Receiver (System Broadcast -> EXPORTED)
        // Original C# equivalent: RegisterReceiver(..., ReceiverFlags.Exported) [cite: 1]
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        // 2. Register Finish Receiver (Internal App Broadcast -> NOT_EXPORTED)
        // Original C# equivalent: RegisterReceiver(..., new IntentFilter("69"), ...) [cite: 1]
        ContextCompat.registerReceiver(
            this,
            finishReceiver,
            IntentFilter("DOWNLOAD_FINISHED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(downloadReceiver)
            unregisterReceiver(finishReceiver)
        } catch (e: Exception) {}
    }

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Hide overlay when finished
            binding.overlayDownloading.visibility = View.GONE
            binding.finishBtn.visibility = View.VISIBLE
            binding.finishBtn.animate().alpha(1.0f)
        }
    }
}