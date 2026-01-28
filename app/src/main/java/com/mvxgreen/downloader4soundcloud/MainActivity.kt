package com.mvxgreen.downloader4soundcloud

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.android.billingclient.api.*
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.mvxgreen.downloader4soundcloud.databinding.ActivityMainBinding
import com.mvxgreen.downloader4soundcloud.databinding.DialogRateBinding
import com.mvxgreen.downloader4soundcloud.databinding.DialogUpgradeBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val interstitialIdTest = "ca-app-pub-3940256099942544/1033173712"
    private val interstitialIdReal = "ca-app-pub-7417392682402637/8953011072"
    private val bannerIdTest = "ca-app-pub-3940256099942544/6300978111"
    private val bannerIdReal = "ca-app-pub-7417392682402637/2881991548"
    private val bannerId = bannerIdTest

    private var fetchJob: Job? = null
    private val VALID_INPUT_REGEX = Pattern.compile("^$|((?:on\\.|m\\.|www\\.)?soundcloud\\.com\\/)", Pattern.CASE_INSENSITIVE)
    private val EXTRACT_URL_REGEX = Pattern.compile("(https?://(?:on\\.|www\\.|m\\.)?soundcloud\\.com/[^\\s]*)", Pattern.CASE_INSENSITIVE)

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null
    private val PRODUCT_ID = "remove_ads_subs"

    private val inputHandler = Handler(Looper.getMainLooper())
    private val inputRunnable = Runnable {
        val text = binding.etMainInput.text.toString()
        if (VALID_INPUT_REGEX.matcher(text).find() && text.isNotEmpty()) handleInput(text)
    }

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            inputHandler.removeCallbacks(inputRunnable)
            if (s.isNullOrEmpty()) updateUI(UIState.EMPTY) else inputHandler.postDelayed(inputRunnable, 1000)
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    enum class UIState { EMPTY, LOADING, PREVIEW, DOWNLOADING, FINISHED }

    private val downloadReceiver = DownloadReceiver()
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI(UIState.FINISHED)
            Toast.makeText(context, "Saved to Documents!", Toast.LENGTH_SHORT).show()
            if (SoundLoader.isShared) {
                SoundLoader.isShared = false
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundLoader.resetVars()
        SoundLoader.isShared = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FirebaseApp.initializeApp(this)
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        SoundLoader.prepareFileDirs()

        requestNotificationLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) Toast.makeText(this, "Notifications are recommended", Toast.LENGTH_SHORT).show()
            requestBatteryOptimization()
        }
        startBackgroundPermissionChain()

        initAdMob()
        setupBilling()
        setupToolbarMenu()
        setupListeners()
        setupWebView()

        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
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
            // 1. STOP EVERYTHING
            fetchJob?.cancel()
            inputHandler.removeCallbacks(inputRunnable)

            binding.previewWebview.stopLoading()
            binding.previewWebview.loadUrl("about:blank")
            //lastLoadedUrl = ""

            // 2. Clear Input
            binding.etMainInput.setText("")

            // 3. CRITICAL FIX: Reset SoundLoader state (clears mClientId and flags)
            SoundLoader.resetVars()

            // 4. Reset UI
            updateUI(UIState.EMPTY)
        }
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text.toString()
                binding.etMainInput.setText(text)
                inputHandler.removeCallbacks(inputRunnable)
                handleInput(text)
            }
        }
        binding.dlBtn.setOnClickListener { startDownloadService() }
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
                R.id.action_upgrade -> { showUpgradeDialog(); true }
                R.id.action_privacy -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/privacy-policy"))); true }
                R.id.action_about -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/"))); true }
                R.id.action_enable_background -> { startBackgroundPermissionChain(); true }
                else -> false
            }
        }
    }

    private fun handleInput(rawInput: String) {
        inputHandler.removeCallbacksAndMessages(null)
        fetchJob?.cancel()

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
        if (url.contains("/sets/")) {
            SoundLoader.isPlaylist = true
            loadMediaData(url)
        } else {
            SoundLoader.isPlaylist = false
            loadMediaData(url)
        }
    }

    private fun loadMediaData(url: String) {
        SoundLoader.mStreamUrl = ""

        CoroutineScope(Dispatchers.Main).launch {
            val success = SoundLoader.loadHtml(url)
            if (success) {
                // Common: Load Image & Widget
                if (!isDestroyed && !isFinishing) {
                    Glide.with(this@MainActivity)
                        .load(SoundLoader.mThumbnailUrl)
                        .centerCrop()
                        .into(binding.previewImg)
                }

                // Only load WebView if we actually need to find a key or playing
                if (SoundLoader.mPlayerUrl.isNotEmpty() && SoundLoader.mClientId.isEmpty()) {
                    binding.previewWebview.loadUrl(SoundLoader.mPlayerUrl)
                }

                // SPLIT LOGIC
                if (SoundLoader.isPlaylist) {
                    binding.previewTitle.text = "Playlist: ${SoundLoader.mTitle}"
                    binding.previewArtist.text = "Loading tracks…"
                    binding.dlBtn.setImageResource(R.drawable.ic_download)

                    // FIX: If loadHtml found the key, fetch immediately! Don't wait for WebView.
                    if (SoundLoader.mClientId.isNotEmpty()) {
                        Log.d("MainActivity", "Key found during scrape. Fetching playlist immediately.")
                        val fetchSuccess = withContext(Dispatchers.IO) {
                            SoundLoader.processPlaylistWithKey(SoundLoader.mClientId)
                        }

                        if (fetchSuccess) {
                            binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks"
                            if (SoundLoader.isShared) startDownload() else updateUI(UIState.PREVIEW)
                        } else {
                            // Fallback to WebView if direct fetch failed (rare)
                            if (SoundLoader.mPlayerUrl.isNotEmpty()) binding.previewWebview.loadUrl(SoundLoader.mPlayerUrl)
                        }
                    }
                    // Else: Stay in LOADING state. The WebView interceptor will handle it.

                } else {
                    // SINGLE TRACK
                    binding.previewTitle.text = SoundLoader.mTitle
                    binding.previewArtist.text = SoundLoader.mArtist
                    if (SoundLoader.isShared) startDownload() else updateUI(UIState.PREVIEW)
                }
            } else {
                updateUI(UIState.EMPTY)
                Toast.makeText(this@MainActivity, "Load Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownload() {
        updateUI(UIState.DOWNLOADING)

        if (SoundLoader.isPlaylist) {
            SoundLoader.isBatchActive = true
            if (SoundLoader.playlistM3uUrls.isNotEmpty()) {
                SoundLoader.mM3uUrl = SoundLoader.playlistM3uUrls.removeAt(0)
                if (SoundLoader.playlistTags.isNotEmpty()) {
                    val tag = SoundLoader.playlistTags.removeAt(0)
                    SoundLoader.mTitle = tag["title"] ?: ""
                    SoundLoader.mArtist = tag["artist"] ?: ""
                    SoundLoader.mThumbnailUrl = tag["artwork_url"] ?: ""
                }
                val intent = Intent(this, DownloadService::class.java)
                intent.action = "START_DOWNLOAD"
                startService(intent)
            } else {
                Toast.makeText(this, "No downloadable tracks found!", Toast.LENGTH_LONG).show()
                updateUI(UIState.PREVIEW)
                SoundLoader.isBatchActive = false
            }
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                if (SoundLoader.mStreamUrl.isNotEmpty() && SoundLoader.mM3uUrl.isEmpty()) {
                    val url = "${SoundLoader.mStreamUrl}?client_id=${SoundLoader.mClientId}"
                    SoundLoader.loadJson(url)
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
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

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
                            // Fetch Tracks
                            val success = SoundLoader.processPlaylistWithKey(id)
                            if (success) {
                                binding.previewArtist.text = "${SoundLoader.batchTotal} Tracks"
                                // CRITICAL FIX: Trigger Shared Download HERE for playlists
                                if (SoundLoader.isShared) startDownload() else updateUI(UIState.PREVIEW)
                            } else {
                                Toast.makeText(this@MainActivity, "Playlist fetch failed", Toast.LENGTH_SHORT).show()
                                updateUI(UIState.EMPTY)
                            }
                        } else if (SoundLoader.mStreamUrl.isNotEmpty()) {
                            val fullUrl = "${SoundLoader.mStreamUrl}?client_id=$id"
                            SoundLoader.loadJson(fullUrl)
                            // Note: Single tracks trigger in loadMediaData, but this ensures M3U is ready
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun startDownloadService() {
        updateUI(UIState.DOWNLOADING)
        startDownload()
    }

    private fun updateUI(state: UIState) {
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
                binding.dlBtn.visibility = View.VISIBLE
                binding.finishBtn.visibility = View.GONE
            }
            UIState.DOWNLOADING -> {
                binding.loadingLayout.visibility = View.INVISIBLE
                binding.previewCard.visibility = View.VISIBLE
                binding.downloaderCard.visibility = View.VISIBLE
                binding.overlayDownloading.visibility = View.VISIBLE
                binding.dlBtn.visibility = View.INVISIBLE
                binding.etMainInput.isEnabled = false
                binding.btnPaste.isEnabled = false
                binding.btnPaste.alpha = 0.5f
            }
            UIState.FINISHED -> {
                binding.overlayDownloading.visibility = View.GONE
                binding.finishBtn.visibility = View.VISIBLE
                binding.finishBtn.animate().alpha(1.0f)
                incrementSuccessfulRuns()
            }
        }
    }

    // ... (Keep incrementSuccessfulRuns, showRateDialog, showUpgradeDialog, setupBilling, etc.) ...

    private fun incrementSuccessfulRuns() {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("SUCCESS_RUNS", 0) + 1
        prefs.edit().putInt("SUCCESS_RUNS", currentCount).apply()
        if (currentCount > 0 && currentCount % 6 == 0) {
            val cycle = currentCount / 6
            if (cycle % 2 != 0) {
                if (!prefs.getBoolean("IS_GOLD", false)) showUpgradeDialog()
            } else {
                showRateDialog()
            }
        }
    }

    private fun showRateDialog() {
        val rateBinding = DialogRateBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(rateBinding.root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        rateBinding.btnNah.setOnClickListener { dialog.dismiss() }
        rateBinding.btnRate.setOnClickListener {
            dialog.dismiss()
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))) }
            catch (e: ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))) }
        }
        dialog.show()
    }

    private fun showUpgradeDialog() {
        val dialogBinding = DialogUpgradeBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogBinding.btnNah.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnUpgrade.setOnClickListener { dialog.dismiss(); launchBillingFlow() }
        dialog.show()
    }

    private fun setupBilling() {
        billingClient = BillingClient.newBuilder(this).setListener(purchasesUpdatedListener).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()
        startBillingConnection()
    }
    private fun startBillingConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) { if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) { queryProductDetails(); queryActivePurchases() } }
            override fun onBillingServiceDisconnected() {}
        })
    }
    private fun queryProductDetails() {
        val productList = listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build())
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(productList).build()) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details.productDetailsList.isNotEmpty()) productDetails = details.productDetailsList[0]
        }
    }
    private fun queryActivePurchases() {
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                var isGold = false
                for (purchase in purchases) if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) isGold = true
                saveGoldStatus(isGold)
            }
        }
    }
    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) for (p in purchases) handlePurchase(p)
    }
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {
                if (it.responseCode == BillingClient.BillingResponseCode.OK) runOnUiThread { Toast.makeText(this, "Thank you!", Toast.LENGTH_SHORT).show(); saveGoldStatus(true); recreate() }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) saveGoldStatus(true)
    }
    private fun saveGoldStatus(isGold: Boolean) {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("IS_GOLD", isGold).apply()
        checkSubscriptionAndLoadAds(isGold)
    }
    private fun checkSubscriptionAndLoadAds(isGold: Boolean) {
        if (!isGold) initAdMob() else { binding.adContainer.removeAllViews(); binding.adContainer.visibility = View.INVISIBLE }
    }
    private fun launchBillingFlow() {
        if (productDetails != null) {
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails!!).setOfferToken(productDetails!!.subscriptionOfferDetails?.get(0)?.offerToken ?: "").build())).build()
            billingClient.launchBillingFlow(this, params)
        }
    }
    private fun initAdMob() {
        MobileAds.initialize(this) {}
        val adView = AdView(this); adView.setAdSize(AdSize.BANNER); adView.adUnitId = bannerId
        binding.adContainer.addView(adView); adView.loadAd(AdRequest.Builder().build())
    }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(downloadReceiver); unregisterReceiver(finishReceiver) }
}