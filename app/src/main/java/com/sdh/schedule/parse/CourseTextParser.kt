package com.sdh.schedule.parse

/**
 * 课表文字解析(纯 Kotlin,无 Android 依赖,可单元测试)。
 *
 * 大模型不可用时的兜底:把一行行课表文字解析成课程。
 * 典型输入(OCR 结果 / 教务平台复制出来的文本):
 *   高等数学 周一 1-2节 1-16周 A301 张老师
 *   周二 3-4节 大学物理 实验楼B203
 */
object CourseTextParser {

    data class ParsedCourse(
        val name: String,
        val teacher: String = "",
        val location: String = "",
        val weekday: Int = 1,
        val startSlot: Int = 1,
        val slotCount: Int = 2,
        val startWeek: Int = 1,
        val endWeek: Int = 16,
        val weekParity: Int = 0
    )

    private val WEEKDAY_MAP = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7
    )

    /** 明显不是课程名的词 */
    private val NOT_COURSE = listOf("课程表", "周次", "时间", "节次", "上午", "下午", "晚上", "备注", "作息")

    fun parse(text: String): List<ParsedCourse> {
        val out = ArrayList<ParsedCourse>()
        for (raw in text.split('\n', '\u3002', ';', '\uFF1B')) {
            val line = raw.trim()
            if (line.length < 4) continue

            val wdMatch = Regex("(?:周|星期|礼拜)([一二三四五六日天1-7])").find(line) ?: continue
            val weekday = WEEKDAY_MAP[wdMatch.groupValues[1]] ?: continue

            var startSlot = 1
            var slotCount = 2
            val slotMatch = Regex("(?:第)?(\\d{1,2})\\s*[-~\u2014\u2013至]\\s*(\\d{1,2})\\s*节").find(line)
            if (slotMatch != null) {
                val a = slotMatch.groupValues[1].toIntOrNull() ?: 1
                val b = slotMatch.groupValues[2].toIntOrNull() ?: (a + 1)
                startSlot = a.coerceIn(1, 12)
                slotCount = (b - a + 1).coerceIn(1, 6)
            } else {
                val one = Regex("第?(\\d{1,2})\\s*节").find(line)
                if (one != null) startSlot = (one.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 12)
            }

            var startWeek = 1
            var endWeek = 16
            var parity = 0
            val weekMatch = Regex("(\\d{1,2})\\s*[-~\u2014\u2013至]\\s*(\\d{1,2})\\s*周").find(line)
            if (weekMatch != null) {
                startWeek = (weekMatch.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
                endWeek = (weekMatch.groupValues[2].toIntOrNull() ?: 16).coerceAtLeast(startWeek)
            }
            if (line.contains("单周")) parity = 1
            if (line.contains("双周")) parity = 2

            // 去掉星期/节次/周次这些结构化字段,剩下的里找地点和课程名
            var rest = line
                .replace(wdMatch.value, " ")
                .replace(Regex("(?:第)?\\d{1,2}\\s*[-~\u2014\u2013至]\\s*\\d{1,2}\\s*节"), " ")
                .replace(Regex("第?\\d{1,2}\\s*节"), " ")
                .replace(Regex("\\d{1,2}\\s*[-~\u2014\u2013至]\\s*\\d{1,2}\\s*周"), " ")
                .replace("单周", " ").replace("双周", " ")

            // 地点:两种写法都要认 ——「实验楼B203」这种带楼/室/馆字样的,和「A301」「B203-1」这种纯编号
            val locWord = Regex("[\\u4e00-\\u9fa5A-Za-z0-9]{0,6}(?:楼|室|馆|校区|教室|实验)[\\u4e00-\\u9fa5A-Za-z0-9]{0,8}")
                .find(rest)?.value?.trim().orEmpty()
            val locCode = Regex("(?:^|\\s)([A-Za-z]{1,3}\\d{2,4}(?:[-\\u2014]\\d{1,4})?)(?=\\s|$)")
                .find(rest)?.groupValues?.get(1)?.trim().orEmpty()
            val location = locWord.ifBlank { locCode }
            if (location.isNotBlank()) rest = rest.replace(location, " ")

            // 老师:"张老师""李教授"这种
            val teacherMatch = Regex("[\\u4e00-\\u9fa5]{1,3}(?:老师|教授|讲师)").find(rest)
            val teacher = teacherMatch?.value?.trim().orEmpty()
            if (teacher.isNotBlank()) rest = rest.replace(teacher, " ")

            val name = rest.split(' ', '\t', '|', ',', '\uFF0C')
                .map { it.trim() }
                .filter { it.length in 2..20 && it.any { ch -> ch.code > 0x4E00 } }
                .filter { candidate -> NOT_COURSE.none { candidate.contains(it) } }
                .maxByOrNull { it.length }
                ?: continue

            out.add(
                ParsedCourse(
                    name = name.take(40),
                    teacher = teacher,
                    location = location,
                    weekday = weekday,
                    startSlot = startSlot,
                    slotCount = slotCount,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekParity = parity
                )
            )
        }
        return out
    }
}
