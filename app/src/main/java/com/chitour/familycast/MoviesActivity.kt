package com.chitour.familycast

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MoviesActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private var sorted: List<Movie> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "سجل المشاهدة وقوائم التجاوز"
        val root = vbox()
        root.add(label("الأحدث أولًا. اضغط على الفيلم لإكماله من حيث توقفت، واضغط مطولًا لحذفه.", 13f))
        list = ListView(this)
        root.add(list, 1f)
        setContentView(root)
        list.setOnItemClickListener { _, _, i, _ ->
            startActivity(Intent(this, MovieActivity::class.java).putExtra("id", sorted[i].id))
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val m = sorted[i]
            AlertDialog.Builder(this).setMessage("حذف «${m.title}» وقائمته؟")
                .setPositiveButton("حذف") { _, _ -> Store.remove(m); refresh() }
                .setNegativeButton("إلغاء", null).show()
            true
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        sorted = Store.movies.sortedByDescending { it.watchedAt }
        val items = sorted.map { m ->
            val prog = if (m.pos > 0) "توقفت عند ${Util.fmt(m.pos / 1000.0)}" + (if (m.dur > 0) " من ${Util.fmt(m.dur / 1000.0)}" else "") else "لم يُشاهد بعد"
            val day = if (m.watchedAt > 0) android.text.format.DateFormat.format("yyyy/MM/dd", m.watchedAt).toString() else ""
            "${m.title}\n$prog • ${m.segments.size} مقطع محذوف • $day"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        if (Store.movies.isEmpty()) toast("لا أفلام بعد. افتح موقعًا من الشاشة الرئيسية أو أضف رابطًا مباشرًا")
    }
}
