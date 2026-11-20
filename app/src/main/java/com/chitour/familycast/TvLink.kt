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
    /** المفضّل دائمًا تطبيق الاستقبال على التلفاز، إلا إذا اختار المستخدم Chromecast صراحة */
    fun preferTv(): Boolean = target() != "cast"
    fun useTv(): Boolean = preferTv() && selected() != null

    private fun info(host: String, port: Int, cto: Int, rto: Int): Rx? = try {
        val c = URL("http://$host:$port/info").openConnection() as HttpURLConnection
        c.connectTimeout = cto; c.readTimeout = rto
        if (c.responseCode == 200) {
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (o.optString("app") == "familycast") Rx(o.optString("name", "التلفاز"), host, port) else null
        } else null
    } catch (e: Exception) { null }

    @Volatile var scanning = false

    /** بحث مباشر في الشبكة المنزلية عن التلفاز (لا يعتمد على خدمة الاكتشاف التي تحجبها بعض الموجّهات) */
    fun scan(done: (() -> Unit)? = null) {
        if (scanning) { done?.let { main.postDelayed({ it() }, 3000) }; return }
        val ip = Proxy.localIp() ?: run { done?.invoke(); return }
        val prefix = ip.substringBeforeLast('.')
        scanning = true
        Thread {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
            for (i in 1..254) {
                val host = "$prefix.$i"
                if (host == ip) continue
                pool.execute {
                    info(host, ReceiverService.PORT, 600, 1200)?.let { rx -> main.post { found[rx.name] = rx; onChange?.invoke() } }
                }
            }
            pool.shutdown()
            try { pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
            scanning = false
            main.post { done?.invoke() }
        }.start()
    }

    /** يتأكد من وجود التلفاز قبل الإرسال: العنوان المحفوظ أولًا، ثم الاكتشاف والبحث */
    fun ensure(ctx: Context, cb: (Rx?) -> Unit) {
        start(ctx)
        val sel = selected()
        Thread {
            val ok = sel?.let { info(it.host, it.port, 1500, 2000) }
            main.post {
                if (ok != null) { select(ok); cb(ok); return@post }
                found.values.firstOrNull()?.let { rx ->
                    Thread { val r = info(rx.host, rx.port, 1500, 2000); main.post { if (r != null) { select(r); cb(r) } else scanThen(cb) } }.start()
                    return@post
                }
                scanThen(cb)
            }
        }.start()
    }

    private fun scanThen(cb: (Rx?) -> Unit) {
        scan {
            val rx = found.values.firstOrNull()
            if (rx != null) select(rx)
            cb(rx)
        }
    }

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
                            if (preferTv() && selected() == null) select(rx)
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

    /** المكتبة كاملة للتلفاز، ليشغّل منها دون الهاتف */
    fun libJson(): JSONArray {
        val a = JSONArray()
        for (m in Store.movies) {
            if (m.isLocal()) continue
            val j = movieJson(m, 0) ?: continue
            j.put("sec", m.section).put("ser", m.series).put("ep", m.ep).put("wl", m.watch).put("po", m.poster)
                .put("pos", m.pos).put("dur", m.dur).put("at", m.watchedAt)
            a.put(j)
        }
        return a
    }

    fun syncLibrary() {
        if (!useTv()) return
        try { post("/library", JSONObject().put("items", libJson()).put("sections", JSONArray(Store.sections))) } catch (e: Exception) { }
    }

    /** يأخذ من التلفاز أماكن التوقف لما شوهد عليه دون الهاتف */
    fun pullHistory(done: () -> Unit) {
        val rx = selected() ?: return
        if (!preferTv()) return
        Thread {
            val t = try { request(rx, "/history", null) } catch (e: Exception) { null }
            main.post {
                if (t != null) try {
                    val o = JSONObject(t); var changed = false
                    for (k in o.keys()) {
                        val h = o.getJSONObject(k); val m = Store.get(k) ?: continue
                        if (h.optLong("at") > m.watchedAt) { m.pos = h.optLong("pos"); m.dur = h.optLong("dur").takeIf { it > 0 } ?: m.dur; m.watchedAt = h.optLong("at"); changed = true }
                    }
                    if (changed) { Store.save(); done() }
                } catch (e: Exception) { }
            }
        }.start()
    }

    fun segJson(m: Movie): JSONArray {
        val a = JSONArray()
        for (s in m.segments) a.put(JSONObject().put("s", s.start).put("e", s.end).put("m", s.mute).put("c", s.cover).put("n", s.note))
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
            .put("coverScale", Store.coverScale.toDouble()).put("tts", Store.ttsMode)
    }
}
