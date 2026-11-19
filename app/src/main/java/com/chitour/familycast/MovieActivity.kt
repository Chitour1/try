package com.chitour.familycast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
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
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.File
import kotlin.math.max

class MovieActivity : AppCompatActivity() {
    private lateinit var movie: Movie
    private lateinit var segBox: LinearLayout
    private lateinit var subBox: LinearLayout
    private lateinit var posText: TextView
    private lateinit var statusText: TextView
    private lateinit var nextText: TextView
    private lateinit var volText: TextView
    private lateinit var qualityBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var titleBox: EditText
    private lateinit var urlBox: EditText
    private lateinit var proxyChk: CheckBox
    private lateinit var playerView: PlayerView
    private lateinit var localBtn: Button
    private var player: ExoPlayer? = null
    private var castCtx: CastContext? = null
    private var pendingCast = false
    private var pendingStart: Double? = null
    private var userSeeking = false
    private var lastLocalSeek = 0L
    private var built = false
    private val handler = Handler(Looper.getMainLooper())

    private val pickTimes = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importTimes(uri)
    }
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importSub(uri)
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
        override fun run() {
            try { tick() } catch (e: Exception) { }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: CastCtl.movie?.id
        val m = id?.let { Store.get(it) }
        if (m == null) { finish(); return }
        movie = m
        title = m.title
        castCtx = try { CastContext.getSharedInstance(this) } catch (e: Exception) { null }
        buildUi()
        built = true
        refreshSegs(); refreshSubs()
        if (intent.getBooleanExtra("autocast", false) && savedInstanceState == null) handler.postDelayed({ castNow() }, 400)
    }

    private fun bigBtn(t: String, f: () -> Unit) = btn(t, f).apply { textSize = 18f; minHeight = dp(52) }

    private fun buildUi() {
        val col = vbox().apply { setPadding(dp(12), dp(8), dp(12), dp(24)) }

        val castRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val route = MediaRouteButton(this)
        try { CastButtonFactory.setUpMediaRouteButton(applicationContext, route) } catch (e: Exception) { }
        castRow.add(route)
        castRow.add(label("← أيقونة البث: اضغطها واختر التلفاز", 13f), 1f)
        col.add(castRow)
        statusText = label("", 14f, true).apply { setTextColor(Color.parseColor("#0E5A6B")) }
        col.add(statusText)

        val play = hbox()
        play.add(bigBtn("📺 اعرض على التلفاز") { castNow() }, 1f)
        localBtn = btn("📱 على الهاتف") { toggleLocal() }
        play.add(localBtn, 0.7f)
        col.add(play)

        playerView = PlayerView(this).apply { visibility = View.GONE }
        col.add(playerView, h = dp(220))

        // ---- جهاز التحكم ----
        col.add(label("جهاز التحكم", 17f, true))
        val ctl = vbox().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        posText = label("0:00:00 / 0:00:00", 20f, true).apply { gravity = Gravity.CENTER }
        ctl.add(posText)
        seek = SeekBar(this).apply { max = 1000; minimumHeight = dp(36) }
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
        val r1 = hbox()
        r1.add(bigBtn("⏪ 10") { seekTo(curPos() - 10_000) }, 1f)
        r1.add(bigBtn("⏯") { togglePlay() }, 1f)
        r1.add(bigBtn("10 ⏩") { seekTo(curPos() + 10_000) }, 1f)
        ctl.add(r1)
        val r2 = hbox()
        r2.add(btn("−5 د") { seekTo(curPos() - 300_000) }, 1f)
        r2.add(btn("−1 د") { seekTo(curPos() - 60_000) }, 1f)
        r2.add(btn("−30") { seekTo(curPos() - 30_000) }, 1f)
        r2.add(btn("+30") { seekTo(curPos() + 30_000) }, 1f)
        r2.add(btn("+1 د") { seekTo(curPos() + 60_000) }, 1f)
        r2.add(btn("+5 د") { seekTo(curPos() + 300_000) }, 1f)
        ctl.add(r2)
        col.add(ctl)

        val vol = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        vol.add(bigBtn("🔉 −") { volume(-0.05) }, 1f)
        volText = label("الصوت", 15f, true).apply { gravity = Gravity.CENTER }
        vol.add(volText, 0.8f)
        vol.add(bigBtn("🔊 +") { volume(0.05) }, 1f)
        vol.add(btn("🔇") { muteToggle() }, 0.6f)
        col.add(vol)

        val r3 = hbox()
        qualityBtn = btn("⚙️ الجودة" + if (movie.variantLabel.isNotBlank()) ": ${movie.variantLabel}" else "") { chooseQuality() }
        r3.add(qualityBtn, 1f)
        r3.add(btn("⏹ إيقاف العرض") { stopAll() }, 1f)
        col.add(r3)
        nextText = label("", 13f)
        col.add(nextText)

        val marks = hbox()
        marks.add(btn("⏺ بداية مقطع الآن") { markStart() }, 1f)
        marks.add(btn("⏹ نهاية مقطع الآن") { markEnd() }, 1f)
        col.add(marks)

        val sw = SwitchCompat(this).apply {
            text = "التجاوز مفعّل"; isChecked = Store.skipEnabled; textSize = 16f
            setOnCheckedChangeListener { _, c -> Store.skipEnabled = c }
        }
        col.add(sw)

        // ---- المقاطع ----
        col.add(label("المقاطع المحذوفة", 17f, true))
        segBox = vbox()
        col.add(segBox)
        val add1 = hbox()
        add1.add(btn("➕ إضافة مقطع") { editSeg(null) }, 1f)
        add1.add(btn("📝 كتابة قائمة") { pasteList() }, 1f)
        col.add(add1)
        val add2 = hbox()
        add2.add(btn("📂 من ملف txt أو srt") { pickTimes.launch(arrayOf("*/*")) }, 1f)
        add2.add(btn("📤 مشاركة القائمة") { shareList() }, 1f)
        col.add(add2)

        // ---- الترجمة ----
        col.add(label("الترجمة", 17f, true))
        val subSw = SwitchCompat(this).apply {
            text = "إظهار الترجمة على التلفاز"; isChecked = movie.subsOn; textSize = 15f
            setOnCheckedChangeListener { _, c -> movie.subsOn = c; Store.save(); if (casting()) CastCtl.setSubs(c) }
        }
        col.add(subSw)
        subBox = vbox()
        col.add(subBox)
        val sr = hbox()
        sr.add(btn("➕ ترجمة من ملف") { pickSub.launch(arrayOf("*/*")) }, 1f)
        sr.add(btn("الخط −") { subScale(-0.15f) }, 0.6f)
        sr.add(btn("الخط +") { subScale(0.15f) }, 0.6f)
        col.add(sr)

        // ---- البيانات ----
        col.add(label("بيانات الفيلم", 17f, true))
        titleBox = EditText(this).apply { hint = "اسم الفيلم"; setText(movie.title) }
        col.add(titleBox)
        urlBox = EditText(this).apply { hint = "رابط الفيديو"; setText(movie.url); textSize = 12f; maxLines = 3 }
        col.add(urlBox)
        proxyChk = CheckBox(this).apply { text = "تمرير الفيديو عبر الهاتف (يُختار تلقائيًا إذا رفض التلفاز الرابط)"; isChecked = movie.useProxy }
        col.add(proxyChk)

        col.add(label(
            "طرق إدخال الأوقات:\n" +
            "• سطر لكل مقطع: 0:12:30 - 0:13:05 (ويُكتب «كتم» في السطر لكتم الصوت بدل التجاوز).\n" +
            "• ملف srt: كل ترجمة فيه مقطع يُحذف، وإن كان نصها «كتم» يُكتم صوتها.\n" +
            "• ملف txt بالصيغة السطرية نفسها، أو ملف edl.\n" +
            "• أثناء المشاهدة: «بداية مقطع الآن» تسجّل قبل اللحظة بثلاث ثوانٍ، و«نهاية مقطع الآن» بعدها بثانية.\n" +
            "• أزرار الصوت في الهاتف تتحكم في صوت التلفاز أثناء العرض.", 13f))

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

    private fun readText(uri: Uri): String? = try {
        Util.decodeText(contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
    } catch (e: Exception) { null }

    private fun importTimes(uri: Uri) {
        val text = readText(uri) ?: run { toast("تعذّرت قراءة الملف"); return }
        AlertDialog.Builder(this).setTitle("استيراد الأوقات")
            .setMessage("أأضيف المقاطع إلى القائمة الحالية أم أستبدلها؟")
            .setPositiveButton("إضافة") { _, _ -> addParsed(text, false) }
            .setNeutralButton("استبدال") { _, _ -> addParsed(text, true) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun shareList() {
        val text = movie.title + "\n" + Util.segmentsToText(movie.segments)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("skips", text))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "مشاركة قائمة التجاوز"))
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

    // ---------- الترجمة ----------
    private fun refreshSubs() {
        subBox.removeAllViews()
        if (movie.subs.isEmpty()) { subBox.add(label("لا ترجمة. تُلتقط تلقائيًا من صفحة الفيلم إن وُجدت، أو أضفها من ملف srt.", 13f)); return }
        movie.subs.forEachIndexed { i, s ->
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            row.add(label((if (i == 0) "✔ " else "") + s.label, 14f), 1f)
            if (i > 0) row.add(btn("اعتمدها") {
                movie.subs.removeAt(i); movie.subs.add(0, s); Store.save(); refreshSubs()
                if (casting()) reloadHere()
            })
            row.add(btn("حذف") { movie.subs.removeAt(i); Store.save(); refreshSubs() })
            subBox.add(row)
        }
    }

    private fun importSub(uri: Uri) {
        val text = readText(uri) ?: run { toast("تعذّرت قراءة الملف"); return }
        val dir = File(filesDir, "subs").apply { mkdirs() }
        val f = File(dir, "${movie.id}_${System.currentTimeMillis()}.vtt")
        f.writeText(Util.toVtt(text))
        val name = uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "ترجمة"
        movie.subs.add(0, Sub("file://" + f.absolutePath, name))
        movie.subsOn = true
        Store.save(); refreshSubs()
        toast("أُضيفت الترجمة")
        if (casting()) reloadHere()
    }

    private fun subScale(d: Float) {
        Store.subScale = (Store.subScale + d).coerceIn(0.6f, 2.5f)
        CastCtl.restyleSubs()
        toast("حجم الخط: ${(Store.subScale * 100).toInt()}٪")
    }

    // ---------- الجودة ----------
    private fun chooseQuality() {
        if (!movie.url.lowercase().contains("m3u8")) {
            toast("هذا الفيديو ملف بجودة واحدة. إن كان الموقع يعرض جودات أخرى فاخترها في الصفحة والتقطها من جديد")
            return
        }
        toast("أبحث عن الجودات المتاحة…")
        Thread {
            val list = try { Probe.variants(movie.url, movie.referer, movie.userAgent) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (list.isEmpty()) { toast("لم أجد جودات متعددة لهذا الفيديو"); return@runOnUiThread }
                val names = listOf("تلقائي (يختار التلفاز)") + list.map { it.first }
                AlertDialog.Builder(this).setTitle("اختر الجودة")
                    .setItems(names.toTypedArray()) { _, i ->
                        if (i == 0) { movie.variant = ""; movie.variantLabel = "" }
                        else { movie.variant = list[i - 1].second; movie.variantLabel = list[i - 1].first.substringBefore(' ') }
                        Store.save()
                        qualityBtn.text = "⚙️ الجودة" + if (movie.variantLabel.isNotBlank()) ": ${movie.variantLabel}" else ""
                        if (casting()) reloadHere()
                    }.show()
            }
        }.start()
    }

    // ---------- التشغيل ----------
    private fun saveFields() {
        if (!built) return
        movie.title = titleBox.text.toString().ifBlank { movie.title }
        val newUrl = urlBox.text.toString().trim()
        if (newUrl != movie.url) { movie.url = newUrl; movie.variant = ""; movie.variantLabel = "" }
        movie.useProxy = proxyChk.isChecked
        title = movie.title
        Store.save()
    }

    private fun casting(): Boolean = CastCtl.active() && CastCtl.movie === movie

    private fun reloadHere() {
        CastCtl.load(movie, CastCtl.lastPos)
    }

    private fun castNow() {
        saveFields()
        val given = intent.getLongExtra("pos", 0L).also { intent.removeExtra("pos") }
        val startAt = player?.currentPosition ?: (if (CastCtl.movie === movie) CastCtl.lastPos else given)
        val err = CastCtl.load(movie, startAt)
        if (err == "NO_SESSION") {
            pendingCast = true
            toast("اضغط أيقونة البث في الأعلى واختر التلفاز، وسيبدأ العرض وحده")
            return
        }
        if (err != null) { toast(err); return }
        player?.pause()
    }

    private fun stopAll() {
        player?.pause()
        CastCtl.stop()
    }

    private fun volume(d: Double) {
        if (CastCtl.session() != null) CastCtl.volume(d)
        else player?.let { it.volume = (it.volume + d.toFloat()).coerceIn(0f, 1f) }
    }

    private fun muteToggle() {
        if (CastCtl.session() != null) CastCtl.toggleMute()
        else player?.let { it.volume = if (it.volume > 0f) 0f else 1f }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CastCtl.session() != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { CastCtl.volume(0.05); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { CastCtl.volume(-0.05); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleLocal() {
        if (player != null) {
            player?.release(); player = null
            playerView.player = null; playerView.visibility = View.GONE
            localBtn.text = "📱 على الهاتف"
            return
        }
        saveFields()
        val src = movie.variant.ifBlank { movie.url }
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val (uaLine, extra) = Util.splitHeaders(movie.userAgent)
        if (uaLine.isNotEmpty()) ds.setUserAgent(uaLine)
        val props = HashMap(extra)
        if (movie.referer.isNotEmpty()) props["Referer"] = movie.referer
        if (props.isNotEmpty()) ds.setDefaultRequestProperties(props)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, ds))).build()
        val item = MediaItem.Builder().setUri(src).apply {
            if (src.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.prepare()
        if (CastCtl.movie === movie && CastCtl.lastPos > 0) p.seekTo(CastCtl.lastPos)
        p.play()
        player = p
        playerView.player = p
        playerView.visibility = View.VISIBLE
        localBtn.text = "⏹ أوقف الهاتف"
    }

    private fun localActive() = player != null && !casting()

    private fun curPos(): Long =
        if (casting()) CastCtl.client()?.approximateStreamPosition ?: CastCtl.lastPos else player?.currentPosition ?: 0L

    private fun duration(): Long {
        if (casting()) return CastCtl.client()?.streamDuration?.takeIf { it > 0 } ?: CastCtl.lastDur
        val d = player?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun seekTo(ms: Long) {
        val t = max(0L, ms)
        if (casting()) CastCtl.seekTo(t) else player?.seekTo(t)
    }

    private fun togglePlay() {
        if (casting()) CastCtl.client()?.togglePlayback()
        else player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun showPos(pos: Long, dur: Long) {
        if (userSeeking) return
        posText.text = "${Util.fmt(pos / 1000.0)} / ${Util.fmt(dur / 1000.0)}"
        seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        val p = pos / 1000.0
        val next = if (Store.skipEnabled) movie.segments.firstOrNull { it.start > p } else null
        nextText.text = if (next != null) "المقطع المحذوف التالي بعد ${Util.fmt(next.start - p)} (عند ${Util.fmt(next.start)})" else ""
    }

    private fun tick() {
        if (casting()) {
            showPos(curPos(), duration())
            volText.text = CastCtl.volumeText().ifBlank { "الصوت" }
            return
        }
        val p = player ?: return
        val pos = p.currentPosition / 1000.0
        val seg = if (Store.skipEnabled) movie.segments.firstOrNull { pos >= it.start - 0.3 && pos < it.end } else null
        val now = SystemClock.elapsedRealtime()
        if (seg != null && !seg.mute && now - lastLocalSeek > 800) {
            lastLocalSeek = now
            p.seekTo((seg.end * 1000).toLong())
        }
        if (seg?.mute == true) p.volume = 0f else if (p.volume == 0f && lastMutedBySkip) p.volume = 1f
        lastMutedBySkip = seg?.mute == true
        volText.text = "${(p.volume * 100).toInt()}٪"
        showPos(p.currentPosition, duration())
    }
    private var lastMutedBySkip = false

    override fun onResume() {
        super.onResume()
        castCtx?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        CastCtl.onStatus = { s -> runOnUiThread { if (built) statusText.text = s } }
        statusText.text = CastCtl.status
        proxyChk.isChecked = movie.useProxy
        handler.post(loop)
    }

    override fun onPause() {
        super.onPause()
        castCtx?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        CastCtl.onStatus = null
        handler.removeCallbacks(loop)
        saveFields()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }
}
