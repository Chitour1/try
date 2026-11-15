package app.hvr.renderer

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** خادم داخلي وهمي على https://hvr.app يقدّم ملفات التطبيق والحزمة وإطارات الفيديو */
object LocalServer {
    private val mimes = mapOf(
        "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "css" to "text/css",
        "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "mp4" to "video/mp4",
        "webm" to "video/webm", "mov" to "video/quicktime", "m4a" to "audio/mp4", "mp3" to "audio/mpeg",
        "wav" to "audio/wav", "ogg" to "audio/ogg", "woff" to "font/woff", "woff2" to "font/woff2",
        "ttf" to "font/ttf", "otf" to "font/otf"
    )

    private fun mime(n: String) = mimes[n.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun text(m: String): String? = if (m.startsWith("text/") || m.contains("json") || m.contains("javascript") || m.contains("svg")) "utf-8" else null

    private fun resp(m: String, data: InputStream, code: Int = 200, extra: Map<String, String> = emptyMap()): WebResourceResponse {
        val h = HashMap<String, String>()
        h["Access-Control-Allow-Origin"] = "*"
        h["Cache-Control"] = "no-store"
        h.putAll(extra)
        val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; else -> "Error" }
        return WebResourceResponse(m, text(m), code, reason, h, data)
    }

    private fun notFound() = resp("text/plain", ByteArrayInputStream(ByteArray(0)), 404)

    fun handle(act: MainActivity, pkg: Pkg, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != "hvr.app") return null
        val path = u.path ?: "/"
        return try {
            when {
                path.startsWith("/assets/") -> {
                    val a = path.removePrefix("/assets/")
                    resp(mime(a), act.assets.open(a))
                }
                path.startsWith("/vframe/") -> VideoFrames.serve(pkg, path)
                path.startsWith("/pkg/") -> servePkg(pkg, req, path.removePrefix("/pkg/"))
                else -> notFound()
            }
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun servePkg(pkg: Pkg, req: WebResourceRequest, rel: String): WebResourceResponse {
        val root = pkg.root()
        val f = File(root, rel)
        if (!f.canonicalPath.startsWith(root.canonicalPath) || !f.isFile) return notFound()
        // صفحة التصيير: نحقن أداة التحكم في الزمن قبل أي رمز في الصفحة
        if (rel == "index.html" && req.url.getQueryParameter("render") == "1") {
            val html = f.readText()
            val inj = "<script src=\"$HOST/assets/inject_render.js\"></script>"
            val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            val out = if (m != null) html.substring(0, m.range.last + 1) + inj + html.substring(m.range.last + 1) else inj + html
            return resp("text/html", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
        }
        val len = f.length()
        val range = req.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        if (range != null) {
            val m = Regex("""bytes=(\d*)-(\d*)""").find(range)
            val a = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var b = m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1)
            if (b >= len) b = len - 1
            if (a >= len) return resp(mime(rel), ByteArrayInputStream(ByteArray(0)), 404)
            val inp = FileInputStream(f)
            var skipped = 0L
            while (skipped < a) { val s = inp.skip(a - skipped); if (s <= 0) break; skipped += s }
            val cnt = b - a + 1
            return resp(mime(rel), Limited(inp, cnt), 206, mapOf(
                "Content-Range" to "bytes $a-$b/$len", "Accept-Ranges" to "bytes", "Content-Length" to cnt.toString()))
        }
        return resp(mime(rel), FileInputStream(f), 200, mapOf("Accept-Ranges" to "bytes", "Content-Length" to len.toString()))
    }

    private class Limited(private val inp: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = inp.read()
            if (r >= 0) left--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = inp.close()
    }
}

/** إطارات لقطات الفيديو صورًا ثابتة، للالتقاط إطارًا إطارًا */
object VideoFrames {
    private val cache = HashMap<String, MediaMetadataRetriever>()

    @Synchronized
    private fun retriever(f: File): MediaMetadataRetriever =
        cache.getOrPut(f.path) { MediaMetadataRetriever().apply { setDataSource(f.path) } }

    @Synchronized
    fun durationSec(f: File): Double =
        (retriever(f).extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0

    fun serve(pkg: Pkg, path: String): WebResourceResponse {
        // /vframe/<ms>/<المسار داخل الحزمة>
        val rest = path.removePrefix("/vframe/")
        val ms = rest.substringBefore('/').toLong()
        val rel = rest.substringAfter('/')
        val f = File(pkg.root(), rel)
        val bmp: Bitmap? = synchronized(this) { retriever(f).getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
        val bos = ByteArrayOutputStream()
        bmp?.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        bmp?.recycle()
        return WebResourceResponse("image/jpeg", null, 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bos.toByteArray()))
    }

    @Synchronized
    fun release() {
        cache.values.forEach { try { it.release() } catch (_: Exception) { } }
        cache.clear()
    }
}
