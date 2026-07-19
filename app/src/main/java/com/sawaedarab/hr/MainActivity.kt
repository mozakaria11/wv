package com.sawaedarab.hr

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineLayout: View

    private val siteUrl = "https://hr.sawaedarab.com/"
    private val allowedHost = "hr.sawaedarab.com"

    // رفع الملفات (صور / مستندات)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // طلبات الموقع الجغرافي
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data == null || data.data == null) {
                // التقاط من الكاميرا
                cameraPhotoPath?.let { path ->
                    results = arrayOf(Uri.fromFile(File(path)))
                }
            } else {
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    results = arrayOf(data.data!!)
                }
            }
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        geoCallback?.invoke(geoOrigin, granted, false)
        geoCallback = null
        geoOrigin = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* النتيجة تُعالج تلقائيًا عبر onShowFileChooser عند إعادة المحاولة */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineLayout = findViewById(R.id.offlineLayout)

        setupWebView()
        setupSwipeRefresh()
        setupOfflineRetry()
        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadSite()
        }
    }

    private fun loadSite() {
        if (isOnline()) {
            offlineLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(siteUrl)
        } else {
            showOffline()
        }
    }

    @Suppress("DEPRECATION")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(true)
        settings.allowFileAccess = true
        settings.userAgentString = settings.userAgentString + " HRSawaedArabApp/1.0"

        // السماح بحفظ الجلسة (تسجيل الدخول) بين مرات فتح التطبيق
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val host = url.host ?: ""

                // إبقاء تصفح موقع الموارد البشرية داخل التطبيق
                if (host.contains(allowedHost)) return false

                // روابط خارجية: اتصال، بريد، واتساب، أو أي تطبيق آخر تُفتح خارجيًا
                return try {
                    when (url.scheme) {
                        "tel", "mailto", "sms", "whatsapp", "intent", "market" -> {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                            true
                        }
                        else -> {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                            true
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "تعذّر فتح الرابط", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    showOffline()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            // دعم رفع الملفات (مستندات، صور) من صفحات الموقع
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val needsCamera = fileChooserParams.acceptTypes.any { it.contains("image") } ||
                        fileChooserParams.isCaptureEnabled

                if (needsCamera && !hasCameraPermission()) {
                    cameraPermissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA)
                    )
                }

                val intents = mutableListOf<Intent>()

                if (hasCameraPermission()) {
                    val photoFile = createImageFile()
                    if (photoFile != null) {
                        cameraPhotoPath = photoFile.absolutePath
                        val photoUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                        cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        intents.add(cameraIntent)
                    }
                }

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentIntent.type = "*/*"
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "اختر ملفًا")
                if (intents.isNotEmpty()) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                }

                filePickerLauncher.launch(chooserIntent)
                return true
            }

            // دعم إذن الموقع الجغرافي (لاعتماد العمل الإضافي عبر GPS)
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false)
                } else {
                    geoOrigin = origin
                    geoCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        // تنزيل الملفات (تصدير PDF / Excel) عبر مدير التنزيلات في النظام
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("جارٍ تنزيل الملف...")
                val fileName = URLUtilGuessFileName(url, contentDisposition, mimeType)
                request.setTitle(fileName)
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "جارٍ تنزيل: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر بدء التنزيل", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun URLUtilGuessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
    }

    private fun createImageFile(): File? {
        return try {
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("IMG_", ".jpg", storageDir)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasCameraPermission() = ContextCompatCheck(Manifest.permission.CAMERA)
    private fun hasLocationPermission() = ContextCompatCheck(Manifest.permission.ACCESS_FINE_LOCATION) ||
            ContextCompatCheck(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun ContextCompatCheck(permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.app_orange)
        swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
    }

    private fun setupOfflineRetry() {
        val retryButton = findViewById<Button>(R.id.retryButton)
        retryButton.setOnClickListener { loadSite() }
    }

    private fun showOffline() {
        webView.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        (webView.parent as? FrameLayout)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
