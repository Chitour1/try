package app.hvr.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** يصيّر الحزمة إطارًا إطارًا داخل متصفح مخفي ثم يرمّزها فيديو */
class Renderer(private val act: MainActivity, private val pkg: Pkg) {
    private lateinit var web: WebView
    private var enc: Encoder? = null
    private val encExec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var waitingNext = false
    private var w = 1080
    private var h = 1920
    private var fps = 30
    private var durSec = Double.NaN
    private var total = 0
    private var frame = 0
    private var token = 0
    private var clip: JSONObject? = null
    private var startTime = 0L
    private var lastUi = 0L
    @Volatile private var stopped = false

    fun start() {
        val cfg = pkg.config()
        w = cfg.optInt("width", 1080)
        h = cfg.optInt("height", 1920)
        fps = cfg.optInt("fps", 30)
        durSec = cfg.optDouble("duration", Double.NaN)
        web = WebView(act)
        act.setupWeb(web)
        web.addJavascriptInterface(Bridge(), "RenderBridge")
        // المتصفح المخفي بمقاس الفيديو الحقيقي، خلف الواجهة
        act.root.addView(web, 0, FrameLayout.LayoutParams(w, h))
        act.renderWeb = web
        act.progress(0, "جارٍ تحميل التصميم…")
        web.loadUrl("$HOST/pkg/index.html?render=1")
    }

    fun cancel() {
        if (stopped) return
        stopped = true
        encExec.execute {
            try { enc?.abort() } catch (_: Exception) { }
            act.main.post { cleanup(); act.renderFinished(null, "أُلغي التصيير.") }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun videoDuration(rel: String): Double =
            try { VideoFrames.durationSec(File(pkg.root(), rel)) } catch (e: Exception) { 0.0 }

        @JavascriptInterface
        fun loaded(info: String) { act.main.post { onLoaded(JSONObject(info)) } }

        @JavascriptInterface
        fun ready(tok: Int) { act.main.post { if (tok == token && !stopped) capture() } }
    }

    private fun onLoaded(info: JSONObject) {
        if (stopped || enc != null) return
        if (durSec.isNaN() || durSec <= 0) {
            val d = info.optDouble("dur", 0.0)
            durSec = if (d > 0) d / 1000.0 else 10.0
        }
        total = Math.round(durSec * fps).toInt()
        clip = info.optJSONObject("clip")
        val audioRel = pkg.config().optString("audio", "")
        val audio = if (audioRel.isNotEmpty()) File(pkg.root(), audioRel).takeIf { it.isFile } else null
        try {
            enc = Encoder(w, h, fps, File(act.cacheDir, "render.mp4"), audio, durSec)
        } catch (e: Exception) {
            fail(e); return
        }
        startTime = SystemClock.elapsedRealtime()
        step()
    }

    private fun step() {
        if (stopped) return
        if (frame >= total) { finish(); return }
        token++
        val ms = frame * 1000.0 / fps
        web.evaluateJavascript("window.__step($ms, $token)", null)
    }

    private fun capture() {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val d = act.resources.displayMetrics.density
        clip?.let { cl ->
            val s = (w / (cl.getDouble("w") * d)).toFloat()
            c.scale(s, s)
            c.translate((-cl.getDouble("x") * d).toFloat(), (-cl.getDouble("y") * d).toFloat())
        }
        web.draw(c)
        val idx = frame
        val e = enc!!
        pending.incrementAndGet()
        encExec.execute {
            try {
                if (!stopped) e.encode(bmp, idx)
            } catch (ex: Exception) {
                act.main.post { fail(ex) }
            }
            bmp.recycle()
            pending.decrementAndGet()
            act.main.post { if (waitingNext && pending.get() < 3) { waitingNext = false; step() } }
        }
        frame++
        updateProgress()
        if (pending.get() < 3) step() else waitingNext = true
    }

    private fun updateProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUi < 500 && frame < total) return
        lastUi = now
        val el = (now - startTime) / 1000.0
        val rate = frame / el.coerceAtLeast(0.001)
        val left = ((total - frame) / rate.coerceAtLeast(0.001)).toInt()
        val pct = if (total > 0) frame * 100 / total else 0
        act.progress(pct, "الإطار $frame من $total · المتبقي نحو ${left / 60}:${"%02d".format(left % 60)}")
    }

    private fun finish() {
        act.progress(100, "جارٍ إنهاء الملف…")
        val e = enc!!
        encExec.execute {
            try {
                e.finish()
                val uri = act.saveToGallery(File(act.cacheDir, "render.mp4"), pkg.name)
                act.main.post { cleanup(); act.renderFinished(uri, null) }
            } catch (ex: Exception) {
                act.main.post { fail(ex) }
            }
        }
    }

    private fun fail(e: Exception) {
        if (stopped) return
        stopped = true
        encExec.execute { try { enc?.abort() } catch (_: Exception) { } }
        cleanup()
        act.renderFinished(null, e.message ?: e.javaClass.simpleName)
    }

    private fun cleanup() {
        try { act.root.removeView(web); web.destroy() } catch (_: Exception) { }
        act.renderWeb = null
        VideoFrames.release()
    }
}
