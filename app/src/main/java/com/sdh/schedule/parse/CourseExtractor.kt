package com.sdh.schedule.parse

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * 从「课表截图 / 学校平台的课表文件 / 粘贴的课表文字」里抽出一张课程表。
 *
 * 现实路径:钉钉的课表和学校教务平台的课表,用户能拿到的是**截图**或**导出的 Excel/网页**,
 * 所以这里走和日程一样的通道 —— 大模型(多模态)优先,失败时用本地规则兜底。
 */
/** 抽出来的单条课程(未落库);解析实现在纯函数 [CourseTextParser] 里 */
typealias ParsedCourse = CourseTextParser.ParsedCourse

object CourseExtractor {

    private const val TAG = "CourseExtractor"



    data class Result(
        val courses: List<ParsedCourse>,
        val engine: String,
        val note: String = ""
    )

    // ---------------- 大模型 ----------------

    private fun prompt(now: java.time.LocalDateTime): String {
        val weekdayCn = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]
        return """
你是课表解析引擎。用户会给你一张**课程表**(可能是钉钉里的课表截图、学校教务平台的课表图片或导出的文本)。
请把它解析成结构化课程列表。今天=$weekdayCn。

输出要求(严格 JSON,不要 markdown 代码块):
{"courses":[{"name":"高等数学","teacher":"张老师","location":"A301","weekday":1,"startSlot":1,"slotCount":2,"startWeek":1,"endWeek":16,"weekParity":0}]}

规则:
1. weekday:1=周一 … 7=周日。
2. startSlot = 从当天第几节课开始(按课表上的节次,1 起);slotCount = 连上几节。
   如果课表给的是时间段(如 08:00-09:40)而不是节次,按下面的作息表换算:
   第1节08:00 第2节08:55 第3节10:00 第4节10:55 第5节14:00 第6节14:55
   第7节16:00 第8节16:55 第9节19:00 第10节19:55 第11节20:50 第12节21:45
   —— 例如 08:00-09:40 就是 startSlot=1, slotCount=2。
3. 周次:startWeek/endWeek 是起止周;"1-16周"→1和16;"单周"→weekParity=1;"双周"→weekParity=2;普通每周→0。
   如果课表没说周次,startWeek=1、endWeek=16。
4. 老师、地点拿不到就留空字符串。
5. 同一门课如果分布在多个时间段,拆成多条。
6. 只输出 JSON。
        """.trimIndent()
    }

    /** 从图片识别(多模态大模型优先,失败退回本机 OCR) */
    fun fromImage(context: Context, bitmap: Bitmap, hintText: String = ""): Result {
        val s = Repo.settings
        val now = java.time.LocalDateTime.now()
        val online = ParseCoordinator.isNetworkAvailable(context)
        val plan = if (s.llmMode != com.sdh.schedule.data.SettingsStore.MODE_LOCAL_ONLY &&
            s.llmConfigured && online
        ) "llm" else "rule"

        var llmError: String? = null
        if (plan == "llm") {
            repeat(2) { attempt ->
                try {
                    val json = LlmParser.parseImageRaw(
                        context, s, bitmap, prompt(now) + if (hintText.isBlank()) "" else "\n\n附带的原始文字(可能不完整):\n$hintText"
                    )
                    val list = jsonToCourses(json)
                    if (list.isNotEmpty()) {
                        return Result(list, "llm", "多模态大模型识别出 ${list.size} 门课" +
                            if (attempt > 0) "(第 ${attempt + 1} 次尝试成功)" else "")
                    }
                    llmError = "模型没有识别出课程"
                } catch (t: Throwable) {
                    llmError = t.message
                    Log.w(TAG, "课表图片识别失败(第 ${attempt + 1} 次)", t)
                    if (attempt == 0) runCatching { Thread.sleep(700) }
                }
            }
        }

        // 本机 OCR 兜底。
        // 关键:课表截图是**网格**,按阅读顺序取文字没用 ——
        // 先用带坐标的 OCR 还原网格(哪个格子属于星期几第几节),不行再退回按行解析。
        if (s.llmMode != com.sdh.schedule.data.SettingsStore.MODE_LLM_ONLY && LocalOcr.isAvailable()) {
            val blocks = LocalOcr.recognizeWithBoxes(bitmap)
            if (blocks.isNotEmpty()) {
                val grid = runCatching {
                    TimetableGridParser.parse(
                        blocks.map {
                            TimetableGridParser.Block(
                                text = it.text, left = it.left, top = it.top,
                                right = it.right, bottom = it.bottom
                            )
                        }
                    )
                }.getOrDefault(emptyList())
                if (grid.isNotEmpty()) {
                    Log.i(TAG, "本机 OCR + 网格还原:${grid.size} 门课")
                    return Result(
                        grid.map { g ->
                            ParsedCourse(
                                name = g.name, teacher = g.teacher, location = g.location,
                                weekday = g.weekday, startSlot = g.startSlot, slotCount = g.slotCount,
                                startWeek = g.startWeek, endWeek = g.endWeek, weekParity = g.weekParity
                            )
                        },
                        "rule",
                        "本机 OCR + 网格还原出 ${grid.size} 门课(不需要联网、不用 AI)"
                    )
                }
                Log.i(TAG, "网格还原没成功,退回按行解析(文字块=${blocks.size})")
            }
            val ocr = LocalOcr.recognize(bitmap)
            if (!ocr.isNullOrBlank()) {
                val list = localParse(ocr)
                if (list.isNotEmpty()) {
                    return Result(list, "rule", "本机 OCR + 本地规则识别出 ${list.size} 门课")
                }
            }
        }
        return Result(
            emptyList(), "none",
            llmError ?: "没能从这张图里识别出课程表(试试截图更清晰完整、把整张课表都截进去)"
        )
    }

    /** 从文字识别(大模型优先,失败退回本地规则) */
    fun fromText(context: Context, text: String): Result {
        val s = Repo.settings
        val online = ParseCoordinator.isNetworkAvailable(context)
        val plan = if (s.llmMode != com.sdh.schedule.data.SettingsStore.MODE_LOCAL_ONLY &&
            s.llmConfigured && online
        ) "llm" else "rule"

        var llmError: String? = null
        if (plan == "llm") {
            repeat(2) { attempt ->
                try {
                    val json = LlmParser.parseTextRaw(
                        context, s, prompt(java.time.LocalDateTime.now()), "课表内容:\n$text"
                    )
                    val list = jsonToCourses(json)
                    if (list.isNotEmpty()) {
                        return Result(list, "llm", "大模型识别出 ${list.size} 门课")
                    }
                    llmError = "模型没有识别出课程"
                } catch (t: Throwable) {
                    llmError = t.message
                    Log.w(TAG, "课表文字识别失败(第 ${attempt + 1} 次)", t)
                    if (attempt == 0) runCatching { Thread.sleep(700) }
                }
            }
        }
        val local = localParse(text)
        if (local.isNotEmpty()) {
            return Result(local, "rule", "本地规则识别出 ${local.size} 门课" +
                (llmError?.let { "($it)" } ?: ""))
        }
        return Result(emptyList(), "none", llmError ?: "没能从这段文字里识别出课程表")
    }

    // ---------------- 解析 ----------------

    private fun jsonToCourses(json: String): List<ParsedCourse> {
        val cleaned = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("courses") ?: return emptyList()
        val out = ArrayList<ParsedCourse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().take(40)
            if (name.length < 2) continue
            out.add(
                ParsedCourse(
                    name = name,
                    teacher = o.optString("teacher").trim().take(20),
                    location = o.optString("location").trim().take(30),
                    weekday = o.optInt("weekday", 1).coerceIn(1, 7),
                    startSlot = o.optInt("startSlot", 1).coerceIn(1, 12),
                    slotCount = o.optInt("slotCount", 2).coerceIn(1, 6),
                    startWeek = o.optInt("startWeek", 1).coerceAtLeast(1),
                    endWeek = o.optInt("endWeek", 16).coerceAtLeast(1),
                    weekParity = o.optInt("weekParity", 0).coerceIn(0, 2)
                )
            )
        }
        return out
    }

    /** 本地兜底:交给纯函数 CourseTextParser(可单元测试) */
    fun localParse(text: String): List<ParsedCourse> = CourseTextParser.parse(text)
}


