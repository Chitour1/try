package com.chitour.familycast

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * طبقتان فوق المشغّل:
 * • بطاقة الملخص: تظهر حين يُتجاوز مقطع له ملخص، والفيلم متوقف، شريحة بعد شريحة (50 كلمة، كل 5 كلمات ثانية).
 * • الغطاء الأسود: شريط أسفل الشاشة يخفي سطرَي الترجمة، وعليه نص اختياري.
 */
class SceneOverlay(private val root: FrameLayout, private val big: Boolean) {
    private val ctx = root.context
    private val h = Handler(Looper.getMainLooper())
    private val card = LinearLayout(ctx)
    private val cardHead = TextView(ctx)
    private val cardText = TextView(ctx)
    private val cardFoot = TextView(ctx)
    private val bar = TextView(ctx)
    var cardActive = false
        private set
    private var onDone: (() -> Unit)? = null

    init {
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.setBackgroundColor(Color.argb(242, 8, 20, 24))
        val pad = px(if (big) 80 else 16)
        card.setPadding(pad, pad / 2, pad, pad / 2)
        cardHead.setTextColor(Color.parseColor("#D99A1E"))
        cardHead.textSize = if (big) 22f else 13f
        cardHead.gravity = Gravity.CENTER
        cardText.setTextColor(Color.WHITE)
        cardText.textSize = if (big) 34f else 16f
        cardText.setTypeface(cardText.typeface, Typeface.BOLD)
        cardText.gravity = Gravity.CENTER
        cardText.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        cardText.setLineSpacing(0f, 1.35f)
        cardFoot.setTextColor(Color.parseColor("#9FB8BD"))
        cardFoot.textSize = if (big) 16f else 11f
        cardFoot.gravity = Gravity.CENTER
        card.addView(cardHead); card.addView(cardText); card.addView(cardFoot)
        card.visibility = View.GONE
        root.addView(card, FrameLayout.LayoutParams(-1, -1))

        bar.setBackgroundColor(Color.BLACK)
        bar.setTextColor(Color.WHITE)
        bar.gravity = Gravity.CENTER
        bar.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        bar.visibility = View.GONE
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun px(dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()

    /** يعرض الملخص شريحة بعد شريحة ثم ينادي done */
    fun showCard(text: String, minMs: Long = 0L, head: String? = null, done: () -> Unit) {
        val slides = Util.slides(text)
        if (slides.isEmpty()) { done(); return }
        cardActive = true
        onDone = done
        val ar = Util.isArabic(text)
        cardHead.text = head ?: if (ar) "ملخص المشهد المحذوف" else "Skipped scene summary"
        card.visibility = View.VISIBLE
        var i = 0
        fun step() {
            if (!cardActive) return
            if (i >= slides.size) { finishCard(); return }
            cardText.text = slides[i]
            cardFoot.text = if (slides.size > 1) "${i + 1} / ${slides.size}" else ""
            val ms = maxOf(Util.slideMs(slides[i]), minMs)
            i++
            h.postDelayed({ step() }, ms)
        }
        step()
    }

    /** إنهاء البطاقة مبكرًا (زر موافق أو تشغيل) */
    fun finishCard() {
        if (!cardActive) return
        cardActive = false
        h.removeCallbacksAndMessages(null)
        card.visibility = View.GONE
        onDone?.invoke(); onDone = null
    }

    /** الغطاء الأسود: يظهر ما دام المشغّل داخل مقطع «غطاء»، والنص يتبدل حسب الوقت المنقضي فيه */
    fun cover(seg: Segment?, insideMs: Long, scale: Float) {
        if (seg == null || !seg.cover) { if (bar.visibility != View.GONE) bar.visibility = View.GONE; return }
        val hgt = (root.height * 0.13f * scale).toInt().coerceAtLeast(px(36))
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        val w = (root.width * 0.86f).toInt()
        val bottom = (root.height * 0.045f).toInt()
        if (lp.height != hgt || lp.width != w || lp.bottomMargin != bottom) {
            lp.height = hgt; lp.width = w; lp.bottomMargin = bottom; bar.layoutParams = lp
        }
        bar.textSize = (hgt / ctx.resources.displayMetrics.scaledDensity) * 0.30f
        bar.setPadding(px(12), 0, px(12), 0)
        val slides = Util.slides(seg.note)
        var t = insideMs
        var txt = slides.lastOrNull() ?: ""
        for (sl in slides) { val d = Util.slideMs(sl); if (t < d) { txt = sl; break }; t -= d }
        if (bar.text.toString() != txt) bar.text = txt
        bar.visibility = View.VISIBLE
    }

    fun hideAll() {
        cardActive = false
        h.removeCallbacksAndMessages(null)
        card.visibility = View.GONE
        bar.visibility = View.GONE
        onDone = null
    }
}
