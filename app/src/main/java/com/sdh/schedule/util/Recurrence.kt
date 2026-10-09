package com.sdh.schedule.util

import com.sdh.schedule.data.Event
import com.sdh.schedule.parse.RepeatKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 日程在某个具体日期上的一次「发生」 */
data class Occurrence(
    val event: Event,
    val startMillis: Long,
    val endMillis: Long,
    val date: LocalDate,
    /** 是否是重复日程的后续实例(而非首次创建的那一次) */
    val isRepeatInstance: Boolean
)

/**
 * 重复规则展开。
 * 设计上只依赖 Event 的首次发生时间 + RepeatKind,给出任意区间内的发生实例。
 */
object Recurrence {

    fun baseDateTime(e: Event, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(e.startMillis), zone)

    fun baseDate(e: Event, zone: ZoneId): LocalDate = baseDateTime(e, zone).toLocalDate()

    /** 事件时长(毫秒),重复实例沿用首次发生的时长 */
    fun durationMillis(e: Event): Long {
        val d = e.endMillis - e.startMillis
        return if (d > 0) d else if (e.allDay) 0L else 60L * 60_000L
    }

    /** 该事件在 date 这一天是否发生 */
    fun occursOn(e: Event, date: LocalDate, zone: ZoneId): Boolean =
        occurrenceOn(e, date, zone) != null

    /** 计算 date 这一天的实例;不发生则返回 null */
    fun occurrenceOn(e: Event, date: LocalDate, zone: ZoneId): Occurrence? {
        val base = baseDate(e, zone)
        if (date.isBefore(base)) return null
        if (e.repeatUntilMillis > 0L) {
            val endDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.repeatUntilMillis), zone).toLocalDate()
            if (date.isAfter(endDate)) return null
        }
        val matches = when (e.repeat) {
            RepeatKind.NONE -> date == base
            RepeatKind.DAILY -> true
            RepeatKind.WEEKDAYS -> date.dayOfWeek.value in 1..5
            RepeatKind.WEEKLY -> {
                val wd = if (e.repeatWeekday in 1..7) e.repeatWeekday else base.dayOfWeek.value
                date.dayOfWeek.value == wd
            }
            RepeatKind.MONTHLY -> date.dayOfMonth == base.dayOfMonth
            RepeatKind.YEARLY -> date.dayOfMonth == base.dayOfMonth && date.monthValue == base.monthValue
        }
        if (!matches) return null
        return buildOccurrence(e, date, base, zone)
    }

    private fun buildOccurrence(e: Event, date: LocalDate, base: LocalDate, zone: ZoneId): Occurrence {
        val startMs: Long
        val endMs: Long
        if (e.allDay) {
            startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
            endMs = date.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
        } else {
            val time = baseDateTime(e, zone).toLocalTime().withSecond(0).withNano(0)
            startMs = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            endMs = startMs + durationMillis(e)
        }
        return Occurrence(e, startMs, endMs, date, date != base)
    }

    /**
     * 某一天应该显示的全部实例。
     * 除了"当天开始"的实例,还包括"前一天开始、跨天到现在还没结束"的实例
     * —— 否则 23:00~次日01:00 这种日程在第二天就看不到了。
     */
    fun occurrencesForDay(e: Event, date: LocalDate, zone: ZoneId): List<Occurrence> {
        val out = ArrayList<Occurrence>(2)
        occurrenceOn(e, date, zone)?.let { out.add(it) }
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        occurrenceOn(e, date.minusDays(1), zone)?.let {
            if (it.endMillis > dayStart) out.add(it)
        }
        return out
    }

    /** [fromMs, toMs] 区间内的所有实例(有迭代上限保护,避免死循环) */
    fun occurrencesBetween(
        e: Event,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId,
        maxDays: Long = 800
    ): List<Occurrence> {
        val base = baseDate(e, zone)
        var day = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMs), zone).toLocalDate()
            // 往前多扫一天,才能捞到"昨天晚上开始、跨进区间"的实例
            .minusDays(1)
        if (day.isBefore(base)) day = base
        val lastDay = LocalDateTime.ofInstant(Instant.ofEpochMilli(toMs), zone).toLocalDate()
        val out = ArrayList<Occurrence>()
        var guard = 0L
        while (!day.isAfter(lastDay) && guard < maxDays) {
            occurrenceOn(e, day, zone)?.let { occ ->
                // 用实例结束时间去重,避免跨天日程被区间切断
                if (occ.endMillis >= fromMs && occ.startMillis <= toMs) out.add(occ)
            }
            day = day.plusDays(1)
            guard++
        }
        return out
    }

    /** >= fromMs 的下一次发生时间,找不到返回 null */
    fun nextOccurrenceStart(e: Event, fromMs: Long, zone: ZoneId, horizonDays: Long = 400): Long? {
        val base = baseDate(e, zone)
        val fromDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMs), zone).toLocalDate()
        val today = if (fromDate.isBefore(base)) base else fromDate
        for (i in 0 until horizonDays) {
            val day = today.plusDays(i)
            val occ = occurrenceOn(e, day, zone) ?: continue
            // 非全天日程要考虑当天时刻是否已过
            if (occ.endMillis >= fromMs) return occ.startMillis
        }
        return null
    }

    /** 严格晚于 afterStartMs 的下一次发生(用于闹钟触发后排下一次,不会重复排到同一次) */
    fun nextOccurrenceStartStrict(
        e: Event,
        afterStartMs: Long,
        zone: ZoneId,
        horizonDays: Long = 400
    ): Long? {
        val base = baseDate(e, zone)
        val fromDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterStartMs), zone).toLocalDate()
        val today = if (fromDate.isBefore(base)) base else fromDate
        for (i in 0 until horizonDays) {
            val day = today.plusDays(i)
            val occ = occurrenceOn(e, day, zone) ?: continue
            if (occ.startMillis > afterStartMs) return occ.startMillis
        }
        return null
    }

    /** 最近一次已经过去的实例(用于"距上次"类显示) */
    fun lastOccurrenceBefore(e: Event, beforeMs: Long, zone: ZoneId, horizonDays: Long = 400): Long? {
        val base = baseDate(e, zone)
        val beforeDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(beforeMs), zone).toLocalDate()
        var day = beforeDate
        var i = 0L
        while (i < horizonDays && !day.isBefore(base)) {
            val occ = occurrenceOn(e, day, zone)
            if (occ != null && occ.startMillis <= beforeMs) return occ.startMillis
            day = day.minusDays(1)
            i++
        }
        return null
    }

    /** 距离下一次发生还有多少天(倒数日用,含今天=0) */
    fun daysUntilNext(e: Event, nowMs: Long, zone: ZoneId): Long? {
        val next = nextOccurrenceStart(e, nowMs, zone) ?: return null
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
        val nextDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(next), zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, nextDate)
    }

    /** 生日/纪念日的周年数 */
    fun yearsSince(e: Event, nowMs: Long, zone: ZoneId): Int? {
        val base = baseDate(e, zone)
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
        val years = ChronoUnit.YEARS.between(base, today).toInt()
        return if (years in 0..200) years else null
    }

    fun repeatLabel(e: Event): String = when (e.repeat) {
        RepeatKind.NONE -> "永不"
        RepeatKind.DAILY -> "每天"
        RepeatKind.WEEKDAYS -> "每个工作日(周一至周五)"
        RepeatKind.WEEKLY -> "每周${weekdayLabel(if (e.repeatWeekday in 1..7) e.repeatWeekday else 1)}"
        RepeatKind.MONTHLY -> "每月${baseDate(e, ZoneId.systemDefault()).dayOfMonth}号"
        RepeatKind.YEARLY -> "每年"
    }

    fun weekdayLabel(isoDay: Int): String = when (isoDay) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
    }
}
