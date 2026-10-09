import com.sdh.schedule.data.Event
import com.sdh.schedule.parse.RepeatKind
import com.sdh.schedule.util.Recurrence
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        pass++
        log.append("[PASS] ").append(name)
    } else {
        fail++
        log.append("[FAIL] ").append(name).append("  -> ").append(detail)
    }
    log.append('\n')
}

private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

private fun day(y: Int, mo: Int, d: Int): LocalDate = LocalDate.of(y, mo, d)

private fun text(millis: Long): String =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).toString()

private fun ev(
    startMs: Long,
    endMs: Long,
    repeat: RepeatKind = RepeatKind.NONE,
    weekday: Int = 0,
    allDay: Boolean = false,
    untilMs: Long = 0L
) = Event(
    id = 1L, title = "测试", startMillis = startMs, endMillis = endMs,
    allDay = allDay, repeat = repeat, repeatWeekday = weekday, repeatUntilMillis = untilMs
)

fun main() {
    // ---- 1. 单次日程 ----
    run {
        val e = ev(ms(2026, 9, 14, 14, 0), ms(2026, 9, 14, 15, 0))
        check("单次:当天命中", Recurrence.occursOn(e, day(2026, 9, 14), zone))
        check("单次:前一天不命中", !Recurrence.occursOn(e, day(2026, 9, 13), zone))
        check("单次:后一天不命中", !Recurrence.occursOn(e, day(2026, 9, 15), zone))
        val occ = Recurrence.occurrenceOn(e, day(2026, 9, 14), zone)!!
        check("单次:时刻正确", occ.startMillis == ms(2026, 9, 14, 14, 0), text(occ.startMillis))
        check("单次:时长 1 小时", occ.endMillis - occ.startMillis == 3600_000L)
    }

    // ---- 2. 每天 ----
    run {
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0), RepeatKind.DAILY)
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 9, 18, 23, 59), zone)
        check("每天:5 天 5 次", list.size == 5, "实际 ${list.size}")
        check("每天:最早的在前", list.first().startMillis == ms(2026, 9, 14, 9, 0))
        check("每天:最晚的在后", list.last().startMillis == ms(2026, 9, 18, 9, 0))
        val reversed = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 9, 18, 23, 59), zone)
            .sortedBy { it.startMillis }
        check("每天:顺序稳定", reversed.map { it.startMillis } == list.map { it.startMillis })
    }

    // ---- 3. 工作日 ----
    run {
        // 2026-09-14 是周一
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0), RepeatKind.WEEKDAYS)
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 9, 20, 23, 59), zone)
        check("工作日:周一到周五共 5 次", list.size == 5, "实际 ${list.size}")
        check("工作日:周六不排", list.none { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.startMillis), zone).dayOfWeek.value == 6 })
        check("工作日:周日不排", list.none { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.startMillis), zone).dayOfWeek.value == 7 })
    }

    // ---- 4. 每周一 ----
    run {
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0), RepeatKind.WEEKLY, weekday = 1)
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 10, 4, 23, 59), zone)
        check("每周一:3 周 3 次", list.size == 3, "实际 ${list.size}")
        check("每周一:都是周一", list.all {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.startMillis), zone).dayOfWeek.value == 1
        })
        check("每周一:第二次是 9/21", list.getOrNull(1)?.startMillis == ms(2026, 9, 21, 9, 0))
    }

    // ---- 5. 每月 31 号:短月要跳过 ----
    run {
        val e = ev(ms(2026, 1, 31, 8, 0), ms(2026, 1, 31, 9, 0), RepeatKind.MONTHLY)
        check("每月31:31 号命中", Recurrence.occursOn(e, day(2026, 3, 31), zone))
        check("每月31:2 月(28 天)不命中", !Recurrence.occursOn(e, day(2026, 2, 28), zone))
        check("每月31:4 月(30 天)不命中", !Recurrence.occursOn(e, day(2026, 4, 30), zone))
        check("每月31:5 月 31 号命中", Recurrence.occursOn(e, day(2026, 5, 31), zone))
    }

    // ---- 6. 每年 2 月 29 日:平年跳过 ----
    run {
        val e = ev(ms(2024, 2, 29, 10, 0), ms(2024, 2, 29, 11, 0), RepeatKind.YEARLY)
        check("每年2/29:2026 平年不命中", !Recurrence.occursOn(e, day(2026, 2, 28), zone) && !Recurrence.occursOn(e, day(2026, 3, 1), zone))
        check("每年2/29:2028 闰年命中", Recurrence.occursOn(e, day(2028, 2, 29), zone))
    }

    // ---- 7. 全天日程边界 ----
    run {
        val e = ev(0L, 0L, RepeatKind.NONE, allDay = true)
        val base = ms(2026, 10, 1)
        val e2 = e.copy(startMillis = base, endMillis = base)
        val occ = Recurrence.occurrenceOn(e2, day(2026, 10, 1), zone)!!
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(occ.startMillis), zone)
        val end = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(occ.endMillis), zone)
        check("全天:从 00:00:00 开始", start.hour == 0 && start.minute == 0 && start.second == 0)
        check("全天:到 23:59:59 结束", end.hour == 23 && end.minute == 59 && end.second == 59)
    }

    // ---- 8. 严格取下一次(闹钟触发后排下一次的关键) ----
    run {
        // 每天 09:00,当前正好是 09:00 -> 下一次必须是明天 09:00,而不是同一个
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0), RepeatKind.DAILY)
        val next = Recurrence.nextOccurrenceStartStrict(e, ms(2026, 9, 14, 9, 0), zone)
        check("严格下一次:跳过当前这次", next == ms(2026, 9, 15, 9, 0), text(next ?: 0))
        val next2 = Recurrence.nextOccurrenceStartStrict(e, ms(2026, 9, 14, 9, 1), zone)
        check("严格下一次:触发后 1 分钟仍取明天", next2 == ms(2026, 9, 15, 9, 0), text(next2 ?: 0))
    }

    // ---- 9. nextOccurrenceStart 取"含当前"的下一次 ----
    run {
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0), RepeatKind.DAILY)
        val next = Recurrence.nextOccurrenceStart(e, ms(2026, 9, 14, 8, 0), zone)
        check("下次(含当前):当天 09:00", next == ms(2026, 9, 14, 9, 0), text(next ?: 0))
        val next2 = Recurrence.nextOccurrenceStart(e, ms(2026, 9, 14, 9, 30), zone)
        check("下次(含当前):进行中仍算当天", next2 == ms(2026, 9, 14, 9, 0), text(next2 ?: 0))
        val next3 = Recurrence.nextOccurrenceStart(e, ms(2026, 9, 14, 11, 0), zone)
        check("下次(含当前):结束后取明天", next3 == ms(2026, 9, 15, 9, 0), text(next3 ?: 0))
    }

    // ---- 10. repeatUntil 截止 ----
    run {
        val e = ev(
            ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 0),
            RepeatKind.DAILY, untilMs = ms(2026, 9, 16, 23, 59)
        )
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 10, 0, 0), ms(2026, 9, 30, 23, 59), zone)
        check("截止日期:只到 9/16 共 3 次", list.size == 3, "实际 ${list.size}")
        check("截止日期:9/17 不排", !Recurrence.occursOn(e, day(2026, 9, 17), zone))
    }

    // ---- 11. 跨天日程 ----
    run {
        val e = ev(ms(2026, 9, 13, 23, 0), ms(2026, 9, 14, 1, 0))
        val occ = Recurrence.occurrenceOn(e, day(2026, 9, 13), zone)!!
        check("跨天:开始是 9/13 23:00", occ.startMillis == ms(2026, 9, 13, 23, 0))
        check("跨天:结束是 9/14 01:00", occ.endMillis == ms(2026, 9, 14, 1, 0))
        check("跨天:9/14 当天不重复出现", !Recurrence.occursOn(e, day(2026, 9, 14), zone))
    }

    // ---- 12. 重复实例保持时长 ----
    run {
        // 每周一 09:00-10:30(90 分钟),第 3 次仍应是 90 分钟
        val e = ev(ms(2026, 9, 14, 9, 0), ms(2026, 9, 14, 10, 30), RepeatKind.WEEKLY, weekday = 1)
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 10, 4, 23, 59), zone)
        check("重复时长:每次都 90 分钟", list.all { it.endMillis - it.startMillis == 90 * 60_000L })
    }

    // ---- 13. 倒数日 ----
    run {
        val today = day(2026, 9, 13)
        val e = ev(ms(2026, 9, 20, 0, 0), ms(2026, 9, 20, 23, 59), allDay = true)
        val nowMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        check("倒数日:还有 7 天", Recurrence.daysUntilNext(e, nowMs, zone) == 7L)
    }

    // ---- 14. 区间查询会把"跨进来"的日程算上 ----
    run {
        val e = ev(ms(2026, 9, 13, 23, 0), ms(2026, 9, 14, 2, 0))
        val list = Recurrence.occurrencesBetween(e, ms(2026, 9, 14, 0, 0), ms(2026, 9, 14, 23, 59), zone)
        check("区间:跨天日程在第二天区间内可见", list.isNotEmpty(), "实际 ${list.size}")
        // 次日单天查询也要能看到(凌晨打开 App 能看见正在进行的日程)
        val forDay = Recurrence.occurrencesForDay(e, day(2026, 9, 14), zone)
        check("单天:跨进来的实例可见", forDay.size == 1, "实际 ${forDay.size}")
        check("单天:跨进来的实例开始时间是前一天 23:00",
            forDay.firstOrNull()?.startMillis == ms(2026, 9, 13, 23, 0))
        // 起始日仍然是 1 条,不会重复
        check("单天:起始日不重复", Recurrence.occurrencesForDay(e, day(2026, 9, 13), zone).size == 1)
    }

    // ---- 14b. 每天 23:00 持续 3 小时的日程:每天应有 2 条(凌晨跨进来的 + 当晚开始的) ----
    run {
        val e = ev(ms(2026, 9, 14, 23, 0), ms(2026, 9, 15, 2, 0), RepeatKind.DAILY)
        val forDay = Recurrence.occurrencesForDay(e, day(2026, 9, 16), zone)
        check("每天跨天:单天出现 2 条", forDay.size == 2, "实际 ${forDay.size}")
    }

    // ---- 15. 未来日程不会出现在过去 ----
    run {
        val e = ev(ms(2026, 12, 1, 9, 0), ms(2026, 12, 1, 10, 0), RepeatKind.DAILY)
        check("起点之前:9/13 不命中", !Recurrence.occursOn(e, day(2026, 9, 13), zone))
        check("起点当天:12/1 命中", Recurrence.occursOn(e, day(2026, 12, 1), zone))
    }

    val out = File("result.txt")
    out.parentFile?.mkdirs()
    val header = "总计 ${pass + fail} 用例,通过 $pass,失败 $fail\n" + "=".repeat(60) + "\n"
    out.writeText(header + log.toString() + if (fail == 0) "ALL TESTS PASSED\n" else "SOME TESTS FAILED\n", Charsets.UTF_8)
    println("total=${pass + fail} pass=$pass fail=$fail")
}

