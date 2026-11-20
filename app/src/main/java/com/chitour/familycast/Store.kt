package com.chitour.familycast

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

data class Sub(var url: String, var label: String)

/** mute: كتم الصوت بدل التجاوز • cover: غطاء أسود على الترجمة • note: ملخص يظهر للمشاهد */
data class Segment(var start: Double, var end: Double, var mute: Boolean = false, var note: String = "", var cover: Boolean = false) {
    fun kind() = when { !mute && !cover -> "تجاوز"; mute && cover -> "كتم وغطاء"; mute -> "كتم الصوت"; else -> "غطاء الترجمة" }
    /** المقطع الذي لا يُتجاوز: يُكتم أو يُغطّى ويستمر العرض */
    fun playsThrough() = mute || cover
}

class Movie(
    val id: String,
    var title: String,
    var url: String,
    var referer: String = "",
    var userAgent: String = "",
    var useProxy: Boolean = false,
    val segments: MutableList<Segment> = mutableListOf(),
    val subs: MutableList<Sub> = mutableListOf(),
    var subsOn: Boolean = true,
    var variant: String = "",
    var variantLabel: String = "",
    var pos: Long = 0L,
    var dur: Long = 0L,
    var watchedAt: Long = 0L,
    var section: String = "أفلام",
    var series: String = "",
    var ep: Int = 0,
    var watch: Boolean = false,
    var poster: String = ""
) {
    fun finished() = dur > 0 && pos >= dur - 120_000
    fun progress(): Int = if (dur > 0) (pos * 100 / dur).toInt().coerceIn(0, 100) else 0
    fun isLocal() = url.startsWith("content://") || url.startsWith("file://")
}

object Store {
    private lateinit var prefs: SharedPreferences
    val movies = mutableListOf<Movie>()
    var skipEnabled = true
    /** سرعة قراءة الملخص: كلمات في الثانية */
    var wps: Float
        get() = prefs.getFloat("wps", 3f)
        set(v) { prefs.edit().putFloat("wps", v).apply() }
    /** قراءة الملخص بصوت: متوقفة إلا إذا اختارها المستخدم */
    var readCards: Boolean
        get() = prefs.getBoolean("readCards", false)
        set(v) { prefs.edit().putBoolean("readCards", v).apply() }
    var ttsMode: Int
        get() = prefs.getInt("ttsMode", 0)
        set(v) { prefs.edit().putInt("ttsMode", v).apply() }
    var coverScale: Float
        get() = prefs.getFloat("coverScale", 1.0f)
        set(v) { prefs.edit().putFloat("coverScale", v).apply() }
    var subScale: Float
        get() = prefs.getFloat("subScale", 1.3f)
        set(v) { prefs.edit().putFloat("subScale", v).apply() }

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
                m.segments.add(Segment(s.optDouble("s"), s.optDouble("e"), s.optBoolean("m"), s.optString("n"), s.optBoolean("c")))
            }
            val ua = o.optJSONArray("subs") ?: JSONArray()
            for (j in 0 until ua.length()) { val x = ua.getJSONObject(j); m.subs.add(Sub(x.optString("u"), x.optString("l"))) }
            m.subsOn = o.optBoolean("subsOn", true)
            m.variant = o.optString("variant"); m.variantLabel = o.optString("variantLabel")
            m.section = o.optString("sec", "أفلام").ifBlank { "أفلام" }; m.series = o.optString("ser"); m.ep = o.optInt("ep")
            m.watch = o.optBoolean("wl"); m.poster = o.optString("po")
            m.pos = o.optLong("pos"); m.dur = o.optLong("dur"); m.watchedAt = o.optLong("watchedAt", o.optString("id").toLongOrNull() ?: 0L)
            movies.add(m)
        }
    }

    fun save() {
        val arr = JSONArray()
        for (m in movies) {
            val sa = JSONArray()
            for (s in m.segments) sa.put(JSONObject().put("s", s.start).put("e", s.end).put("m", s.mute).put("n", s.note).put("c", s.cover))
            val ua = JSONArray()
            for (x in m.subs) ua.put(JSONObject().put("u", x.url).put("l", x.label))
            arr.put(JSONObject().put("subs", ua).put("subsOn", m.subsOn).put("variant", m.variant).put("variantLabel", m.variantLabel).put("pos", m.pos).put("dur", m.dur).put("watchedAt", m.watchedAt).put("sec", m.section).put("ser", m.series).put("ep", m.ep).put("wl", m.watch).put("po", m.poster).put("id", m.id).put("title", m.title).put("url", m.url)
                .put("referer", m.referer).put("ua", m.userAgent).put("proxy", m.useProxy).put("segments", sa))
        }
        prefs.edit().putString("movies", arr.toString()).apply()
    }

    fun get(id: String) = movies.firstOrNull { it.id == id }

    // ---- المكتبة ----
    var sections: List<String>
        get() = try { val a = JSONArray(prefs.getString("sections", null) ?: throw Exception()); (0 until a.length()).map { a.getString(it) } }
                catch (e: Exception) { listOf("أفلام", "مسلسلات", "وثائقيات", "للأطفال") }
        set(v) { prefs.edit().putString("sections", JSONArray(v).toString()).apply() }

    fun episodes(series: String) = movies.filter { it.series == series }.sortedBy { it.ep }
    fun nextEpisode(m: Movie): Movie? = if (m.series.isBlank()) null else episodes(m.series).firstOrNull { it.ep > m.ep }

    /** متابعة المشاهدة: ما بدأته ولم تكمله، وفي المسلسل آخر حلقة فقط */
    fun continueWatching(): List<Movie> {
        val list = movies.filter { it.pos > 60_000 && !it.finished() }.sortedByDescending { it.watchedAt }
        val seen = HashSet<String>()
        return list.filter { it.series.isBlank() || seen.add(it.series) }
    }
    fun archive() = movies.filter { it.pos > 0 || it.watchedAt > 0 }.sortedByDescending { it.watchedAt }

    fun pref(k: String): String = prefs.getString(k, "") ?: ""
    fun setPref(k: String, v: String) { prefs.edit().putString(k, v).apply() }

    private fun norm(u: String) = u.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
    private fun normTitle(t: String) = t.trim().lowercase().replace(Regex("""\s+"""), " ")

    /** يجد الفيلم المحفوظ نفسه حين يصل مرة أخرى (بالرابط أو بالاسم) حتى لا تضيع قائمته ومكان توقفه */
    fun match(url: String, title: String?): Movie? {
        movies.firstOrNull { it.url == url }?.let { return it }
        val t = title?.let { normTitle(it) }
        if (!t.isNullOrBlank() && t != "فيلم جديد" && t != "فيلم") movies.firstOrNull { normTitle(it.title) == t }?.let { return it }
        val n = norm(url)
        if (n.length > 20) movies.firstOrNull { norm(it.url) == n }?.let { return it }
        return null
    }

    /** يحفظ مكان التوقف (كل بضع ثوانٍ) */
    fun savePos(m: Movie, pos: Long, dur: Long) {
        if (pos <= 0) return
        m.pos = pos; if (dur > 0) m.dur = dur
        m.watchedAt = System.currentTimeMillis()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPosSave > 5000) { lastPosSave = now; save() }
    }
    private var lastPosSave = 0L

    /** نقطة الإكمال: لا نكمل من أول دقيقة ولا من آخر دقيقتين */
    fun resumeAt(m: Movie): Long = if (m.pos > 60_000 && (m.dur <= 0 || m.pos < m.dur - 120_000)) m.pos - 5_000 else 0L
    fun add(m: Movie) { m.watchedAt = System.currentTimeMillis(); movies.add(0, m); save() }
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

    private val kwWords = setOf("كتم", "غطاء", "تغطية", "تجاوز", "mute", "cover", "skip", "و", "+", "-", "–", ":", "|", "،", ",")

    /** ما بعد الأوقات: الكلمات الأولى «كتم» و«غطاء» تحدد النوع، وكل ما بعدها هو الملخص الذي يراه المشاهد */
    private fun kindAndNote(rest: String): Triple<Boolean, Boolean, String> {
        val toks = rest.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var i = 0
        var mute = false; var cover = false
        while (i < toks.size) {
            val t = toks[i].lowercase().trim(',', '،', ':', '|')
            val parts = t.split('+', '/').filter { it.isNotEmpty() }
            if (t.isEmpty() || (parts.isNotEmpty() && parts.all { it in kwWords }) || t in kwWords) {
                for (x in parts.ifEmpty { listOf(t) }) {
                    if (x == "كتم" || x == "mute") mute = true
                    if (x == "غطاء" || x == "تغطية" || x == "cover") cover = true
                }
                i++
            } else break
        }
        return Triple(mute, cover, toks.drop(i).joinToString(" ").trim())
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
                val rest = block.substring(m.range.last + 1).trim().replace('\n', ' ')
                val (mu, co, note) = kindAndNote(rest)
                if (e > s) out.add(Segment(s, e, mu, note, co))
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
            val ms = timeRx.findAll(line).toList()
            if (ms.size < 2) continue
            val s = parse(ms[0].value) ?: continue
            val e = parse(ms[1].value) ?: continue
            if (e <= s) continue
            val (mu, co, note) = kindAndNote(line.substring(ms[1].range.last + 1))
            out.add(Segment(s, e, mu, note, co))
        }
        return out
    }

    // ---- شرائح الملخص: 50 كلمة في الشريحة، والسرعة يختارها المستخدم (الافتراضي 3 كلمات في الثانية) ----
    /** الكلمة: ما فيه حرف أو رقم (لا تُحسب الشرطات وعلامات الترقيم وحدها) */
    fun words(text: String): List<String> =
        text.trim().split(Regex("""\s+""")).filter { w -> w.any { it.isLetterOrDigit() } }

    /** الشريحة 50 كلمة على الأكثر، وما زاد ينتقل إلى شريحة تالية */
    fun slides(text: String): List<String> {
        val all = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>(); var cur = mutableListOf<String>(); var n = 0
        for (t in all) {
            val isWord = t.any { it.isLetterOrDigit() }
            if (isWord && n == 50) { out.add(cur.joinToString(" ")); cur = mutableListOf(); n = 0 }
            cur.add(t); if (isWord) n++
        }
        if (cur.isNotEmpty() && n > 0) out.add(cur.joinToString(" "))
        return out
    }

    /** مدة الشريحة بحسب سرعة القراءة المختارة (الافتراضي 3 كلمات في الثانية) */
    fun slideMs(slide: String): Long {
        val n = words(slide).size
        return maxOf(2000L, (n * 1000f / Store.wps).toLong())
    }
    fun totalMs(text: String): Long = slides(text).sumOf { slideMs(it) }
    fun isArabic(t: String) = t.any { it in '\u0600'..'\u06FF' }

    fun segmentsToText(list: List<Segment>) = list.joinToString("\n") {
        val k = when { it.mute && it.cover -> "كتم غطاء"; it.mute -> "كتم"; it.cover -> "غطاء"; else -> "تجاوز" }
        "${fmt(it.start)} - ${fmt(it.end)} $k" + (if (it.note.isNotBlank()) " | ${it.note}" else "")
    }

    /** حقل «userAgent» قد يحمل في سطوره التالية ترويسات إضافية بصيغة «الاسم: القيمة» */
    fun splitHeaders(ua: String): Pair<String, Map<String, String>> {
        val lines = ua.split('\n')
        val extra = LinkedHashMap<String, String>()
        for (l in lines.drop(1)) {
            val k = l.substringBefore(':').trim(); val v = l.substringAfter(':', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) extra[k] = v
        }
        return lines.first().trim() to extra
    }

    fun isSubUrl(u: String): Boolean =
        u.lowercase().startsWith("http") && Regex("""\.(vtt|srt)(\?|#|$)""").containsMatchIn(u.lowercase())

    fun decodeText(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE)
        val utf = String(b, Charsets.UTF_8)
        return if (utf.contains('\uFFFD')) String(b, charset("windows-1256")) else utf.removePrefix("\uFEFF")
    }

    fun toVtt(s: String): String {
        val t = s.replace("\r", "").removePrefix("\uFEFF")
        if (t.trimStart().startsWith("WEBVTT")) return t
        return "WEBVTT\n\n" + t.replace(Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{1,3})"""), "$1.$2")
    }

    private val adHosts = listOf("doubleclick", "googlesyndication", "googleadservices", "adservice", "popads", "popcash",
        "propellerads", "adsterra", "exoclick", "juicyads", "onclickads", "hilltopads", "a-ads.", "mgid.", "taboola",
        "outbrain", "revcontent", "adnxs", "clickadu", "trafficjunky", "pushame", "notix", "highperformanceformat",
        "profitableratecpm", "realsrv", "displayvertising", "popunder", "adcash", "admaven", "ad-maven", "clickaine",
        "monetag", "onclkds", "bidgear", "adskeeper", "yllix", "galaksion", "richpush", "pushnami", "evadav")

    fun isAd(u: String): Boolean {
        val h = try { java.net.URL(u).host.lowercase() } catch (e: Exception) { return false }
        return adHosts.any { h.contains(it) }
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
