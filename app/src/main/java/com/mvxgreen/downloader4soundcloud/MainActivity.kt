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
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.bumptech.glide.Glide
import com.google.android.gms.ads.*
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.mvxgreen.downloader4soundcloud.databinding.ActivityMainBinding
import com.mvxgreen.downloader4soundcloud.databinding.DialogRateBinding
import com.mvxgreen.downloader4soundcloud.databinding.DialogUpgradeBinding
import kotlinx.coroutines.*
import java.util.regex.Pattern
import kotlin.toString

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val interstitialIdTest = "ca-app-pub-3940256099942544/1033173712" // Test
    private val interstitialIdReal = "ca-app-pub-7417392682402637/8953011072" // Real
    private val interstitialId = interstitialIdTest

    private val bannerIdTest = "ca-app-pub-3940256099942544/6300978111" // Test
    private val bannerIdReal = "ca-app-pub-7417392682402637/2881991548" // Real
    private val bannerId = bannerIdTest

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

    private lateinit var requestNotificationLauncher: androidx.activity.result.ActivityResultLauncher<String>

    // Billing Variables
    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null
    private val PRODUCT_ID = "remove_ads_subs"

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
            binding.btnClear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE

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

        // init permission launcher
        requestNotificationLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            // This block runs immediately after the user clicks Allow/Deny
            if (isGranted) {
                Log.d("MainActivity", "Notifications granted")
            } else {
                Toast.makeText(this, "Notifications are recommended for background downloads", Toast.LENGTH_SHORT).show()
            }

            // --- CHAIN REACTION: NOW REQUEST BATTERY ---
            requestBatteryOptimization()
        }

        initAdMob()
        setupBilling()
        setupToolbarMenu()
        setupListeners()
        setupWebView()

        // Register Receivers
        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, finishReceiver, IntentFilter("DOWNLOAD_FINISHED"), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Check Intent (Sharing)
        updateUI(UIState.EMPTY)
        checkIntent(intent)
    }

    // Since launchMode is singleInstance, new shares will call this if app is already open
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent reference
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                Log.d("MainActivity", "Received Shared Intent: $sharedText")

                SoundLoader.isShared = true

                binding.etMainInput.removeTextChangedListener(textWatcher)
                binding.etMainInput.setText(sharedText)
                binding.etMainInput.addTextChangedListener(textWatcher)

                // 4. Manual Trigger (Only one download starts)
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

        // Clear Button (UPDATED)
        binding.btnClear.setOnClickListener {
            // 1. STOP EVERYTHING
            fetchJob?.cancel()          // Stops the scraping/loading (Coroutines)
            //SoundLoader.cancelBatch(this) // Stops the downloading (Service/Receiver)
            inputHandler.removeCallbacks(inputRunnable) // Stops pending debounce

            binding.previewWebview.stopLoading()
            binding.previewWebview.loadUrl("about:blank") // Optional: clear the visual state
            lastLoadedUrl = ""
            //lastLoadedMediaId = ""

            // 2. Clear Input
            binding.etMainInput.setText("")

            // 3. Reset UI
            updateUI(UIState.EMPTY)

            //Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
        }

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

    private fun updateBackgroundMenuVisibility() {
        val item = binding.toolbar.menu.findItem(R.id.action_enable_background) ?: return
        // Show item ONLY if permissions are missing
        item.isVisible = !hasBackgroundPermissions()
    }

    private fun startBackgroundPermissionChain() {
        // STEP 1: Check Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Request Notifs -> The 'requestNotificationLauncher' callback will handle Step 2
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // If we already have notifications (or are on Android < 13), jump straight to Step 2
        requestBatteryOptimization()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        // STEP 2: Check Battery Optimization (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open background settings", Toast.LENGTH_SHORT).show()
                }
            } else {
                //Toast.makeText(this, "Background setup complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasBackgroundPermissions(): Boolean {
        // 1. Check Notification Permission (Android 13+)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required below Android 13
        }

        // 2. Check Battery Optimization (Android 6+)
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Not required below Android 6
        }

        return notificationGranted && batteryIgnored
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_upgrade -> {
                    showUpgradeDialog()
                    true
                }
                R.id.action_privacy -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/privacy-policy"))
                    startActivity(intent)
                    true
                }
                R.id.action_about -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mobileapps.green/"))
                    startActivity(intent)
                    true
                }
                // NEW CASE
                R.id.action_enable_background -> {
                    startBackgroundPermissionChain()
                    true
                }
                else -> false
            }
        }
    }

    private fun logInputEvent(eventName: String) {
        val inputValue = binding.etMainInput.text.toString()
        val bundle = Bundle().apply {
            putString("input_value", inputValue)
        }
        firebaseAnalytics.logEvent(eventName, bundle)
        Log.d("Analytics", "Logged event: $eventName with value: $inputValue")
    }

    private fun handleInput(rawInput: String) {
        logInputEvent("soundloader_input")

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
            input = matcher.group(1) ?: rawInput // We found a URL, replace input with just the URL
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

    private fun loadMediaData(url: String) {
        fetchJob = CoroutineScope(Dispatchers.Main).launch {
            val success = SoundLoader.loadHtml(url)

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

                // FIX: Load the Extracted Player URL (Widget) to get the correct Client ID [cite: 2]
                if (SoundLoader.mPlayerUrl.isNotEmpty()) {
                    SoundLoader.mClientId = ""
                    Log.d("MainActivity", "Loading Widget URL: ${SoundLoader.mPlayerUrl}")
                    binding.previewWebview.loadUrl(SoundLoader.mPlayerUrl)
                } else {
                    Log.e("MainActivity", "Player URL missing")
                    updateUI(UIState.EMPTY)
                }

            } else {
                Toast.makeText(this@MainActivity, "Could not load track data", Toast.LENGTH_SHORT).show()
                updateUI(UIState.EMPTY)
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
                // ... (Existing shortlink logic remains here) ...
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()

                // 1. Check if the URL contains the client_id we need
                if (url.contains("client_id=") && SoundLoader.mClientId.isEmpty() && SoundLoader.mStreamUrl.isNotEmpty()) {

                    // 2. Extract the Client ID
                    val id = url.substringAfter("client_id=").substringBefore("&")
                    SoundLoader.mClientId = id
                    Log.d("MainActivity", "Intercepted Client ID: $id")

                    // 3. CALL LOADJSON HERE
                    // We must jump back to the Main thread to launch the coroutine and update UI
                    CoroutineScope(Dispatchers.Main).launch {
                        // Construct the authorized JSON URL
                        val fullUrl = "${SoundLoader.mStreamUrl}?client_id=$id"

                        // Parse the JSON to get the .m3u8 playlist URL
                        SoundLoader.loadJson(fullUrl)

                        // 4. Now that we have the Playlist URL, we can finish the UI state
                        if (SoundLoader.isShared) {
                            startDownloadService()
                        } else {
                            updateUI(UIState.PREVIEW)
                        }
                    }
                }
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

                logInputEvent("soundloader_finished")
                incrementSuccessfulRuns()
            }
        }
    }

    private fun incrementSuccessfulRuns() {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)

        // 1. Increment Counter
        val currentCount = prefs.getInt("SUCCESS_RUNS", 0) + 1
        prefs.edit().putInt("SUCCESS_RUNS", currentCount).apply()

        Log.d("MainActivity", "Successful Runs: $currentCount")

        // 2. Check if multiple of 6
        if (currentCount > 0 && currentCount % 6 == 0) {
            val cycle = currentCount / 6

            // Odd cycles (1, 3, 5... -> runs 6, 18, 30): Show Upgrade
            // Even cycles (2, 4, 6... -> runs 12, 24, 36): Show Rate
            if (cycle % 2 != 0) {
                // Check if user is already Gold before annoying them with Upgrade dialog
                val isGold = prefs.getBoolean("IS_GOLD", false)
                if (!isGold) {
                    showUpgradeDialog()
                }
            } else {
                showRateDialog()
            }
        }
    }

    private fun showRateDialog() {
        // Inflate the Rate Dialog layout
        val rateBinding = DialogRateBinding.inflate(layoutInflater)

        val builder = AlertDialog.Builder(this)
            .setView(rateBinding.root)
            .setCancelable(true)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // "Nah" Button -> Dismiss
        rateBinding.btnNah.setOnClickListener {
            dialog.dismiss()
        }

        // "Rate" Button (ID is btnUpgrade in your xml) -> Open Play Store
        rateBinding.btnRate.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            }
        }

        dialog.show()
    }

    private fun showUpgradeDialog() {
        // Inflate the Dialog layout using Binding
        val dialogBinding = DialogUpgradeBinding.inflate(layoutInflater)

        val builder = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bind Dialog Listeners directly
        //dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnNah.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnUpgrade.setOnClickListener {
            dialog.dismiss()
            launchBillingFlow()
        }

        dialog.show()
    }

    // --- BILLING LOGIC ---

    private fun setupBilling() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        startBillingConnection()
    }

    private fun startBillingConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryActivePurchases()
                }
            }
            override fun onBillingServiceDisconnected() { }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { billingResult, detailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK
                && detailsResult.productDetailsList.isNotEmpty()
            ) {
                productDetails = detailsResult.productDetailsList[0]
            }
        }
    }

    private fun queryActivePurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isGold = false
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isGold = true
                        if (!purchase.isAcknowledged) handlePurchase(purchase)
                    }
                }
                saveGoldStatus(isGold)
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) handlePurchase(purchase)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        runOnUiThread {
                            Toast.makeText(this, "Thank you for your support <3", Toast.LENGTH_SHORT).show()
                            saveGoldStatus(true)
                            recreate()
                        }
                    }
                }
            } else {
                saveGoldStatus(true)
            }
        }
    }

    private fun saveGoldStatus(isGold: Boolean) {
        val prefs = getSharedPreferences("com.xxxgreen.mvx.prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("IS_GOLD", isGold).apply()
        checkSubscriptionAndLoadAds(isGold)
        runOnUiThread { updateUpgradeIcon(isGold) }
    }

    private fun checkSubscriptionAndLoadAds(isGold: Boolean) {
        if (!isGold) {
            initAdMob()
        } else {
            binding.adContainer.removeAllViews()
            binding.adContainer.visibility = View.INVISIBLE
        }
    }

    private fun launchBillingFlow() {
        if (productDetails != null) {
            val offerToken = productDetails!!.subscriptionOfferDetails?.get(0)?.offerToken ?: ""
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails!!)
                    .setOfferToken(offerToken)
                    .build()
            )
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
            billingClient.launchBillingFlow(this, billingFlowParams)
        } else {
            Toast.makeText(this, "Billing not ready yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUpgradeIcon(isGold: Boolean) {
        val upgradeItem = binding.toolbar.menu.findItem(R.id.action_upgrade)
        if (upgradeItem != null) {
            if (isGold) {
                upgradeItem.icon?.setTint(Color.parseColor("#FFD700"))
                upgradeItem.isEnabled = false
            } else {
                upgradeItem.icon?.setTintList(null)
                upgradeItem.isEnabled = true
            }
        }
    }

    private fun initAdMob() {
        MobileAds.initialize(this) {}
        val adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = bannerId
        binding.adContainer.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
        unregisterReceiver(finishReceiver)
    }
}