package com.sdh.schedule.parse

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.sdh.schedule.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * OpenAI 兼容接口的大模型解析(DeepSeek / 通义千问 / Kimi / GLM / 豆包 等都支持这种格式)。
 * 用 HttpURLConnection + org.json,不引入任何第三方网络库。
 */
object LlmParser {

    private const val TAG = "LlmParser"

    class LlmException(message: String) : Exception(message)

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun systemPrompt(nowMs: Long, zone: ZoneId, aiDecideReminder: Boolean): String {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).id
        val reminderRule = if (aiDecideReminder) {
            """
5. reminderMinutesBefore = 提前多少分钟提醒。文字里写了(如"提前半小时提醒")就照写;**没写时不要返回 -1,按任务类型自己判断一个合理值**:
   - 面试 / 考试 / 答辩 / 航班 / 高铁 / 火车 / 体检 / 手术 / 重要会议 → 60
   - 普通会议 / 上课 / 培训 / 见客户 / 约会 / 聚餐 → 30
   - 截止日期 / 交材料 / 报名 / 缴费 / 还款 → 1440(提前一天)
   - 生日 / 纪念日 / 全天事件 / 倒数日 → 1440
   - 拿不准 → 10
            """.trimIndent()
        } else {
            "5. reminderMinutesBefore 是「提前多少分钟提醒」;文字没提就写 -1(交给 App 用默认值)。"
        }
        return """
你是日程抽取引擎。把用户给的一段文字(可能来自聊天通知、短信、公告、图片 OCR 结果)里的**所有**日程/待办时间点抽出来。

当前时间:${now.format(ISO)}(时区 $offset,${zone.id})
星期参考:今天=${weekdayCn(now)},明天=${weekdayCn(now.plusDays(1))},后天=${weekdayCn(now.plusDays(2))}

输出要求(必须是严格 JSON,不要 markdown 代码块,不要多余解释):
{"events":[{"title":"标题","start":"yyyy-MM-ddTHH:mm:ss","end":"yyyy-MM-ddTHH:mm:ss","allDay":false,"repeat":"NONE","repeatWeekday":0,"location":"","reminderMinutesBefore":-1,"confidence":0.9,"notes":""}],
 "todos":[{"title":"要做的某件事","due":"yyyy-MM-ddTHH:mm:ss 或 null","note":"","confidence":0.8}]}

events 与 todos 的区别(**很重要**):
- events = **有明确开始时间**的安排(会议、上课、面试、航班、聚餐…)
- todos = **需要做的一件事,但没有开始时间**(准备一寸照片、记得带身份证、打印报名表、交材料…)
- 同一段话可能两者都有。例:「明天会发一张表格下来需要我们填写,准备好一寸照片」
  → events 里放"填写表格"(如有时间),todos 里**必须**放「准备一寸照片」
- 只有催促/提醒性质、没有明确时间的句子,一律放 todos,不要硬塞进 events

规则:
1. 相对时间必须结合"当前时间"换算成绝对时间。没有年份就用最近的将来日期。
2. 只有日期没有具体时刻 → allDay=true,start 取当天 00:00:00,end 取当天 23:59:59。
3. 有时刻但没写结束时间 → end = start + 1 小时。写了"到/至/-"就按写的时间段。
4. repeat 取值:NONE/DAILY/WEEKDAYS/WEEKLY/MONTHLY/YEARLY;"每周三"→WEEKLY 且 repeatWeekday=3(周一=1…周日=7);"工作日"→WEEKDAYS;"每月5号"→MONTHLY;"每年"→YEARLY。
$reminderRule
6. 文字里没有任何时间信息 → events 返回空数组,但**如果里面有"要做的事",要放进 todos**。
7. 一句话里有多件事就输出多个 events / todos。
8. 标题要干净:去掉时间词、提醒词、地点标记、打招呼和表情,不超过 40 字。
9. confidence 0~1:日期+时刻明确≈0.9 以上;只有日期≈0.7;只有模糊时段≈0.5;待办≈0.7~0.85。
10. 只输出 JSON。
        """.trimIndent()
    }

    private fun weekdayCn(dt: LocalDateTime): String =
        arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[dt.dayOfWeek.value - 1]

    /** 文本解析 */
    fun parseText(
        context: Context,
        settings: SettingsStore,
        text: String,
        nowMs: Long,
        zone: ZoneId
    ): LlmResult {
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt(nowMs, zone, settings.aiDecideReminder))
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", text)
                })
            })
        }
        return requestAndParse(context, settings, payload, text, nowMs, zone, allowRetryWithoutJsonMode = true)
    }

    /** 图片解析(多模态)。bitmap 会被压成 JPEG base64 传过去 */
    fun parseImage(
        context: Context,
        settings: SettingsStore,
        bitmap: Bitmap,
        hintText: String,
        nowMs: Long,
        zone: ZoneId
    ): LlmResult {
        val model = settings.llmVisionModel.ifBlank { settings.llmModel }
        val b64 = bitmapToBase64(bitmap)
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put(
                    "text",
                    buildString {
                        append("请识别这张图片里的全部日程信息(截图/聊天图片/海报/通知截图)。")
                        if (hintText.isNotBlank()) append("\n随附的原始通知文字(仅供辅助,可能不完整):").append(hintText)
                        append("\n请直接按系统要求输出 JSON。")
                    }
                )
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt(nowMs, zone, settings.aiDecideReminder))
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", userContent)
                })
            })
        }
        return requestAndParse(context, settings, payload, hintText, nowMs, zone, allowRetryWithoutJsonMode = false, isImage = true)
    }

    private fun requestAndParse(
        context: Context,
        settings: SettingsStore,
        payload: JSONObject,
        sourceText: String,
        nowMs: Long,
        zone: ZoneId,
        allowRetryWithoutJsonMode: Boolean,
        isImage: Boolean = false
    ): LlmResult {
        var body = payload.toString()
        var response = post(context, settings, body)
        if (response == null && allowRetryWithoutJsonMode) {
            // 有些服务不支持 response_format,去掉重试一次
            payload.remove("response_format")
            body = payload.toString()
            response = post(context, settings, body)
        }
        val content = response ?: throw LlmException("大模型接口没有返回内容")
        return parseJsonFull(content, sourceText, nowMs, zone)
    }

    // ---------------- 自定义提示词(课表识别等) ----------------

    /** 用自定义提示词做一次文本请求,返回模型原始输出 */
    fun parseTextRaw(context: Context, settings: SettingsStore, systemPrompt: String, userContent: String): String {
        val payload = JSONObject().apply {
            put("model", settings.llmModel)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userContent) })
            })
        }
        var body = payload.toString()
        var response = post(context, settings, body)
        if (response == null) {
            payload.remove("response_format")
            body = payload.toString()
            response = post(context, settings, body)
        }
        return response ?: throw LlmException("大模型接口没有返回内容")
    }

    /** 用自定义提示词做一次图片(多模态)请求,返回模型原始输出 */
    fun parseImageRaw(context: Context, settings: SettingsStore, bitmap: Bitmap, systemPrompt: String): String {
        val model = settings.llmVisionModel.ifBlank { settings.llmModel }
        val b64 = bitmapToBase64(bitmap)
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "请按系统要求解析这张图,只输出 JSON。")
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            })
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userContent) })
            })
            put("response_format", JSONObject().put("type", "json_object"))
        }
        var body = payload.toString()
        var response = post(context, settings, body)
        if (response == null) {
            payload.remove("response_format")
            body = payload.toString()
            response = post(context, settings, body)
        }
        return response ?: throw LlmException("大模型接口没有返回内容")
    }

    private fun post(context: Context, settings: SettingsStore, body: String): String? {        val base = settings.llmBaseUrl.trimEnd('/')
        if (base.isBlank()) throw LlmException("未配置接口地址")
        if (settings.llmApiKey.isBlank()) throw LlmException("未配置 API Key")
        val url = URL("$base/chat/completions")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = settings.llmTimeoutSeconds * 1000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${settings.llmApiKey}")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw LlmException("接口返回 HTTP $code ${msg ?: text.take(200)}")
            }
            val json = JSONObject(text)
            val choices = json.optJSONArray("choices") ?: throw LlmException("返回结构异常")
            if (choices.length() == 0) throw LlmException("返回结果为空")
            val message = choices.getJSONObject(0).optJSONObject("message") ?: throw LlmException("返回缺少 message")
            return message.optString("content")
        } catch (e: LlmException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "请求大模型失败", t)
            throw LlmException("网络请求失败:${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        // 长边压到 1280,既能看清文字又不至于请求过大
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val bos = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /** 大模型一次返回的完整结果:日程 + 待办 */
    data class LlmResult(
        val events: List<ParsedEvent> = emptyList(),
        val todos: List<com.sdh.schedule.data.ParsedTodo> = emptyList()
    )

    /** 把模型返回的 JSON 解析成 日程 + 待办;容错处理 markdown 代码块 */
    fun parseJsonFull(content: String, sourceText: String, nowMs: Long, zone: ZoneId): LlmResult {
        val events = parseJsonEvents(content, sourceText, nowMs, zone)
        val todos = parseJsonTodos(content, sourceText, nowMs, zone)
        return LlmResult(events, todos)
    }

    /** 解析 todos 数组 */
    fun parseJsonTodos(content: String, sourceText: String, nowMs: Long, zone: ZoneId): List<com.sdh.schedule.data.ParsedTodo> {
        val body = extractJsonObject(content) ?: return emptyList()
        val arr = body.optJSONArray("todos") ?: return emptyList()
        val out = ArrayList<com.sdh.schedule.data.ParsedTodo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim().take(60)
            if (title.length < 2) continue
            val dueStr = o.optString("due", "")
            val due = if (dueStr.isBlank() || dueStr == "null") 0L else {
                parseLdt(dueStr)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L
            }
            out.add(
                com.sdh.schedule.data.ParsedTodo(
                    title = title,
                    dueMillis = due,
                    note = o.optString("note").trim(),
                    confidence = o.optDouble("confidence", 0.75).coerceIn(0.0, 1.0),
                    matched = listOf("大模型解析"),
                    sourceText = sourceText
                )
            )
        }
        return out
    }

    /** 从可能带 markdown 包裹的文本里取出 JSON 对象 */
    private fun extractJsonObject(content: String): JSONObject? {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    fun parseJsonEvents(content: String, sourceText: String, nowMs: Long, zone: ZoneId): List<ParsedEvent> {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val json = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<ParsedEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val startStr = o.optString("start")
            if (startStr.isBlank()) continue
            val startLdt = parseLdt(startStr) ?: continue
            val endStr = o.optString("end")
            var endLdt = parseLdt(endStr) ?: startLdt.plusHours(1)
            val allDay = o.optBoolean("allDay", false)
            if (!endLdt.isAfter(startLdt)) endLdt = startLdt.plusHours(1)
            val repeat = runCatching {
                RepeatKind.valueOf(o.optString("repeat", "NONE").uppercase())
            }.getOrDefault(RepeatKind.NONE)
            val startMs = startLdt.atZone(zone).toInstant().toEpochMilli()
            val endMs = endLdt.atZone(zone).toInstant().toEpochMilli()
            out.add(
                ParsedEvent(
                    title = title.ifBlank { "新日程" }.take(60),
                    startMillis = startMs,
                    endMillis = endMs,
                    allDay = allDay,
                    repeat = repeat,
                    repeatWeekday = o.optInt("repeatWeekday", 0).coerceIn(0, 7),
                    location = o.optString("location").trim(),
                    reminderMinutesBefore = o.optInt("reminderMinutesBefore", -1),
                    confidence = o.optDouble("confidence", 0.8).coerceIn(0.0, 1.0),
                    notes = o.optString("notes").trim(),
                    matched = listOf("大模型解析"),
                    sourceText = sourceText
                )
            )
        }
        return out
    }

    private fun parseLdt(s: String): LocalDateTime? {
        if (s.isBlank()) return null
        return runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T'), ISO) }
            .recoverCatching { LocalDateTime.parse(s.trim().replace(' ', 'T')) }
            .getOrNull()
    }
}


