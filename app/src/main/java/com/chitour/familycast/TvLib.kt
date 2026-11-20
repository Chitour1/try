package com.chitour.familycast

import org.json.JSONArray
import org.json.JSONObject

/** مكتبة التلفاز: نسخة من مكتبة الهاتف + سجل المشاهدة على التلفاز نفسه */
object TvLib {
    fun save(o: JSONObject) {
        Store.setPref("lib", (o.optJSONArray("items") ?: JSONArray()).toString())
        o.optJSONArray("sections")?.let { Store.setPref("libsec", it.toString()) }
    }

    fun items(): List<JSONObject> = try {
        val a = JSONArray(Store.pref("lib").ifBlank { "[]" }); (0 until a.length()).map { a.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    fun sections(): List<String> = try {
        val a = JSONArray(Store.pref("libsec")); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { listOf("أفلام", "مسلسلات", "وثائقيات", "للأطفال") }

    private fun hist(): JSONObject = try { JSONObject(Store.pref("tvhist").ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
    fun history(): String = hist().toString()

    fun record(id: String, pos: Long, dur: Long) {
        if (id.isBlank()) return
        val h = hist()
        h.put(id, JSONObject().put("pos", pos).put("dur", dur).put("at", System.currentTimeMillis()))
        Store.setPref("tvhist", h.toString())
    }

    /** أحدث مكان توقف (التلفاز أو الهاتف) */
    fun state(j: JSONObject): Triple<Long, Long, Long> {
        val h = hist().optJSONObject(j.optString("id"))
        return if (h != null && h.optLong("at") >= j.optLong("at")) Triple(h.optLong("pos"), h.optLong("dur"), h.optLong("at"))
               else Triple(j.optLong("pos"), j.optLong("dur"), j.optLong("at"))
    }
    fun finished(j: JSONObject): Boolean { val (p, d, _) = state(j); return d > 0 && p >= d - 120_000 }
    fun progress(j: JSONObject): Int { val (p, d, _) = state(j); return if (d > 0) (p * 100 / d).toInt().coerceIn(0, 100) else 0 }

    fun episodes(series: String) = items().filter { it.optString("ser") == series }.sortedBy { it.optInt("ep") }
    fun next(id: String): JSONObject? {
        val cur = items().firstOrNull { it.optString("id") == id } ?: return null
        val s = cur.optString("ser"); if (s.isBlank()) return null
        return episodes(s).firstOrNull { it.optInt("ep") > cur.optInt("ep") }
    }

    fun continueWatching(): List<JSONObject> {
        val seen = HashSet<String>()
        return items().filter { val (p, _, _) = state(it); p > 60_000 && !finished(it) }
            .sortedByDescending { state(it).third }
            .filter { it.optString("ser").isBlank() || seen.add(it.optString("ser")) }
    }
}
