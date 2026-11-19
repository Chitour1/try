package com.chitour.familycast

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/** شاشة الاستقبال على التلفاز: تشغّل الفيلم بإنترنت التلفاز وتطبّق التجاوز والكتم والترجمة وتحفظ مكان التوقف */
class TvActivity : AppCompatActivity() {
    companion object {
        @Volatile var instance: TvActivity? = null
        @Volatile var pendingLoad: JSONObject? = null
        @Volatile var statusJson: String = "{\"state\":\"idle\"}"
    }

    private lateinit var pv: PlayerView
    private lateinit var idle: LinearLayout
    private lateinit var info: TextView
    private lateinit var note: TextView
    private lateinit var audio: AudioManager
    private var player: ExoPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private var movieId = ""
    private var mTitle = ""
    private var segs = listOf<Segment>()
    private var skipOn = true
    private var subsOn = true
    private var mutedBySkip = false
    private var lastSeek = 0L
    private var retries = 0
    private var okTicks = 0
    private var lastPos = 0L
    private var failed = false
    private var msg = ""
    private var backAt = 0L
    private var lastSave = 0L

    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { }
            h.postDelayed(this, 200)
        }
    }
    private val hideNote = Runnable { note.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pv = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        idle = vbox().apply { gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#0A3F4B")); setPadding(dp(48), dp(32), dp(48), dp(32)) }
        idle.add(label("عرض عائلي", 44f, true).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        info = label("", 22f).apply { setTextColor(Color.parseColor("#E8F1F2")); gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f) }
        idle.add(info)
        root.addView(idle, FrameLayout.LayoutParams(-1, -1))

        note = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE
        }
        root.addView(note, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })
        setContentView(root)

        ReceiverService.start(this)
        instance = this
        refreshIdle()
        pendingLoad?.let { pendingLoad = null; load(it) }
        h.post(loop)
        askOverlay()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLoad?.let { pendingLoad = null; load(it) }
    }

    /** إذن الظهور فوق التطبيقات يسمح بفتح شاشة الاستقبال وحدها حين يرسل الهاتف فيلمًا */
    private fun askOverlay() {
        if (Settings.canDrawOverlays(this) || Store.pref("askedOverlay") == "1") return
        Store.setPref("askedOverlay", "1")
        AlertDialog.Builder(this).setTitle("إذن مهم")
            .setMessage("ليفتح التلفاز الفيلم وحده حين ترسله من الهاتف ولو كان التطبيق مغلقًا، اسمح لـ«عرض عائلي» بالظهور فوق التطبيقات الأخرى.")
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }.setNegativeButton("لاحقًا", null).show()
    }

    private fun refreshIdle() {
        info.text = "جهاز الاستقبال جاهز باسم: «${tvName()}»\n" +
            "عنوانه في الشبكة: ${Proxy.localIp() ?: "غير متصل"}\n\n" +
            "على الهاتف: افتح «عرض عائلي»، واختر هذا التلفاز من زر «الجهاز»،\nثم اضغط «اعرض على التلفاز».\n\n" +
            "أثناء العرض: ◀ ▶ للتقديم والتأخير 10 ثوانٍ • زر القائمة للجودة • «رجوع» مرتين للإيقاف"
    }

    private fun show(s: String, ms: Long = 3000) {
        msg = s
        note.text = s; note.visibility = View.VISIBLE
        h.removeCallbacks(hideNote); h.postDelayed(hideNote, ms)
    }

    private fun parseSegs(a: JSONArray?): List<Segment> {
        val out = mutableListOf<Segment>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Segment(o.optDouble("s"), o.optDouble("e"), o.optBoolean("m")))
        }
        return out.sortedBy { it.start }
    }

    // ---- ذاكرة التلفاز لمكان التوقف (تعمل حتى لو لم يرسله الهاتف) ----
    private fun savedPos(id: String): Long = Store.pref("tvpos_$id").toLongOrNull() ?: 0L
    private fun savePos() {
        val p = player ?: return
        if (movieId.isEmpty() || p.currentPosition <= 0) return
        val dur = p.duration
        val keep = if (dur > 0 && p.currentPosition > dur - 120_000) 0L else p.currentPosition
        Store.setPref("tvpos_$movieId", keep.toString())
    }

    fun load(j: JSONObject) {
        savePos()
        try { player?.release() } catch (e: Exception) { }
        movieId = j.optString("id"); mTitle = j.optString("title")
        segs = parseSegs(j.optJSONArray("segments"))
        skipOn = j.optBoolean("skip", true)
        subsOn = j.optBoolean("subsOn", true)
        failed = false; retries = 0; okTicks = 0

        val (uaLine, extra) = Util.splitHeaders(j.optString("ua"))
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
        if (uaLine.isNotEmpty()) ds.setUserAgent(uaLine)
        val props = HashMap(extra)
        val ref = j.optString("referer")
        if (ref.isNotEmpty()) props["Referer"] = ref
        if (props.isNotEmpty()) ds.setDefaultRequestProperties(props)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, ds)))
            .build()

        val url = j.optString("url")
        val subsCfg = mutableListOf<MediaItem.SubtitleConfiguration>()
        val sa = j.optJSONArray("subs")
        if (sa != null) for (i in 0 until sa.length()) {
            val o = sa.getJSONObject(i)
            val text = o.optString("text"); val su = o.optString("url")
            val uri = if (text.isNotEmpty()) {
                val f = File(cacheDir, "sub_$i.vtt"); f.writeText(Util.toVtt(text)); Uri.fromFile(f)
            } else Uri.parse(su)
            val mime = if (text.isNotEmpty() || su.lowercase().contains(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            subsCfg.add(MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLanguage("ar")
                .setLabel(o.optString("label")).setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0).build())
        }
        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subsCfg)
            .apply { if (url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (retries < 8) {
                    retries++
                    show("انقطع البث، أعيد الاتصال ($retries)…")
                    h.postDelayed({
                        player?.let { it.prepare(); if (lastPos > 0) it.seekTo(max(0L, lastPos - 3000)); it.play() }
                    }, 2000L * retries)
                } else { failed = true; show("تعذّر تشغيل الفيلم. قد يكون الرابط انتهى، فالتقطه من جديد على الهاتف", 8000) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { show("انتهى الفيلم"); Store.setPref("tvpos_$movieId", "0") }
            }
        })
        p.setMediaItem(item)
        p.prepare()
        var start = j.optLong("start")
        if (start <= 0) start = savedPos(movieId).let { if (it > 60_000) it - 5000 else 0L }
        if (start > 0) p.seekTo(start)
        p.playWhenReady = true
        player = p
        pv.player = p
        applySubs(j.optDouble("subScale", 1.3).toFloat())
        idle.visibility = View.GONE
        show(if (start > 0) "▶ $mTitle — أكمل من ${Util.fmt(start / 1000.0)}" else "▶ $mTitle", 4000)
    }

    private fun applySubs(scale: Float) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn).setPreferredTextLanguage("ar").build()
        pv.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            setStyle(CaptionStyleCompat(Color.WHITE, Color.argb(110, 0, 0, 0), Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        }
    }

    private fun qualities(): List<Int> {
        val p = player ?: return emptyList()
        val out = sortedSetOf<Int>(compareByDescending { it })
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i) && g.getTrackFormat(i).height > 0) out.add(g.getTrackFormat(i).height)
        }
        return out.toList()
    }

    private fun setQuality(hgt: Int) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (hgt <= 0) { b.clearOverridesOfType(C.TRACK_TYPE_VIDEO); show("الجودة: تلقائي") }
        else for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) if (g.getTrackFormat(i).height == hgt) {
                b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)); show("الجودة: ${hgt}p")
            }
        }
        p.trackSelectionParameters = b.build()
    }

    private fun qualityDialog() {
        val q = qualities()
        if (q.isEmpty()) { show("لا جودات أخرى لهذا الفيلم"); return }
        val names = listOf("تلقائي") + q.map { "${it}p" }
        AlertDialog.Builder(this).setTitle("الجودة")
            .setItems(names.toTypedArray()) { _, i -> setQuality(if (i == 0) 0 else q[i - 1]) }.show()
    }

    private fun stopPlayback() {
        savePos()
        try { player?.release() } catch (e: Exception) { }
        player = null; pv.player = null
        movieId = ""; mTitle = ""; segs = emptyList()
        idle.visibility = View.VISIBLE
        refreshIdle()
    }

    fun cmd(j: JSONObject) {
        val p = player
        when (j.optString("a")) {
            "toggle" -> p?.let { if (it.isPlaying) { it.pause(); show("⏸ إيقاف مؤقت") } else { it.play(); show("▶ تشغيل") } }
            "seek" -> p?.seekTo(max(0L, j.optLong("ms")))
            "rel" -> p?.let { val d = j.optLong("ms"); it.seekTo(max(0L, it.currentPosition + d)); show(if (d > 0) "⏩ ${d / 1000} ث" else "⏪ ${-d / 1000} ث", 1500) }
            "vol" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (j.optInt("d") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "stop" -> stopPlayback()
            "skip" -> { skipOn = j.optBoolean("on", true); show(if (skipOn) "التجاوز مفعّل" else "التجاوز متوقف") }
            "segments" -> { if (j.optString("id") == movieId) segs = parseSegs(j.optJSONArray("list")) }
            "subs" -> { subsOn = j.optBoolean("on", true); applySubs(j.optDouble("scale", 1.3).toFloat()) }
            "subscale" -> applySubs(j.optDouble("v", 1.3).toFloat())
            "quality" -> setQuality(j.optInt("h"))
            "rename" -> { Store.setPref("tvName", j.optString("name")); refreshIdle() }
        }
    }

    private fun tick() {
        val p = player
        if (p != null) {
            val pos = p.currentPosition
            if (p.playbackState == Player.STATE_READY) lastPos = pos
            if (p.isPlaying && ++okTicks > 300) { retries = 0; okTicks = 0 }
            val sec = pos / 1000.0
            val seg = if (skipOn) segs.firstOrNull { sec >= it.start - 0.3 && sec < it.end } else null
            val now = SystemClock.elapsedRealtime()
            if (seg != null && !seg.mute && now - lastSeek > 800) {
                lastSeek = now
                p.seekTo((seg.end * 1000).toLong())
                show("⏭ تجاوزت مقطعًا", 2000)
            }
            if (seg?.mute == true) { if (!mutedBySkip) { mutedBySkip = true; p.volume = 0f; show("🔇 كتم مؤقت", 2000) } }
            else if (mutedBySkip) { mutedBySkip = false; p.volume = 1f }
            if (now - lastSave > 5000) { lastSave = now; savePos() }
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val st = when {
            p == null -> "idle"
            failed -> "error"
            p.playbackState == Player.STATE_BUFFERING -> "buffering"
            p.playbackState == Player.STATE_ENDED -> "ended"
            p.isPlaying -> "playing"
            else -> "paused"
        }
        val q = JSONArray(); qualities().forEach { q.put(it) }
        statusJson = JSONObject()
            .put("state", st).put("id", movieId).put("title", mTitle)
            .put("pos", p?.currentPosition ?: 0L).put("dur", p?.duration?.takeIf { it > 0 } ?: 0L)
            .put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            .put("mute", audio.isStreamMute(AudioManager.STREAM_MUSIC))
            .put("skip", skipOn).put("subsOn", subsOn).put("qualities", q).put("msg", msg)
            .put("name", tvName())
            .toString()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val p = player
        if (e.action == KeyEvent.ACTION_DOWN && p != null) {
            if (!pv.isControllerFullyVisible) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekTo(max(0L, p.currentPosition - 10_000)); show("⏪ 10 ثوانٍ", 1200); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekTo(p.currentPosition + 10_000); show("⏩ 10 ثوانٍ", 1200); return true }
            }
            when (e.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { qualityDialog(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (pv.isControllerFullyVisible) { pv.hideController(); return true }
                    val now = SystemClock.elapsedRealtime()
                    if (now - backAt < 2500) { stopPlayback(); return true }
                    backAt = now; show("اضغط «رجوع» مرة أخرى لإيقاف الفيلم"); return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        super.onStop()
        savePos()
        player?.pause()
    }

    override fun onDestroy() {
        savePos()
        h.removeCallbacksAndMessages(null)
        try { player?.release() } catch (e: Exception) { }
        player = null
        if (instance === this) instance = null
        statusJson = "{\"state\":\"idle\"}"
        super.onDestroy()
    }
}
