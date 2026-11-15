package app.hvr.renderer

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** ترميز H.264 بمرمّز الهاتف نفسه، ودمج الصوت (AAC) دون إعادة ترميز */
class Encoder(
    private val w: Int, private val h: Int, private val fps: Int,
    out: File, audio: File?, private val durSec: Double
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType("video/avc")
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var vTrack = -1
    private var aTrack = -1
    private var started = false
    private var aExtractor: MediaExtractor? = null
    private var aIndex = -1
    private val argb = IntArray(w * h)
    private val yRow = ByteArray(w)
    private val uArr = ByteArray((w / 2) * (h / 2))
    private val vArr = ByteArray((w / 2) * (h / 2))
    private var lastPts = 0L

    init {
        val f = MediaFormat.createVideoFormat("video/avc", w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 4).coerceAtMost(40_000_000L).toInt())
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        if (audio != null) {
            val ex = MediaExtractor()
            ex.setDataSource(audio.path)
            for (i in 0 until ex.trackCount) {
                val mf = ex.getTrackFormat(i)
                if ((mf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/mp4a")) { aIndex = i; break }
            }
            if (aIndex >= 0) { ex.selectTrack(aIndex); aExtractor = ex } else ex.release()
        }
    }

    fun encode(bmp: Bitmap, idx: Int) {
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        var inIdx: Int
        while (true) {
            inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) break
            drain(false)
        }
        val img = codec.getInputImage(inIdx) ?: throw Exception("المرمّز لا يدعم الإدخال المطلوب")
        fillYuv(img)
        lastPts = idx * 1_000_000L / fps
        codec.queueInputBuffer(inIdx, 0, w * h * 3 / 2, lastPts, 0)
        drain(false)
    }

    private fun fillYuv(img: Image) {
        val pY = img.planes[0]
        val yBuf = pY.buffer
        val yRs = pY.rowStride
        val yPs = pY.pixelStride
        val w2 = w / 2
        for (y in 0 until h) {
            val off = y * w
            val even = (y and 1) == 0
            for (x in 0 until w) {
                val c = argb[off + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (even && (x and 1) == 0) {
                    val i = (y / 2) * w2 + x / 2
                    uArr[i] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    vArr[i] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
            if (yPs == 1) { yBuf.position(y * yRs); yBuf.put(yRow, 0, w) }
            else for (x in 0 until w) yBuf.put(y * yRs + x * yPs, yRow[x])
        }
        writeChroma(img.planes[1], uArr, w2, h / 2)
        writeChroma(img.planes[2], vArr, w2, h / 2)
    }

    private fun writeChroma(p: Image.Plane, arr: ByteArray, cw: Int, ch: Int) {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        for (y in 0 until ch) {
            if (ps == 1) { buf.position(y * rs); buf.put(arr, y * cw, cw) }
            else { val base = y * rs; for (x in 0 until cw) buf.put(base + x * ps, arr[y * cw + x]) }
        }
    }

    private fun drain(end: Boolean) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                vTrack = muxer.addTrack(codec.outputFormat)
                aExtractor?.let { aTrack = muxer.addTrack(it.getTrackFormat(aIndex)) }
                muxer.start()
                started = true
                continue
            }
            if (o < 0) continue
            val buf = codec.getOutputBuffer(o)!!
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
            if (info.size > 0 && started) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                muxer.writeSampleData(vTrack, buf, info)
            }
            codec.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    fun finish() {
        var inIdx: Int
        while (true) {
            inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) break
            drain(false)
        }
        codec.queueInputBuffer(inIdx, 0, 0, lastPts + 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop()
        codec.release()
        aExtractor?.let { ex ->
            if (aTrack >= 0 && started) {
                val bb = ByteBuffer.allocate(1 shl 20)
                val bi = MediaCodec.BufferInfo()
                val limitUs = (durSec * 1_000_000).toLong()
                while (true) {
                    val n = ex.readSampleData(bb, 0)
                    if (n < 0) break
                    val t = ex.sampleTime
                    if (t > limitUs) break
                    val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    bi.set(0, n, t, flags)
                    muxer.writeSampleData(aTrack, bb, bi)
                    ex.advance()
                }
            }
            ex.release()
        }
        if (started) muxer.stop()
        muxer.release()
    }

    fun abort() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { aExtractor?.release() } catch (_: Exception) { }
        try { if (started) muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}
