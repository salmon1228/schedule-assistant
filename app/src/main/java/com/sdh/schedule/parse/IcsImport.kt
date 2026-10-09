package com.sdh.schedule.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 解析 **iCalendar(.ics)** 课表 —— WakeUp课程表 等 App 的导出格式。
 *
 * 为什么走这条路:WakeUp 的「分享口令」接口被官方锁了(只有自家 App 能调),
 * 但它支持**导出 .ics**,而 .ics 是标准格式、格式稳定、信息完整(课程名/教室/时间/重复规则),
 * 解析出来是**精确**的,不需要 OCR、也不需要登录任何系统。
 *
 * 纯 Kotlin(不依赖 Android),可单元测试。
 */
object IcsImport {

    data class Result(
        val courses: List<CourseTextParser.ParsedCourse> = emptyList(),
        /** 从文件里推导出的节次时间 node -> "08:00-08:50" */
        val slotTimes: List<Pair<Int, String>> = emptyList(),
        /** 学期第一周的周一(yyyy-MM-dd):取所有课里最早那天的所在周 */
        val semesterStart: String? = null,
        val note: String = ""
    )

    /** 一眼判断是不是 ics */
    fun looksLikeIcs(text: String): Boolean =
        text.contains("BEGIN:VCALENDAR", ignoreCase = true) ||
            (text.contains("BEGIN:VEVENT", ignoreCase = true) && text.contains("DTSTART", ignoreCase = true))

    /** RFC5545 折行:续行以空格或 Tab 开头,要接回上一行 */
    private fun unfold(raw: String): List<String> {
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = ArrayList<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + line.substring(1)
            } else {
                out.add(line)
            }
        }
        return out
    }

    /** 取 "DTSTART;TZID=xx:值" 里的值 */
    private fun valueOf(line: String): String {
        val idx = line.indexOf(':')
        return if (idx >= 0) line.substring(idx + 1).trim() else ""
    }

    /** 解析 ics 时间:20260901T080000 / 20260901T080000Z / 20260901 */
    private fun parseIcsTime(v: String): LocalDateTime? {
        val s = v.trim().removeSuffix("Z")
        return runCatching {
            when {
                s.length >= 15 -> LocalDateTime.of(
                    s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                    s.substring(9, 11).toInt(), s.substring(11, 13).toInt(), s.substring(13, 15).toInt()
                )
                s.length == 8 -> LocalDate.of(
                    s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt()
                ).atStartOfDay()
                else -> null
            }
        }.getOrNull()
    }

    private fun unescape(s: String): String = s
        .replace("\\n", "\n").replace("\\N", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
        .trim()

    fun parse(text: String): Result {
        if (!looksLikeIcs(text)) return Result(note = "这不是 ics 文件")
        val lines = unfold(text)

        // 收集 VEVENT
        data class Raw(
            val summary: String, val location: String, val description: String,
            val start: LocalDateTime, val end: LocalDateTime?, val rrule: String
        )
        val raws = ArrayList<Raw>()
        var inEvent = false
        var summary = ""; var location = ""; var description = ""
        var start: LocalDateTime? = null; var end: LocalDateTime? = null; var rrule = ""

        for (line in lines) {
            val up = line.uppercase()
            when {
                up.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true; summary = ""; location = ""; description = ""
                    start = null; end = null; rrule = ""
                }
                up.startsWith("END:VEVENT") -> {
                    inEvent = false
                    val s = start
                    if (s != null && summary.isNotBlank()) {
                        raws.add(Raw(summary, location, description, s, end, rrule))
                    }
                }
                inEvent && up.startsWith("SUMMARY") -> summary = unescape(valueOf(line))
                inEvent && up.startsWith("LOCATION") -> location = unescape(valueOf(line))
                inEvent && up.startsWith("DESCRIPTION") -> description = unescape(valueOf(line))
                inEvent && up.startsWith("DTSTART") -> start = parseIcsTime(valueOf(line))
                inEvent && up.startsWith("DTEND") -> end = parseIcsTime(valueOf(line))
                inEvent && up.startsWith("RRULE") -> rrule = valueOf(line)
            }
        }
        if (raws.isEmpty()) return Result(note = "ics 里没有可用的课(没有 VEVENT)")

        // ---- ① 节次表 ----
        // ics 里只有"有课"的时段,中间没课的节次不出现,所以要补。
        // 但**不能用"最短课长+课间"去猜**(实测踩过:猜出 10:00 而真实是 10:05,
        // 偏差累积后节次表出现 08:00/08:20、11:45/11:50 这种重复项,序号全乱,
        // 结果"英语1(15:15)被标成第6节(11:45)" —— 用户说"下午的课不见了")。
        //
        // 正确做法:
        //   a) 真实起点之间的**最小间隔**就是学校的"一节课+课间"(该校 08:20→09:10 = 50 分钟)
        //   b) 用这个间隔从每节课的起点推进,补出它占用的中间节次
        //   c) 补出来的时间**吸附到附近真实起点**(±8 分钟内),避免 10:00 vs 10:05 这种假节次
        val rawStarts = raws.map { it.start.toLocalTime().withSecond(0).withNano(0) }.distinct().sorted()
        val gaps = rawStarts.zipWithNext { a, b -> ChronoUnit.MINUTES.between(a, b).toInt() }
            .filter { it in 25..90 }
        val stepMinutes = (gaps.minOrNull() ?: 50).coerceIn(30, 70).toLong()

        val realStarts = rawStarts.toSet()
        val periodStarts = sortedSetOf<LocalTime>()
        periodStarts.addAll(rawStarts)
        for (r in raws) {
            val endT = r.end?.toLocalTime() ?: continue
            var cur = r.start.toLocalTime().withSecond(0).withNano(0).plusMinutes(stepMinutes)
            var guard = 0
            while (!cur.isAfter(endT) && guard++ < 12) {
                // 吸附:附近 8 分钟内有真实起点就用真实的那个
                val snapped = realStarts.firstOrNull { kotlin.math.abs(ChronoUnit.MINUTES.between(cur, it)) <= 8 }
                periodStarts.add(snapped ?: cur)
                cur = cur.plusMinutes(stepMinutes)
            }
        }
        val startTimes = periodStarts.toList()
        if (startTimes.isEmpty()) return Result(note = "ics 里没解析出上课时间")

        // 单节时长:取所有课里最短的一节课(用于没有 DTEND 时的兜底结束时间)
        val durations = raws.mapNotNull { r ->
            r.end?.let { ChronoUnit.MINUTES.between(r.start, it).toInt().takeIf { m -> m in 20..300 } }
        }
        val unitMinutes = (durations.minOrNull() ?: 45).coerceIn(20, 90)

        // 每个节次的结束时间:不能直接用整门课的 DTEND(两节课的课会把第 1 节显示成 13:30-15:05),
        // 而是取"下一个节次开始 - 5 分钟课间"和"这门课自己的结束时间"里更早的那个。
        val endTimes = HashMap<LocalTime, LocalTime>()
        for ((i, t) in startTimes.withIndex()) {
            val nextStart = startTimes.getOrNull(i + 1)
            val byGap = nextStart?.minusMinutes(5)
            val own = raws.filter { it.start.toLocalTime().withSecond(0).withNano(0) == t }
                .mapNotNull { it.end?.toLocalTime() }
                .minOrNull()          // 该时间开始的课里最早结束的(单节长度)
            endTimes[t] = listOfNotNull(byGap, own).minOrNull()
                ?: t.plusMinutes(stepMinutes)
        }

        // ---- ② 学期开始:所有课里最早那天的所在周(周一) ----
        val earliest = raws.minOf { it.start.toLocalDate() }
        val semesterMonday = earliest.with(java.time.DayOfWeek.MONDAY)

        // ---- ③ 逐门课转换 ----
        val out = ArrayList<CourseTextParser.ParsedCourse>()
        for (r in raws) {
            val date = r.start.toLocalDate()
            val time = r.start.toLocalTime().withSecond(0).withNano(0)
            val slotIndex = startTimes.indexOf(time) + 1
            if (slotIndex <= 0) continue

            // 连上几节:直接数"这节课的时间段里落进了几个节次" ——
            // 比按时长÷(单节+课间)去猜准得多(例如 13:30-15:05 覆盖 13:30 和 14:20 两节 = 2 节)
            var span = 1
            if (r.end != null) {
                val endT = r.end.toLocalTime()
                span = startTimes.count { t -> !t.isBefore(time) && t.isBefore(endT) }.coerceIn(1, 6)
            }

            // 周次:优先用 RRULE 的 UNTIL/COUNT,其次看 DESCRIPTION 里写的 "1-16周"
            val weekFromStart = (ChronoUnit.DAYS.between(semesterMonday, date) / 7).toInt() + 1
            var startWeek = weekFromStart.coerceAtLeast(1)
            var endWeek = startWeek
            var parity = 0

            // DESCRIPTION 里的周次(很多导出会带)
            val weekText = Regex("(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*周").find(r.description)?.let {
                (it.groupValues[1].toIntOrNull() ?: 1) to (it.groupValues[2].toIntOrNull() ?: 16)
            }
            if (weekText != null) {
                startWeek = weekText.first.coerceAtLeast(1)
                endWeek = weekText.second.coerceAtLeast(startWeek)
                if (r.description.contains("单周")) parity = 1
                if (r.description.contains("双周")) parity = 2
            }

            val until = Regex("UNTIL=(\\d{8})", RegexOption.IGNORE_CASE).find(r.rrule)?.groupValues?.get(1)
            if (weekText == null && until != null) {
                val untilDate = runCatching {
                    LocalDate.of(until.substring(0, 4).toInt(), until.substring(4, 6).toInt(), until.substring(6, 8).toInt())
                }.getOrNull()
                if (untilDate != null) {
                    endWeek = ((ChronoUnit.DAYS.between(semesterMonday, untilDate) / 7).toInt() + 1).coerceAtLeast(startWeek)
                }
            }
            if (weekText == null) {
                val interval = Regex("INTERVAL=(\\d+)", RegexOption.IGNORE_CASE).find(r.rrule)?.groupValues?.get(1)?.toIntOrNull()
                if (interval == 2) parity = if (startWeek % 2 == 1) 1 else 2
                // 没有 UNTIL 也没有周次:按一个学期(到 20 周)兜底
                if (until == null) endWeek = maxOf(endWeek, 20).coerceAtMost(30)
            }
            if (parity == 0) {
                if (r.description.contains("单周")) parity = 1
                if (r.description.contains("双周")) parity = 2
            }

            // 老师:DESCRIPTION 里的 "教师:xxx" / "老师:xxx"
            val teacher = Regex("(?:教师|老师|授课教师)\\s*[:：]\\s*([^\\n,;，;]{1,20})")
                .find(r.description)?.groupValues?.get(1)?.trim().orEmpty()

            // 课程名:SUMMARY 去掉括号里的"(必修)"这类后缀
            val name = r.summary
                .replace(Regex("[（(](必修|选修|限选|任选|公选)[)）]"), "")
                .trim()
                .take(40)
            if (name.length < 2) continue

            out.add(
                CourseTextParser.ParsedCourse(
                    name = name,
                    teacher = teacher,
                    location = r.location.take(40),
                    weekday = date.dayOfWeek.value,
                    startSlot = slotIndex,
                    slotCount = span,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekParity = parity
                )
            )
        }

        // 去重(同课同星期同节次同周次)
        val seen = HashSet<String>()
        val unique = out.filter { seen.add("${it.name}@${it.weekday}@${it.startSlot}@${it.startWeek}-${it.endWeek}") }

        val slotPairs = startTimes.mapIndexed { i, t ->
            (i + 1) to "%02d:%02d-%02d:%02d".format(
                t.hour, t.minute,
                endTimes[t]!!.hour, endTimes[t]!!.minute
            )
        }

        return Result(
            courses = unique.sortedWith(compareBy({ it.weekday }, { it.startSlot })),
            slotTimes = slotPairs,
            semesterStart = semesterMonday.toString(),
            note = "从 ics 解析出 ${unique.size} 门课;推导出 ${slotPairs.size} 个节次;" +
                "学期起始 ${semesterMonday}(按最早一节课推算)"
        )
    }
}
