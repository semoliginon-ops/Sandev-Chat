package com.sandev.life

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val nativeExecutor = Executors.newSingleThreadExecutor()
    private val pickModelRequest = 7101
    private var pendingPicker = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            databaseEnabled = false
        }
        webView.addJavascriptInterface(NativeBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != "appassets.androidplatform.net"
            }
        }
        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun emit(js: String) {
        if (!::webView.isInitialized) return
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(js, null) }
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    inner class NativeBridge {
        @JavascriptInterface
        fun pickModel() {
            runOnUiThread {
                if (pendingPicker) return@runOnUiThread
                pendingPicker = true
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, "Pilih model AI (.gguf)")
                }
                startActivityForResult(intent, pickModelRequest)
            }
        }

        @JavascriptInterface
        fun loadModel(path: String, contextSize: Int, threads: Int, requestId: Int) {
            nativeExecutor.execute {
                val modelsDir = File(filesDir, "models").canonicalFile
                val modelFile = File(path).canonicalFile
                val result = if (!modelFile.path.startsWith(modelsDir.path + File.separator) || !modelFile.isFile) {
                    "ERROR: File model lokal tidak valid. Pilih ulang file GGUF."
                } else {
                    LlamaNative.nativeLoadModel(modelFile.absolutePath, contextSize, threads)
                }
                emit("window.onNativeLoadResult($requestId, ${jsString(result)});")
            }
        }

        @JavascriptInterface
        fun generate(prompt: String, maxTokens: Int, requestId: Int) {
            nativeExecutor.execute {
                val result = LlamaNative.nativeGenerate(prompt, maxTokens)
                emit("window.onNativeInferenceResult($requestId, ${jsString(result)});")
            }
        }

        @JavascriptInterface
        fun unloadModel(requestId: Int) {
            nativeExecutor.execute {
                val result = LlamaNative.nativeUnloadModel()
                emit("window.onNativeUnloadResult($requestId, ${jsString(result)});")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "selected-model.gguf"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && it.moveToFirst()) name = it.getString(column) ?: name
        }
        return name
    }

    @Deprecated("Deprecated in Android API, retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickModelRequest) return
        pendingPicker = false
        if (resultCode != RESULT_OK || data?.data == null) {
            emit("window.onNativeModelSelected('', 'Pemilihan model dibatalkan.');")
            return
        }
        val uri = data.data!!
        val originalName = displayName(uri)
        if (!originalName.lowercase().endsWith(".gguf")) {
            emit("window.onNativeModelSelected('', 'File harus berformat .gguf.');")
            return
        }
        nativeExecutor.execute {
            try {
                val modelsDir = File(filesDir, "models")
                if (!modelsDir.exists() && !modelsDir.mkdirs()) throw IllegalStateException("Folder model lokal tidak bisa dibuat.")
                val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = File(modelsDir, safeName)
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalStateException("File model tidak bisa dibaca.")
                    FileOutputStream(destination).use { output -> input.copyTo(output, 1024 * 1024) }
                }
                if (destination.length() == 0L) throw IllegalStateException("File model kosong.")
                emit("window.onNativeModelSelected(${jsString(destination.canonicalPath)}, '');")
            } catch (e: Exception) {
                emit("window.onNativeModelSelected('', ${jsString(e.message ?: "Gagal menyalin model dari penyimpanan.")});")
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.stopLoading()
            webView.destroy()
        }
        nativeExecutor.shutdownNow()
        super.onDestroy()
    }
}
