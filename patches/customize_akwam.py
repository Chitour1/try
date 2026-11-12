#!/usr/bin/env python3
"""Customize a pinned MIT-licensed Mrowser source tree for Akwam TV.
No popup is allowed to navigate the visible tab; no hidden ads are loaded.
"""
from pathlib import Path
import sys

base = Path(sys.argv[1])
def change(path, old, new):
    src = base / path
    text = src.read_text()
    if old not in text:
        raise RuntimeError(f"Expected source anchor missing: {src}: {old[:85]}")
    src.write_text(text.replace(old, new, 1))

change(
    "app/src/main/kotlin/net/mrowser/data/DefaultFavorites.kt",
    'Favorite("YouTube", "https://www.youtube.com/tv"),\n        Favorite("Google", "https://www.google.com")',
    'Favorite("أكوام الجديد", "https://akwam.ss/"),\n        Favorite("أكوام القديم", "https://old.akwam.ss/")'
)
change(
    "app/src/main/res/values/strings.xml",
    '<string name="app_name">mrowser</string>',
    '<string name="app_name">بوابة أكوام TV</string>'
)
change(
    "app/src/main/res/values/strings.xml",
    '<string name="url_hint">Enter address</string>',
    '<string name="url_hint">اختر أكوام الجديد أو القديم أدناه</string>'
)
change(
    "app/build.gradle.kts",
    'applicationId = "net.mrowser"',
    'applicationId = "com.independent.akwam.tvengine"'
)
change("app/build.gradle.kts", "versionCode = 9", "versionCode = 105")
change("app/build.gradle.kts", 'versionName = "1.4.0"', 'versionName = "2.1.0"')

# Never load an advertisement into the currently displayed page.
# A refused popup also means no invisible WebView to keep running after exit.
chrome = "app/src/main/kotlin/net/mrowser/web/BrowserWebChromeClient.kt"
change(
    chrome,
    """        val host = view ?: return false
        return openInCurrentWindow(host, resultMsg)
    }""",
    """        // Akwam TV kiosk mode: no popup can replace the visible movie page.
        // Reject rather than opening an invisible ad in the background.
        return false
    }"""
)

# Avoid an unknown ad domain forcing an ordinary main-frame navigation.
# This does not affect media requests or video embedded in an iframe.
client = "app/src/main/kotlin/net/mrowser/stream/SniffingWebViewClient.kt"
change(
    client,
    """        if (consume(url.scheme, target, request.hasGesture())) return true
        return request.isForMainFrame && refuseIfListed(target)""",
    """        if (request.isForMainFrame && isUnexpectedExternalPage(view, url)) return true
        if (consume(url.scheme, target, request.hasGesture())) return true
        return request.isForMainFrame && refuseIfListed(target)"""
)
change(
    client,
    """        if (consume(Uri.parse(target).scheme, target, isUserGesture = true)) return true
        // No frame flag either""",
    """        val uri = Uri.parse(target)
        if (isUnexpectedExternalPage(view, uri)) return true
        if (consume(uri.scheme, target, isUserGesture = true)) return true
        // No frame flag either"""
)
change(
    client,
    """    /** @return true when mrowser took the navigation and the WebView must not load it. */""",
    """    /**
     * The user only requested the Akwam domains. Click-hijack scripts frequently
     * redirect the visible top-level tab to unrelated advertising domains.
     * Keep that page blocked while leaving subframe/video streams untouched.
     */
    private fun isUnexpectedExternalPage(view: WebView?, target: Uri): Boolean {
        val from = Uri.parse(view?.url.orEmpty()).host?.lowercase() ?: return false
        fun isAkwam(host: String): Boolean =
            host == "akwam.ss" || host.endsWith(".akwam.ss")
        if (!isAkwam(from)) return false
        val scheme = target.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val to = target.host?.lowercase() ?: return false
        if (isAkwam(to)) return false
        // Explicit direct media can still be sniffed / handed to the native player.
        val path = target.path.orEmpty().lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") ||
            path.endsWith(".mpd") || path.endsWith(".webm")) return false
        return true
    }

    /** @return true when mrowser took the navigation and the WebView must not load it. */"""
)

main = "app/src/main/kotlin/net/mrowser/MainActivity.kt"
change(
    main,
    "onPopupBlocked = { Toast.makeText(this, R.string.popup_blocked, Toast.LENGTH_SHORT).show() },",
    "onPopupBlocked = { /* Silently discard advertising popups. */ },"
)
change(
    main,
    "onNavigationBlocked = { Toast.makeText(this, R.string.navigation_blocked, Toast.LENGTH_SHORT).show() }",
    "onNavigationBlocked = { /* Keep the film page in place without an interruption. */ }"
)
change(
    main,
    """    override fun onDestroy() {
        super.onDestroy()""",
    """    override fun onDestroy() {
        // Fully dispose the only active WebView and all its pending loads on exit.
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()"""
)
change(
    main,
    """                webView.loadUrl("about:blank")
                showHome()""",
    """                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                showHome()"""
)

license_text = (base / "LICENSE").read_text()
assets = base / "app/src/main/assets"
assets.mkdir(parents=True, exist_ok=True)
(assets / "THIRD_PARTY_LICENSE_MROWSER.txt").write_text(license_text)
print("Applied Akwam TV 2.1.0 silent popup and session-cleanup modifications.")


# 2.2: Avoid automatically choosing the first detected stream, which may be an ad.
# The user can still launch the detected movie from the native Play chip.
change(
    "app/src/main/kotlin/net/mrowser/data/Settings.kt",
    "val autoOpenPlayer: Boolean = true,",
    "val autoOpenPlayer: Boolean = false,"
)
change(
    main,
    '''        settings = JsonSettingsStore(File(filesDir, "settings.json"))
        seedDefaultFavorites()''',
    '''        settings = JsonSettingsStore(File(filesDir, "settings.json"))
        // One-time migration for updates from v2.1, whose native player auto-launched
        // for the first sniffed media URL, sometimes an advertising stream.
        val migration = getPreferences(MODE_PRIVATE)
        if (!migration.getBoolean("akwam_22_no_auto_player", false)) {
            settings.update(settings.get().copy(autoOpenPlayer = false))
            migration.edit().putBoolean("akwam_22_no_auto_player", true).apply()
        }
        seedDefaultFavorites()'''
)
change(
    client,
    '''        sniffer.onRequest(url, req.requestHeaders.orEmpty())
        return adBlocker.intercept(req)''',
    '''        val blocked = adBlocker.intercept(req)
        if (blocked == null) sniffer.onRequest(url, req.requestHeaders.orEmpty())
        return blocked'''
)
selector = "app/src/main/kotlin/net/mrowser/stream/StreamCandidateSelector.kt"
change(selector, "?: manifests.minByOrNull { it.seq }", "?: manifests.maxByOrNull { it.seq }")
player_source = "app/src/main/kotlin/net/mrowser/player/PlayerActivity.kt"
change(
    player_source,
    '''            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Stream error — back to web player", Toast.LENGTH_LONG).show()
                finish()
            }''',
    '''            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Akwam native playback error: code=" + error.errorCode, error)
                exo.playWhenReady = false
                val host = android.net.Uri.parse(request.url).host ?: "غير معروف"
                var httpCode: Int? = null
                var cause: Throwable? = error
                var depth = 0
                while (cause != null && depth < 8) {
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        httpCode = cause.responseCode
                        break
                    }
                    cause = cause.cause
                    depth++
                }
                val info = buildString {
                    append("رمز الخطأ: ")
                    append(error.errorCode)
                    append("\\nخادم الفيديو: ")
                    append(host)
                    if (httpCode != null) {
                        append("\\nاستجابة الخادم: HTTP ")
                        append(httpCode)
                    }
                    append("\\nنوع المشكلة: ")
                    append(error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
                }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("تعذر تشغيل مصدر الفيديو")
                    .setMessage("أظهر التطبيق سبب تعطل المشغل بدل إغلاقه تلقائيًا.\\n\\n" +
                        info + "\\n\\nإذا ظهر HTTP 403/401 فربما رفض الخادم تشغيل الرابط خارج المتصفح. " +
                        "إذا ظهر رمز فك ترميز، فصيغة الفيديو قد لا يدعمها التلفزيون.")
                    .setNegativeButton("إعادة المحاولة") { _, _ ->
                        exo.prepare()
                        exo.playWhenReady = true
                    }
                    .setPositiveButton("العودة إلى الموقع") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }'''
)
change("app/build.gradle.kts", "versionCode = 105", "versionCode = 106")
change("app/build.gradle.kts", 'versionName = "2.1.0"', 'versionName = "2.2.0"')
print("Applied Akwam TV 2.2.0 playback diagnostics and ad stream filtering.")


# v2.3: Accept legitimate Akwam navigation again. The 2.1 blanket denial of
# window.open and every non-akwam top-level URL also rejected watch servers.
# Use a short-lived relay to discover the URL, then require an explicit choice
# before visiting unknown hosts. Known ad hosts are refused without a prompt.
change(
    chrome,
    """        // Akwam TV kiosk mode: no popup can replace the visible movie page.
        // Reject rather than opening an invisible ad in the background.
        return false
    }""",
    """        val host = view ?: return false
        return openInCurrentWindow(host, resultMsg)
    }"""
)
change(
    chrome,
    "    private val launchExternal: (String) -> Unit = {}",
    """    private val onPopupTarget: (String) -> Unit = {},
    private val launchExternal: (String) -> Unit = {}"""
)
change(
    chrome,
    """        if (decision == ExternalSchemePolicy.Decision.LetWebViewLoad) host.loadUrl(url)
        else launchExternal(url)""",
    """        // Only the approved target is loaded in the visible WebView.
        // Popup relay itself never gets to load an advertisement.
        if (decision == ExternalSchemePolicy.Decision.LetWebViewLoad) onPopupTarget(url)
        else launchExternal(url)"""
)

change(
    client,
    """    private val onNavigationBlocked: () -> Unit = {}""",
    """    private val onNavigationBlocked: () -> Unit = {},
    private val isTrustedExternalHost: (String) -> Boolean = { false },
    private val onExternalPage: (String) -> Unit = {}"""
)
change(
    client,
    """        if (request.isForMainFrame && isUnexpectedExternalPage(view, url)) return true""",
    """        if (request.isForMainFrame && isUnexpectedExternalPage(view, url)) {
            onExternalPage(target)
            return true
        }"""
)
change(
    client,
    """        if (isUnexpectedExternalPage(view, uri)) return true""",
    """        if (isUnexpectedExternalPage(view, uri)) {
            onExternalPage(target)
            return true
        }"""
)
change(
    client,
    """        if (!isAkwam(from)) return false""",
    """        if (!isAkwam(from) && !isTrustedExternalHost(from)) return false"""
)
change(
    client,
    """        if (isAkwam(to)) return false""",
    """        if (isAkwam(to) || isTrustedExternalHost(to)) return false"""
)
change(
    main,
    """    private lateinit var handoff: HandoffController""",
    """    private lateinit var handoff: HandoffController
    private val trustedExternalHosts = mutableSetOf<String>()
    private var linkChoiceShowing = false"""
)
change(
    main,
    """            onNavigationBlocked = { /* Keep the film page in place without an interruption. */ }""",
    """            onNavigationBlocked = { /* Keep the film page in place without an interruption. */ },
            isTrustedExternalHost = { host -> host in trustedExternalHosts },
            onExternalPage = { url -> askAboutExternalLink(url) }"""
)
change(
    main,
    """            isBlockedPopup = { url -> adBlocker.isBlockedPopup(url) },
            launchExternal = { url -> externalLinks.launch(url) }""",
    """            isBlockedPopup = { url -> adBlocker.isBlockedPopup(url) },
            onPopupTarget = { url -> askAboutExternalLink(url) },
            launchExternal = { url -> externalLinks.launch(url) }"""
)
change(
    main,
    """    private fun openUrl(url: String) {""",
    """    private fun askAboutExternalLink(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        val host = uri.host?.lowercase() ?: return
        if (adBlocker.isBlockedPopup(url)) return
        if (host == "akwam.ss" || host.endsWith(".akwam.ss") ||
            host in trustedExternalHosts) {
            webView.loadUrl(url)
            return
        }
        // External video hosts may be necessary, but unsolicited ad redirects
        // must not replace the visible film page without the viewer's consent.
        if (linkChoiceShowing) return
        linkChoiceShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("رابط مشاهدة خارجي")
            .setMessage("يريد الموقع فتح خادم خارجي: " + host +
                "\\nقد يكون مشغل فيلم أو إعلانًا. هل تريد فتحه؟")
            .setNegativeButton("حظر الرابط", null)
            .setPositiveButton("فتح الرابط") { _, _ ->
                trustedExternalHosts.add(host)
                webView.loadUrl(url)
            }
            .create()
        dialog.setOnDismissListener { linkChoiceShowing = false }
        dialog.show()
    }

    private fun openUrl(url: String) {"""
)

# The site works on the user's phone but can serve different playback APIs to
# TV WebView. Provide a Chrome/Android mobile user-agent (actual Chromium
# version preserved) for its first-party/embedded web player as a fallback.
change(
    main,
    """            mediaPlaybackRequiresUserGesture = false
            // Route window.open""",
    """            mediaPlaybackRequiresUserGesture = false
            val chromiumVersion = Regex("Chrome/([0-9.]+)")
                .find(userAgentString)?.groupValues?.get(1) ?: "120.0.0.0"
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                chromiumVersion + " Mobile Safari/537.36"
            // Route window.open"""
)
change("app/build.gradle.kts", "versionCode = 106", "versionCode = 107")
change("app/build.gradle.kts", 'versionName = "2.2.0"', 'versionName = "2.3.0"')
print("Applied Akwam TV 2.3.0: user-approved watch links, mobile UA, no ad windows.")


# v2.4: strict window-level behavior requested by the viewer.
# Any attempt to create a NEW browsing window is discarded silently,
# regardless of ad classification. Main-frame navigation in the SAME
# window and embedded frames remain available.
change(
    chrome,
    """        val host = view ?: return false
        return openInCurrentWindow(host, resultMsg)
    }""",
    """        // Reject any new browsing window, never redirect it to the current page.
        // Do not create relay WebViews, overlays, tabs or alerts.
        return false
    }"""
)
# Earlier cross-domain guard suppressed ordinary same-window navigation.
# Remove it completely rather than asking users to authorize every transition.
change(
    client,
    """        if (request.isForMainFrame && isUnexpectedExternalPage(view, url)) {
            onExternalPage(target)
            return true
        }
""",
    ""
)
change(
    client,
    """        if (isUnexpectedExternalPage(view, uri)) {
            onExternalPage(target)
            return true
        }
""",
    ""
)
def remove_between(path, start, end):
    p = base / path
    s = p.read_text()
    a = s.index(start)
    b = s.index(end, a)
    p.write_text(s[:a] + s[b:])
remove_between(
    client,
    "    /**\n     * The user only requested the Akwam domains.",
    "    /** @return true when mrowser took the navigation and the WebView must not load it. */"
)
change(
    client,
    """    private val onNavigationBlocked: () -> Unit = {},
    private val isTrustedExternalHost: (String) -> Boolean = { false },
    private val onExternalPage: (String) -> Unit = {}""",
    """    private val onNavigationBlocked: () -> Unit = {}"""
)
change(
    main,
    """    private lateinit var handoff: HandoffController
    private val trustedExternalHosts = mutableSetOf<String>()
    private var linkChoiceShowing = false""",
    """    private lateinit var handoff: HandoffController"""
)
change(
    main,
    """            onNavigationBlocked = { /* Keep the film page in place without an interruption. */ },
            isTrustedExternalHost = { host -> host in trustedExternalHosts },
            onExternalPage = { url -> askAboutExternalLink(url) }""",
    """            onNavigationBlocked = { /* Ignore blocked resources silently. */ }"""
)
change(
    main,
    """            onPopupTarget = { url -> askAboutExternalLink(url) },""",
    """            onPopupTarget = { /* Never show or navigate popup targets. */ },"""
)
remove_between(
    main,
    "    private fun askAboutExternalLink(url: String) {",
    "    private fun openUrl(url: String) {"
)
# Preserve browser defaults; mobile spoofing can change which video player is used.
change(
    main,
    """            mediaPlaybackRequiresUserGesture = false
            val chromiumVersion = Regex("Chrome/([0-9.]+)")
                .find(userAgentString)?.groupValues?.get(1) ?: "120.0.0.0"
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                chromiumVersion + " Mobile Safari/537.36"
            // Route window.open""",
    """            mediaPlaybackRequiresUserGesture = false
            // Route window.open"""
)
change("app/build.gradle.kts", "versionCode = 107", "versionCode = 108")
change("app/build.gradle.kts", 'versionName = "2.3.0"', 'versionName = "2.4.0"')
print("Akwam TV 2.4: all new windows rejected silently; same-window navigation preserved.")


# v2.5: On a connection failure to the observed downet.net CDN (error 2001),
# use OkHttp's HTTP/2-capable network stack. Leave previously working hosts on
# the original data source and preserve cookies/Referer/Origin and the exact
# media URL. This is a targeted transport experiment, not a DRM bypass.
change(
    "app/build.gradle.kts",
    "    implementation(libs.media3.ui)",
    """    implementation(libs.media3.ui)
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")"""
)
change(
    player_source,
    """        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(baseHeaders)
            .setAllowCrossProtocolRedirects(true)""",
    """        // One observed movie source fails to connect with the built-in
        // HttpURLConnection transport on this TV, while other sources work.
        // OkHttp enables HTTP/2 for this CDN only; do not disturb working hosts.
        val sourceHost = android.net.Uri.parse(request.url).host.orEmpty()
            .lowercase(java.util.Locale.ROOT)
        val httpFactory: androidx.media3.datasource.HttpDataSource.Factory =
            if (sourceHost == "downet.net" || sourceHost.endsWith(".downet.net")) {
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                    okhttp3.OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                ).setDefaultRequestProperties(baseHeaders)
            } else {
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(baseHeaders)
                    .setAllowCrossProtocolRedirects(true)
            }"""
)
change(
    player_source,
    """                    append(error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)""",
    """                    append(error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
                    val deepestCause = generateSequence(error.cause) { it.cause }
                        .take(8).lastOrNull()
                    if (deepestCause != null) {
                        append("\\nالسبب التفصيلي: ")
                        append(deepestCause.javaClass.simpleName)
                        val reason = deepestCause.message.orEmpty()
                            .substringBefore("?").take(120)
                        if (reason.isNotBlank()) {
                            append(" — ")
                            append(reason)
                        }
                    }"""
)
change("app/build.gradle.kts", "versionCode = 108", "versionCode = 109")
change("app/build.gradle.kts", 'versionName = "2.4.0"', 'versionName = "2.5.0"')
print("Applied Akwam TV 2.5: targeted HTTP/2-capable OkHttp transport for downet.net and deeper error diagnostics.")


# v2.6: The user wants no ad blocking at all. Both normal ads and genuine
# video dependencies must load. Every new-window navigation is hosted in a
# separate invisible WebView, never replacing the visible movie page. Each
# temporary window has a strict memory/time limit and is destroyed on exit.
change(
    main,
    """            blockAds = { settings.get().blockAds },
            blockPopups = { settings.get().blockPopups },""",
    """            blockAds = { false },
            blockPopups = { false },"""
)
change(
    chrome,
    """        // Reject any new browsing window, never redirect it to the current page.
        // Do not create relay WebViews, overlays, tabs or alerts.
        return false
    }""",
    """        return createHiddenPopup(view, resultMsg)
    }"""
)
change(
    chrome,
    """    private val mainHandler = Handler(Looper.getMainLooper())""",
    """    private val mainHandler = Handler(Looper.getMainLooper())
    private val hiddenWindows = LinkedHashSet<WebView>()
    private val hiddenTimers = mutableMapOf<WebView, Runnable>()
    private val maxHiddenWindows = 8
    private val hiddenWindowLifetimeMs = 30_000L

    private fun createHiddenPopup(source: WebView?, resultMsg: Message?): Boolean {
        val host = source ?: return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        // Bound allocation and lifetime so ad scripts cannot exhaust TV memory.
        if (hiddenWindows.size >= maxHiddenWindows) {
            closeHiddenWindow(hiddenWindows.first())
        }
        val hidden = WebView(host.context)
        hidden.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        hidden.alpha = 0f
        hidden.translationX = -10000f
        hidden.isFocusable = false
        hidden.isFocusableInTouchMode = false
        hidden.isClickable = false
        hidden.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(hidden, true)
        hidden.webViewClient = object : WebViewClient() {}
        hidden.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = createHiddenPopup(view, resultMsg)
            override fun onCloseWindow(window: WebView?) {
                if (window != null) closeHiddenWindow(window)
            }
        }
        hiddenWindows.add(hidden)
        container.addView(hidden, ViewGroup.LayoutParams(2, 2))
        val timeout = Runnable { closeHiddenWindow(hidden) }
        hiddenTimers[hidden] = timeout
        mainHandler.postDelayed(timeout, hiddenWindowLifetimeMs)
        transport.webView = hidden
        resultMsg.sendToTarget()
        return true
    }

    private fun closeHiddenWindow(hidden: WebView) {
        if (!hiddenWindows.remove(hidden)) return
        hiddenTimers.remove(hidden)?.let { mainHandler.removeCallbacks(it) }
        hidden.stopLoading()
        hidden.loadUrl("about:blank")
        hidden.clearHistory()
        (hidden.parent as? ViewGroup)?.removeView(hidden)
        hidden.destroy()
    }

    fun closeAllHiddenWindows() {
        hiddenWindows.toList().forEach(::closeHiddenWindow)
    }"""
)
change(
    main,
    """            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true""",
    """            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true"""
)
change(
    main,
    """    override fun onDestroy() {
        // Fully dispose the only active WebView""",
    """    override fun onStop() {
        if (::chromeClient.isInitialized) chromeClient.closeAllHiddenWindows()
        super.onStop()
    }

    override fun onDestroy() {
        if (::chromeClient.isInitialized) chromeClient.closeAllHiddenWindows()
        // Fully dispose the only active WebView"""
)
change("app/build.gradle.kts", "versionCode = 109", "versionCode = 110")
change("app/build.gradle.kts", 'versionName = "2.5.0"', 'versionName = "2.6.0"')
print("Akwam TV 2.6: all web requests allowed; popups loaded invisibly and disposed safely.")


# v2.7: web-player-only mode. Completely detach the native ExoPlayer handoff
# and its "Play synchronized" chip. Fullscreen remains the HTML5 video
# fullscreen provided by BrowserWebChromeClient, *not* a second player.
change(
    main,
    """    private lateinit var handoff: HandoffController""",
    """    // Video is played exclusively by the original site's HTML5/WebView player."""
)
change(
    main,
    """        sniffer = StreamSniffer(
            userAgent = { webView.settings.userAgentString },
            onStreamAvailable = {
                runOnUiThread {
                    showChip()
                    if (settings.get().autoOpenPlayer) handoff.play()
                }
            },
            onCleared = { playChip.visibility = View.GONE },
            schedule = { delayMs, task -> uiHandler.postDelayed(task, delayMs) }
        )
        handoff = HandoffController(this, sniffer)""",
    """        sniffer = StreamSniffer(
            userAgent = { webView.settings.userAgentString },
            onStreamAvailable = { /* No separate player; stay inside site. */ },
            onCleared = { playChip.visibility = View.GONE },
            schedule = { delayMs, task -> uiHandler.postDelayed(task, delayMs) }
        )
        playChip.visibility = View.GONE"""
)
change(
    main,
    """        layout.onChipClick = { handoff.play() }""",
    """        layout.onChipClick = { /* The native player is permanently disabled. */ }"""
)
change(
    main,
    """        if (::sniffer.isInitialized && sniffer.hasStream() &&
            homeView.visibility != View.VISIBLE && historyView.visibility != View.VISIBLE &&
            settingsView.visibility != View.VISIBLE) showChip()""",
    """        playChip.visibility = View.GONE"""
)
change(
    main,
    """    private fun showChip() {
        playChip.visibility = View.VISIBLE
        uiHandler.removeCallbacks(chipHideRunnable)
        uiHandler.postDelayed(chipHideRunnable, CHIP_TIMEOUT_MS)
    }""",
    """    private fun showChip() {
        // The site's embedded video player remains the only player.
        playChip.visibility = View.GONE
    }"""
)
change(
    main,
    """        webView.webChromeClient = chromeClient
        webView.settings.apply {""",
    """        webView.webChromeClient = chromeClient
        // Embedded third-party video frames may require their own cookies.
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {"""
)
change(
    "app/src/main/AndroidManifest.xml",
    """        <activity
            android:name=".player.PlayerActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboardHidden"
            android:screenOrientation="landscape"
            android:theme="@style/Theme.Mrowser" />""",
    """        <!-- External native video PlayerActivity removed: use the site's player. -->"""
)
# Remove the now-unreachable native player classes, rather than shipping them
# alongside the page player, and remove libraries exclusive to that player.
(base / "app/src/main/kotlin/net/mrowser/player/PlayerActivity.kt").unlink()
(base / "app/src/main/kotlin/net/mrowser/handoff/HandoffController.kt").unlink()
gradle_file = base / "app/build.gradle.kts"
gradle_source = gradle_file.read_text()
for dependency in [
    "    implementation(libs.media3.exoplayer)",
    "    implementation(libs.media3.exoplayer.hls)",
    "    implementation(libs.media3.exoplayer.dash)",
    "    implementation(libs.media3.ui)",
    '    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")',
    '    implementation("com.squareup.okhttp3:okhttp:4.12.0")',
]:
    if dependency not in gradle_source:
        raise RuntimeError("Cannot remove dependency: " + dependency)
    gradle_source = gradle_source.replace(dependency + "\n", "")
gradle_file.write_text(gradle_source)
change("app/build.gradle.kts", "versionCode = 110", "versionCode = 111")
change("app/build.gradle.kts", 'versionName = "2.6.0"', 'versionName = "2.7.0"')
print("Akwam TV 2.7: no native player, no handoff, only embedded website HTML5 video.")


# Complete web-only build cleanup: remove orphan ExoPlayer view and controller
# and stale import which previously kept Kotlin/resource compilation failing.
main_path = base / main
main_text = main_path.read_text()
main_text = main_text.replace("import net.mrowser.handoff.HandoffController\n", "")
main_path.write_text(main_text)
for obsolete in [
    "app/src/main/kotlin/net/mrowser/player/SubtitleSyncController.kt",
    "app/src/main/res/layout/player_activity.xml",
]:
    candidate = base / obsolete
    if not candidate.exists():
        raise RuntimeError("Expected obsolete player asset not found: "+obsolete)
    candidate.unlink()
print("Removed remaining native-player-only components.")


# v2.8 - Web pages link to the system's VLC/MX Player. They may redirect to
# intent:/vlc: from a user click or open target=_blank in hidden popup WebViews.
# Delegate only these recognized player links to installed Android apps.
player_bridge = base / "app/src/main/kotlin/net/mrowser/web/AkwamPlayerLink.kt"
player_bridge.write_text("""package net.mrowser.web

import android.content.Intent
import android.net.Uri
import java.net.URISyntaxException

/** Strictly classify player-specific Android deep links, not ads or arbitrary intents. */
object AkwamPlayerLink {
    fun isPlayerLink(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
        if (scheme == "vlc" || scheme == "vlclauncher") return true
        if (scheme != "intent") return false
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) { return false }
        return intent.getPackage() in setOf(
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "org.videolan.vlc"
        )
    }
}
""")

change(
    "app/src/main/kotlin/net/mrowser/stream/SniffingWebViewClient.kt",
    """import net.mrowser.web.ExternalSchemePolicy""",
    """import net.mrowser.web.ExternalSchemePolicy
import net.mrowser.web.AkwamPlayerLink"""
)
change(
    "app/src/main/kotlin/net/mrowser/stream/SniffingWebViewClient.kt",
    """            ExternalSchemePolicy.Decision.Ignore -> true""",
    """            ExternalSchemePolicy.Decision.Ignore -> {
                // Click-driven redirects sometimes lose the gesture flag.
                // Only explicitly targeted VLC/MX links may reach an external app.
                if (AkwamPlayerLink.isPlayerLink(url)) onExternalScheme(url)
                true
            }"""
)

# Hidden windows are still invisible and bounded. When a window created for
# a video button points to VLC/MX, hand the URL to that installed app rather
# than trying to load an intent: address as if it were an ordinary web page.
change(
    chrome,
    """        hidden.webViewClient = object : WebViewClient() {}""",
    """        hidden.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                if (AkwamPlayerLink.isPlayerLink(target)) {
                    mainHandler.post { launchExternal(target) }
                    return true
                }
                return false
            }

            @Deprecated("Compatibility with older WebView")
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return false
                if (AkwamPlayerLink.isPlayerLink(target)) {
                    mainHandler.post { launchExternal(target) }
                    return true
                }
                return false
            }
        }"""
)
change(
    chrome,
    """import android.net.Uri""",
    """import android.net.Uri
import net.mrowser.web.AkwamPlayerLink"""
)

# MX intent links carry their real video URI. If VLC is installed but MX isn't,
# forward that same URL to VLC's public ACTION_VIEW interface.
external = "app/src/main/kotlin/net/mrowser/web/ExternalIntentLauncher.kt"
change(
    external,
    """    fun launch(url: String) {""",
    """    private fun openInVlc(mxIntent: Intent): Boolean {
        val media = mxIntent.data ?: return false
        val scheme = media.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val vlc = Intent(Intent.ACTION_VIEW)
            .setDataAndType(media, "video/*")
            .setPackage("org.videolan.vlc")
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            context.startActivity(vlc)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun launch(url: String) {"""
)
change(
    external,
    """        } catch (e: ActivityNotFoundException) {
            if (fallback != null) onFallback(fallback) else onNoApp()""",
    """        } catch (e: ActivityNotFoundException) {
            val pkg = intent.getPackage()
            val mx = pkg == "com.mxtech.videoplayer.ad" ||
                pkg == "com.mxtech.videoplayer.pro"
            if (!mx || !openInVlc(intent)) {
                if (fallback != null) onFallback(fallback) else onNoApp()
            }"""
)

change(
    "app/src/main/AndroidManifest.xml",
    """    <queries>
        <intent>""",
    """    <queries>
        <package android:name="org.videolan.vlc" />
        <package android:name="com.mxtech.videoplayer.ad" />
        <package android:name="com.mxtech.videoplayer.pro" />
        <intent>"""
)
change("app/build.gradle.kts", "versionCode = 111", "versionCode = 112")
change("app/build.gradle.kts", 'versionName = "2.7.0"', 'versionName = "2.8.0"')
print("Akwam TV 2.8: handle VLC/MX links from page and hidden popup, preserve site-only web playback.")
