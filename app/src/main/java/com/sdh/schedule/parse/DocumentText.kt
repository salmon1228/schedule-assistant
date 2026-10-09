package com.sdh.schedule.parse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 文档正文抽取(零第三方依赖):
 *   - PDF  : 用系统自带 PdfRenderer 把页面渲染成图片,交给多模态大模型识别(扫描件也能认)
 *   - docx : 本质是 zip,里面 word/document.xml 就是正文
 *   - xlsx : xl/sharedStrings.xml
 *   - pptx : ppt/slides/slideN.xml
 *   - txt/md/csv/html/json/xml : 直接读文本(自动识别 UTF-8 / GBK / UTF-16)
 *   - doc/xls/ppt(旧二进制格式):尽力而为地抽取可见文本
 */
object DocumentText {

    private const val TAG = "DocumentText"
    private const val MAX_TEXT = 8000
    private const val MAX_PDF_PAGES = 3
    private const val PDF_RENDER_WIDTH = 1400

    data class Extracted(
        /** 抽出来的文字(可能为空,例如扫描版 PDF) */
        val text: String = "",
        /** PDF 渲染出来的页面图片,交给多模态大模型 */
        val pages: List<Bitmap> = emptyList(),
        /** 一句话说明,写进日志/提示 */
        val note: String = ""
    )

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    fun extract(context: Context, uri: Uri, mime: String?, name: String?): Extracted {
        val fileName = name?.takeIf { it.isNotBlank() } ?: displayName(context, uri)
        val type = (mime ?: context.contentResolver.getType(uri)).orEmpty().lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        Log.i(TAG, "解析文档: name=$fileName mime=$type ext=$ext")

        return when {
            type.contains("pdf") || ext == "pdf" -> extractPdf(context, uri)
            ext == "docx" || type.contains("wordprocessingml") -> zipXml(context, uri, listOf("word/document.xml"), "Word 文档")
            ext == "xlsx" || type.contains("spreadsheetml") -> zipXml(context, uri, listOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml"), "Excel 表格")
            ext == "pptx" || type.contains("presentationml") -> zipXml(context, uri, null, "PPT 演示")
            ext == "doc" || ext == "xls" || ext == "ppt" || type.contains("msword") || type.contains("ms-excel") || type.contains("ms-powerpoint") ->
                legacyBinary(context, uri, fileName)
            ext in listOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "ini", "conf") || type.startsWith("text/") ->
                plainText(context, uri, ext)
            else -> {
                // 兜底:当作纯文本试一下,不行就直说
                val t = plainText(context, uri, ext)
                if (t.text.isNotBlank()) t else Extracted(note = "暂不支持这种文件类型:${type.ifBlank { ext }}")
            }
        }
    }

    // ---------------- PDF ----------------

    private fun extractPdf(context: Context, uri: Uri): Extracted {
        // 先试"文本层直读":带文字层的 PDF(电子版通知/公文)不用联网、不用 Key 就能读出来
        val layerText = pdfTextLayer(context, uri)
        if (layerText.isNotBlank()) {
            return Extracted(text = layerText, note = "已直接读取 PDF 文本层")
        }

        // 文本层读不出来(扫描件/图片版),渲染成图交给 OCR 或多模态大模型
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val pages = ArrayList<Bitmap>()
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return Extracted(note = "打不开这个 PDF")
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            val take = minOf(count, MAX_PDF_PAGES)
            for (i in 0 until take) {
                renderer.openPage(i).use { page ->
                    val w = PDF_RENDER_WIDTH
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // PDF 背景透明,先铺白底,否则识别会糊
                    android.graphics.Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pages.add(bmp)
                }
            }
            Extracted(
                pages = pages,
                note = "PDF 共 $count 页,已渲染前 ${pages.size} 页用于识别"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "PDF 渲染失败", t)
            Extracted(note = "PDF 解析失败:${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    /**
     * PDF 文本层直读(零依赖,尽力而为)。
     * 处理常见情况:未压缩或 FlateDecode 的 content stream,提取 (…)Tj / […]TJ 里的字符串。
     * 中文若用 CID 字体(无 ToUnicode 映射)取出来是乱码,会通过"可读字符占比"判定失败,
     * 交给「渲染成图 + OCR/多模态」处理。
     */
    private fun pdfTextLayer(context: Context, uri: Uri): String {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { ins ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < 12 * 1024 * 1024) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
                bos.toByteArray()
            } ?: return ""
            if (bytes.size < 8) return ""

            val out = StringBuilder()
            val streamMarker = "stream".toByteArray(Charsets.ISO_8859_1)
            val endMarker = "endstream".toByteArray(Charsets.ISO_8859_1)
            var cursor = 0
            var streams = 0
            while (streams < 60) {
                val s = bytes.indexOfSeq(streamMarker, cursor)
                if (s < 0) break
                var dataStart = s + streamMarker.size
                if (dataStart < bytes.size && bytes[dataStart] == 0x0D.toByte()) dataStart++
                if (dataStart < bytes.size && bytes[dataStart] == 0x0A.toByte()) dataStart++
                val e = bytes.indexOfSeq(endMarker, dataStart)
                if (e < 0 || e <= dataStart) break
                val raw = bytes.copyOfRange(dataStart, e)
                val dictFrom = (s - 400).coerceAtLeast(0)
                val dict = String(bytes, dictFrom, s - dictFrom, Charsets.ISO_8859_1)
                cursor = e + endMarker.size
                streams++
                val decoded = if (dict.contains("FlateDecode")) inflateOrNull(raw) else raw
                if (decoded != null) extractShowText(decoded, out)
                if (out.length > MAX_TEXT * 2) break
            }
            val text = out.toString().trim()
            if (looksLikeRealText(text)) text.take(MAX_TEXT) else ""
        } catch (t: Throwable) {
            Log.w(TAG, "PDF 文本层读取失败", t)
            ""
        }
    }

    private fun ByteArray.indexOfSeq(needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0) return -1
        outer@ for (i in from..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun inflateOrNull(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        return try {
            val inf = java.util.zip.Inflater()
            inf.setInput(data)
            val bos = ByteArrayOutputStream(data.size * 4 + 64)
            val buf = ByteArray(8192)
            var total = 0
            while (!inf.finished() && total < 4 * 1024 * 1024) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
                total += n
            }
            inf.end()
            bos.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 content stream 里取出所有 PDF 字面量字符串(顺带把换行算子变成真换行) */
    private fun extractShowText(data: ByteArray, out: StringBuilder) {
        val s = String(data, Charsets.ISO_8859_1)
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("Td", i) || s.startsWith("TD", i) || s.startsWith("T*", i) -> {
                    out.append('\n')
                    i += 2
                }
                s[i] == '(' -> {
                    val sb = StringBuilder()
                    var depth = 1
                    i++
                    while (i < s.length && depth > 0) {
                        val ch = s[i]
                        if (ch == '\\') {
                            i++
                            if (i < s.length) {
                                val n = s[i]
                                when {
                                    n == 'n' -> sb.append('\n')
                                    n == 'r' -> sb.append('\r')
                                    n == 't' -> sb.append('\t')
                                    n in '0'..'7' -> {
                                        var oct = ""
                                        var k = 0
                                        while (k < 3 && i < s.length && s[i] in '0'..'7') {
                                            oct += s[i]; i++; k++
                                        }
                                        i--
                                        oct.toIntOrNull(8)?.let { v ->
                                            if (v in 1..0xFFFF) sb.append(v.toChar())
                                        }
                                    }
                                    else -> sb.append(n)
                                }
                            }
                        } else if (ch == '(') {
                            depth++
                            sb.append(ch)
                        } else if (ch == ')') {
                            depth--
                            if (depth > 0) sb.append(ch)
                        } else {
                            sb.append(ch)
                        }
                        i++
                    }
                    val piece = sb.toString()
                    if (piece.isNotBlank()) {
                        out.append(piece).append(' ')
                    }
                }
                else -> i++
            }
        }
    }

    /** 判断抽出来的到底是不是"人能看的文字",用来挡掉 CID 字体的乱码 */
    private fun looksLikeRealText(s: String): Boolean {
        if (s.length < 6) return false
        var good = 0
        var bad = 0
        for (c in s) {
            when {
                c.isLetterOrDigit() -> good++
                c in "，。：；！？、,.:;!?/()（）-—~～%年月日号点分 时" -> good++
                c == '\n' || c == '\t' || c == '\r' -> good++
                c.code < 0x20 -> bad++
                else -> bad++
            }
        }
        return good >= 6 && good * 100 / (good + bad).coerceAtLeast(1) >= 70
    }

    // ---------------- OOXML (docx/xlsx/pptx) ----------------
    private fun zipXml(context: Context, uri: Uri, entries: List<String>?, label: String): Extracted {
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return Extracted(note = "打不开文件")
            val sb = StringBuilder()
            val seen = ArrayList<String>()
            ZipInputStream(stream).use { zip ->
                var entry = try {
                    zip.nextEntry
                } catch (t: Throwable) {
                    Log.w(TAG, "$label zip 首个条目异常", t)
                    null
                }
                while (entry != null) {
                    // 注意:有些工具(Windows 上打的包)用反斜杠当分隔符,统一成 /
                    val en = entry.name.replace('\\', '/')
                    if (seen.size < 30) seen.add(en)
                    val wanted = when {
                        entries != null -> entries.any { en.equals(it, ignoreCase = true) }
                        en.startsWith("ppt/slides/slide") && en.endsWith(".xml") -> true
                        en.equals("word/document.xml", ignoreCase = true) -> true
                        else -> false
                    }
                    if (wanted) {
                        val bytes = readEntry(zip)
                        if (bytes.isNotEmpty()) {
                            val xml = String(bytes, Charsets.UTF_8)
                            sb.append(xmlToText(xml)).append('\n')
                        }
                    }
                    entry = try {
                        zip.nextEntry
                    } catch (t: Throwable) {
                        Log.w(TAG, "$label 后续 zip 条目异常,停止读取", t)
                        null
                    }
                    if (sb.length > MAX_TEXT * 2) break
                }
            }
            val text = sb.toString().trim().take(MAX_TEXT)
            if (text.isBlank()) {
                Log.w(TAG, "$label 没读到正文,zip 内条目=$seen")
                Extracted(note = "$label 里没读到文字(可能是纯图片内容)")
            } else {
                Extracted(text = text, note = "已从 $label 抽取正文")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label 解析失败", t)
            Extracted(note = "$label 解析失败:${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < 2 * 1024 * 1024) {
            val n = zip.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    /** OOXML → 文本:段落/单元格/幻灯片换行,去标签 */
    fun xmlToText(xml: String): String {
        var s = xml
        s = s.replace(Regex("(?i)</w:p>"), "\n")
        s = s.replace(Regex("(?i)</a:p>"), "\n")
        s = s.replace(Regex("(?i)</w:tr>"), "\n")
        s = s.replace(Regex("(?i)</c>"), "\t")
        s = s.replace(Regex("(?i)<w:tab[^>]*/>"), "\t")
        s = s.replace(Regex("(?i)<w:br[^>]*/>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
        s = s.replace("&#10;", "\n").replace("&#9;", "\t")
        s = s.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        return s
    }

    // ---------------- 纯文本 / 旧格式 ----------------

    private fun plainText(context: Context, uri: Uri, ext: String): Extracted {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { ins ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < 2 * 1024 * 1024) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
                bos.toByteArray()
            } ?: return Extracted(note = "打不开文件")

            val raw = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> {
                    val utf8 = String(bytes, Charsets.UTF_8)
                    val bad = utf8.count { it == '\uFFFD' }
                    if (bad > 2) runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8) else utf8
                }
            }
            val text = if (ext == "html" || ext == "htm") LinkFetcher.htmlToText(raw) else raw
            Extracted(text = text.take(MAX_TEXT), note = "已读取文本文件")
        } catch (t: Throwable) {
            Extracted(note = "读取失败:${t.message}")
        }
    }

    /** 旧版 .doc/.xls/.ppt 是二进制复合文档,这里尽量把可见文本抠出来 */
    private fun legacyBinary(context: Context, uri: Uri, fileName: String): Extracted {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Extracted(note = "打不开文件")
            val sb = StringBuilder()
            // 先试 UTF-16LE(Word 正文常见编码)
            val u16 = String(bytes, Charsets.UTF_16LE)
            val runs = Regex("[\\u4e00-\\u9fa5\\u3000-\\u303f，。：；！？、0-9A-Za-z:：\\-—~～/()（） ]{6,}")
                .findAll(u16).map { it.value.trim() }.filter { it.count { c -> c.isLetterOrDigit() } >= 4 }
            runs.take(400).forEach { sb.append(it).append('\n') }
            if (sb.length < 30) {
                // 再试 GBK / ASCII
                val gbk = runCatching { String(bytes, charset("GBK")) }.getOrDefault("")
                Regex("[\\u4e00-\\u9fa5，。：；！？、0-9A-Za-z:：\\-—~～/()（） ]{6,}")
                    .findAll(gbk).map { it.value.trim() }.filter { it.count { c -> c.isLetterOrDigit() } >= 4 }
                    .take(400).forEach { sb.append(it).append('\n') }
            }
            val text = sb.toString().trim().take(MAX_TEXT)
            if (text.isBlank()) {
                Extracted(note = "旧版 Office 格式(如 .doc)较难解析,建议另存为 .docx 或 PDF 后再分享过来")
            } else {
                Extracted(text = text, note = "已尽力从旧版 Office 文件抽取文本(可能不完整)")
            }
        } catch (t: Throwable) {
            Extracted(note = "读取失败:${t.message}")
        }
    }
}
