package com.chitour.familycast

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder()
            .setTargetActivityClassName(MovieActivity::class.java.name).build()
        val media = CastMediaOptions.Builder().setNotificationOptions(notif).build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** يراقب وقت العرض على التلفاز ويقفز فوق المقاطع أو يكتم صوتها */
object SkipEngine : RemoteMediaClient.ProgressListener {
    var movie: Movie? = null
    var onProgress: ((Long, Long) -> Unit)? = null
    private var client: RemoteMediaClient? = null
    private var muting = false
    private var lastSeek = 0L
    private const val LEAD = 0.7

    fun client(): RemoteMediaClient? = client

    fun attach(session: CastSession?) {
        val c = session?.remoteMediaClient
        if (c === client) return
        client?.removeProgressListener(this)
        client = c
        muting = false
        c?.addProgressListener(this, 200)
    }

    override fun onProgressUpdated(progressMs: Long, durationMs: Long) {
        onProgress?.invoke(progressMs, durationMs)
        val m = movie ?: return
        val c = client ?: return
        val pos = progressMs / 1000.0
        val seg = if (Store.skipEnabled) m.segments.firstOrNull { pos >= it.start - LEAD && pos < it.end } else null
        val now = SystemClock.elapsedRealtime()
        if (seg != null && !seg.mute && now - lastSeek > 1500) {
            lastSeek = now
            c.seek(MediaSeekOptions.Builder().setPosition((seg.end * 1000).toLong()).build())
        }
        val wantMute = seg?.mute == true
        if (wantMute != muting) {
            muting = wantMute
            c.setStreamMute(wantMute)
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Proxy.ctx = applicationContext
        try {
            val sm = CastContext.getSharedInstance(this).sessionManager
            sm.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, id: String) { SkipEngine.attach(s) }
                override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { SkipEngine.attach(s) }
                override fun onSessionEnded(s: CastSession, error: Int) {
                    SkipEngine.attach(null); KeepService.stop(this@App)
                }
                override fun onSessionStarting(s: CastSession) {}
                override fun onSessionStartFailed(s: CastSession, error: Int) {}
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionResuming(s: CastSession, id: String) {}
                override fun onSessionResumeFailed(s: CastSession, error: Int) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) {}
            }, CastSession::class.java)
            sm.currentCastSession?.let { SkipEngine.attach(it) }
        } catch (e: Exception) { }
    }
}
