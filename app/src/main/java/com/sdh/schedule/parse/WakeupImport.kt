package com.sdh.schedule.parse

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 导入 WakeUp课程表 的**备份文件**(`.wakeup_schedule`)。
 *
 * 为什么走这条路:
 *   用户学校(正方教务 + 钉钉扫码 + WebVPN)没法在内置浏览器里自动登录,
 *   而 WakeUp 已经把这些都做好了。它的备份文件是**纯文本、逐行 JSON**,格式确定:
 *
 *   ```
 *   第0行 文件头
 *   第1行 [{"node":1,"startTime":"08:00","endTime":"08:45"}, ...]      节次时间表
 *   第2行 {"startDate":"2026-09-01", ...}                              学期信息
 *   第3行 [{"id":1,"courseName":"高等数学G1","teacher":"邵牧好"}, ...]   课程信息
 *   第4行 [{"id":1,"day":1,"startNode":1,"step":2,"startWeek":1,
 *           "endWeek":16,"room":"1101","teacher":"邵牧好"}, ...]        课程明细
 *   ```
 *   即:weekday=day、节次=startNode、连上几节=step、周次=startWeek~endWeek、教室=room。
 *
 * 纯 Kotlin(org.json 由 Android 提供),逻辑本身可单元测试。
 */
typealias WakeupCourse = CourseTextParser.ParsedCourse

object WakeupImport {

    data class Result(
        val courses: List<WakeupCourse> = emptyList(),
        /** 从备份里读到的节次时间(node -> "08:00-08:45"),可以用来对齐本 App 的节次时间 */
        val slotTimes: List<Pair<Int, String>> = emptyList(),
        /** 学期开始日期(yyyy-MM-dd),没有就是 null */
        val semesterStart: String? = null,
        val note: String = ""
    )

    /** 判断这段文本像不像 WakeUp 备份(前几行是 JSON 数组) */
    fun looksLikeBackup(text: String): Boolean {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 4) return false
        val l1 = lines.getOrNull(1)?.trim().orEmpty()
        val l3 = lines.getOrNull(3)?.trim().orEmpty()
        return l1.startsWith("[") && l3.startsWith("[") &&
            text.contains("courseName") || (l1.startsWith("[") && text.contains("startNode"))
    }

    fun parse(text: String): Result {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 5) return Result(note = "备份文件行数不对(至少 5 行)")

        val timeTable: JSONArray
        val tableInfo: JSONObject
        val courseInfos: JSONArray
        val courseDetails: JSONArray
        try {
            timeTable = JSONArray(lines[1].trim())
            tableInfo = runCatching { JSONObject(lines[2].trim()) }.getOrDefault(JSONObject())
            courseInfos = JSONArray(lines[3].trim())
            courseDetails = JSONArray(lines[4].trim())
        } catch (t: Throwable) {
            return Result(note = "备份文件解析失败(前几行不是预期的 JSON):${t.message}")
        }

        // 节次时间
        val slots = ArrayList<Pair<Int, String>>()
        for (i in 0 until timeTable.length()) {
            val o = timeTable.optJSONObject(i) ?: continue
            val node = o.optInt("node", i + 1)
            val s = o.optString("startTime").take(5)
            val e = o.optString("endTime").take(5)
            if (s.isNotBlank()) slots.add(node to "$s-$e")
        }

        // 课程名 / 老师:按 id 索引
        val infoById = HashMap<Int, JSONObject>()
        for (i in 0 until courseInfos.length()) {
            val o = courseInfos.optJSONObject(i) ?: continue
            infoById[o.optInt("id", -1)] = o
        }

        val out = ArrayList<WakeupCourse>()
        for (i in 0 until courseDetails.length()) {
            val d = courseDetails.optJSONObject(i) ?: continue
            // ownTime = 自定义时间(不按节次),本 App 的课表按节次排,跳过
            if (d.optBoolean("ownTime", false)) continue

            val info = infoById[d.optInt("id", -1)] ?: continue
            val name = (info.optString("courseName").ifBlank { info.optString("name") }).trim()
            if (name.isBlank()) continue

            val weekday = d.optInt("day", 0)
            if (weekday !in 1..7) continue
            val startNode = d.optInt("startNode", 0)
            if (startNode <= 0) continue
            val step = d.optInt("step", 1).coerceIn(1, 6)

            // 周次:优先 startWeek/endWeek,否则用 weeks 数组的首尾
            var startWeek = d.optInt("startWeek", 0)
            var endWeek = d.optInt("endWeek", 0)
            val weeksArr = d.optJSONArray("weeks")
            if ((startWeek <= 0 || endWeek <= 0) && weeksArr != null && weeksArr.length() > 0) {
                val list = (0 until weeksArr.length()).mapNotNull { k -> weeksArr.optInt(k, 0).takeIf { it > 0 } }
                if (list.isNotEmpty()) {
                    startWeek = list.min()
                    endWeek = list.max()
                }
            }
            if (startWeek <= 0) startWeek = 1
            if (endWeek < startWeek) endWeek = startWeek

            val teacher = d.optString("teacher").ifBlank { info.optString("teacher") }.trim()
            val room = d.optString("room").ifBlank { d.optString("location") }
                .ifBlank { info.optString("room") }.trim()

            out.add(
                WakeupCourse(
                    name = name.take(40),
                    teacher = teacher.take(30),
                    location = room.take(30),
                    weekday = weekday,
                    startSlot = startNode.coerceIn(1, 15),
                    slotCount = step,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekParity = 0
                )
            )
        }

        // 去重(同一门课同一时间可能重复存)
        val seen = HashSet<String>()
        val unique = out.filter { seen.add("${it.name}@${it.weekday}@${it.startSlot}@${it.startWeek}-${it.endWeek}") }

        val startDate = tableInfo.optString("startDate").takeIf { it.length >= 8 }
        val weekCount = unique.maxOfOrNull { it.endWeek } ?: 0
        val note = buildString {
            append("从 WakeUp 备份解析出 ${unique.size} 门课")
            if (startDate != null) append(",学期开始 $startDate")
            if (slots.isNotEmpty()) append(",节次时间 ${slots.size} 节")
            if (weekCount > 0) append(",最大周次 $weekCount")
        }
        return Result(
            courses = unique.sortedWith(compareBy({ it.weekday }, { it.startSlot })),
            slotTimes = slots,
            semesterStart = startDate,
            note = note
        )
    }

    /** 把备份里的节次时间拼成本 App 的格式:"08:00-08:45,08:55-09:40" */
    fun slotTimesString(slots: List<Pair<Int, String>>): String =
        slots.sortedBy { it.first }.joinToString(",") { it.second }

    /** 学期开始日期 → 那一周的周一(epochDay) */
    fun semesterStartEpochDay(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return runCatching {
            LocalDate.parse(date.take(10)).with(java.time.DayOfWeek.MONDAY).toEpochDay()
        }.getOrNull()
    }
}

