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
    private val foundSubs = mutableListOf<String>()
    private var lastClip = ""
    private var pendingSub: String? = null
    private var pendingPos = 0L
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
        bottom.add(btn("السجل") { startActivity(Intent(this, MoviesActivity::class.java)) }, 1f)
        bottom.add(btn("رابط مباشر") { askDirect() }, 1f)
        root.add(bottom)
        setContentView(root)

        setupWeb()
        if (!handleShare(intent)) web.loadUrl("https://www.google.com")
        showCrash()
    }

    /** إن توقف التطبيق في المرة السابقة أعرض السبب ليصوّره المستخدم */
    private fun showCrash() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists()) return
        val t = try { f.readText().take(1500) } catch (e: Exception) { "" }
        f.delete()
        AlertDialog.Builder(this).setTitle("توقف التطبيق في المرة السابقة")
            .setMessage("صوّر هذه الرسالة وأرسلها ليُصلح الخلل:\n\n$t")
            .setPositiveButton("نسخ") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash", t)); toast("نُسخ")
            }.setNegativeButton("إغلاق", null).show()
    }

    /** إن كان في الحافظة رابط فيديو منسوخ (من تطبيق أفلام مثلًا) أعرضه */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            val u = Regex("""https?://\S+""").find(t)?.value ?: return
            if (u == lastClip || !Util.isVideoUrl(u)) return
            lastClip = u
            AlertDialog.Builder(this).setTitle("رابط فيديو منسوخ")
                .setMessage("وجدت في الحافظة رابط فيديو. أأعرضه على التلفاز؟")
                .setPositiveButton("نعم") { _, _ -> newMovie(u, "", "", false, null) }
                .setNegativeButton("لا", null).show()
        } catch (e: Exception) { }
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
                val d = i.data
                if (d == null) url = findUrlInExtras(ex)
                else if (d.scheme == "wvc-x-callback" || d.scheme == "familycast") {
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
            else -> url = i.dataString ?: findUrlInExtras(ex)
        }
        if (url == null && i.action == Intent.ACTION_MAIN && i.categories?.contains(Intent.CATEGORY_LAUNCHER) == true) return false
        if (url == null && i.action == Intent.ACTION_MAIN) return false
        val extraHdr = LinkedHashMap<String, String>()
        var subUrl: String? = null
        var startPos = 0L
        if (ex != null) {
            title = title ?: ex.getString("title") ?: ex.getString(Intent.EXTRA_TITLE) ?: ex.getString("filename")
            val hs = try { ex.getStringArray("headers") } catch (e: Exception) { null }
            if (hs != null) for (k in 0 until hs.size - 1 step 2) {
                when {
                    hs[k].equals("Referer", true) -> ref = hs[k + 1]
                    hs[k].equals("User-Agent", true) -> ua = hs[k + 1]
                    else -> extraHdr[hs[k]] = hs[k + 1]
                }
            }
            val hb = try { ex.getBundle("android.media.intent.extra.HTTP_HEADERS") } catch (e: Exception) { null }
            hb?.keySet()?.forEach { k ->
                val v = hb.getString(k) ?: return@forEach
                when {
                    k.equals("Referer", true) -> ref = v
                    k.equals("User-Agent", true) -> ua = v
                    else -> extraHdr[k] = v
                }
            }
            ex.getString("referer")?.let { ref = it }
            ex.getString("Referer")?.let { ref = it }
            ex.getString("user-agent")?.let { ua = it }
            @Suppress("DEPRECATION")
            val subsP = try { ex.getParcelableArray("subs") } catch (e: Exception) { null }
            subUrl = ex.getString("subtitle") ?: (subsP?.firstOrNull() as? Uri)?.toString()
                ?: (try { ex.getStringArray("subs") } catch (e: Exception) { null })?.firstOrNull()
            @Suppress("DEPRECATION")
            val posObj = ex.get("position")
            startPos = (posObj as? Number)?.toLong() ?: 0L
        }
        if (extraHdr.isNotEmpty()) ua = (listOf(ua) + extraHdr.map { "${it.key}: ${it.value}" }).joinToString("\n")
        pendingSub = subUrl
        pendingPos = startPos
        if (url.isNullOrBlank()) { showIntent(i); return true }
        val u = url!!
        if (u.startsWith("http") && !Util.isVideoUrl(u) && i.type?.startsWith("video") != true &&
            i.action == Intent.ACTION_SEND) { web.loadUrl(u); return true }
        val needProxy = ref.isNotEmpty() || extraHdr.isNotEmpty()
        newMovie(u, ref, ua, needProxy, title)
        return true
    }

    /** يبحث عن رابط فيديو في أي معطى من المعطيات المرسلة */
    private fun findUrlInExtras(ex: Bundle?): String? {
        if (ex == null) return null
        for (k in listOf("url", "uri", "video", "videoUrl", "video_url", "link", "stream", "file")) {
            val v = try { ex.getString(k) } catch (e: Exception) { null }
            if (v != null && (v.startsWith("http") || v.startsWith("content"))) return v
        }
        for (k in ex.keySet()) {
            @Suppress("DEPRECATION")
            val v = ex.get(k)
            val t = (v as? String) ?: (v as? Uri)?.toString() ?: continue
            Regex("""(https?|content)://\S+""").find(t)?.let { return it.value }
        }
        return null
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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.webChromeClient = object : WebChromeClient() {
            // النوافذ المنبثقة: لا تُفتح إلا بلمسة من المستخدم، وتُحجب إن كانت إعلانًا
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    var handled = false
                    fun take(v: WebView, u: String) {
                        if (handled) return
                        handled = true
                        if (!Util.isAd(u) && u.startsWith("http")) web.loadUrl(u)
                        handler.post { try { v.stopLoading(); v.destroy() } catch (e: Exception) { } }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(v, r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { take(v, url) }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: ""
                if (!(s == "http" || s == "https")) return true
                val u = request.url.toString()
                if (Util.isAd(u)) return true
                if (request.isForMainFrame && !request.hasGesture() && !request.isRedirect) {
                    val cur = try { Uri.parse(pageUrl).host ?: "" } catch (e: Exception) { "" }
                    val nxt = request.url.host ?: ""
                    if (cur.isNotEmpty() && baseDomain(cur) != baseDomain(nxt)) return true
                }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url; urlBox.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String) { scanDom() }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (Util.isAd(u)) return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                val ref = request.requestHeaders["Referer"] ?: request.requestHeaders["referer"] ?: pageUrl
                if (Util.isVideoUrl(u)) runOnUiThread { addFound(u, ref) }
                else if (Util.isSubUrl(u)) runOnUiThread { if (!foundSubs.contains(u)) foundSubs.add(u) }
                return null
            }
            // إذا تعطلت الصفحة لا يُغلق التطبيق، بل تُعاد الصفحة
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                handler.post { recreateWeb() }
                return true
            }
        }
    }

    private fun baseDomain(h: String): String = h.split('.').takeLast(2).joinToString(".")

    private fun recreateWeb() {
        try {
            val parent = web.parent as android.widget.LinearLayout
            val idx = parent.indexOfChild(web)
            parent.removeView(web)
            try { web.destroy() } catch (e: Exception) { }
            web = WebView(this)
            setupWeb()
            parent.addView(web, idx, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            web.loadUrl(pageUrl.ifBlank { "https://www.google.com" })
            toast("تعطلت الصفحة فأعدت تحميلها")
        } catch (e: Exception) { }
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
            .setNeutralButton("مسح القائمة") { _, _ -> found.clear(); foundSubs.clear(); announced = false; updateFoundBtn() }
            .show()
    }

    private fun chooseTarget(f: Found) {
        val ua = web.settings.userAgentString ?: ""
        Store.match(f.url, null)?.let { reuse(it, f.url, f.ref, ua, false); return }
        if (Store.movies.isEmpty()) { newMovie(f.url, f.ref, ua, false, null); return }
        val names = listOf("➕ فيلم جديد") + Store.movies.map { "ربطه بقائمة: ${it.title}" }
        AlertDialog.Builder(this).setTitle("أين تضع هذا الفيديو؟")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == 0) newMovie(f.url, f.ref, ua, false, null)
                else {
                    val m = Store.movies[i - 1]
                    m.url = f.url; m.referer = f.ref; m.userAgent = ua; m.useProxy = false; m.variant = ""; m.variantLabel = ""
                    addSubs(m)
                    Store.save(); open(m)
                }
            }.show()
    }

    private fun askDirect() {
        val box = EditText(this).apply { hint = "الصق رابط الفيديو المباشر (mp4 أو m3u8)" }
        AlertDialog.Builder(this).setTitle("رابط مباشر").setView(box)
            .setPositiveButton("متابعة") { _, _ ->
                val u = box.text.toString().trim()
                if (u.startsWith("http")) newMovie(u, "", "", false, null) else toast("الرابط غير صحيح")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun newMovie(u: String, ref: String, ua: String, proxy: Boolean, title: String?) {
        // إن كان الفيلم محفوظًا من قبل نكمل عليه: قائمة التجاوز ومكان التوقف والترجمة تبقى كما هي
        val old = Store.match(u, title)
        if (old != null) { reuse(old, u, ref, ua, proxy); return }
        val start = { name: String ->
            val m = Movie(System.currentTimeMillis().toString(), name.ifBlank { "فيلم" }, u, ref, ua, proxy)
            if (u.startsWith("http") && ref.isNotEmpty()) addSubs(m)
            pendingSub?.let { if (it.startsWith("http")) m.subs.add(0, Sub(it, "ترجمة التطبيق")) }
            pendingSub = null
            Store.add(m)
            val pos = pendingPos; pendingPos = 0L
            startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id).putExtra("autocast", true).putExtra("pos", pos))
        }
        // إذا وصل اسم الفيلم من تطبيق الأفلام لا نسأل عنه
        if (!title.isNullOrBlank()) { start(title); return }
        val box = EditText(this).apply {
            setText(web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "فيلم جديد")
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("اسم الفيلم").setView(box)
            .setPositiveButton("حفظ") { _, _ -> start(box.text.toString()) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun reuse(m: Movie, u: String, ref: String, ua: String, proxy: Boolean) {
        if (m.url != u) { m.url = u; m.variant = ""; m.variantLabel = "" }
        m.referer = ref; m.userAgent = ua; m.useProxy = proxy
        if (u.startsWith("http") && ref.isNotEmpty()) addSubs(m)
        pendingSub?.let { s -> if (s.startsWith("http") && m.subs.none { it.url == s }) m.subs.add(0, Sub(s, "ترجمة التطبيق")) }
        pendingSub = null
        m.watchedAt = System.currentTimeMillis()
        Store.movies.remove(m); Store.movies.add(0, m); Store.save()
        val pos = pendingPos.takeIf { it > 0 } ?: 0L; pendingPos = 0L
        toast("«${m.title}»: قائمة التجاوز محفوظة (${m.segments.size} مقطع)")
        startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id).putExtra("autocast", true).putExtra("pos", pos))
    }

    private fun addSubs(m: Movie) {
        for (u in foundSubs) if (m.subs.none { it.url == u }) m.subs.add(Sub(u, "ترجمة ${m.subs.size + 1}"))
    }

    private fun open(m: Movie) = startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id).putExtra("autocast", true))

    private fun go() {
        var t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        if (Util.isVideoUrl(t)) { newMovie(t, "", "", false, null); return }
        if (!t.startsWith("http")) {
            t = if (t.contains('.') && !t.contains(' ')) "https://$t"
                else "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(t)
    }

    override fun onResume() { super.onResume(); handler.postDelayed(scanLoop, 3000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(scanLoop) }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
