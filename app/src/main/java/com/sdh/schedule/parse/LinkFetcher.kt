package com.sdh.schedule.parse

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 链接内容抓取:消息里常常只给一个链接(报名页、会议通知、公告),
 * 真正的时间信息在网页里。这里把网页正文抠出来交给解析引擎。
 *
 * 只做纯文本抽取(不执行 JS),限制体积与超时,尽量识别中文站点的 GBK 编码。
 */
object LinkFetcher {

    private const val TAG = "LinkFetcher"
    private const val MAX_BYTES = 512 * 1024      // 最多读 512KB
    private const val MAX_TEXT = 6000             // 抽出的正文最多 6000 字
    private const val TIMEOUT_MS = 10_000

    private val URL_REGEX = Regex("""https?://[^\s，。；;、)）(（"'<>【】\[\]]+""")

    /** 从一段文字里找出所有链接 */
    fun findUrls(text: String): List<String> =
        URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', '。', '，', ')', '）') }
            .filter { it.length > 8 }
            .distinct()
            .take(2)
            .toList()

    /**
     * 抓取并抽取正文。失败返回 null(不抛异常,解析流程不该因为一个链接挂掉)。
     */
    fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "抓取失败 HTTP $code: $url")
                return null
            }
            val contentType = conn.contentType.orEmpty()
            val charset = detectCharset(contentType, conn.contentType?.let { null })
            val bytes = readCapped(conn.inputStream, MAX_BYTES)
            if (bytes.isEmpty()) return null

            val raw = decode(bytes, contentType)
            val text = if (contentType.contains("html", true) || raw.trimStart().startsWith("<")) {
                htmlToText(raw)
            } else {
                raw
            }
            val cleaned = text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
            if (cleaned.isBlank()) null else cleaned.take(MAX_TEXT)
        } catch (t: Throwable) {
            Log.w(TAG, "抓取异常 $url: ${t.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        input.use { ins ->
            while (total < cap) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
                total += n
            }
        }
        return bos.toByteArray()
    }

    private fun detectCharset(contentType: String?, body: String?): String {
        Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(contentType.orEmpty())?.let {
            return it.groupValues[1]
        }
        return "UTF-8"
    }

    /** 先按声明编码解;解出大量替换符时再按 GBK 重试(国内站点常见) */
    private fun decode(bytes: ByteArray, contentType: String): String {
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        var cs = detectCharset(contentType, null)
        // HTML 头部里的 meta charset 优先
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        Regex("charset=[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE).find(head)?.let { cs = it.groupValues[1] }
        val decoded = try {
            String(bytes, charsetOf(cs))
        } catch (_: Throwable) {
            String(bytes, Charsets.UTF_8)
        }
        val bad = decoded.count { it == '\uFFFD' }
        if (bad > 3 && bad * 100 / maxOf(1, decoded.length) > 1) {
            val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull()
            if (gbk != null && gbk.count { it == '\uFFFD' } < bad) return gbk
        }
        return decoded
    }

    private fun charsetOf(name: String) = runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)

    /** 极简 HTML → 文本:去脚本样式、块级标签转换行、去标签、解几个常用实体 */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<head.*?</head>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        // 标题单独留一行(往往就是事件名)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(s)?.groupValues?.get(1)?.trim().orEmpty()
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/section|/article)[^>]*>"), "\n")
        s = s.replace(Regex("(?i)<(p|div|li|tr|h[1-6]|section|article)[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("&#(\\d+);")) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault(" ")
        }
        s = s.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        return if (title.isNotBlank()) "$title\n$s" else s
    }
}
