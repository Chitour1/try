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
    private val found = LinkedHashMap<String, Found>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
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

    /** يستقبل الروابط المرسلة من تطبيقات الأفلام («فتح باستخدام») أو المشاركة */
    private fun handleShare(i: Intent?): Boolean {
        if (i == null) return false
        var url: String? = null
        var title: String? = null
        var ref = ""
        var ua = ""
        val ex = i.extras
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val d = i.data ?: return false
                if (d.scheme == "wvc-x-callback" || d.scheme == "familycast") {
                    url = d.getQueryParameter("url") ?: d.getQueryParameter("uri")
                    title = d.getQueryParameter("title") ?: d.getQueryParameter("filename")
                    ref = d.getQueryParameter("referer") ?: d.getQueryParameter("referrer") ?: ""
                    ua = d.getQueryParameter("user-agent") ?: d.getQueryParameter("useragent") ?: ""
                    for (h in d.getQueryParameters("header") + d.getQueryParameters("headers")) {
                        val k = h.substringBefore(':').trim().lowercase(); val v = h.substringAfter(':', "").trim()
                        if (k == "referer") ref = v
                        if (k == "user-agent") ua = v
                    }
                } else url = d.toString()
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val st = ex?.get(Intent.EXTRA_STREAM) as? Uri
                url = st?.toString() ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("""https?://\S+""").find(it)?.value }
            }
            else -> return false
        }
        if (ex != null) {
            title = title ?: ex.getString("title") ?: ex.getString(Intent.EXTRA_TITLE)
            val hs = try { ex.getStringArray("headers") } catch (e: Exception) { null }
            if (hs != null) for (k in 0 until hs.size - 1 step 2) {
                if (hs[k].equals("Referer", true)) ref = hs[k + 1]
                if (hs[k].equals("User-Agent", true)) ua = hs[k + 1]
            }
            ex.getString("referer")?.let { ref = it }
            ex.getString("Referer")?.let { ref = it }
            ex.getString("user-agent")?.let { ua = it }
        }
        if (url.isNullOrBlank()) { showIntent(i); return true }
        val u = url!!
        if (u.startsWith("http") && !Util.isVideoUrl(u) && i.type?.startsWith("video") != true &&
            i.action == Intent.ACTION_SEND) { web.loadUrl(u); return true }
        val needProxy = ref.isNotEmpty() || ua.isNotEmpty() || u.lowercase().contains("m3u8")
        newMovie(u, ref, ua, needProxy, title)
        return true
    }

    /** إن وصل رابط بصيغة لا أعرفها أعرض تفاصيله ليصوّرها المستخدم */
    private fun showIntent(i: Intent) {
        val sb = StringBuilder()
        sb.append("action: ${i.action}\ndata: ${i.dataString}\ntype: ${i.type}\n")
        i.extras?.keySet()?.forEach { k -> @Suppress("DEPRECATION") sb.append("$k = ${i.extras?.get(k)}\n") }
        AlertDialog.Builder(this).setTitle("وصل طلب لم أجد فيه رابط فيديو")
            .setMessage("صوّر هذه الشاشة وأرسلها لأضيف دعم هذا التطبيق:\n\n$sb")
            .setPositiveButton("حسنًا", null).show()
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
        val f = Found(u, ref)
        found[u] = f
        updateFoundBtn()
        val ua = web.settings.userAgentString ?: ""
        pool.execute { Probe.run(f, ua); runOnUiThread { updateFoundBtn() } }
    }

    private fun sorted(): List<Found> = found.values.filter { it.passes() }.sortedByDescending { it.score() }

    private fun best(): Found? = sorted().firstOrNull { it.done && it.score() >= 600 }

    private var announced = false

    private fun updateFoundBtn() {
        val b = best()
        foundBtn.text = if (b != null) "📺 أرسل الفيلم إلى التلفاز" + (b.dur?.let { " (${Util.fmt(it)})" } ?: "")
                        else "فيديوهات مكتشفة (${sorted().size})"
        if (b != null && !announced) { announced = true; toast("وجدت الفيلم. اضغط «أرسل الفيلم إلى التلفاز» في الأسفل") }
    }

    private fun showFound() {
        val list = sorted()
        if (list.isEmpty()) {
            toast(if (found.isEmpty()) "لم يُكتشف فيديو بعد. افتح صفحة الفيلم وشغّله فيها أولًا"
                  else "كل ما وُجد حتى الآن إعلانات صغيرة (أقل من 50 ميغابايت) فاستبعدتها. شغّل الفيلم في الصفحة وانتظر قليلًا")
            return
        }
        val b = best()
        val names = list.map { f -> (if (f === b) "⭐ الأرجح أنه الفيلم\n" else "") + f.describe() }
        AlertDialog.Builder(this).setTitle("اختر الفيديو (الأطول أولًا)")
            .setItems(names.toTypedArray()) { _, i -> chooseTarget(list[i]) }
            .setNeutralButton("مسح القائمة") { _, _ -> found.clear(); announced = false; updateFoundBtn() }
            .show()
    }

    private fun chooseTarget(f: Found) {
        val ua = web.settings.userAgentString ?: ""
        if (Store.movies.isEmpty()) { newMovie(f.url, f.ref, ua, true, null); return }
        val names = listOf("➕ فيلم جديد") + Store.movies.map { "ربطه بقائمة: ${it.title}" }
        AlertDialog.Builder(this).setTitle("أين تضع هذا الفيديو؟")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) newMovie(f.url, f.ref, ua, true, null)
                else {
                    val m = Store.movies[i - 1]
                    m.url = f.url; m.referer = f.ref; m.userAgent = ua; m.useProxy = true
                    Store.save(); open(m)
                }
            }.show()
    }

    private fun askDirect() {
        val box = EditText(this).apply { hint = "الصق رابط الفيديو المباشر (mp4 أو m3u8)" }
        AlertDialog.Builder(this).setTitle("رابط مباشر").setView(box)
            .setPositiveButton("متابعة") { _, _ ->
                val u = box.text.toString().trim()
                if (u.startsWith("http")) newMovie(u, "", "", u.lowercase().contains("m3u8"), null) else toast("الرابط غير صحيح")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun newMovie(u: String, ref: String, ua: String, proxy: Boolean, title: String?) {
        val box = EditText(this).apply {
            setText(title?.takeIf { it.isNotBlank() } ?: web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "فيلم جديد")
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("اسم الفيلم").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val m = Movie(System.currentTimeMillis().toString(), box.text.toString().ifBlank { "فيلم" }, u, ref, ua, proxy)
                Store.add(m); open(m)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun open(m: Movie) = startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id).putExtra("autocast", true))

    private fun go() {
        var t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        if (Util.isVideoUrl(t)) { newMovie(t, "", "", t.lowercase().contains("m3u8"), null); return }
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
