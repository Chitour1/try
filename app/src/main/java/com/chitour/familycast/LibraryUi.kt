package com.chitour.familycast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Theme {
    val BG = Color.parseColor("#0B1E23")
    val PANEL = Color.parseColor("#12313A")
    val TEAL = Color.parseColor("#0E5A6B")
    val GOLD = Color.parseColor("#D99A1E")
    val TEXT = Color.parseColor("#EAF2F3")
    val MUTED = Color.parseColor("#93AEB4")
}

/** تحميل الملصقات من الإنترنت مع ذاكرة مؤقتة */
object Img {
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    fun load(url: String, iv: ImageView) {
        if (url.isBlank()) return
        iv.tag = url
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            } catch (e: Exception) { null }
            if (bmp != null) { cache.put(url, bmp); main.post { if (iv.tag == url) iv.setImageBitmap(bmp) } }
        }
    }
}

fun rounded(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, strokeColor)
}

/** بطاقة فيلم أو مسلسل: ملصق أو حرف أول، والاسم، وسطر صغير، وشريط التقدم */
fun Context.posterCard(title: String, sub: String, poster: String, progress: Int, wDp: Int, hDp: Int, tv: Boolean,
                       badge: String = "", onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
    val r = dp(12).toFloat()
    val card = FrameLayout(this)
    val palette = listOf("#0E5A6B", "#3B4A7A", "#6B3B5A", "#2F6B4A", "#7A5A2F", "#4A3B7A")
    card.background = rounded(Color.parseColor(palette[Math.abs(title.hashCode()) % palette.size]), r)
    card.clipToOutline = true
    val letter = TextView(this).apply {
        text = title.trim().take(1); textSize = if (tv) 64f else 48f; setTextColor(Color.argb(70, 255, 255, 255))
        gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
    }
    card.addView(letter, FrameLayout.LayoutParams(-1, -1))
    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    card.addView(iv, FrameLayout.LayoutParams(-1, -1))
    if (poster.isNotBlank()) Img.load(poster, iv)
    val shade = View(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(235, 0, 0, 0), Color.TRANSPARENT))
    }
    card.addView(shade, FrameLayout.LayoutParams(-1, dp(hDp / 2), Gravity.BOTTOM))
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    box.addView(TextView(this).apply {
        text = title; setTextColor(Color.WHITE); textSize = if (tv) 16f else 13f; maxLines = 2
        setTypeface(typeface, Typeface.BOLD); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    })
    if (sub.isNotBlank()) box.addView(TextView(this).apply { text = sub; setTextColor(Theme.MUTED); textSize = if (tv) 12f else 10f; maxLines = 1 })
    if (progress in 1..99) box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; this.progress = progress
        progressTintList = android.content.res.ColorStateList.valueOf(Theme.GOLD)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(4) })
    card.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    if (badge.isNotBlank()) card.addView(TextView(this).apply {
        text = badge; setTextColor(Color.BLACK); textSize = if (tv) 12f else 10f
        background = rounded(Theme.GOLD, dp(8).toFloat()); setPadding(dp(6), dp(2), dp(6), dp(2))
    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
    card.setOnClickListener { onClick() }
    if (onLong != null) card.setOnLongClickListener { onLong(); true }
    if (tv) {
        card.isFocusable = true; card.isFocusableInTouchMode = true
        card.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
            v.foreground = if (f) rounded(Color.TRANSPARENT, r, dp(3), Theme.GOLD) else null
            v.elevation = if (f) dp(8).toFloat() else 0f
        }
    }
    val lp = LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) }
    card.layoutParams = lp
    return card
}

/** صف أفقي بعنوان */
fun Context.shelf(title: String, cards: List<View>, tv: Boolean): View {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
    col.addView(TextView(this).apply {
        text = title; setTextColor(Theme.TEXT); textSize = if (tv) 22f else 17f; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(4), dp(12), 0)
    })
    val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(6), 0, dp(6), 0) }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    cards.forEach { row.addView(it) }
    hs.addView(row)
    col.addView(hs)
    return col
}
