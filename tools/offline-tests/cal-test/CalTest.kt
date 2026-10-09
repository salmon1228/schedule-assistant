import com.sdh.schedule.util.CalendarLabels
import com.sdh.schedule.util.HolidayRepo
import java.io.File
import java.time.LocalDate

private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

fun main() {
    // 载入打包进 APK 的真实数据
    val json = File(System.getProperty("projectRoot") ?: ".", "app/src/main/assets/holidays.json").readText(Charsets.UTF_8)
    HolidayRepo.loadFromJson(json)
    log.append("数据覆盖年份: ").append(HolidayRepo.coveredYears().toString()).append('\n')

    // ---- 1. 法定放假 ----
    run {
        val l = CalendarLabels.forDate(d(2026, 10, 1))
        check("国庆: 标记为放假", l.kind == CalendarLabels.Kind.OFF_DAY, l.toString())
        check("国庆: 显示节日名", l.text.contains("国庆"), l.text)
        check("元旦: 也是放假", CalendarLabels.forDate(d(2026, 1, 1)).kind == CalendarLabels.Kind.OFF_DAY)
        val suffix = CalendarLabels.dateSuffix(d(2026, 10, 1))
        check("国庆: 标题后缀含放假", suffix != null && suffix.contains("放假"), suffix ?: "null")
    }

    // ---- 2. 调休上班 ----
    run {
        val makeup = d(2026, 1, 4)
        val h = HolidayRepo.holidayOf(makeup)
        if (h == null) {
            check("调休日 2026-01-04 存在于数据里", false, "数据里没有这一天")
        } else {
            check("调休日: isOffDay=false", !h.isOffDay, h.toString())
            val l = CalendarLabels.forDate(makeup)
            check("调休日: 格子显示「班」", l.text == "班" && l.kind == CalendarLabels.Kind.MAKEUP, l.toString())
            val suffix = CalendarLabels.dateSuffix(makeup)
            check("调休日: 标题后缀写调休上班", suffix != null && suffix.contains("调休上班"), suffix ?: "null")
        }
    }

    // ---- 3. 春节(放假 + 农历节日重合, 应以放假为准) ----
    run {
        val l = CalendarLabels.forDate(d(2026, 2, 17))
        check("春节: 标记为放假", l.kind == CalendarLabels.Kind.OFF_DAY, l.toString())
        check("春节: 文本含春节", l.text.contains("春节"), l.text)
    }

    // ---- 4. 传统节日(不是法定假日) ----
    run {
        val l = CalendarLabels.forDate(d(2026, 3, 3)) // 元宵节
        check("元宵: 标为传统节日", l.kind == CalendarLabels.Kind.FESTIVAL, l.toString())
        check("元宵: 文本是元宵节", l.text.contains("元宵"), l.text)
    }

    // ---- 5. 平常日子显示农历 ----
    run {
        val l = CalendarLabels.forDate(d(2026, 9, 19))
        check("平常日: 显示农历", l.kind == CalendarLabels.Kind.LUNAR, l.toString())
        check("平常日: 农历文本非空", l.text.isNotBlank(), l.text)
        log.append("  2026-09-19 的格子小字 = ").append(l.text).append('\n')
    }

    // ---- 6. 数据未收录的年份 ----
    run {
        check("2027 未收录", HolidayRepo.isYearUncovered(2027))
        check("2026 已收录", !HolidayRepo.isYearUncovered(2026))
        check("CalendarLabels.isYearUncovered(2027)", CalendarLabels.isYearUncovered(2027))
        val l = CalendarLabels.forDate(d(2027, 1, 1))
        check("2027-01-01: 不崩(可能只有农历)", l.kind != CalendarLabels.Kind.OFF_DAY, l.toString())
    }

    // ---- 7. 全年扫描:不能有异常 ----
    run {
        var bad = 0
        var holidayCount = 0
        var makeupCount = 0
        var day = d(2026, 1, 1)
        while (day.year == 2026) {
            val l = runCatching { CalendarLabels.forDate(day) }.getOrElse { bad++; null }
            when (l?.kind) {
                CalendarLabels.Kind.OFF_DAY -> holidayCount++
                CalendarLabels.Kind.MAKEUP -> makeupCount++
                else -> {}
            }
            day = day.plusDays(1)
        }
        check("2026 全年扫描无异常", bad == 0, "异常 $bad 天")
        log.append("  2026 年: 放假日 $holidayCount 天, 调休上班 $makeupCount 天\n")
        check("2026 放假日数量合理(>10)", holidayCount > 10, "$holidayCount")
        check("2026 调休日数量合理(>0)", makeupCount > 0, "$makeupCount")
    }

    val out = File("result.txt")
    out.parentFile?.mkdirs()
    val header = "总计 ${pass + fail} 用例,通过 $pass,失败 $fail\n" + "=".repeat(60) + "\n"
    out.writeText(
        header + log.toString() + if (fail == 0) "ALL TESTS PASSED\n" else "SOME TESTS FAILED\n",
        Charsets.UTF_8
    )
    println("total=${pass + fail} pass=$pass fail=$fail")
}

