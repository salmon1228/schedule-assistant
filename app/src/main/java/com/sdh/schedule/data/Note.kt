package com.sdh.schedule.data

/** 便签(与日程无关的随手记) */
data class Note(
    val id: Long = 0L,
    val title: String = "",
    val content: String = "",
    /** 置顶 */
    val pinned: Boolean = false,
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
) {
    /** 列表里显示用的摘要(取正文第一行非空内容) */
    val preview: String
        get() = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    companion object {
        fun fromShare(text: String): Note {
            val clean = text.trim()
            val firstLine = clean.lineSequence().firstOrNull().orEmpty().trim()
            val title = if (firstLine.length <= 20) firstLine else firstLine.take(20)
            return Note(title = title, content = clean)
        }
    }
}

/**
 * 课程表的一门课。
 * 周次按"学期第一周的周一"为第 1 周计算(见 [weekOf])。
 */
data class Course(
    val id: Long = 0L,
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    /** 1=周一 … 7=周日 */
    val weekday: Int = 1,
    /** 第几节开始(1 起) */
    val startSlot: Int = 1,
    /** 连上几节 */
    val slotCount: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    /** 0=每周 1=单周 2=双周 */
    val weekParity: Int = 0,
    val colorIndex: Int = 0,
    /** 上课前多少分钟提醒;-1 = 不提醒 */
    val reminderMinutes: Int = 10,
    val enabled: Boolean = true,
    val note: String = ""
) {
    val parityLabel: String
        get() = when (weekParity) {
            1 -> "单周"
            2 -> "双周"
            else -> "每周"
        }

    val weekLabel: String
        get() = "第${startWeek}-${endWeek}周 $parityLabel"

    /**
     * 自动配色下标(调色板大小由调用方给)。
     * **同一门课(同名)永远得到同一个颜色** —— 重复的课一眼能认出是一套。
     */
    fun autoColorIndex(paletteSize: Int): Int {
        if (paletteSize <= 1) return 0
        val h = name.trim().ifBlank { "课程" }.hashCode()
        return Math.floorMod(h, paletteSize)
    }

    /** 实际用的配色下标:手动选过(>0)就用手动的,否则按课程名自动 */
    fun effectiveColorIndex(paletteSize: Int): Int =
        if (colorIndex > 0) colorIndex.coerceIn(0, paletteSize - 1) else autoColorIndex(paletteSize)

    /** 这门课在某学年周次是否上课 */
    fun activeInWeek(week: Int): Boolean {
        if (!enabled) return false
        if (week < startWeek || week > endWeek) return false
        return when (weekParity) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    companion object {
        /** 计算某天属于第几周(1 起);学期开始前的日期返回 <=0 */
        fun weekOf(date: java.time.LocalDate, semesterStartMonday: java.time.LocalDate): Int {
            val days = java.time.temporal.ChronoUnit.DAYS.between(semesterStartMonday, date)
            return Math.floorDiv(days, 7L).toInt() + 1
        }
    }
}
