package com.sdh.schedule.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 解析 **iCalendar(.ics)** 课表 —— WakeUp课程表 等 App 的导出格式。
 *
 * 为什么走这条路:WakeUp 的「分享口令」接口被官方锁了(只有自家 App 能调),
 * 但它支持**导出 .ics**,而 .ics 是标准格式、信息完整,解析出来是**精确**的。
 *
 * ⚠️ 关键:WakeUp 导出的每个事件里,**DESCRIPTION 直接写了节次**,例如:
 * ```
 * SUMMARY:C语言程序设计1(必修)
 * DTSTART;TZID=/Asia/Shanghai:20260917T082000
 * DTEND;TZID=/Asia/Shanghai:20260917T095500
 * RRULE:FREQ=WEEKLY;UNTIL=20270106T160000Z;INTERVAL=1
 * LOCATION:B108 张锦明
 * DESCRIPTION:第1 - 2节\nB108\n张锦明
 * ```
 * 早期版本**忽略了这个字段**,改成"从时间反推节次表",结果推出
 * 「高等数学 = 第12节(19:55)」这种错位(用户报"我下午的课呢?")。
 * 现在改成:**节次以 DESCRIPTION 为准**,时间表由「节次号 → 上课时间」的锚点推出来。
 */
object IcsImport {

    data class Result(
        val courses: List<CourseTextParser.ParsedCourse> = emptyList(),
        /** 推导出的节次时间 node -> "08:20-09:05" */
        val slotTimes: List<Pair<Int, String>> = emptyList(),
        /** 学期第一周的周一(yyyy-MM-dd):取所有课里最早那天的所在周 */
        val semesterStart: String? = null,
        val note: String = ""
    )

    /** 一眼判断是不是 ics */
    fun looksLikeIcs(text: String): Boolean =
        text.contains("BEGIN:VCALENDAR", ignoreCase = true) ||
            (text.contains("BEGIN:VEVENT", ignoreCase = true) && text.contains("DTSTART", ignoreCase = true))

    // ---- ics 字段正则 ----
    /** 「第1 - 2节」/「第1-2节」/「第3至5节」 */
    private val PERIOD_RANGE = Regex("第\\s*(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*节")
    private val PERIOD_SINGLE = Regex("第\\s*(\\d{1,2})\\s*节")
    /** 「1-16周」 */
    private val WEEK_RANGE = Regex("(\\d{1,2})\\s*[-~—–至]\\s*(\\d{1,2})\\s*周")
    private val WEEK_UNTIL = Regex("UNTIL=(\\d{8})", RegexOption.IGNORE_CASE)
    private val INTERVAL = Regex("INTERVAL=(\\d+)", RegexOption.IGNORE_CASE)
    private val SINGLE_WEEK = Regex("(\\d{1,2})\\s*周")

    private data class Raw(
        val summary: String,
        val location: String,
        val description: String,
        val start: LocalDateTime,
        val end: LocalDateTime?,
        val rrule: String
    )

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

    /** 取 "键;参数:值" 里的值 */
    private fun valueOf(line: String): String {
        val idx = line.indexOf(':')
        return if (idx >= 0) line.substring(idx + 1).trim() else ""
    }

    /** 解析 ics 时间:20260917T082000 / 20260917T082000Z / 20260917 */
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

    /** 收集 VEVENT;⚠️ VALARM 内部的行必须跳过,否则闹钟的 DESCRIPTION 会覆盖课程的 */
    private fun parseEvents(lines: List<String>): List<Raw> {
        val out = ArrayList<Raw>()
        var inEvent = false
        var inAlarm = false
        var summary = ""; var location = ""; var description = ""
        var start: LocalDateTime? = null; var end: LocalDateTime? = null; var rrule = ""

        for (line in lines) {
            val up = line.uppercase()
            when {
                up.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true; inAlarm = false
                    summary = ""; location = ""; description = ""
                    start = null; end = null; rrule = ""
                }
                up.startsWith("BEGIN:VALARM") -> inAlarm = true
                up.startsWith("END:VALARM") -> inAlarm = false
                up.startsWith("END:VEVENT") -> {
                    inEvent = false
                    val s = start
                    if (s != null && summary.isNotBlank()) {
                        out.add(Raw(summary, location, description, s, end, rrule))
                    }
                }
                inEvent && !inAlarm && up.startsWith("SUMMARY") -> summary = unescape(valueOf(line))
                inEvent && !inAlarm && up.startsWith("LOCATION") -> location = unescape(valueOf(line))
                inEvent && !inAlarm && up.startsWith("DESCRIPTION") -> description = unescape(valueOf(line))
                inEvent && !inAlarm && up.startsWith("DTSTART") -> start = parseIcsTime(valueOf(line))
                inEvent && !inAlarm && up.startsWith("DTEND") -> end = parseIcsTime(valueOf(line))
                inEvent && !inAlarm && up.startsWith("RRULE") -> rrule = valueOf(line)
            }
        }
        return out
    }

    /** 一行拆成若干段(DESCRIPTION 里用 \n 分隔:节次 | 教室 | 老师) */
    private fun parts(s: String): List<String> =
        s.split('\n', '|', '｜')
            .map { it.trim() }
            // 去掉「教师:」「教室:」「周次:」这类前缀,便于后面判断
            .map { p ->
                listOf("授课教师", "教师", "老师", "教室", "地点", "上课地点", "周次", "时间")
                    .firstOrNull { p.startsWith("$it:") || p.startsWith("$it：") }
                    ?.let { p.substring(it.length + 1).trim() } ?: p
            }
            // 「1-16周」「第5周」这种不是教室也不是老师
            .filter { it.isNotEmpty() && !it.contains('周') }

    /** 这段文字像不像老师(中文姓名,可能多个用 / 分隔) */
    private fun looksLikeTeacher(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains('楼') || t.contains('室') || t.contains('馆') || t.contains("校区")) return false
        if (t.any { it.isDigit() }) return false
        val names = t.split('/', '、', '，', ',')
        return names.all { n ->
            val x = n.trim().removeSuffix("老师").removeSuffix("教授")
            x.length in 2..4 && x.all { it.code in 0x4E00..0x9FA5 }
        }
    }

    /** 这段文字像不像地点(有数字/楼层/场馆字样) */
    private fun looksLikePlace(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains('周')) return false          // 「5-11周」不是教室
        if (looksLikeTeacher(t)) return false       // 纯姓名优先当老师
        if (t.any { it.isDigit() }) return true
        return listOf("楼", "室", "馆", "校区", "教室", "实验", "中心", "场", "区").any { t.contains(it) }
    }

    fun parse(text: String): Result {
        if (!looksLikeIcs(text)) return Result(note = "这不是 ics 文件")
        val raws = parseEvents(unfold(text))
        if (raws.isEmpty()) return Result(note = "ics 里没有可用的课(没有 VEVENT)")

        // ---- 学期第一条周的周一:取所有课里最早那天的所在周 ----
        val earliest = raws.minOf { it.start.toLocalDate() }
        val semesterMonday = earliest.with(DayOfWeek.MONDAY)

        // ---- 逐门课解析 ----
        data class Course0(
            val name: String, val teacher: String, val location: String,
            val weekday: Int, val startSlot: Int, val span: Int,
            val startWeek: Int, val endWeek: Int, val parity: Int,
            val startTime: LocalTime
        )

        val list = ArrayList<Course0>()
        for (r in raws) {
            val date = r.start.toLocalDate()
            val time = r.start.toLocalTime().withSecond(0).withNano(0)
            val descParts = parts(r.description)

            // ① 节次:优先 DESCRIPTION 里的「第X - Y节」(WakeUp 的导出里有,是权威信息)
            val range = PERIOD_RANGE.find(r.description)
            var slotFromDesc: Int? = null
            var spanFromDesc: Int? = null
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull()
                val b = range.groupValues[2].toIntOrNull()
                if (a != null && b != null && b >= a) {
                    slotFromDesc = a
                    spanFromDesc = (b - a + 1).coerceIn(1, 8)
                }
            } else {
                val one = PERIOD_SINGLE.find(r.description)?.groupValues?.get(1)?.toIntOrNull()
                if (one != null) { slotFromDesc = one; spanFromDesc = 1 }
            }

            // ② 没有节次信息时的兜底:按时间正推(稍后用节次表归一)
            var spanGuess = spanFromDesc
            if (spanGuess == null && r.end != null) {
                val mins = ChronoUnit.MINUTES.between(r.start, r.end).toInt()
                spanGuess = Math.round(mins / 50f).coerceIn(1, 6)
            }

            // ③ 地点 / 老师:DESCRIPTION 的分段最可靠(「第X节 | 教室 | 老师」)
            var location = ""
            var teacher = ""
            val extra = descParts.filter { PERIOD_RANGE.find(it) == null && PERIOD_SINGLE.find(it) == null }
            for (p in extra) {
                when {
                    teacher.isBlank() && looksLikeTeacher(p) -> teacher = p
                    location.isBlank() && looksLikePlace(p) -> location = p
                }
            }
            // LOCATION 兜底:常见「B108 张锦明」「格致楼318-320(...) 林海云」
            if (location.isBlank() || teacher.isBlank()) {
                val loc = r.location.trim()
                if (loc.isNotEmpty()) {
                    val idx = loc.lastIndexOf(' ')
                    val tail = if (idx > 0) loc.substring(idx + 1).trim() else ""
                    if (teacher.isBlank() && tail.isNotEmpty() && looksLikeTeacher(tail)) teacher = tail
                    val head = if (idx > 0) loc.substring(0, idx).trim() else loc
                    if (location.isBlank() && head.isNotEmpty()) location = head
                }
            }

            // ④ 课程名:去掉「(必修)」这类后缀
            var name = r.summary
                .replace(Regex("[（(](必修|选修|限选|任选|公选|通识)[)）]"), "")
                .trim()
            if (name.length < 2) name = descParts.firstOrNull().orEmpty()
            if (name.length < 2) continue

            // ⑤ 周次:优先 DESCRIPTION 里的「1-16周」,否则用 RRULE 的 UNTIL 推算
            val weekFromStart = (ChronoUnit.DAYS.between(semesterMonday, date) / 7).toInt() + 1
            var startWeek = weekFromStart.coerceAtLeast(1)
            var endWeek = startWeek
            var parity = 0
            val weekText = WEEK_RANGE.find(r.description)
            val untilDate = WEEK_UNTIL.find(r.rrule)?.groupValues?.get(1)?.let { u ->
                runCatching {
                    LocalDate.of(u.substring(0, 4).toInt(), u.substring(4, 6).toInt(), u.substring(6, 8).toInt())
                }.getOrNull()
            }
            when {
                weekText != null -> {
                    startWeek = (weekText.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
                    endWeek = (weekText.groupValues[2].toIntOrNull() ?: startWeek).coerceAtLeast(startWeek)
                }
                untilDate != null -> {
                    endWeek = ((ChronoUnit.DAYS.between(semesterMonday, untilDate) / 7).toInt() + 1)
                        .coerceAtLeast(startWeek)
                }
                else -> endWeek = maxOf(startWeek, 20)
            }
            if (r.description.contains("单周")) parity = 1
            if (r.description.contains("双周")) parity = 2
            if (parity == 0 && (INTERVAL.find(r.rrule)?.groupValues?.get(1)?.toIntOrNull() == 2)) {
                parity = if (startWeek % 2 == 1) 1 else 2
            }
            // 描述里只有「第N周」这种单周次写法
            if (weekText == null && r.description.contains("周")) {
                val single = SINGLE_WEEK.findAll(r.description)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .filter { it in 1..30 }
                    .toList()
                if (single.isNotEmpty()) {
                    startWeek = single.min()
                    endWeek = single.max().coerceAtLeast(startWeek)
                }
            }

            list.add(
                Course0(
                    name = name.take(40),
                    teacher = teacher.take(30),
                    location = location.take(40),
                    weekday = date.dayOfWeek.value,
                    startSlot = slotFromDesc ?: 0,          // 0 = 待按时间表归一
                    span = (spanGuess ?: 2).coerceIn(1, 8),
                    startWeek = startWeek,
                    endWeek = endWeek,
                    parity = parity,
                    startTime = time
                )
            )
        }
        if (list.isEmpty()) return Result(note = "ics 里没解析出课程")

        // ---- 节次表 ----
        // ① 锚点:有「第X节」的课 → 第X节的上课时间就是它的 DTSTART
        val anchors = HashMap<Int, LocalTime>()
        for (c in list) if (c.startSlot > 0) anchors.putIfAbsent(c.startSlot, c.startTime)

        // ② 没有节次信息(别的 App 导出的 ics)时:按时间正推移位成节次
        if (anchors.isEmpty()) {
            val times = list.map { it.startTime }.distinct().sorted()
            times.forEachIndexed { i, t -> anchors[i + 1] = t }
            for (i in list.indices) {
                val idx = times.indexOf(list[i].startTime) + 1
                list[i] = list[i].copy(startSlot = idx)
            }
        } else {
            // 有节次但个别课缺节次(理论上不会有):按时间最近的锚点补
            for (i in list.indices) {
                if (list[i].startSlot == 0) {
                    val nearest = anchors.entries.minByOrNull { k ->
                        kotlin.math.abs(ChronoUnit.MINUTES.between(k.value, list[i].startTime))
                    }
                    list[i] = list[i].copy(startSlot = nearest?.key ?: 1)
                }
            }
        }

        val maxSlot = maxOf(
            anchors.keys.maxOrNull() ?: 1,
            list.maxOf { it.startSlot + it.span - 1 }   // 晚自习是「第10-12节」,最后要能显示到第12节
        ).coerceIn(1, 20)

        // ③ 步长(一节课 + 课间):用「同一门课跨几节 vs 它的实际时长」解出来,最准。
        //    例:2 节 = 95 分钟、3 节 = 145 分钟 → 步长 = (145-95)/(3-2) = 50 分钟 ✅
        //    (早期版本用锚点平均间隔再四舍五入,得到 55,导致 09:15/13:50 这种偏差)
        val bySpan = HashMap<Int, Int>()   // 跨节数 -> 该课总分钟数
        for (c in list) {
            val e = raws.firstOrNull { it.summary.contains(c.name.take(6)) }?.end ?: continue
            val mins = ChronoUnit.MINUTES.between(
                c.startTime,
                e.toLocalTime().withSecond(0).withNano(0)
            ).toInt()
            if (mins in 30..400 && c.span >= 1) bySpan.putIfAbsent(c.span, mins)
        }
        val steps = ArrayList<Double>()
        val spans = bySpan.keys.sorted()
        for (i in spans.indices) {
            for (j in i + 1 until spans.size) {
                val dn = spans[j] - spans[i]
                val dm = (bySpan[spans[j]]!! - bySpan[spans[i]]!!).toDouble()
                if (dn > 0 && dm > 0) {
                    val s = dm / dn
                    if (s in 30.0..70.0) steps.add(s)
                }
            }
        }
        val sortedAnchors = anchors.entries.sortedBy { it.key }
        val ratioFallback = sortedAnchors.flatMapIndexed { i, a ->
            sortedAnchors.drop(i + 1).mapNotNull { b ->
                val dp = b.key - a.key
                val dm = ChronoUnit.MINUTES.between(a.value, b.value).toDouble()
                if (dp > 0 && dm > 0 && dm / dp in 30.0..70.0) dm / dp else null
            }
        }.minOrNull()
        // 向下取整到 5 分钟:52.5 → 50(学校作息是 45 分钟一节 + 5 分钟课间)
        val step = when {
            steps.isNotEmpty() -> (Math.floor(steps.min() / 5.0 + 0.4) * 5).toLong().coerceIn(30, 70)
            ratioFallback != null -> (Math.floor(ratioFallback / 5.0 + 0.4) * 5).toLong().coerceIn(30, 70)
            else -> 50L
        }

        // ④ 生成 1..maxSlot 的时间表:锚点用真实值;缺的**从它前面最近的那个锚点顺推**(不能都从第1节乘,会越推越偏)
        val slotMap = HashMap<Int, LocalTime>()
        for (k in 1..maxSlot) {
            anchors[k]?.let { slotMap[k] = it; return@let }
            if (slotMap[k] == null) {
                val prev = (k - 1 downTo 1).firstOrNull { slotMap[it] != null }
                slotMap[k] = if (prev != null) {
                    slotMap[prev]!!.plusMinutes((k - prev).toLong() * step)
                } else {
                    // 在第一个锚点之前:从第一个锚点往前推
                    val first = sortedAnchors.first()
                    first.value.minusMinutes((first.key - k).toLong() * step)
                }
            }
        }
        val slotPairs = (1..maxSlot).map { k ->
            val st = slotMap[k]!!
            val next = slotMap[k + 1]
            val en = next?.minusMinutes(5) ?: st.plusMinutes(step - 5)
            k to "%02d:%02d-%02d:%02d".format(st.hour, st.minute, en.hour, en.minute)
        }

        // ---- 去重 + 排序 ----
        val seen = HashSet<String>()
        val courses = list
            .filter { seen.add("${it.name}@${it.weekday}@${it.startSlot}@${it.startWeek}-${it.endWeek}") }
            .sortedWith(compareBy({ it.weekday }, { it.startSlot }))
            .map {
                CourseTextParser.ParsedCourse(
                    name = it.name, teacher = it.teacher, location = it.location,
                    weekday = it.weekday, startSlot = it.startSlot.coerceIn(1, 20),
                    slotCount = it.span, startWeek = it.startWeek, endWeek = it.endWeek,
                    weekParity = it.parity
                )
            }

        val withSlotInfo = list.count { PERIOD_RANGE.containsMatchIn("") || it.startSlot > 0 }
        return Result(
            courses = courses,
            slotTimes = slotPairs,
            semesterStart = semesterMonday.toString(),
            note = "从 ics 解析出 ${courses.size} 门课;节次表 ${slotPairs.size} 节(节次以 ics 里的「第X节」为准" +
                ",上课时间由锚点推算);学期起始 $semesterMonday"
        )
    }
}
