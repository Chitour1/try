package com.chitour.familycast

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/** خادم صغير على الهاتف يمرّر الفيديو إلى التلفاز مع ترويسات الموقع (للمواقع التي ترفض التلفاز مباشرة) */
object Proxy {
    private var server: Server? = null
    private const val F = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun enc(s: String): String = Base64.encodeToString(s.toByteArray(), F)
    fun dec(s: String): String = String(Base64.decode(s, F))

    @Synchronized
    fun ensure(): Int {
        server?.let { if (it.isAlive) return it.listeningPort }
        val s = try {
            Server(8765).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        } catch (e: Exception) {
            Server(0).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        }
        server = s
        return s.listeningPort
    }

    fun localIp(): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        for (nif in ifs.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }) {
            if (!nif.isUp || nif.isLoopback) continue
            for (a in nif.inetAddresses.toList()) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
            }
        }
        return null
    }

    fun ext(u: String): String {
        val path = try { URL(u).path } catch (e: Exception) { "" }
        val m = Regex("""\.([a-zA-Z0-9]{2,5})$""").find(path)
        return if (m != null) "." + m.groupValues[1].lowercase() else ""
    }

    fun q(target: String, ref: String, ua: String) = "u=${enc(target)}&r=${enc(ref)}&a=${enc(ua)}"

    fun wrap(target: String, ref: String, ua: String): String? {
        val ip = localIp() ?: return null
        val port = ensure()
        val e = if (target.lowercase().contains("m3u8")) ".m3u8" else ext(target).ifEmpty { ".mp4" }
        return "http://$ip:$port/v$e?${q(target, ref, ua)}"
    }

    class Server(port: Int) : NanoHTTPD(port) {
        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Headers", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            r.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range")
            return r
        }

        private fun status(code: Int) = object : Response.IStatus {
            override fun getRequestStatus() = code
            override fun getDescription() = "$code " + when (code) {
                200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; 403 -> "Forbidden"; else -> "Status"
            }
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.OPTIONS)
                return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
            return try {
                val p = session.parameters
                val u = p["u"]?.firstOrNull()
                    ?: return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no url"))
                val target = Proxy.dec(u)
                val ref = p["r"]?.firstOrNull()?.let { Proxy.dec(it) } ?: ""
                val ua = p["a"]?.firstOrNull()?.let { Proxy.dec(it) } ?: ""
                fetch(session, target, ref, ua)
            } catch (e: Exception) {
                cors(NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString()))
            }
        }

        private fun fetch(session: IHTTPSession, target: String, ref: String, ua: String): Response {
            val c = URL(target).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
            c.setRequestProperty("User-Agent", ua.ifEmpty { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36" })
            session.headers["range"]?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            val ctype = c.contentType ?: ""
            val isList = target.lowercase().contains("m3u8") || ctype.lowercase().contains("mpegurl")
            if (isList && code < 400) {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val out = rewrite(text, c.url.toString(), ref, ua)
                return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", out))
            }
            val stream = if (code >= 400) (c.errorStream ?: ByteArrayInputStream(ByteArray(0))) else c.inputStream
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val mime = ctype.ifBlank { Util.mimeOf(target) }
            val r = if (len >= 0) NanoHTTPD.newFixedLengthResponse(status(code), mime, stream, len)
                    else NanoHTTPD.newChunkedResponse(status(code), mime, stream)
            c.getHeaderField("Content-Range")?.let { r.addHeader("Content-Range", it) }
            r.addHeader("Accept-Ranges", "bytes")
            return cors(r)
        }

        private fun link(abs: String, ref: String, ua: String): String {
            val e = if (abs.lowercase().contains("m3u8")) ".m3u8" else Proxy.ext(abs)
            return "/s$e?${Proxy.q(abs, ref, ua)}"
        }

        private fun rewrite(text: String, base: String, ref: String, ua: String): String {
            val b = URL(base)
            val uriRx = Regex("URI=\"([^\"]+)\"")
            return text.lines().joinToString("\n") { line ->
                val t = line.trim()
                when {
                    t.isEmpty() -> line
                    t.startsWith("#") -> uriRx.replace(line) { m ->
                        "URI=\"" + link(URL(b, m.groupValues[1]).toString(), ref, ua) + "\""
                    }
                    else -> link(URL(b, t).toString(), ref, ua)
                }
            }
        }
    }
}
