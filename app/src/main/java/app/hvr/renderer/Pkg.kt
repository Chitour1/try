package app.hvr.renderer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** الحزمة المفتوحة حاليًا داخل التطبيق */
class Pkg(private val ctx: Context) {
    private val base = File(ctx.filesDir, "pkg")
    private val metaFile = File(ctx.filesDir, "pkgname.txt")
    var name: String = if (metaFile.exists()) metaFile.readText() else "video"

    fun root(): File = findRoot(base) ?: base

    private fun findRoot(d: File): File? {
        if (!d.exists()) return null
        if (File(d, "index.html").exists()) return d
        d.listFiles()?.filter { it.isDirectory }?.forEach { sub -> findRoot(sub)?.let { return it } }
        val htmls = d.listFiles()?.filter { it.isFile && (it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) }
        if (htmls != null && htmls.size == 1) {
            htmls[0].copyTo(File(d, "index.html"), true)
            return d
        }
        return null
    }

    fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "file"
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** يستورد ملف zip أو أجزاءه (name.zip.001 ...) */
    fun import(uris: List<Uri>): String {
        val items = uris.map { it to displayName(it) }.sortedBy { it.second.lowercase() }
        val partRe = Regex("""^(.*\.zip)\.(\d{3})$""", RegexOption.IGNORE_CASE)
        val tmp = File(ctx.cacheDir, "in.zip")
        val first = items.first().second
        val newName: String
        if (items.size > 1 || partRe.matches(first)) {
            val parts = items.filter { partRe.matches(it.second) }
            if (parts.isEmpty()) throw Exception("اختر ملف zip واحدًا، أو كل أجزاء الحزمة معًا.")
            parts.forEachIndexed { i, p ->
                val n = partRe.find(p.second)!!.groupValues[2].toInt()
                if (n != i + 1) throw Exception("الجزء رقم ${i + 1} ناقص. اختر كل الأجزاء معًا.")
            }
            FileOutputStream(tmp).use { out ->
                parts.forEach { p -> ctx.contentResolver.openInputStream(p.first)!!.use { it.copyTo(out) } }
            }
            newName = partRe.find(parts[0].second)!!.groupValues[1].replace(Regex("(?i)\\.zip$"), "")
        } else {
            if (!first.endsWith(".zip", true)) throw Exception("الملف ليس حزمة zip.")
            ctx.contentResolver.openInputStream(items[0].first)!!.use { inp -> FileOutputStream(tmp).use { inp.copyTo(it) } }
            newName = first.replace(Regex("(?i)\\.zip$"), "")
        }
        base.deleteRecursively()
        base.mkdirs()
        unzip(tmp, base)
        tmp.delete()
        if (findRoot(base) == null) throw Exception("لم أجد ملف index.html داخل الحزمة.")
        name = newName
        metaFile.writeText(name)
        return name
    }

    private fun unzip(zip: File, dest: File) {
        val canon = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(canon)) throw Exception("مسار غير آمن داخل الحزمة")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    fun config(): JSONObject {
        val c = JSONObject("""{"width":1080,"height":1920,"fps":30,"crf":18}""")
        val f = File(root(), "render.json")
        if (f.exists()) {
            try {
                val j = JSONObject(f.readText().removePrefix("﻿"))
                j.keys().forEach { k -> c.put(k, j.get(k)) }
            } catch (_: Exception) { }
        }
        return c
    }

    fun save(html: String, cfg: String) {
        val r = root()
        val idx = File(r, "index.html")
        val orig = File(r, "index.original.html")
        if (!orig.exists()) idx.copyTo(orig)
        idx.writeText(html)
        File(r, "render.json").writeText(cfg)
    }

    fun addAsset(uri: Uri): String {
        val nm = displayName(uri).replace(Regex("""[\\/:*?"<>|]+"""), "_")
        val dir = File(root(), "assets/edited")
        dir.mkdirs()
        var f = File(dir, nm)
        var k = 1
        while (f.exists()) {
            val stem = nm.substringBeforeLast('.', nm)
            val ext = if (nm.contains('.')) "." + nm.substringAfterLast('.') else ""
            f = File(dir, "${stem}_${k++}$ext")
        }
        ctx.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        return f.relativeTo(root()).path.replace(File.separatorChar, '/')
    }

    /** يحفظ الحزمة بعد التعديل في مجلد التنزيلات */
    fun exportZip(): String {
        val fname = "${name}_معدلة.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fname)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HtmlVideoRenderer")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("تعذر الحفظ")
        val r = root()
        ctx.contentResolver.openOutputStream(uri)!!.use { os ->
            ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                r.walkTopDown().filter { it.isFile }.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.relativeTo(r).path.replace(File.separatorChar, '/')))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return "Download/HtmlVideoRenderer/$fname"
    }
}
