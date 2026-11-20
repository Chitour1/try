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
    private lateinit var deviceText: TextView
    private var tickN = 0
    private var phoneDetached = false
    private lateinit var nextText: TextView
    private lateinit var volText: TextView
    private lateinit var qualityBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var titleBox: EditText
    private lateinit var urlBox: EditText
    private lateinit var proxyChk: CheckBox
    private lateinit var seriesBox: EditText
    private lateinit var epBox: EditText
    private lateinit var watchChk: CheckBox
    private lateinit var secBtn: Button
    private lateinit var playerView: PlayerView
    private lateinit var pvBox: android.widget.FrameLayout
    private lateinit var overlay: SceneOverlay
    private lateinit var reader: SubReader
    private lateinit var ttsBtn: Button
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
        val devRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        deviceText = label("", 15f, true)
        devRow.add(deviceText, 1f)
        devRow.add(btn("📺 الجهاز") { chooseDevice() })
        col.add(devRow)
        statusText = label("", 14f, true).apply { setTextColor(Color.parseColor("#0E5A6B")) }
        col.add(statusText)

        val play = hbox()
        play.add(bigBtn("📺 اعرض على التلفاز") { castNow() }, 1f)
        localBtn = btn("📱 على الهاتف") { toggleLocal() }
        play.add(localBtn, 0.7f)
        col.add(play)

        pvBox = android.widget.FrameLayout(this).apply { visibility = View.GONE }
        playerView = PlayerView(this)
        pvBox.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, -1))
        overlay = SceneOverlay(pvBox, false)
        reader = SubReader(this).also { it.mode = Store.ttsMode }
        col.add(pvBox, h = dp(220))

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
        val r4 = hbox()
        r4.add(btn("🔌 فصل الهاتف (يكمل التلفاز)") { detachPhone() }, 1.4f)
        r4.add(btn("🔗 وصل التحكم") { phoneDetached = false; TvLink.poll { }; statusText.text = "عاد التحكم من الهاتف" }, 1f)
        col.add(r4)
        nextText = label("", 13f)
        col.add(nextText)

        val marks = hbox()
        marks.add(btn("⏺ بداية مقطع الآن") { markStart() }, 1f)
        marks.add(btn("⏹ نهاية مقطع الآن") { markEnd() }, 1f)
        col.add(marks)

        val sw = SwitchCompat(this).apply {
            text = "التجاوز مفعّل"; isChecked = Store.skipEnabled; textSize = 16f
            setOnCheckedChangeListener { _, c -> Store.skipEnabled = c; if (tvActive()) TvLink.cmd("skip", "on" to c) }
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
            setOnCheckedChangeListener { _, c -> movie.subsOn = c; Store.save(); if (tvActive()) TvLink.cmd("subs", "on" to c, "scale" to Store.subScale.toDouble()) else if (casting()) CastCtl.setSubs(c) }
        }
        col.add(subSw)
        subBox = vbox()
        col.add(subBox)
        val sr = hbox()
        sr.add(btn("➕ ترجمة من ملف") { pickSub.launch(arrayOf("*/*")) }, 1f)
        sr.add(btn("الخط −") { subScale(-0.15f) }, 0.6f)
        sr.add(btn("الخط +") { subScale(0.15f) }, 0.6f)
        col.add(sr)
        ttsBtn = btn("🔊 قراءة الترجمة بصوت: " + SubReader.names[Store.ttsMode.coerceIn(0, 2)]) { chooseTts() }
        col.add(ttsBtn)
        val cr = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        cr.add(label("حجم الغطاء الأسود:", 14f), 1f)
        cr.add(btn("أصغر") { coverScale(-0.15f) }, 0.6f)
        cr.add(btn("أكبر") { coverScale(0.15f) }, 0.6f)
        col.add(cr)

        // ---- البيانات ----
        col.add(label("بيانات الفيلم", 17f, true))
        titleBox = EditText(this).apply { hint = "اسم الفيلم"; setText(movie.title) }
        col.add(titleBox)
        urlBox = EditText(this).apply { hint = "رابط الفيديو"; setText(movie.url); textSize = 12f; maxLines = 3 }
        col.add(urlBox)
        val libRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        secBtn = btn("📁 القسم: ${movie.section}") {
            val secs = Store.sections
            AlertDialog.Builder(this).setTitle("القسم").setItems(secs.toTypedArray()) { _, i ->
                movie.section = secs[i]; secBtn.text = "📁 القسم: ${movie.section}"; Store.save() }.show()
        }
        libRow.add(secBtn, 1f)
        watchChk = CheckBox(this).apply { text = "⭐ قائمة المشاهدة"; isChecked = movie.watch }
        libRow.add(watchChk)
        col.add(libRow)
        val serRow = hbox()
        seriesBox = EditText(this).apply { hint = "اسم المسلسل (إن كان حلقة)"; setText(movie.series) }
        serRow.add(seriesBox, 1f)
        epBox = EditText(this).apply { hint = "رقم الحلقة"; inputType = InputType.TYPE_CLASS_NUMBER; setText(if (movie.ep > 0) "${movie.ep}" else "") }
        serRow.add(epBox, 0.5f)
        col.add(serRow)
        proxyChk = CheckBox(this).apply { text = "تمرير الفيديو عبر الهاتف (يُختار تلقائيًا إذا رفض التلفاز الرابط)"; isChecked = movie.useProxy }
        col.add(proxyChk)

        col.add(label(
            "طرق إدخال الأوقات:\n" +
            "• سطر لكل مقطع: الوقتان ثم ما تريد. مثل: 0:12:30 - 0:13:05\n" +
            "• بعد الوقتين: «كتم» لكتم الصوت، و«غطاء» لتغطية الترجمة بشريط أسود، ويمكن جمعهما «كتم غطاء».\n" +
            "• أي كلام بعد ذلك هو ملخص يراه المشاهد: في التجاوز يتوقف الفيلم ويظهر في بطاقة، وفي الغطاء يُكتب على الشريط.\n" +
            "• ملف srt بالطريقة نفسها: وقت كل ترجمة هو المقطع، ونصها هو «كتم» أو «غطاء» أو الملخص.\n" +
            "• ملف txt بالصيغة السطرية نفسها، أو ملف edl.\n" +
            "• أثناء المشاهدة: «بداية مقطع الآن» تسجّل قبل اللحظة بثلاث ثوانٍ، و«نهاية مقطع الآن» بعدها بثانية.\n" +
            "• أزرار الصوت في الهاتف تتحكم في صوت التلفاز أثناء العرض.", 13f))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------- المقاطع ----------
    private fun refreshSegs() {
        if (built && tvActive()) TvLink.post("/cmd", org.json.JSONObject().put("a", "segments").put("id", movie.id).put("list", TvLink.segJson(movie)))
        movie.segments.sortBy { it.start }
        segBox.removeAllViews()
        if (movie.segments.isEmpty()) { segBox.add(label("لا مقاطع بعد.", 14f)); return }
        movie.segments.forEachIndexed { i, s ->
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            val t = label("${i + 1}) من ${Util.fmt(s.start)} إلى ${Util.fmt(s.end)} — ${s.kind()}" +
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
        val opts = listOf("تجاوز المقطع", "كتم الصوت", "كتم الصوت + غطاء أسود على الترجمة", "غطاء أسود على الترجمة فقط")
        val ids = opts.map { t -> RadioButton(this).apply { text = t; id = View.generateViewId() }.also { rg.addView(it) }.id }
        val cur = when { seg == null -> 0; !seg.mute && !seg.cover -> 0; seg.mute && !seg.cover -> 1; seg.mute -> 2; else -> 3 }
        rg.check(ids[cur])
        val count = label("", 12f)
        val note = EditText(this).apply {
            hint = "ملخص يراه المشاهد (اختياري): مثل «في هذا المشهد يتعرّف سامي على ليلى»"
            setText(seg?.note ?: ""); minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        fun upd() {
            val t = note.text.toString()
            val n = t.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
            count.text = if (n == 0) "بلا ملخص" else
                "$n كلمة • ${Util.slides(t).size} شريحة • نحو ${(Util.totalMs(t) + 500) / 1000} ثانية" +
                (if (rg.checkedRadioButtonId == ids[0]) " (يتوقف الفيلم حتى تنتهي)" else " (تظهر على الغطاء)")
        }
        note.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { upd() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        rg.setOnCheckedChangeListener { _, _ -> upd() }
        upd()
        box.add(label("البداية")); box.add(st); box.add(label("النهاية")); box.add(en); box.add(rg); box.add(note); box.add(count)
        AlertDialog.Builder(this).setTitle(if (seg == null) "مقطع جديد" else "تعديل المقطع").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                val s = Util.parse(st.text.toString()); val e = Util.parse(en.text.toString())
                if (s == null || e == null || e <= s) { toast("الوقت غير صحيح: يجب أن تكون النهاية بعد البداية"); return@setPositiveButton }
                val k = ids.indexOf(rg.checkedRadioButtonId).coerceAtLeast(0)
                val mute = k == 1 || k == 2; val cover = k == 2 || k == 3
                if (seg == null) movie.segments.add(Segment(s, e, mute, note.text.toString().trim(), cover))
                else { seg.start = s; seg.end = e; seg.mute = mute; seg.cover = cover; seg.note = note.text.toString().trim() }
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
            hint = "0:12:30 - 0:13:05\n0:30:00 - 0:31:10 في هذا المشهد يتعرّف سامي على ليلى\n0:45:10 - 0:46:00 كتم\n0:52:00 - 0:52:20 كتم غطاء يهدده بالقتل إن لم يدفع"
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

    private fun chooseTts() {
        AlertDialog.Builder(this).setTitle("قراءة الترجمة بصوت")
            .setItems(arrayOf("متوقفة", "تقرأ مع خفض صوت الفيلم أثناء القراءة", "تقرأ بدل صوت الفيلم")) { _, k ->
                Store.ttsMode = k; reader.mode = k
                ttsBtn.text = "🔊 قراءة الترجمة بصوت: " + SubReader.names[k]
                if (tvActive()) TvLink.cmd("tts", "mode" to k)
                if (k != 0 && movie.subs.isEmpty()) toast("أضف ترجمة للفيلم أولًا، فالقراءة تكون من الترجمة")
            }.show()
    }

    private fun coverScale(d: Float) {
        Store.coverScale = (Store.coverScale + d).coerceIn(0.5f, 2.5f)
        if (tvActive()) TvLink.cmd("coverscale", "v" to Store.coverScale.toDouble())
        toast("حجم الغطاء: ${(Store.coverScale * 100).toInt()}٪")
    }

    private fun subScale(d: Float) {
        Store.subScale = (Store.subScale + d).coerceIn(0.6f, 2.5f)
        if (tvActive()) TvLink.cmd("subscale", "v" to Store.subScale.toDouble()) else CastCtl.restyleSubs()
        toast("حجم الخط: ${(Store.subScale * 100).toInt()}٪")
    }

    // ---------- الجودة ----------
    private fun chooseQuality() {
        if (tvActive()) {
            val q = TvLink.last?.optJSONArray("qualities")
            val hs = (0 until (q?.length() ?: 0)).map { q!!.getInt(it) }
            if (hs.isEmpty()) { toast("لم يجد التلفاز جودات أخرى لهذا الفيلم"); return }
            AlertDialog.Builder(this).setTitle("الجودة على التلفاز")
                .setItems((listOf("تلقائي") + hs.map { "${it}p" }).toTypedArray()) { _, i ->
                    TvLink.cmd("quality", "h" to (if (i == 0) 0 else hs[i - 1]))
                    qualityBtn.text = "⚙️ الجودة" + if (i > 0) ": ${hs[i - 1]}p" else ""
                }.show()
            return
        }
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
        movie.watch = watchChk.isChecked
        movie.series = seriesBox.text.toString().trim()
        movie.ep = epBox.text.toString().toIntOrNull() ?: if (movie.series.isNotBlank() && movie.ep == 0) (Store.episodes(movie.series).maxOfOrNull { it.ep } ?: 0) + 1 else movie.ep
        title = movie.title
        Store.save()
    }

    private fun casting(): Boolean = CastCtl.active() && CastCtl.movie === movie

    private fun reloadHere() {
        if (tvActive()) castNow() else CastCtl.load(movie, CastCtl.lastPos)
    }

    // ---------- اختيار الجهاز ----------
    private fun tvActive(): Boolean {
        if (!TvLink.useTv() || phoneDetached) return false
        val l = TvLink.last ?: return false
        return l.optString("id") == movie.id && l.optString("state") != "idle"
    }

    private var tvChecked = false

    private fun notFound() {
        statusText.text = "لم أجد تطبيق الاستقبال على التلفاز"
        AlertDialog.Builder(this).setTitle("لم أجد التلفاز")
            .setMessage("افتح تطبيق «عرض عائلي» على التلفاز حتى تظهر شاشة «جهاز الاستقبال جاهز»، وتأكد أن الهاتف والتلفاز على شبكة Wi-Fi نفسها، ثم أعد المحاولة.\n\nإن لم ينجح البحث، فاكتب العنوان الظاهر على شاشة التلفاز.")
            .setPositiveButton("أعد البحث") { _, _ -> castNow() }
            .setNeutralButton("كتابة العنوان") { _, _ -> manualAddress(thenCast = true) }
            .setNegativeButton("عبر Chromecast") { _, _ -> TvLink.selectCast(); refreshDevice(); castNow() }
            .show()
    }

    private fun refreshDevice() {
        if (!built) return
        deviceText.text = when {
            !TvLink.preferTv() -> "الجهاز: Chromecast (أيقونة البث)"
            TvLink.selected() != null -> "الجهاز: ${TvLink.selected()?.name} (تطبيق الاستقبال)"
            else -> "الجهاز: تطبيق الاستقبال على التلفاز (يُبحث عنه)"
        }
    }

    private fun chooseDevice() {
        val rxs = TvLink.found.values.toList()
        val names = rxs.map { "📺 ${it.name} (${it.host})" } + listOf("🔍 ابحث عن التلفاز من جديد", "Chromecast (أيقونة البث)", "إدخال عنوان التلفاز يدويًا")
        AlertDialog.Builder(this).setTitle(if (rxs.isEmpty()) "لم أجد تطبيق الاستقبال بعد — افتحه على التلفاز" else "اختر الجهاز")
            .setItems(names.toTypedArray()) { _, i ->
                when {
                    i < rxs.size -> { TvLink.select(rxs[i]); phoneDetached = false; refreshDevice() }
                    i == rxs.size -> {
                        statusText.text = "أبحث في الشبكة…"
                        TvLink.scan { statusText.text = if (TvLink.found.isEmpty()) "لم أجد تطبيق الاستقبال" else "وجدت: " + TvLink.found.keys.joinToString("، "); chooseDevice() }
                    }
                    i == rxs.size + 1 -> { TvLink.selectCast(); refreshDevice() }
                    else -> manualAddress()
                }
            }.show()
    }

    private fun manualAddress(thenCast: Boolean = false) {
        val box = EditText(this).apply { hint = "مثل 192.168.1.50 (يظهر على شاشة التلفاز)"; inputType = InputType.TYPE_CLASS_PHONE }
        AlertDialog.Builder(this).setTitle("عنوان التلفاز").setView(box)
            .setPositiveButton("اتصال") { _, _ ->
                val h = box.text.toString().trim()
                TvLink.probe(h) { rx ->
                    if (rx == null) toast("لم أصل إلى التلفاز على هذا العنوان. تأكد أن «عرض عائلي» مفتوح عليه")
                    else { TvLink.select(rx); phoneDetached = false; refreshDevice(); toast("اتصلت بـ «${rx.name}»"); if (thenCast) castNow() }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    /** يترك الفيلم يعمل على التلفاز ويوقف تحكم الهاتف */
    private fun detachPhone() {
        phoneDetached = true
        statusText.text = "فُصل الهاتف. الفيلم مستمر على التلفاز، ويمكنك العودة للتحكم من «📺 اعرض على التلفاز» أو «وصل التحكم»."
    }

    private fun castNow() {
        saveFields()
        if (TvLink.preferTv() && !tvChecked) {
            statusText.text = "أبحث عن تطبيق الاستقبال على التلفاز…"
            TvLink.ensure(this) { rx ->
                if (rx != null) { tvChecked = true; refreshDevice(); castNow(); tvChecked = false }
                else notFound()
            }
            return
        }
        if (TvLink.useTv()) {
            phoneDetached = false
            val l = TvLink.last
            val given = intent.getLongExtra("pos", 0L).also { intent.removeExtra("pos") }
            val startAt = when {
                l != null && l.optString("id") == movie.id && l.optString("state") != "idle" -> l.optLong("pos")
                player != null -> player!!.currentPosition
                given > 0 -> given
                else -> Store.resumeAt(movie)
            }
            val j = TvLink.movieJson(movie, startAt) ?: run { toast("الهاتف غير متصل بشبكة Wi-Fi"); return }
            val name = TvLink.selected()?.name ?: "التلفاز"
            statusText.text = "يُرسل إلى «$name»…"
            TvLink.post("/load", j) { ok ->
                if (ok) {
                    player?.pause()
                    statusText.text = "يُعرض على «$name» بإنترنت التلفاز" + if (startAt > 0) " — من ${Util.fmt(startAt / 1000.0)}" else ""
                } else statusText.text = "لم أصل إلى «$name». افتح «عرض عائلي» على التلفاز، وتأكد أن الهاتف والتلفاز على الشبكة نفسها"
            }
            return
        }
        val given = intent.getLongExtra("pos", 0L).also { intent.removeExtra("pos") }
        val startAt = player?.currentPosition ?: (if (CastCtl.movie === movie) CastCtl.lastPos else given.takeIf { it > 0 } ?: Store.resumeAt(movie))
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
        if (tvActive()) { TvLink.cmd("stop"); statusText.text = "أُوقف العرض على التلفاز"; return }
        CastCtl.stop()
    }

    private fun volume(d: Double) {
        if (tvActive()) TvLink.cmd("vol", "d" to (if (d > 0) 1 else -1))
        else if (CastCtl.session() != null) CastCtl.volume(d)
        else (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            if (d > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
    }

    private fun muteToggle() {
        if (tvActive()) TvLink.cmd("mute")
        else if (CastCtl.session() != null) CastCtl.toggleMute()
        else (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (tvActive() && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { TvLink.cmd("vol", "d" to 1); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { TvLink.cmd("vol", "d" to -1); return true }
            }
        }
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
            playerView.player = null; pvBox.visibility = View.GONE; overlay.hideAll()
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
        val subsCfg = movie.subs.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(if (s.url.startsWith("file") || s.url.lowercase().contains(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar").setLabel(s.label).setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val item = MediaItem.Builder().setUri(src).setSubtitleConfigurations(subsCfg).apply {
            if (src.lowercase().contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (lastMutedBySkip) return
                val t = cueGroup.cues.joinToString(" ") { it.text?.toString() ?: "" }
                if (t.isNotBlank()) reader.speak(t)
            }
        })
        p.setMediaItem(item)
        p.prepare()
        if (CastCtl.movie === movie && CastCtl.lastPos > 0) p.seekTo(CastCtl.lastPos)
        p.play()
        player = p
        playerView.player = p
        pvBox.visibility = View.VISIBLE
        localBtn.text = "⏹ أوقف الهاتف"
    }

    private fun localActive() = player != null && !casting() && !tvActive()

    private fun curPos(): Long =
        if (tvActive()) TvLink.last?.optLong("pos") ?: 0L
        else if (casting()) CastCtl.client()?.approximateStreamPosition ?: CastCtl.lastPos else player?.currentPosition ?: 0L

    private fun duration(): Long {
        if (tvActive()) return TvLink.last?.optLong("dur") ?: 0L
        if (casting()) return CastCtl.client()?.streamDuration?.takeIf { it > 0 } ?: CastCtl.lastDur
        val d = player?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun seekTo(ms: Long) {
        val t = max(0L, ms)
        if (tvActive()) { TvLink.cmd("seek", "ms" to t); TvLink.last?.put("pos", t) }
        else if (casting()) CastCtl.seekTo(t) else player?.seekTo(t)
    }

    private fun togglePlay() {
        if (tvActive()) TvLink.cmd("toggle")
        else if (::overlay.isInitialized && overlay.cardActive) overlay.finishCard()
        else if (casting()) CastCtl.client()?.togglePlayback()
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
        tickN++
        if (TvLink.useTv() && !phoneDetached) {
            if (tickN % 4 == 0) TvLink.poll { }
            val l = TvLink.last
            if (l != null && l.optString("id") == movie.id && l.optString("state") != "idle") {
                val pos = l.optLong("pos"); val dur = l.optLong("dur")
                showPos(pos, dur)
                Store.savePos(movie, pos, dur)
                volText.text = if (l.optBoolean("mute")) "مكتوم" else "${l.optInt("vol")}٪"
                val st = when (l.optString("state")) {
                    "playing" -> "▶ يُعرض"; "paused" -> "⏸ متوقف مؤقتًا"; "buffering" -> "⏳ يُحمّل"
                    "ended" -> "انتهى الفيلم"; "error" -> "⚠ تعذّر التشغيل"; else -> ""
                }
                val age = System.currentTimeMillis() - TvLink.lastOk
                statusText.text = if (age > 6000) "⚠ انقطع الاتصال بالتلفاز… الفيلم يكمل عليه" else "$st على «${l.optString("name")}» • ${l.optString("msg")}"
                return
            }
        }
        if (casting()) {
            if (CastCtl.movie === movie) Store.savePos(movie, CastCtl.lastPos, CastCtl.lastDur)
            showPos(curPos(), duration())
            volText.text = CastCtl.volumeText().ifBlank { "الصوت" }
            return
        }
        val p = player ?: return
        Store.savePos(movie, p.currentPosition, duration())
        val pos = p.currentPosition / 1000.0
        val seg = if (Store.skipEnabled && !overlay.cardActive) movie.segments.firstOrNull { pos >= it.start - 0.3 && pos < it.end } else null
        val now = SystemClock.elapsedRealtime()
        if (seg != null && !seg.playsThrough() && now - lastLocalSeek > 800) {
            lastLocalSeek = now
            p.seekTo((seg.end * 1000).toLong())
            if (seg.note.isNotBlank()) { p.pause(); reader.speak(seg.note, reader.mode != 0); overlay.showCard(seg.note) { reader.stop(); player?.play() } }
        }
        val through = seg?.takeIf { it.playsThrough() }
        overlay.cover(through, if (through != null) ((pos - through.start) * 1000).toLong().coerceAtLeast(0L) else 0L, Store.coverScale)
        if (seg?.mute == true && !lastMutedBySkip) reader.stop()
        val vol = if (seg?.mute == true) 0f else reader.filmVolume()
        if (p.volume != vol) p.volume = vol
        lastMutedBySkip = seg?.mute == true
        volText.text = "${(p.volume * 100).toInt()}٪"
        showPos(p.currentPosition, duration())
    }
    private var lastMutedBySkip = false

    override fun onResume() {
        super.onResume()
        TvLink.start(this)
        TvLink.onChange = { refreshDevice() }
        refreshDevice()
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
        TvLink.onChange = null
        TvLink.syncLibrary()
        handler.removeCallbacks(loop)
        saveFields()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        if (::reader.isInitialized) reader.shutdown()
        super.onDestroy()
    }
}
