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
