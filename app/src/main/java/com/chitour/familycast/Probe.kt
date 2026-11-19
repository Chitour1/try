package com.chitour.familycast

import java.net.HttpURLConnection
import java.net.URL

/** فيديو التُقط من الصفحة، مع ما عُرف عنه بالفحص (المدة أو الحجم) لتمييز الفيلم من الإعلانات */
class Found(val url: String, val ref: String) {
    @Volatile var kind = ""
    @Volatile var dur: Double? = null
    @Volatile var size: Long? = null
    @Volatile var master = false
    @Volatile var done = false
    @Volatile var failed = false

    /** يُستبعد كل ما دون 50 ميغابايت (أو دون عشر دقائق في البث) لأنه إعلان وليس الفيلم */
    fun passes(): Boolean {
        if (!done) return false
        if (failed) return true
        size?.let { return it >= 50_000_000L }
        dur?.let { return it >= 600.0 }
        return true
    }

    /** درجة الترجيح: المدة بالثواني، أو تقدير المدة من الحجم */
    fun score(): Double = dur ?: size?.let { it / 150_000.0 } ?: -1.0

    fun describe(): String {
        val info = when {
            !done -> "يُفحص…"
            failed -> "تعذّر الفحص"
            dur != null && dur!! > 0 -> "المدة ${Util.fmt(dur!!)}"
            size != null -> "الحجم ${size!! / 1_000_000} ميغابايت"
            else -> "مدة غير معروفة"
        }
        val host = try { URL(url).host } catch (e: Exception) { "" }
        return "$kind  •  $info\n$host"
    }
}

object Probe {
    private fun open(u: String, ref: String, ua: String, range: String? = null): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
        val (uaLine, extra) = Util.splitHeaders(ua)
        if (uaLine.isNotEmpty()) c.setRequestProperty("User-Agent", uaLine)
        for ((k, v) in extra) c.setRequestProperty(k, v)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun text(u: String, ref: String, ua: String): Pair<String, String> {
        val c = open(u, ref, ua)
        val t = c.inputStream.bufferedReader().use { it.readText() }
        return t to c.url.toString()
    }

    /** جودات البث المتاحة في رابط HLS الرئيسي: (الوصف، الرابط) */
    fun variants(u: String, ref: String, ua: String): List<Pair<String, String>> {
        val (t, base) = text(u, ref, ua)
        val out = mutableListOf<Pair<String, String>>()
        val lines = t.lines().map { it.trim() }
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val res = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull()
            val label = (if (res != null) "${res}p" else "جودة") + (if (bw != null) "  (${bw / 1000} ك.ب/ث)" else "")
            out.add(label to URL(URL(base), next).toString())
        }
        return out.sortedByDescending { Regex("""^(\d+)p""").find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }

    fun run(f: Found, ua: String) {
        try {
            if (f.url.lowercase().contains("m3u8")) {
                f.kind = "بث HLS"
                var (t, base) = text(f.url, f.ref, ua)
                if (t.contains("#EXT-X-STREAM-INF")) {
                    f.master = true
                    val v = t.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (v != null) { val r = text(URL(URL(base), v).toString(), f.ref, ua); t = r.first; base = r.second }
                }
                val d = Regex("""#EXTINF:\s*([0-9.]+)""").findAll(t).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                if (d > 0) f.dur = d
            } else {
                f.kind = "ملف فيديو"
                val c = open(f.url, f.ref, ua, "bytes=0-0")
                val cr = c.getHeaderField("Content-Range")
                f.size = cr?.substringAfter('/')?.toLongOrNull() ?: c.getHeaderField("Content-Length")?.toLongOrNull()
                c.disconnect()
            }
        } catch (e: Exception) {
            f.failed = true
        }
        f.done = true
    }
}
