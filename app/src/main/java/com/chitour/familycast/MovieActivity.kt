package com.chitour.familycast

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlin.math.max

class MovieActivity : AppCompatActivity() {
    private lateinit var movie: Movie
    private lateinit var segBox: LinearLayout
    private lateinit var posText: TextView
    private lateinit var seek: SeekBar
    private lateinit var titleBox: EditText
    private lateinit var urlBox: EditText
    private lateinit var proxyChk: CheckBox
    private lateinit var playerView: PlayerView
    private lateinit var localBtn: android.widget.Button
    private var player: ExoPlayer? = null
    private var castCtx: CastContext? = null
    private var pendingCast = false
    private var pendingStart: Double? = null
    private var userSeeking = false
    private var lastLocalSeek = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFile(uri)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) { if (pendingCast) { pendingCast = false; castNow() } }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { if (pendingCast) { pendingCast = false; castNow() } }
        override fun onSessionEnded(s: CastSession, error: Int) {}
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) { toast("تعذّر الاتصال بالتلفاز") }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    private val loop = object : Runnable {
        override fun run() { localTick(); handler.postDelayed(this, 200) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: SkipEngine.movie?.id
        val m = id?.let { Store.get(it) }
        if (m == null) { finish(); return }
        movie = m
        title = m.title
        castCtx = try { CastContext.getSharedInstance(this) } catch (e: Exception) { null }
        buildUi()
        refreshSegs()
        if (intent.getBooleanExtra("autocast", false)) handler.postDelayed({ castNow() }, 400)
    }

    private fun buildUi() {
        val col = vbox().apply { setPadding(dp(12), dp(8), dp(12), dp(24)) }

        val castRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val route = MediaRouteButton(this)
        try { CastButtonFactory.setUpMediaRouteButton(applicationContext, route) } catch (e: Exception) { }
        castRow.add(route)
        castRow.add(label("← أيقونة البث: اضغطها واختر التلفاز", 13f), 1f)
        col.add(castRow)

        titleBox = EditText(this).apply { hint = "اسم الفيلم"; setText(movie.title) }
        col.add(titleBox)
        urlBox = EditText(this).apply { hint = "رابط الفيديو"; setText(movie.url); textSize = 12f; maxLines = 3 }
        col.add(urlBox)
        proxyChk = CheckBox(this).apply { text = "تمرير الفيديو عبر الهاتف (للمواقع التي ترفض التلفاز)"; isChecked = movie.useProxy }
        col.add(proxyChk)

        val play = hbox()
        play.add(btn("📺 اعرض على التلفاز") { castNow() }, 1f)
        localBtn = btn("📱 شغّل على الهاتف") { toggleLocal() }
        play.add(localBtn, 1f)
        col.add(play)

        playerView = PlayerView(this).apply { visibility = View.GONE }
        col.add(playerView, h = dp(220))

        col.add(label("التحكم", 17f, true))
        val ctl = vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        posText = label("0:00:00 / 0:00:00", 16f).apply { gravity = Gravity.CENTER }
        ctl.add(posText)
        seek = SeekBar(this).apply { max = 1000 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) posText.text = "${Util.fmt(p / 1000.0 * duration() / 1000.0)} / ${Util.fmt(duration() / 1000.0)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                seekTo((sb.progress / 1000.0 * duration()).toLong())
            }
        })
        ctl.add(seek)
        val keys = hbox()
        keys.add(btn("−30") { seekTo(curPos() - 30000) }, 1f)
        keys.add(btn("−10") { seekTo(curPos() - 10000) }, 1f)
        keys.add(btn("⏯") { togglePlay() }, 1f)
        keys.add(btn("+10") { seekTo(curPos() + 10000) }, 1f)
        keys.add(btn("+30") { seekTo(curPos() + 30000) }, 1f)
        ctl.add(keys)
        col.add(ctl)

        val marks = hbox()
        marks.add(btn("⏺ بداية مقطع الآن") { markStart() }, 1f)
        marks.add(btn("⏹ نهاية مقطع الآن") { markEnd() }, 1f)
        col.add(marks)

        val sw = SwitchCompat(this).apply {
            text = "التجاوز مفعّل"; isChecked = Store.skipEnabled; textSize = 16f
            setOnCheckedChangeListener { _, c -> Store.skipEnabled = c }
        }
        col.add(sw)

        col.add(label("المقاطع المحذوفة", 17f, true))
        segBox = vbox()
        col.add(segBox)

        val add1 = hbox()
        add1.add(btn("➕ إضافة مقطع") { editSeg(null) }, 1f)
        add1.add(btn("📝 كتابة قائمة") { pasteList() }, 1f)
        col.add(add1)
        val add2 = hbox()
        add2.add(btn("📂 من ملف txt أو srt") { pickFile.launch(arrayOf("*/*")) }, 1f)
        add2.add(btn("📋 نسخ القائمة") { copyList() }, 1f)
        col.add(add2)

        col.add(label(
            "طرق إدخال الأوقات:\n" +
            "• سطر لكل مقطع: 0:12:30 - 0:13:05 (ويُكتب «كتم» في السطر لكتم الصوت بدل التجاوز).\n" +
            "• ملف srt: كل ترجمة فيه مقطع يُحذف، وإن كان نصها «كتم» يُكتم صوتها.\n" +
            "• ملف txt بالصيغة السطرية نفسها، أو ملف edl.\n" +
            "• أثناء المشاهدة: «بداية مقطع الآن» تسجّل قبل اللحظة بثلاث ثوانٍ، و«نهاية مقطع الآن» بعدها بثانية.", 13f))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------- المقاطع ----------
    private fun refreshSegs() {
        movie.segments.sortBy { it.start }
        segBox.removeAllViews()
        if (movie.segments.isEmpty()) { segBox.add(label("لا مقاطع بعد.", 14f)); return }
        movie.segments.forEachIndexed { i, s ->
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            val t = label("${i + 1}) من ${Util.fmt(s.start)} إلى ${Util.fmt(s.end)} — ${if (s.mute) "كتم الصوت" else "تجاوز"}" +
                    (if (s.note.isNotBlank()) "\n${s.note}" else ""), 14f)
            t.setOnClickListener { editSeg(s) }
            row.add(t, 1f)
            row.add(btn("جرّب") { seekTo(((s.start - 5) * 1000).toLong()) })
            row.add(btn("حذف") {
                AlertDialog.Builder(this).setMessage("حذف المقطع ${i + 1}؟")
                    .setPositiveButton("حذف") { _, _ -> movie.segments.remove(s); Store.save(); refreshSegs() }
                    .setNegativeButton("إلغاء", null).show()
            })
            segBox.add(row)
        }
    }

    private fun editSeg(seg: Segment?) {
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val st = EditText(this).apply {
            hint = "البداية مثل 1:02:30"; inputType = InputType.TYPE_CLASS_TEXT
            setText(seg?.let { Util.fmt(it.start) } ?: Util.fmt(curPos() / 1000.0))
        }
        val en = EditText(this).apply { hint = "النهاية مثل 1:03:10"; setText(seg?.let { Util.fmt(it.end) } ?: "") }
        val rg = RadioGroup(this)
        val r1 = RadioButton(this).apply { text = "تجاوز المقطع"; id = View.generateViewId() }
        val r2 = RadioButton(this).apply { text = "كتم الصوت فقط"; id = View.generateViewId() }
        rg.addView(r1); rg.addView(r2)
        rg.check(if (seg?.mute == true) r2.id else r1.id)
        val note = EditText(this).apply { hint = "ملاحظة (اختياري)"; setText(seg?.note ?: "") }
        box.add(label("البداية")); box.add(st); box.add(label("النهاية")); box.add(en); box.add(rg); box.add(note)
        AlertDialog.Builder(this).setTitle(if (seg == null) "مقطع جديد" else "تعديل المقطع").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val s = Util.parse(st.text.toString()); val e = Util.parse(en.text.toString())
                if (s == null || e == null || e <= s) { toast("الوقت غير صحيح: يجب أن تكون النهاية بعد البداية"); return@setPositiveButton }
                if (seg == null) movie.segments.add(Segment(s, e, rg.checkedRadioButtonId == r2.id, note.text.toString()))
                else { seg.start = s; seg.end = e; seg.mute = rg.checkedRadioButtonId == r2.id; seg.note = note.text.toString() }
                Store.save(); refreshSegs()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun addParsed(text: String, replace: Boolean) {
        val list = Util.parseSegments(text)
        if (list.isEmpty()) { toast("لم أجد أوقاتًا صالحة. مثال: 0:12:30 - 0:13:05"); return }
        if (replace) movie.segments.clear()
        movie.segments.addAll(list)
        Store.save(); refreshSegs()
        toast("أُضيف ${list.size} مقطع")
    }

    private fun pasteList() {
        val box = EditText(this).apply {
            hint = "0:12:30 - 0:13:05\n0:45:10 - 0:46:00 كتم\n1:20:00 - 1:21:30"
            minLines = 6; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle("اكتب أو الصق الأوقات (سطر لكل مقطع)").setView(box)
            .setPositiveButton("إضافة") { _, _ -> addParsed(box.text.toString(), false) }
            .setNeutralButton("استبدال القائمة") { _, _ -> addParsed(box.text.toString(), true) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun importFile(uri: Uri) {
        val text = try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            decode(bytes)
        } catch (e: Exception) { toast("تعذّرت قراءة الملف"); return }
        AlertDialog.Builder(this).setTitle("استيراد الأوقات")
            .setMessage("أأضيف المقاطع إلى القائمة الحالية أم أستبدلها؟")
            .setPositiveButton("إضافة") { _, _ -> addParsed(text, false) }
            .setNeutralButton("استبدال") { _, _ -> addParsed(text, true) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE)
        val utf = String(b, Charsets.UTF_8)
        return if (utf.contains('�')) String(b, charset("windows-1256")) else utf.removePrefix("﻿")
    }

    private fun copyList() {
        val text = movie.title + "\n" + Util.segmentsToText(movie.segments)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("skips", text))
        toast("نُسخت القائمة، ويمكنك لصقها في رسالة أو ملف")
    }

    private fun markStart() {
        pendingStart = max(0.0, curPos() / 1000.0 - 3)
        toast("بداية المقطع: ${Util.fmt(pendingStart!!)} — اضغط «نهاية مقطع» عند انتهائه")
    }

    private fun markEnd() {
        val s = pendingStart ?: run { toast("اضغط «بداية مقطع الآن» أولًا"); return }
        val e = curPos() / 1000.0 + 1
        if (e <= s) { toast("النهاية قبل البداية"); return }
        movie.segments.add(Segment(s, e))
        pendingStart = null
        Store.save(); refreshSegs()
        toast("أُضيف المقطع من ${Util.fmt(s)} إلى ${Util.fmt(e)}")
    }

    // ---------- التشغيل ----------
    private fun saveFields() {
        movie.title = titleBox.text.toString().ifBlank { movie.title }
        movie.url = urlBox.text.toString().trim()
        movie.useProxy = proxyChk.isChecked
        title = movie.title
        Store.save()
    }

    private fun castClient(): RemoteMediaClient? = castCtx?.sessionManager?.currentCastSession?.remoteMediaClient

    private fun casting(): Boolean = castClient()?.hasMediaSession() == true

    private fun castNow() {
        saveFields()
        val session = castCtx?.sessionManager?.currentCastSession
        val c = session?.remoteMediaClient
        if (c == null) {
            pendingCast = true
            toast("اضغط أيقونة البث في الأعلى واختر التلفاز، وسيبدأ العرض وحده")
            return
        }
        var url = movie.url
        val local = url.startsWith("content://") || url.startsWith("file://")
        if (local) {
            url = Proxy.wrapLocal(movie.url) ?: run { toast("الهاتف غير متصل بشبكة Wi-Fi"); return }
        } else if (movie.useProxy) {
            url = Proxy.wrap(movie.url, movie.referer, movie.userAgent)
                ?: run { toast("الهاتف غير متصل بشبكة Wi-Fi"); return }
        }
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        md.putString(MediaMetadata.KEY_TITLE, movie.title)
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (local) "video/mp4" else Util.mimeOf(movie.url))
            .setMetadata(md)
            .build()
        SkipEngine.movie = movie
        SkipEngine.attach(session)
        val startAt = player?.currentPosition ?: 0L
        player?.pause()
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(startAt).build())
            .setResultCallback { r ->
                if (!r.status.isSuccess) toast(
                    if (movie.useProxy) "تعذّر تشغيل الفيديو على التلفاز. قد يكون الرابط انتهى؛ افتح الصفحة من جديد والتقطه"
                    else "تعذّر التشغيل على التلفاز. فعّل «تمرير الفيديو عبر الهاتف» وأعد المحاولة")
            }
        KeepService.start(this)
        toast("يُرسل إلى التلفاز… والتجاوز يعمل تلقائيًا")
    }

    private fun toggleLocal() {
        if (player != null) {
            player?.release(); player = null
            playerView.player = null; playerView.visibility = View.GONE
            localBtn.text = "📱 شغّل على الهاتف"
            return
        }
        saveFields()
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        if (movie.userAgent.isNotEmpty()) ds.setUserAgent(movie.userAgent)
        if (movie.referer.isNotEmpty()) ds.setDefaultRequestProperties(mapOf("Referer" to movie.referer))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, ds))).build()
        val item = MediaItem.Builder().setUri(movie.url).apply {
            if (movie.url.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        player = p
        playerView.player = p
        playerView.visibility = View.VISIBLE
        localBtn.text = "⏹ أوقف على الهاتف"
    }

    private fun localActive() = player != null && !casting()

    private fun curPos(): Long =
        if (casting()) castClient()?.approximateStreamPosition ?: 0L else player?.currentPosition ?: 0L

    private fun duration(): Long {
        if (casting()) return castClient()?.streamDuration ?: 0L
        val d = player?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun seekTo(ms: Long) {
        val t = max(0L, ms)
        if (casting()) castClient()?.seek(MediaSeekOptions.Builder().setPosition(t).build())
        else player?.seekTo(t)
    }

    private fun togglePlay() {
        if (casting()) castClient()?.togglePlayback()
        else player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun showPos(pos: Long, dur: Long) {
        if (userSeeking) return
        posText.text = "${Util.fmt(pos / 1000.0)} / ${Util.fmt(dur / 1000.0)}"
        seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun localTick() {
        val p = player ?: return
        if (!localActive()) return
        val pos = p.currentPosition / 1000.0
        val seg = if (Store.skipEnabled) movie.segments.firstOrNull { pos >= it.start - 0.3 && pos < it.end } else null
        val now = SystemClock.elapsedRealtime()
        if (seg != null && !seg.mute && now - lastLocalSeek > 800) {
            lastLocalSeek = now
            p.seekTo((seg.end * 1000).toLong())
        }
        p.volume = if (seg?.mute == true) 0f else 1f
        showPos(p.currentPosition, duration())
    }

    override fun onResume() {
        super.onResume()
        castCtx?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        SkipEngine.onProgress = { pos, dur -> if (SkipEngine.movie === movie) showPos(pos, dur) }
        handler.post(loop)
    }

    override fun onPause() {
        super.onPause()
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        SkipEngine.onProgress = null
        handler.removeCallbacks(loop)
        if (::titleBox.isInitialized) saveFields()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }
}
