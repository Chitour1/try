package com.chitour.familycast

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

data class Segment(var start: Double, var end: Double, var mute: Boolean = false, var note: String = "")

class Movie(
    val id: String,
    var title: String,
    var url: String,
    var referer: String = "",
    var userAgent: String = "",
    var useProxy: Boolean = false,
    val segments: MutableList<Segment> = mutableListOf()
)

object Store {
    private lateinit var prefs: SharedPreferences
    val movies = mutableListOf<Movie>()
    var skipEnabled = true

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("family_cast", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        movies.clear()
        val arr = try { JSONArray(prefs.getString("movies", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = Movie(o.optString("id"), o.optString("title"), o.optString("url"),
                o.optString("referer"), o.optString("ua"), o.optBoolean("proxy"))
            val sa = o.optJSONArray("segments") ?: JSONArray()
            for (j in 0 until sa.length()) {
                val s = sa.getJSONObject(j)
                m.segments.add(Segment(s.optDouble("s"), s.optDouble("e"), s.optBoolean("m"), s.optString("n")))
            }
            movies.add(m)
        }
    }

    fun save() {
        val arr = JSONArray()
        for (m in movies) {
            val sa = JSONArray()
            for (s in m.segments) sa.put(JSONObject().put("s", s.start).put("e", s.end).put("m", s.mute).put("n", s.note))
            arr.put(JSONObject().put("id", m.id).put("title", m.title).put("url", m.url)
                .put("referer", m.referer).put("ua", m.userAgent).put("proxy", m.useProxy).put("segments", sa))
        }
        prefs.edit().putString("movies", arr.toString()).apply()
    }

    fun get(id: String) = movies.firstOrNull { it.id == id }
    fun add(m: Movie) { movies.add(0, m); save() }
    fun remove(m: Movie) { movies.remove(m); save() }
}

object Util {
    fun fmt(sec: Double): String {
        val t = max(0.0, sec).toLong()
        return String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> '.'
                    '：' -> ':'
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** يقبل: 75 أو 12:30 أو 1:02:30 أو 1:02:30,500 */
    fun parse(raw: String): Double? {
        val s = normalize(raw).trim().replace(',', '.')
        if (s.isEmpty()) return null
        val parts = s.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (p in parts) {
            val v = p.trim().toDoubleOrNull() ?: return null
            total = total * 60 + v
        }
        return total
    }

    private val timeRx = Regex("""\d+(?::\d{1,2}){0,2}(?:[.,]\d+)?""")
    private val srtRx = Regex("""(\d+:\d{1,2}:\d{1,2}(?:[.,]\d+)?)\s*-->\s*(\d+:\d{1,2}:\d{1,2}(?:[.,]\d+)?)""")
    private val edlRx = Regex("""^\s*(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s+([0-3])\s*$""")

    private fun isMuteWord(t: String): Boolean {
        val l = t.lowercase()
        return l.contains("كتم") || l.contains("mute") || l.contains("صوت")
    }

    /** يقرأ نصًا يدويًا أو ملف txt أو srt أو edl */
    fun parseSegments(text: String): List<Segment> {
        val src = normalize(text).replace("\r", "")
        val out = mutableListOf<Segment>()
        if (srtRx.containsMatchIn(src)) {
            for (block in src.split(Regex("""\n\s*\n"""))) {
                val m = srtRx.find(block) ?: continue
                val s = parse(m.groupValues[1]) ?: continue
                val e = parse(m.groupValues[2]) ?: continue
                val rest = block.substring(m.range.last + 1).trim()
                if (e > s) out.add(Segment(s, e, isMuteWord(rest), rest.lines().firstOrNull()?.take(60) ?: ""))
            }
            return out
        }
        for (line in src.lines()) {
            val edl = edlRx.find(line)
            if (edl != null) {
                val s = edl.groupValues[1].toDouble(); val e = edl.groupValues[2].toDouble()
                if (e > s) out.add(Segment(s, e, edl.groupValues[3] == "1"))
                continue
            }
            val ms = timeRx.findAll(line).map { it.value }.toList()
            if (ms.size < 2) continue
            val s = parse(ms[0]) ?: continue
            val e = parse(ms[1]) ?: continue
            if (e <= s) continue
            val after = line.substring(line.indexOf(ms[1]) + ms[1].length).trim()
            out.add(Segment(s, e, isMuteWord(line), after.replace(Regex("^(-|–|:)+"), "").trim().take(60)))
        }
        return out
    }

    fun segmentsToText(list: List<Segment>) = list.joinToString("\n") {
        "${fmt(it.start)} - ${fmt(it.end)} ${if (it.mute) "كتم" else "تجاوز"}" + (if (it.note.isNotBlank()) " ${it.note}" else "")
    }

    fun isVideoUrl(u: String): Boolean {
        val l = u.lowercase()
        if (!l.startsWith("http")) return false
        if (Regex("""\.(ts|m4s|aac|vtt|srt|jpg|png|gif|js|css)(\?|#|$)""").containsMatchIn(l)) return false
        return Regex("""\.(mp4|m3u8|webm|mkv|mov|m4v)(\?|#|$)""").containsMatchIn(l) || l.contains("m3u8") || l.contains("mime=video")
    }

    fun mimeOf(u: String): String {
        val l = u.lowercase()
        return when {
            l.contains("m3u8") -> "application/x-mpegURL"
            l.contains(".webm") -> "video/webm"
            l.contains(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }
}
