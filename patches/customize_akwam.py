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
