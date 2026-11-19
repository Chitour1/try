package com.chitour.familycast

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import org.json.JSONArray
import org.json.JSONTokener

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var foundBtn: Button
    @Volatile private var pageUrl = ""
    private val found = LinkedHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val scanLoop = object : Runnable {
        override fun run() { scanDom(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "عرض عائلي"
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = vbox()
        val top = hbox().apply { setPadding(dp(4), dp(4), dp(4), 0) }
        urlBox = EditText(this).apply {
            hint = "عنوان موقع أو رابط فيديو أو كلمة بحث"
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_GO; textSize = 14f
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        top.add(urlBox, 1f)
        top.add(btn("اذهب") { go() })
        val route = MediaRouteButton(this)
        try { CastButtonFactory.setUpMediaRouteButton(applicationContext, route) } catch (e: Exception) { }
        top.add(route)
        root.add(top)

        web = WebView(this)
        root.add(web, 1f)

        val bottom = hbox()
        foundBtn = btn("فيديوهات مكتشفة (0)") { showFound() }
        bottom.add(foundBtn, 1.4f)
        bottom.add(btn("أفلامي") { startActivity(Intent(this, MoviesActivity::class.java)) }, 1f)
        bottom.add(btn("رابط مباشر") { askDirect() }, 1f)
        root.add(bottom)
        setContentView(root)

        setupWeb()
        if (!handleShare(intent)) web.loadUrl("https://www.google.com")
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        val url = Regex("""https?://\S+""").find(text)?.value ?: return false
        if (Util.isVideoUrl(url)) newMovie(url, "", "", false) else web.loadUrl(url)
        return true
    }

    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: ""
                return !(s == "http" || s == "https")
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url; urlBox.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String) { scanDom() }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (Util.isVideoUrl(u)) {
                    val ref = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"] ?: pageUrl
                    runOnUiThread { addFound(u, ref) }
                }
                return null
            }
        }
    }

    private fun scanDom() {
        if (!::web.isInitialized) return
        val js = "(function(){try{return JSON.stringify(Array.from(document.querySelectorAll('video,video source')).map(function(v){return v.currentSrc||v.src}).filter(function(s){return s&&s.indexOf('http')==0}))}catch(e){return '[]'}})()"
        web.evaluateJavascript(js) { res ->
            try {
                val inner = JSONTokener(res).nextValue() as? String ?: return@evaluateJavascript
                val arr = JSONArray(inner)
                for (i in 0 until arr.length()) addFound(arr.getString(i), pageUrl)
            } catch (e: Exception) { }
        }
    }

    private fun addFound(u: String, ref: String) {
        if (found.containsKey(u)) return
        found[u] = ref
        foundBtn.text = "فيديوهات مكتشفة (${found.size})"
        if (found.size == 1) toast("عُثر على فيديو في الصفحة. اضغط «فيديوهات مكتشفة»")
    }

    private fun shortName(u: String): String {
        val uri = Uri.parse(u)
        val last = uri.lastPathSegment ?: ""
        return "${uri.host ?: ""} … ${last.takeLast(40)}"
    }

    private fun showFound() {
        if (found.isEmpty()) { toast("لم يُكتشف فيديو بعد. افتح صفحة الفيلم وشغّله فيها أولًا"); return }
        val keys = found.keys.toList().reversed()
        AlertDialog.Builder(this).setTitle("اختر الفيديو (الأحدث أولًا)")
            .setItems(keys.map { shortName(it) }.toTypedArray()) { _, i -> chooseTarget(keys[i], found[keys[i]] ?: "") }
            .setNeutralButton("مسح القائمة") { _, _ -> found.clear(); foundBtn.text = "فيديوهات مكتشفة (0)" }
            .show()
    }

    private fun chooseTarget(u: String, ref: String) {
        val ua = web.settings.userAgentString ?: ""
        val names = listOf("➕ فيلم جديد") + Store.movies.map { "ربطه بقائمة: ${it.title}" }
        AlertDialog.Builder(this).setTitle("أين تضع هذا الفيديو؟")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) newMovie(u, ref, ua, true)
                else {
                    val m = Store.movies[i - 1]
                    m.url = u; m.referer = ref; m.userAgent = ua; m.useProxy = true
                    Store.save(); open(m)
                }
            }.show()
    }

    private fun askDirect() {
        val box = EditText(this).apply { hint = "الصق رابط الفيديو المباشر (mp4 أو m3u8)" }
        AlertDialog.Builder(this).setTitle("رابط مباشر").setView(box)
            .setPositiveButton("متابعة") { _, _ ->
                val u = box.text.toString().trim()
                if (u.startsWith("http")) newMovie(u, "", "", false) else toast("الرابط غير صحيح")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun newMovie(u: String, ref: String, ua: String, proxy: Boolean) {
        val box = EditText(this).apply {
            setText(web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "فيلم جديد")
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("اسم الفيلم").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val m = Movie(System.currentTimeMillis().toString(), box.text.toString().ifBlank { "فيلم" }, u, ref, ua, proxy)
                Store.add(m); open(m)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun open(m: Movie) = startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id))

    private fun go() {
        var t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        if (Util.isVideoUrl(t)) { newMovie(t, pageUrl, web.settings.userAgentString ?: "", false); return }
        if (!t.startsWith("http")) {
            t = if (t.contains('.') && !t.contains(' ')) "https://$t"
                else "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(t)
    }

    override fun onResume() { super.onResume(); handler.postDelayed(scanLoop, 3000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(scanLoop) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
