package com.sdh.schedule.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 农历信息 */
data class LunarInfo(
    val lunarYear: Int,
    val lunarMonth: Int,          // 1..12
    val lunarDay: Int,            // 1..30
    val isLeapMonth: Boolean,     // 该月是否为闰月
    val monthName: String,        // 正月/二月/三月/四月/五月/六月/七月/八月/九月/十月/冬月/腊月
    val dayName: String,          // 初一/初二/…/十五/廿一/三十
    val festival: String?         // 传统节日名,如 春节/元宵节/端午节/中秋节/七夕节/重阳节/除夕/腊八节/小年,没有则 null
)

/**
 * 纯 Kotlin 农历算法(不依赖 Android,不依赖 java.util.Calendar 的农历扩展)。
 *
 * 采用经典压缩数据表 LUNAR_INFO,每项 20 bit 描述一个农历年:
 *  - bit 3..0   闰月月份(0 = 无闰月)
 *  - bit 15..4  12 个常规月的大小(1 = 30 天/大月,0 = 29 天/小月),正月在 bit15,腊月在 bit4
 *  - bit 16     闰月大小(1 = 30 天,0 = 29 天)
 *
 * 基准:公历 1900-01-31 == 农历 1900 年正月初一。
 * 覆盖范围:1900-01-31 起,至农历 2100 年除夕为止;范围外一律返回 null,不抛异常。
 *
 * 对外只读、无状态、线程安全。
 */
object LunarCalendar {

    /** 内置数据覆盖的最小农历年 / 最大农历年 */
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100

    private const val MONTH_MASK = 0x10000   // 闰月大小位
    private const val LEAP_MONTH_MASK = 0xF  // 闰月月份

    /** 压缩农历数据表,索引 0 对应 1900 年,索引 200 对应 2100 年 */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
        0x0d520                                                                                       // 2100
    )

    private val MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )

    private val DAY_DIGITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    /** 基准日:公历 1900-01-31 == 农历 1900 年正月初一 */
    private val BASE_DATE: LocalDate = LocalDate.of(1900, 1, 31)

    /** 数据覆盖的总天数(1900 农历年首日 ~ 2100 农历年除夕),用于范围判断 */
    private val TOTAL_DAYS: Long = run {
        var sum = 0L
        for (y in MIN_YEAR..MAX_YEAR) sum += yearDays(y).toLong()
        sum
    }

    private fun indexOf(year: Int): Int = year - MIN_YEAR

    private fun inRange(year: Int): Boolean = year in MIN_YEAR..MAX_YEAR

    /** 某农历年的闰月月份,0 表示该年无闰月 */
    fun leapMonth(year: Int): Int {
        if (!inRange(year)) return 0
        return LUNAR_INFO[indexOf(year)] and LEAP_MONTH_MASK
    }

    /** 某农历年闰月的天数,无闰月返回 0 */
    fun leapDays(year: Int): Int {
        if (leapMonth(year) == 0) return 0
        return if (LUNAR_INFO[indexOf(year)] and MONTH_MASK != 0) 30 else 29
    }

    /** 某农历年常规月 m(1..12)的天数:29 或 30 */
    fun monthDays(year: Int, month: Int): Int {
        if (!inRange(year) || month !in 1..12) return 0
        return if (LUNAR_INFO[indexOf(year)] and (MONTH_MASK shr month) != 0) 30 else 29
    }

    /** 某农历年的总天数(含闰月) */
    fun yearDays(year: Int): Int {
        if (!inRange(year)) return 0
        // 348 = 12 * 29,再逐位补上大月的 1 天
        var sum = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if (LUNAR_INFO[indexOf(year)] and bit != 0) sum++
            bit = bit shr 1
        }
        return sum + leapDays(year)
    }

    /**
     * 农历信息;超出内置数据范围返回 null(不抛异常)。
     */
    fun of(date: LocalDate): LunarInfo? {
        val offset: Long
        try {
            offset = ChronoUnit.DAYS.between(BASE_DATE, date)
        } catch (e: RuntimeException) {
            return null
        }
        if (offset < 0 || offset >= TOTAL_DAYS) return null

        var rest = offset.toInt()
        var year = MIN_YEAR
        while (year <= MAX_YEAR) {
            val yd = yearDays(year)
            if (rest < yd) break
            rest -= yd
            year++
        }
        if (year > MAX_YEAR) return null

        val leap = leapMonth(year)
        var month = 0
        var isLeap = false
        var day = 0
        var m = 1
        var resolved = false
        while (m <= 12) {
            val len = monthDays(year, m)
            if (rest < len) {
                month = m; isLeap = false; day = rest + 1; resolved = true
                break
            }
            rest -= len
            if (leap == m) {
                val llen = leapDays(year)
                if (rest < llen) {
                    month = m; isLeap = true; day = rest + 1; resolved = true
                    break
                }
                rest -= llen
            }
            m++
        }
        if (!resolved) return null

        return LunarInfo(
            lunarYear = year,
            lunarMonth = month,
            lunarDay = day,
            isLeapMonth = isLeap,
            monthName = MONTH_NAMES[month - 1],
            dayName = dayName(day),
            festival = festivalOf(year, month, day, isLeap)
        )
    }

    /** 指定公历年的春节(农历正月初一)对应的公历日期;超出数据范围返回 null */
    fun springFestival(year: Int): LocalDate? {
        if (!inRange(year)) return null
        var days = 0L
        for (y in MIN_YEAR until year) days += yearDays(y).toLong()
        return BASE_DATE.plusDays(days)
    }

    /** 农历日名:初一/初二/…/十五/廿一/三十 */
    private fun dayName(day: Int): String = when {
        day == 10 -> "初十"
        day == 20 -> "二十"
        day == 30 -> "三十"
        day in 1..9 -> "初" + DAY_DIGITS[day - 1]
        day in 11..19 -> "十" + DAY_DIGITS[day - 11]
        day in 21..29 -> "廿" + DAY_DIGITS[day - 21]
        else -> day.toString()
    }

    /** 传统节日(只认常规月,闰月不算节日) */
    private fun festivalOf(year: Int, month: Int, day: Int, isLeap: Boolean): String? {
        if (isLeap) return null
        return when {
            month == 1 && day == 1 -> "春节"
            month == 1 && day == 15 -> "元宵节"
            month == 5 && day == 5 -> "端午节"
            month == 7 && day == 7 -> "七夕节"
            month == 8 && day == 15 -> "中秋节"
            month == 9 && day == 9 -> "重阳节"
            month == 12 && day == 8 -> "腊八节"
            month == 12 && day == 23 -> "小年"
            month == 12 && day == monthDays(year, 12) -> "除夕"
            else -> null
        }
    }
}
