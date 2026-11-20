package com.chitour.familycast

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import java.io.File
import java.util.Date
import kotlin.math.max

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder()
            .setTargetActivityClassName(MovieActivity::class.java.name).build()
        val media = CastMediaOptions.Builder().setNotificationOptions(notif).build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(false)
            .setResumeSavedSession(true)
            .build()
    }
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * يدير العرض على التلفاز: التحميل، والتجاوز والكتم، وإعادة الاتصال تلقائيًا إذا انقطع البث،
 * والتبديل بين التشغيل المباشر والتمرير عبر الهاتف إذا فشلت إحدى الطريقتين.
 */
object CastCtl : RemoteMediaClient.ProgressListener {
    lateinit var app: Context
    var movie: Movie? = null
    var onProgress: ((Long, Long) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var status = ""
        private set

    private var client: RemoteMediaClient? = null
    private var loadedUrl: String? = null
    private var muting = false
    private var lastSeek = 0L
    private var lastSkipTarget = -1L
    var lastPos = 0L
        private set
    var lastDur = 0L
        private set
    var usingProxy = false
        private set
    private var retries = 0
    private var lastRetryAt = 0L
    private var fallbackTried = false
    private val main = Handler(Looper.getMainLooper())
    private const val LEAD = 0.6

    private val cb = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { try { checkStatus() } catch (e: Exception) { Log.w("FC", e) } }
    }

    fun say(s: String) { status = s; onStatus?.invoke(s) }

    fun session(): CastSession? = try { CastContext.getSharedInstance(app).sessionManager.currentCastSession } catch (e: Exception) { null }

    fun client(): RemoteMediaClient? = client ?: session()?.remoteMediaClient

    /** هل يعرض التلفاز الآن فيلمًا أرسلناه نحن؟ */
    fun active(): Boolean {
        val c = client() ?: return false
        return c.hasMediaSession() && (loadedUrl == null || c.mediaInfo?.contentId == loadedUrl)
    }

    fun attach(session: CastSession?) {
        val c = session?.remoteMediaClient
        if (c === client) return
        try { client?.removeProgressListener(this); client?.unregisterCallback(cb) } catch (e: Exception) { }
        client = c
        muting = false
        c?.addProgressListener(this, 200)
        c?.registerCallback(cb)
    }

    private fun subStyle() = TextTrackStyle().apply {
        fontScale = Store.subScale
        foregroundColor = Color.WHITE
        backgroundColor = Color.argb(120, 0, 0, 0)
        edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
        edgeColor = Color.BLACK
    }

    /** يرسل الفيلم إلى التلفاز. يرجع رسالة خطأ أو null عند النجاح في الإرسال */
    fun load(m: Movie, startMs: Long, viaProxy: Boolean = m.useProxy, userAction: Boolean = true): String? {
        val s = session() ?: return "NO_SESSION"
        val c = s.remoteMediaClient ?: return "NO_SESSION"
        if (userAction) { fallbackTried = false; retries = 0 }
        val src = m.variant.ifBlank { m.url }.trim()
        val local = src.startsWith("content://") || src.startsWith("file://")
        val url = when {
            local -> Proxy.wrapLocal(src)
            viaProxy -> Proxy.wrap(src, m.referer, m.userAgent)
            else -> src
        } ?: return "الهاتف غير متصل بشبكة Wi-Fi"
        usingProxy = local || viaProxy

        val tracks = m.subs.mapIndexedNotNull { i, sub ->
            val su = Proxy.subUrl(sub.url, m.referer, m.userAgent) ?: return@mapIndexedNotNull null
            MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT)
                .setName(sub.label).setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(su).setContentType("text/vtt").setLanguage("ar").build()
        }
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        md.putString(MediaMetadata.KEY_TITLE, m.title)
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (local) "video/mp4" else Util.mimeOf(src))
            .setMetadata(md)
            .setMediaTracks(tracks)
            .setTextTrackStyle(subStyle())
            .build()
        movie = m
        loadedUrl = url
        lastPos = startMs
        lastSkipTarget = -1L
        attach(s)
        val req = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(max(0L, startMs))
        if (tracks.isNotEmpty() && m.subsOn) req.setActiveTrackIds(longArrayOf(1L))
        c.load(req.build()).setResultCallback { r ->
            if (r.status.isSuccess) say("يُعرض على التلفاز" + if (usingProxy) " (عبر الهاتف)" else "")
            else failed(m, startMs, viaProxy, local)
        }
        KeepService.start(app)
        say("يُرسل إلى التلفاز…")
        return null
    }

    private fun failed(m: Movie, startMs: Long, viaProxy: Boolean, local: Boolean) {
        if (!local && !fallbackTried) {
            fallbackTried = true
            m.useProxy = !viaProxy; Store.save()
            say(if (m.useProxy) "لم يقبل التلفاز الرابط مباشرة، أجرّب تمريره عبر الهاتف…" else "أجرّب التشغيل المباشر…")
            main.postDelayed({ load(m, startMs, m.useProxy, false) }, 800)
        } else say("تعذّر التشغيل على التلفاز. قد يكون الرابط انتهى؛ افتح صفحة الفيلم والتقطه من جديد")
    }

    private fun checkStatus() {
        val c = client ?: return
        val st = c.mediaStatus ?: return
        val m = movie ?: return
        if (st.playerState != MediaStatus.PLAYER_STATE_IDLE || st.idleReason != MediaStatus.IDLE_REASON_ERROR) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRetryAt > 5 * 60_000) retries = 0
        if (retries >= 6 || now - lastRetryAt < 4000) return
        retries++
        lastRetryAt = now
        val local = m.url.startsWith("content://") || m.url.startsWith("file://")
        // في أول ثوانٍ يعني الخطأ غالبًا أن الطريقة لا تصلح، فنبدّلها
        val proxy = if (!local && lastPos < 20_000 && !fallbackTried) { fallbackTried = true; !usingProxy } else usingProxy
        if (proxy != m.useProxy && !local) { m.useProxy = proxy; Store.save() }
        say("انقطع البث، أعيد الاتصال تلقائيًا ($retries)…")
        main.postDelayed({ load(m, max(0L, lastPos - 4000), proxy, false) }, 1500)
    }

    override fun onProgressUpdated(progressMs: Long, durationMs: Long) {
        val c = client ?: return
        if (loadedUrl != null && c.mediaInfo?.contentId != loadedUrl) return
        if (progressMs > 0) lastPos = progressMs
        if (durationMs > 0) lastDur = durationMs
        onProgress?.invoke(progressMs, durationMs)
        val m = movie ?: return
        if (c.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_BUFFERING && progressMs == 0L) return
        val pos = progressMs / 1000.0
        val seg = if (Store.skipEnabled) m.segments.firstOrNull { pos >= it.start - LEAD && pos < it.end } else null
        val now = SystemClock.elapsedRealtime()
        if (seg != null && !seg.playsThrough()) {
            val target = (seg.end * 1000).toLong()
            if (now - lastSeek > 2000 || target != lastSkipTarget) {
                lastSeek = now; lastSkipTarget = target
                c.seek(MediaSeekOptions.Builder().setPosition(target).build())
                say("تجاوزت مقطعًا إلى ${Util.fmt(seg.end)}")
            }
        }
        val wantMute = seg?.mute == true
        if (wantMute != muting) {
            muting = wantMute
            c.setStreamMute(wantMute)
        }
    }

    fun seekTo(ms: Long) { client()?.seek(MediaSeekOptions.Builder().setPosition(max(0L, ms)).build()) }

    fun volume(delta: Double) {
        val s = session() ?: return
        try { s.volume = (s.volume + delta).coerceIn(0.0, 1.0); if (s.isMute) s.isMute = false } catch (e: Exception) { }
    }

    fun toggleMute() { val s = session() ?: return; try { s.isMute = !s.isMute } catch (e: Exception) { } }

    fun volumeText(): String {
        val s = session() ?: return ""
        return try { if (s.isMute) "مكتوم" else "${(s.volume * 100).toInt()}٪" } catch (e: Exception) { "" }
    }

    fun setSubs(on: Boolean) {
        val c = client() ?: return
        c.setActiveMediaTracks(if (on) longArrayOf(1L) else longArrayOf())
    }

    fun restyleSubs() { client()?.setTextTrackStyle(subStyle()) }

    fun stop() {
        movie = null; loadedUrl = null
        try { client()?.stop() } catch (e: Exception) { }
        KeepService.stop(app)
        say("أُوقف العرض")
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(Date().toString() + "\n" + Log.getStackTraceString(e)) } catch (x: Exception) { }
            prev?.uncaughtException(t, e)
        }
        Store.init(this)
        Proxy.ctx = applicationContext
        CastCtl.app = applicationContext
        if (!isTv()) TvLink.start(this) else ReceiverService.start(this)
        try {
            val sm = CastContext.getSharedInstance(this).sessionManager
            sm.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, id: String) { CastCtl.attach(s) }
                override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { CastCtl.attach(s) }
                override fun onSessionEnded(s: CastSession, error: Int) {
                    CastCtl.attach(null); KeepService.stop(this@App); CastCtl.say("انقطع الاتصال بالتلفاز")
                }
                override fun onSessionStarting(s: CastSession) {}
                override fun onSessionStartFailed(s: CastSession, error: Int) {}
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionResuming(s: CastSession, id: String) {}
                override fun onSessionResumeFailed(s: CastSession, error: Int) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) { CastCtl.say("ضعف الاتصال بالتلفاز… أحاول الاستئناف") }
            }, CastSession::class.java)
            sm.currentCastSession?.let { CastCtl.attach(it) }
        } catch (e: Exception) { }
    }
}
