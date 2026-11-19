package com.chitour.familycast

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MoviesActivity : AppCompatActivity() {
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "أفلامي وقوائم التجاوز"
        val root = vbox()
        root.add(label("اضغط على الفيلم لفتحه، واضغط مطولًا لحذفه.", 13f))
        list = ListView(this)
        root.add(list, 1f)
        setContentView(root)
        list.setOnItemClickListener { _, _, i, _ ->
            startActivity(Intent(this, MovieActivity::class.java).putExtra("id", Store.movies[i].id))
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val m = Store.movies[i]
            AlertDialog.Builder(this).setMessage("حذف «${m.title}» وقائمته؟")
                .setPositiveButton("حذف") { _, _ -> Store.remove(m); refresh() }
                .setNegativeButton("إلغاء", null).show()
            true
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val items = if (Store.movies.isEmpty()) listOf() else
            Store.movies.map { "${it.title}\n${it.segments.size} مقطع محذوف" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        if (Store.movies.isEmpty()) toast("لا أفلام بعد. افتح موقعًا من الشاشة الرئيسية أو أضف رابطًا مباشرًا")
    }
}
