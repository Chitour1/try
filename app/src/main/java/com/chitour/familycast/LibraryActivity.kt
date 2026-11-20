package com.chitour.familycast

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** مكتبتي: متابعة المشاهدة، قائمة المشاهدة، الأقسام والمسلسلات، الأرشيف */
class LibraryActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "مكتبتي"
        body = vbox().apply { setPadding(0, dp(6), 0, dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(body) })
    }

    override fun onResume() {
        super.onResume()
        render()
        TvLink.pullHistory { render() }
    }

    override fun onPause() { super.onPause(); TvLink.syncLibrary() }

    private fun pill(t: String, f: () -> Unit) = btn(t, f).apply {
        setTextColor(Color.WHITE); background = rounded(Theme.TEAL, dp(20).toFloat()); textSize = 14f
    }

    private fun sub(m: Movie): String = when {
        m.finished() -> "✔ شوهد"
        m.pos > 0 -> "توقفت عند ${Util.fmt(m.pos / 1000.0)}"
        m.segments.isNotEmpty() -> "${m.segments.size} مقطع محذوف"
        else -> "جديد"
    }

    private fun card(m: Movie, w: Int = 118, h: Int = 176) = posterCard(
        if (m.series.isNotBlank() && m.ep > 0) "${m.series} • ${m.ep}" else m.title,
        sub(m), m.poster, m.progress(), w, h, false, if (m.watch) "⭐" else "", { actions(m) }, { actions(m) })

    private fun seriesCard(name: String): android.view.View {
        val eps = Store.episodes(name)
        val next = eps.firstOrNull { !it.finished() } ?: eps.lastOrNull()
        val done = eps.count { it.finished() }
        return posterCard(name, "${eps.size} حلقة • شوهد $done", eps.firstOrNull { it.poster.isNotBlank() }?.poster ?: "",
            if (eps.isNotEmpty()) done * 100 / eps.size else 0, 118, 176, false, "مسلسل", { seriesDialog(name) }, { seriesDialog(name) })
    }

    private fun render() {
        body.removeAllViews()
        val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        head.add(pill("➕ فيلم") { addMovie() }, 1f)
        head.add(pill("➕ مسلسل كامل") { addSeries() }, 1.3f)
        head.add(pill("➕ قسم") { addSection() }, 1f)
        body.add(head)
        if (Store.movies.isEmpty()) {
            body.add(TextView(this).apply {
                text = "المكتبة فارغة.\nأضف فيلمًا أو مسلسلًا كاملًا بروابطه، أو أرسل فيلمًا من تطبيق الأفلام فيُحفظ هنا وحده."
                setTextColor(Theme.MUTED); textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            return
        }
        val cont = Store.continueWatching()
        if (cont.isNotEmpty()) body.add(shelf("▶ متابعة المشاهدة", cont.map { card(it, 150, 100) }, false))
        val wl = Store.movies.filter { it.watch }
        if (wl.isNotEmpty()) body.add(shelf("⭐ قائمة المشاهدة", wl.map { card(it) }, false))
        val secs = (Store.sections + Store.movies.map { it.section }).distinct()
        for (sec in secs) {
            val items = Store.movies.filter { it.section == sec }
            if (items.isEmpty()) continue
            val cards = mutableListOf<android.view.View>()
            val doneSeries = HashSet<String>()
            for (m in items) {
                if (m.series.isBlank()) cards.add(card(m))
                else if (doneSeries.add(m.series)) cards.add(seriesCard(m.series))
            }
            body.add(shelf("📁 $sec", cards, false))
        }
        val arch = Store.archive()
        if (arch.isNotEmpty()) {
            body.add(TextView(this).apply { text = "🕘 الأرشيف"; setTextColor(Theme.TEXT); textSize = 17f; setPadding(dp(12), dp(12), dp(12), dp(4)) })
            for (m in arch.take(60)) {
                val day = android.text.format.DateFormat.format("yyyy/MM/dd", m.watchedAt).toString()
                val row = TextView(this).apply {
                    text = "${if (m.series.isNotBlank()) "${m.series} • ${m.ep}: " else ""}${m.title}\n${sub(m)} • $day"
                    setTextColor(Theme.TEXT); textSize = 14f; background = rounded(Theme.PANEL, dp(10).toFloat())
                    setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { actions(m) }
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(3), dp(10), dp(3)) })
            }
        }
    }

    private fun play(m: Movie) =
        startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id).putExtra("autocast", true))

    private fun actions(m: Movie) {
        val items = listOf("📺 شغّله على التلفاز", "📄 صفحة الفيلم (التجاوز والترجمة)",
            if (m.watch) "إزالته من قائمة المشاهدة" else "⭐ أضفه إلى قائمة المشاهدة",
            "📁 نقله إلى قسم", "🎞 ضمّه إلى مسلسل", "↺ ابدأ من الأول", "🗑 حذف")
        AlertDialog.Builder(this).setTitle(m.title).setItems(items.toTypedArray()) { _, i ->
            when (i) {
                0 -> play(m)
                1 -> startActivity(Intent(this, MovieActivity::class.java).putExtra("id", m.id))
                2 -> { m.watch = !m.watch; Store.save(); render() }
                3 -> pickSection(m.section) { s -> m.section = s; Store.save(); render() }
                4 -> toSeries(m)
                5 -> { m.pos = 0; Store.save(); render() }
                6 -> AlertDialog.Builder(this).setMessage("حذف «${m.title}» وقائمة تجاوزه؟")
                    .setPositiveButton("حذف") { _, _ -> Store.remove(m); render() }.setNegativeButton("إلغاء", null).show()
            }
        }.show()
    }

    private fun seriesDialog(name: String) {
        val eps = Store.episodes(name)
        val next = eps.firstOrNull { !it.finished() }
        val head = listOfNotNull(next?.let { "▶ تابع: الحلقة ${it.ep}" }, "➕ أضف حلقات", "📁 نقل المسلسل إلى قسم")
        val rows = eps.map { e -> "الحلقة ${e.ep}: ${e.title}" + when { e.finished() -> "  ✔"; e.pos > 0 -> "  (${e.progress()}٪)"; else -> "" } }
        AlertDialog.Builder(this).setTitle(name).setItems((head + rows).toTypedArray()) { _, i ->
            val off = head.size
            when {
                next != null && i == 0 -> play(next)
                i == off - 2 -> addSeries(name)
                i == off - 1 -> pickSection(eps.firstOrNull()?.section ?: "مسلسلات") { s -> eps.forEach { it.section = s }; Store.save(); render() }
                else -> actions(eps[i - off])
            }
        }.show()
    }

    private fun sectionSpinner(sel: String): Spinner {
        val secs = Store.sections
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@LibraryActivity, android.R.layout.simple_spinner_dropdown_item, secs)
            setSelection(secs.indexOf(sel).coerceAtLeast(0))
        }
    }

    private fun pickSection(cur: String, done: (String) -> Unit) {
        val secs = Store.sections
        AlertDialog.Builder(this).setTitle("اختر القسم").setItems(secs.toTypedArray()) { _, i -> done(secs[i]) }.show()
    }

    private fun toSeries(m: Movie) {
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val names = Store.movies.map { it.series }.filter { it.isNotBlank() }.distinct()
        val name = EditText(this).apply { hint = "اسم المسلسل" + if (names.isNotEmpty()) " (مثل: ${names.first()})" else ""; setText(m.series) }
        val ep = EditText(this).apply { hint = "رقم الحلقة"; inputType = InputType.TYPE_CLASS_NUMBER; setText(if (m.ep > 0) "${m.ep}" else "") }
        box.add(name); box.add(ep)
        AlertDialog.Builder(this).setTitle("ضمّه إلى مسلسل").setView(box).setPositiveButton("حفظ") { _, _ ->
            m.series = name.text.toString().trim()
            m.ep = ep.text.toString().toIntOrNull() ?: (Store.episodes(m.series).maxOfOrNull { it.ep } ?: 0) + 1
            if (m.series.isNotBlank() && m.section == "أفلام") m.section = "مسلسلات"
            Store.save(); render()
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun addMovie() {
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val t = EditText(this).apply { hint = "اسم الفيلم" }
        val u = EditText(this).apply { hint = "رابط الفيديو (mp4 أو m3u8)" }
        val sp = sectionSpinner("أفلام")
        box.add(t); box.add(u); box.add(label("القسم", 13f)); box.add(sp)
        AlertDialog.Builder(this).setTitle("إضافة فيلم").setView(box).setPositiveButton("حفظ") { _, _ ->
            val url = u.text.toString().trim()
            if (!url.startsWith("http")) { toast("الرابط غير صحيح"); return@setPositiveButton }
            val m = Movie(System.currentTimeMillis().toString(), t.text.toString().ifBlank { "فيلم" }, url)
            m.section = sp.selectedItem as String; m.watch = true
            Store.add(m); render()
        }.setNegativeButton("إلغاء", null).show()
    }

    /** مسلسل كامل: رابط في كل سطر، ويمكن كتابة اسم الحلقة قبل الرابط */
    private fun addSeries(existing: String? = null) {
        val box = vbox().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val name = EditText(this).apply { hint = "اسم المسلسل"; setText(existing ?: "") }
        val sp = sectionSpinner(existing?.let { Store.episodes(it).firstOrNull()?.section } ?: "مسلسلات")
        val links = EditText(this).apply {
            hint = "رابط كل حلقة في سطر، بالترتيب.\nويمكن كتابة اسم الحلقة قبل الرابط:\nالحلقة الأولى https://...\nhttps://..."
            minLines = 6; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.add(name); box.add(label("القسم", 13f)); box.add(sp); box.add(links)
        AlertDialog.Builder(this).setTitle(if (existing == null) "إضافة مسلسل كامل" else "إضافة حلقات إلى «$existing»")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                val sName = name.text.toString().trim()
                if (sName.isBlank()) { toast("اكتب اسم المسلسل"); return@setPositiveButton }
                var ep = Store.episodes(sName).maxOfOrNull { it.ep } ?: 0
                var n = 0
                val base = System.currentTimeMillis()
                for (line in links.text.toString().lines()) {
                    val url = Regex("""https?://\S+""").find(line)?.value ?: continue
                    val label = line.replace(url, "").replace("|", "").trim()
                    ep++; n++
                    val m = Movie((base + n).toString(), label.ifBlank { "الحلقة $ep" }, url)
                    m.series = sName; m.ep = ep; m.section = sp.selectedItem as String; m.watchedAt = 0L
                    Store.movies.add(m)
                }
                Store.save(); render()
                toast(if (n == 0) "لم أجد روابط" else "أُضيفت $n حلقة إلى «$sName»")
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun addSection() {
        val t = EditText(this).apply { hint = "اسم القسم، مثل: أفلام تاريخية" }
        AlertDialog.Builder(this).setTitle("قسم جديد").setView(t).setPositiveButton("إضافة") { _, _ ->
            val n = t.text.toString().trim()
            if (n.isNotBlank() && n !in Store.sections) { Store.sections = Store.sections + n; render() }
        }.setNegativeButton("إلغاء", null).show()
    }
}
