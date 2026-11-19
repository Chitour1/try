package com.chitour.familycast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** الهاتف: يكتشف أجهزة الاستقبال في الشبكة ويرسل إليها الأفلام والأوامر */
object TvLink {
    data class Rx(val name: String, val host: String, val port: Int)

    val found = LinkedHashMap<String, Rx>()
    var onChange: (() -> Unit)? = null
    @Volatile var last: JSONObject? = null
    @Volatile var lastOk = 0L
    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var discovering = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun target(): String = Store.pref("target")
    fun selected(): Rx? {
        val h = Store.pref("tvHost"); if (h.isBlank()) return null
        return Rx(Store.pref("rxName").ifBlank { "التلفاز" }, h, Store.pref("tvPort").toIntOrNull() ?: ReceiverService.PORT)
    }
    fun useTv(): Boolean = target() == "tv" && selected() != null

    fun select(rx: Rx) {
        Store.setPref("target", "tv"); Store.setPref("tvHost", rx.host)
        Store.setPref("tvPort", rx.port.toString()); Store.setPref("rxName", rx.name)
        last = null
    }
    fun selectCast() { Store.setPref("target", "cast") }

    fun start(ctx: Context) {
        if (discovering) return
        try {
            nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd?.discoverServices(ReceiverService.TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) { discovering = true }
                override fun onDiscoveryStopped(t: String) { discovering = false }
                override fun onStartDiscoveryFailed(t: String, e: Int) { discovering = false }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceFound(i: NsdServiceInfo) { main.post { queue.add(i); next() } }
                override fun onServiceLost(i: NsdServiceInfo) { main.post { found.remove(i.serviceName); onChange?.invoke() } }
            })
            discovering = true
        } catch (e: Exception) { }
    }

    private fun next() {
        if (resolving) return
        val i = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd?.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, e: Int) { main.post { resolving = false; next() } }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = si.host?.hostAddress
                    main.post {
                        if (host != null) {
                            val rx = Rx(si.serviceName, host, si.port)
                            found[si.serviceName] = rx
                            // إن تغيّر عنوان التلفاز المختار نحدّثه تلقائيًا
                            if (Store.pref("rxName") == rx.name && Store.pref("tvHost") != host) Store.setPref("tvHost", host)
                            if (target().isBlank()) select(rx)
                            onChange?.invoke()
                        }
                        resolving = false; next()
                    }
                }
            })
        } catch (e: Exception) { resolving = false }
    }

    private fun request(rx: Rx, path: String, body: JSONObject?): String? {
        val c = URL("http://${rx.host}:${rx.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 6000
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        return if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
    }

    fun post(path: String, body: JSONObject, cb: ((Boolean) -> Unit)? = null) {
        val rx = selected() ?: run { cb?.invoke(false); return }
        Thread {
            val ok = try { request(rx, path, body) != null } catch (e: Exception) { false }
            main.post { cb?.invoke(ok) }
        }.start()
    }

    fun cmd(a: String, vararg kv: Pair<String, Any>) {
        val j = JSONObject().put("a", a)
        for ((k, v) in kv) j.put(k, v)
        post("/cmd", j)
    }

    @Volatile private var polling = false
    fun poll(cb: () -> Unit) {
        val rx = selected() ?: return
        if (polling) return
        polling = true
        Thread {
            try { request(rx, "/status", null)?.let { last = JSONObject(it); lastOk = System.currentTimeMillis() } } catch (e: Exception) { }
            polling = false
            main.post { cb() }
        }.start()
    }

    /** يتحقق من عنوان أُدخل يدويًا */
    fun probe(host: String, cb: (Rx?) -> Unit) {
        Thread {
            val rx = try {
                val t = request(Rx("?", host, ReceiverService.PORT), "/info", null)
                t?.let { Rx(JSONObject(it).optString("name", "التلفاز"), host, ReceiverService.PORT) }
            } catch (e: Exception) { null }
            main.post { cb(rx) }
        }.start()
    }

    fun segJson(m: Movie): JSONArray {
        val a = JSONArray()
        for (s in m.segments) a.put(JSONObject().put("s", s.start).put("e", s.end).put("m", s.mute))
        return a
    }

    /** الفيلم كاملًا للتلفاز: الرابط وترويساته، والتجاوز، والترجمة، ونقطة البدء */
    fun movieJson(m: Movie, startMs: Long): JSONObject? {
        var url = m.variant.ifBlank { m.url }
        if (url.startsWith("content://") || url.startsWith("file://")) url = Proxy.wrapLocal(url) ?: return null
        val subs = JSONArray()
        for (s in m.subs) {
            val o = JSONObject().put("label", s.label)
            if (s.url.startsWith("file://")) o.put("text", try { File(android.net.Uri.parse(s.url).path!!).readText() } catch (e: Exception) { "" })
            else o.put("url", s.url)
            subs.put(o)
        }
        return JSONObject().put("id", m.id).put("title", m.title).put("url", url)
            .put("referer", m.referer).put("ua", m.userAgent).put("start", startMs)
            .put("segments", segJson(m)).put("skip", Store.skipEnabled)
            .put("subs", subs).put("subsOn", m.subsOn).put("subScale", Store.subScale.toDouble())
    }
}
