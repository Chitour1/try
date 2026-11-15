package app.hvr.renderer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

const val HOST = "https://hvr.app"

class MainActivity : Activity() {
    lateinit var root: FrameLayout
    lateinit var ui: WebView
    lateinit var pkg: Pkg
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newSingleThreadExecutor()
    var renderer: Renderer? = null
    var renderWeb: WebView? = null
    var lastOutput: Uri? = null
    var pendingPick: String? = null
    var fastMode = false
    var lastMessage: String? = null

    companion object {
        const val REQ_PKG = 11
        const val REQ_ASSET = 12
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.enableSlowWholeDocumentDraw()
        pkg = Pkg(this)
        root = FrameLayout(this)
        ui = WebView(this)
        setupWeb(ui)
        ui.addJavascriptInterface(AppBridge(), "AppBridge")
        ui.addJavascriptInterface(EditorBridge(), "AndroidBridge")
        root.addView(ui, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        goHome()
        handleIntent(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWeb(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.allowFileAccess = false
        w.settings.textZoom = 100
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                LocalServer.handle(this@MainActivity, pkg, request)
        }
        w.webChromeClient = WebChromeClient()
    }

    fun goHome() { ui.loadUrl("$HOST/assets/home.html") }

    fun js(code: String) { main.post { ui.evaluateJavascript(code, null) } }

    fun message(text: String) {
        lastMessage = text
        js("window.showMsg && showMsg(${JSONObject.quote(text)})")
    }

    fun progress(pct: Int, text: String) {
        js("window.progress && progress($pct, ${JSONObject.quote(text)})")
    }

    fun callback(id: String, result: String?) {
        js("window.__hostCallback && __hostCallback(${JSONObject.quote(id)}, ${if (result == null) "null" else JSONObject.quote(result)})")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) importPackage(uris)
    }

    fun importPackage(uris: List<Uri>) {
        if (renderer != null) { message("التصيير جارٍ؛ انتظر حتى ينتهي."); return }
        message("جارٍ فتح الحزمة…")
        io.execute {
            try {
                val name = pkg.import(uris)
                main.post {
                    lastMessage = "فُتحت الحزمة: $name"
                    if (fastMode) { goHome(); startRender() } else ui.loadUrl("$HOST/assets/editor/index.html")
                }
            } catch (e: Exception) {
                message("تعذر فتح الحزمة: ${e.message}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PKG) {
            if (resultCode != RESULT_OK || data == null) return
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            else data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importPackage(uris)
        } else if (requestCode == REQ_ASSET) {
            val id = pendingPick ?: return
            pendingPick = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) { callback(id, null); return }
            io.execute {
                val r = try { JSONObject().put("path", pkg.addAsset(uri)).toString() } catch (e: Exception) { null }
                callback(id, r)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val url = ui.url ?: ""
        if (url.contains("/editor/")) {
            ui.evaluateJavascript("(function(){var b=document.getElementById('bCancel'); if(b){b.click(); return 1;} return 0;})()") { r ->
                if (r != "1") goHome()
            }
        } else if (renderer == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    fun startRender() {
        if (renderer != null) return
        if (!File(pkg.root(), "index.html").exists()) { message("لا توجد حزمة مفتوحة."); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastOutput = null
        renderer = Renderer(this, pkg).also { it.start() }
    }

    fun renderFinished(uri: Uri?, error: String?) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderer = null
        if (uri != null) {
            lastOutput = uri
            lastMessage = null
            js("window.showDone && showDone(${JSONObject.quote(pkg.name + ".mp4")})")
        } else {
            message("فشل التصيير: ${error ?: "خطأ غير معروف"}")
        }
    }

    fun saveToGallery(f: File, name: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HtmlVideoRenderer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw Exception("تعذر حفظ الفيديو في المعرض")
        contentResolver.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os) } }
        v.clear()
        v.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        f.delete()
        return uri
    }

    /* ---------------- جسر الصفحة الرئيسية ---------------- */
    inner class AppBridge {
        @JavascriptInterface
        fun state(): String = JSONObject()
            .put("hasPackage", File(pkg.root(), "index.html").exists())
            .put("name", pkg.name)
            .put("rendering", renderer != null)
            .put("done", lastOutput != null)
            .put("message", lastMessage ?: "")
            .toString()

        @JavascriptInterface
        fun pickPackage(fast: Boolean) {
            main.post {
                fastMode = fast
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_PKG)
            }
        }

        @JavascriptInterface
        fun editLast() { main.post { ui.loadUrl("$HOST/assets/editor/index.html") } }

        @JavascriptInterface
        fun renderLast() { main.post { startRender() } }

        @JavascriptInterface
        fun cancelRender() { main.post { renderer?.cancel() } }

        @JavascriptInterface
        fun openOutput() {
            val u = lastOutput ?: return
            main.post {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) { message("لا يوجد تطبيق لعرض الفيديو.") }
            }
        }

        @JavascriptInterface
        fun shareOutput() {
            val u = lastOutput ?: return
            main.post {
                val s = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(s, "مشاركة الفيديو"))
            }
        }
    }

    /* ---------------- جسر واجهة المعاينة والتعديل ---------------- */
    inner class EditorBridge {
        @JavascriptInterface fun pkgBase(): String = "$HOST/pkg/"
        @JavascriptInterface fun getSource(): String = File(pkg.root(), "index.html").readText()
        @JavascriptInterface fun getOriginal(): String {
            val o = File(pkg.root(), "index.original.html")
            return if (o.exists()) o.readText() else getSource()
        }
        @JavascriptInterface fun getConfig(): String = pkg.config().toString()
        @JavascriptInterface fun save(html: String, cfg: String): String =
            try { pkg.save(html, cfg); "ok" } catch (e: Exception) { "err" }

        @JavascriptInterface
        fun pickAsset(id: String, accept: String) {
            main.post {
                pendingPick = id
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (accept.isBlank()) "*/*" else accept)
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_ASSET)
            }
        }

        @JavascriptInterface
        fun exportZip(id: String) {
            io.execute {
                val r = try { pkg.exportZip() } catch (e: Exception) { null }
                callback(id, r)
            }
        }

        @JavascriptInterface fun render() { main.post { goHome(); startRender() } }
        @JavascriptInterface fun cancel() { main.post { goHome() } }
    }
}
