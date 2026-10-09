package com.sdh.schedule.util

import java.time.LocalDate

/**
 * 月历格子 / 日期标题上该显示什么(节假日、调休、农历)。
 *
 * 从 Compose 的 DayCell 里抽出来,变成纯函数 —— 这样能单元测试,
 * 也保证"月历上到底显示什么"和"日程列表页头显示什么"用的是同一套规则。
 */
object CalendarLabels {

    enum class Kind {
        /** 法定放假 */
        OFF_DAY,

        /** 调休上班 */
        MAKEUP,

        /** 传统节日(非放假,如元宵、七夕) */
        FESTIVAL,

        /** 平常日子,显示农历 */
        LUNAR,

        /** 什么都没有(农历数据超范围) */
        NONE
    }

    data class DayLabel(
        val text: String = "",
        val kind: Kind = Kind.NONE
    ) {
        val isHolidayLike: Boolean get() = kind == Kind.OFF_DAY || kind == Kind.MAKEUP
    }

    /**
     * 月历格子里那行小字:
     *   调休 → 「班」;放假/节日 → 节日名;否则 → 农历(初一显示月份,如"正月")
     */
    fun forDate(date: LocalDate): DayLabel {
        val holiday = runCatching { HolidayRepo.holidayOf(date) }.getOrNull()
        if (holiday != null) {
            return if (holiday.isOffDay) {
                DayLabel(holiday.name, Kind.OFF_DAY)
            } else {
                DayLabel("班", Kind.MAKEUP)
            }
        }
        val lunar = runCatching { HolidayRepo.lunarOf(date) }.getOrNull() ?: return DayLabel()
        if (!lunar.festival.isNullOrBlank()) return DayLabel(lunar.festival, Kind.FESTIVAL)
        val text = if (lunar.lunarDay == 1) lunar.monthName else lunar.dayName
        return DayLabel(text, Kind.LUNAR)
    }

    /** 日程列表页头 / 选中日标题后面的补充说明,例如「放假·国庆节」「调休上班(春节)」「中秋节」 */
    fun dateSuffix(date: LocalDate): String? {
        val holiday = runCatching { HolidayRepo.holidayOf(date) }.getOrNull()
        if (holiday != null) {
            return if (holiday.isOffDay) "放假·${holiday.name}" else "调休上班(${holiday.name})"
        }
        val lunar = runCatching { HolidayRepo.lunarOf(date) }.getOrNull() ?: return null
        if (!lunar.festival.isNullOrBlank()) return lunar.festival
        return "农历${lunar.monthName}${lunar.dayName}"
    }

    /** 该年的节假日数据是否还没收录 */
    fun isYearUncovered(year: Int): Boolean =
        runCatching { HolidayRepo.isYearUncovered(year) }.getOrDefault(false)
}
