package com.chitour.familycast

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.btn(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 15f; setOnClickListener { onClick() }
}

fun Context.label(text: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(4), dp(6), dp(4), dp(6))
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun LinearLayout.add(v: View, weight: Float = 0f, w: Int = -1, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
    val width = if (w == -1) (if (orientation == LinearLayout.HORIZONTAL && weight > 0f) 0 else
        if (orientation == LinearLayout.HORIZONTAL) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT) else w
    val height = if (orientation == LinearLayout.VERTICAL && weight > 0f) 0 else h
    addView(v, LinearLayout.LayoutParams(width, height, weight))
    return v
}
