package com.example.hybridemulator

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Boots the real, offline v86 x86 emulation engine (bundled as local assets
 * at build time — see .github/workflows/build.yml) against a boot image the
 * user picked from device storage.
 *
 * The picked content:// image is never copied or fully read into memory.
 * Instead, a WebViewClientCompat.shouldInterceptRequest override serves it
 * to the page as a virtual HTTPS resource with real HTTP Range support, so
 * v86's own on-demand sector loading can stream arbitrarily large ISO/IMG
 * files directly from storage.
 */
class OfflineVmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_RAM_MB = "extra_ram_mb"
        const val EXTRA_KIND = "extra_kind" // "cdrom" or "hda"

        private const val VIRTUAL_HOST = "appassets.androidplatform.net"
        private const val IMAGE_PATH_PREFIX = "/iso/"
    }

    private lateinit var webView: WebView

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val ramMb = intent.getIntExtra(EXTRA_RAM_MB, 1024)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "cdrom"

        if (imageUriString == null) {
            Toast.makeText(this, "No boot image selected", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val imageUri = Uri.parse(imageUriString)
        val imageSize = queryFileSize(imageUri)
        if (imageSize <= 0L) {
            Toast.makeText(this, "Unable to read the selected image file", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(buildRootView(ramMb))
        configureWebView(imageUri, imageSize, ramMb, kind)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun buildRootView(ramMb: Int): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary_dark))
            setPadding(dp(4), dp(10), dp(16), dp(10))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(Color.WHITE)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = "Offline Virtual Machine · ${ramMb} MB RAM"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        webView = WebView(this)
        webContainer.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(webContainer)

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(imageUri: Uri, imageSize: Long, ramMb: Int, kind: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                return if (url.host == VIRTUAL_HOST && url.path?.startsWith(IMAGE_PATH_PREFIX) == true) {
                    serveImageRange(imageUri, imageSize, request)
                } else {
                    assetLoader.shouldInterceptRequest(url)
                }
            }
        }

        val query = "ram=$ramMb&kind=$kind&size=$imageSize"
        webView.loadUrl("https://$VIRTUAL_HOST/assets/v86/offline.html?$query")
    }

    /**
     * Serves the picked boot image as a virtual HTTPS resource, honoring
     * HTTP Range requests so v86's async sector loader can read arbitrarily
     * large files without ever buffering them fully in memory.
     */
    private fun serveImageRange(imageUri: Uri, totalSize: Long, request: WebResourceRequest): WebResourceResponse {
        val rangeHeader = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value

        var start = 0L
        var end = totalSize - 1
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            end = spec.getOrNull(1)?.toLongOrNull()?.takeIf { it in start until totalSize } ?: (totalSize - 1)
            isPartial = true
        }

        val length = (end - start + 1).coerceAtLeast(0L)

        val body: InputStream = try {
            openBoundedStream(imageUri, start, length)
        } catch (e: IOException) {
            return WebResourceResponse(
                "text/plain", "UTF-8", 500, "Internal Server Error",
                emptyMap(), textErrorStream("Failed to read image: ${e.message}")
            )
        }

        val headers = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to length.toString(),
            "Cache-Control" to "no-store"
        )

        return if (isPartial) {
            headers["Content-Range"] = "bytes $start-$end/$totalSize"
            WebResourceResponse("application/octet-stream", null, 206, "Partial Content", headers, body)
        } else {
            WebResourceResponse("application/octet-stream", null, 200, "OK", headers, body)
        }
    }

    /**
     * Opens a read-only file descriptor on the content:// image and returns
     * a bounded InputStream positioned at [start] that yields exactly
     * [length] bytes, closing the underlying descriptor when the stream is
     * closed (i.e. once the WebView engine has consumed the response body).
     */
    private fun openBoundedStream(uri: Uri, start: Long, length: Long): InputStream {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open image file")
        val fis = FileInputStream(pfd.fileDescriptor)
        fis.channel.position(start)

        return object : InputStream() {
            private var remaining = length

            override fun read(): Int {
                if (remaining <= 0) return -1
                val value = fis.read()
                if (value != -1) remaining--
                return value
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val read = fis.read(b, off, toRead)
                if (read > 0) remaining -= read
                return read
            }

            override fun close() {
                fis.close()
                pfd.close()
            }
        }
    }

    private fun textErrorStream(text: String) = java.io.ByteArrayInputStream(text.toByteArray())

    private fun queryFileSize(uri: Uri): Long {
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Exception) {
            // Fall through to the cursor-based lookup below.
        }
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) return cursor.getLong(index)
                }
            }
        } catch (_: Exception) {
            // Give up; caller handles the <= 0 sentinel.
        }
        return -1L
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.apply {
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
