import com.sdh.schedule.util.HolidayRepo
import com.sdh.schedule.util.LunarCalendar
import com.sdh.schedule.util.LunarInfo
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Expected strings are written as \u escapes on purpose: the assertions must not
// depend on how the JVM/compiler decodes this source file's Chinese characters.
// ---------------------------------------------------------------------------
private const val SPRING = "\u6625\u8282"                 // chun jie
private const val LANTERN = "\u5143\u5BB5\u8282"          // yuan xiao jie
private const val DUANWU = "\u7AEF\u5348\u8282"           // duan wu jie
private const val MIDAUTUMN = "\u4E2D\u79CB\u8282"        // zhong qiu jie
private const val QIXI = "\u4E03\u5915\u8282"             // qi xi jie
private const val CHONGYANG = "\u91CD\u9633\u8282"        // chong yang jie
private const val LABAI = "\u814A\u516B\u8282"            // la ba jie
private const val XIAONIAN = "\u5C0F\u5E74"               // xiao nian
private const val CHUXI = "\u9664\u5915"                  // chu xi
private const val M_ZHENG = "\u6B63\u6708"                // zheng yue
private const val M_LA = "\u814A\u6708"                   // la yue
private const val M_DONG = "\u51AC\u6708"                 // dong yue
private const val D_CHUYI = "\u521D\u4E00"                // chu yi
private const val D_SHIWU = "\u5341\u4E94"                // shi wu
private const val D_NIANJIU = "\u5EFF\u4E5D"              // nian jiu
private const val D_SANSHI = "\u4E09\u5341"               // san shi
private const val D_ERSHI = "\u4E8C\u5341"                // er shi
private const val H_NEWYEAR = "\u5143\u65E6"              // yuan dan
private const val H_QINGMING = "\u6E05\u660E\u8282"       // qing ming jie
private const val H_LABOR = "\u52B3\u52A8\u8282"          // lao dong jie
private const val H_GUOQING = "\u56FD\u5E86\u8282"        // guo qing jie

private val sb = StringBuilder()
private var passCount = 0
private var failCount = 0

private fun check(id: String, title: String, ok: Boolean, detail: String) {
    if (ok) passCount++ else failCount++
    sb.append(if (ok) "PASS" else "FAIL").append("  ").append(id).append("  ").append(title)
    sb.append("\n         expected/actual: ").append(detail).append('\n')
}

private fun eq(id: String, title: String, expected: Any?, actual: Any?) {
    check(id, title, expected == actual, "expected=$expected actual=$actual")
}

private fun fmt(i: LunarInfo?): String =
    if (i == null) "null"
    else "${i.lunarYear}-${i.lunarMonth}-${i.lunarDay} leap=${i.isLeapMonth} " +
        "month=${i.monthName} day=${i.dayName} festival=${i.festival}"

private fun isNewYear1(i: LunarInfo?, year: Int): Boolean =
    i != null && i.lunarMonth == 1 && i.lunarDay == 1 && !i.isLeapMonth &&
        i.lunarYear == year && i.monthName == M_ZHENG && i.dayName == D_CHUYI && i.festival == SPRING

fun main(args: Array<String>) {
    val jsonPath = if (args.isNotEmpty()) args[0] else "holidays.json"
    val outPath = if (args.size > 1) args[1] else "result.txt"

    sb.append("=== LunarCalendar / HolidayRepo JVM self-test ===\n")
    sb.append("json = ").append(jsonPath).append('\n')
    sb.append("jvm  = ").append(System.getProperty("java.version")).append('\n')
    sb.append("date = ").append(LocalDate.now().toString()).append("\n\n")

    // ---------------- 1..3  Spring Festival of 2026 / 2025 / 2024 ----------------
    val d2026ny = LocalDate.of(2026, 2, 17)
    val d2025ny = LocalDate.of(2025, 1, 29)
    val d2024ny = LocalDate.of(2024, 2, 10)
    val l2026ny = LunarCalendar.of(d2026ny)
    val l2025ny = LunarCalendar.of(d2025ny)
    val l2024ny = LunarCalendar.of(d2024ny)
    check("T1 ", "2026-02-17 == lunar 1/1 chun jie", isNewYear1(l2026ny, 2026), fmt(l2026ny))
    check("T2 ", "2025-01-29 == lunar 1/1 chun jie", isNewYear1(l2025ny, 2025), fmt(l2025ny))
    check("T3 ", "2024-02-10 == lunar 1/1 chun jie", isNewYear1(l2024ny, 2024), fmt(l2024ny))

    // ---------------- 4  Lantern Festival 2026-03-03 = new year + 14 ----------------
    val d2026l = LocalDate.of(2026, 3, 3)
    val l2026l = LunarCalendar.of(d2026l)
    check(
        "T4 ", "2026-03-03 == lunar 1/15 yuan xiao jie",
        l2026l != null && l2026l.lunarMonth == 1 && l2026l.lunarDay == 15 &&
            !l2026l.isLeapMonth && l2026l.dayName == D_SHIWU && l2026l.festival == LANTERN,
        fmt(l2026l)
    )
    eq(
        "T4b", "2026-03-03 == springFestival(2026) + 14 days",
        d2026ny.plusDays(14), d2026l
    )

    // ---------------- 5  Mid-Autumn 2026-09-25 ----------------
    val l2026m = LunarCalendar.of(LocalDate.of(2026, 9, 25))
    check(
        "T5 ", "2026-09-25 == lunar 8/15 zhong qiu jie",
        l2026m != null && l2026m.lunarMonth == 8 && l2026m.lunarDay == 15 &&
            !l2026m.isLeapMonth && l2026m.festival == MIDAUTUMN,
        fmt(l2026m)
    )

    // ---------------- 6  Dragon Boat 2026-06-19 ----------------
    val l2026d = LunarCalendar.of(LocalDate.of(2026, 6, 19))
    check(
        "T6 ", "2026-06-19 == lunar 5/5 duan wu jie",
        l2026d != null && l2026d.lunarMonth == 5 && l2026d.lunarDay == 5 &&
            !l2026d.isLeapMonth && l2026d.festival == DUANWU,
        fmt(l2026d)
    )

    // ---------------- 7  New Year's Eve 2026-02-16 ----------------
    val d2026cx = LocalDate.of(2026, 2, 16)
    val l2026cx = LunarCalendar.of(d2026cx)
    check(
        "T7 ", "2026-02-16 == la yue last day -> chu xi",
        l2026cx != null && l2026cx.festival == CHUXI && l2026cx.lunarMonth == 12 &&
            !l2026cx.isLeapMonth && l2026cx.monthName == M_LA &&
            l2026cx.lunarDay == LunarCalendar.monthDays(2026, 12),
        fmt(l2026cx)
    )
    eq("T7b", "2026-02-16 == springFestival(2026) - 1", d2026cyMinus1(d2026ny), d2026cx)

    // ---------------- load holidays.json (pure logic, no Context) ----------------
    var loaded = false
    var loadError = ""
    try {
        val text = String(Files.readAllBytes(Paths.get(jsonPath)), Charsets.UTF_8)
        sb.append("json bytes = ").append(text.toByteArray(Charsets.UTF_8).size)
            .append(", first 40 chars = ").append(text.take(40).replace("\n", " ")).append("\n\n")
        HolidayRepo.loadFromJson(text)
        loaded = true
    } catch (e: Exception) {
        loadError = e.toString()
    }
    check("T0 ", "load holidays.json without Context", loaded, "loaded=$loaded err=$loadError")

    // ---------------- 8  makeup workday / rest day ----------------
    val makeup = LocalDate.of(2026, 1, 4)   // Sunday, official 调休上班
    val offDay = LocalDate.of(2026, 1, 1)   // New Year holiday
    check(
        "T8 ", "isMakeupWorkday(2026-01-04) == true (=false, Sunday!)",
        HolidayRepo.isMakeupWorkday(makeup) && !HolidayRepo.isRestDay(makeup),
        "makeup=" + HolidayRepo.isMakeupWorkday(makeup) + " rest=" + HolidayRepo.isRestDay(makeup) +
            " holiday=" + HolidayRepo.holidayOf(makeup)
    )
    check(
        "T8b", "isRestDay(2026-01-01) == true and holidayOf name is yuan dan",
        HolidayRepo.isRestDay(offDay) && HolidayRepo.holidayOf(offDay)?.name == H_NEWYEAR &&
            HolidayRepo.holidayOf(offDay)?.isOffDay == true && !HolidayRepo.isMakeupWorkday(offDay),
        "rest=" + HolidayRepo.isRestDay(offDay) + " holiday=" + HolidayRepo.holidayOf(offDay)
    )
    check(
        "T8c", "spring festival 2026-02-17 is an off day, 2026-02-14 is a makeup workday",
        HolidayRepo.holidayOf(d2026ny)?.name == SPRING &&
            HolidayRepo.holidayOf(d2026ny)?.isOffDay == true &&
            HolidayRepo.holidayOf(LocalDate.of(2026, 2, 14))?.isOffDay == false &&
            HolidayRepo.isMakeupWorkday(LocalDate.of(2026, 2, 14)),
        "2026-02-17=" + HolidayRepo.holidayOf(d2026ny) + " 2026-02-14=" +
            HolidayRepo.holidayOf(LocalDate.of(2026, 2, 14))
    )
    check(
        "T8d", "plain days: 2026-03-15 (Sun) rest / 2026-01-05 (Mon) work / holidayOf null",
        HolidayRepo.holidayOf(LocalDate.of(2026, 3, 15)) == null &&
            HolidayRepo.isRestDay(LocalDate.of(2026, 3, 15)) &&
            !HolidayRepo.isRestDay(LocalDate.of(2026, 1, 5)) &&
            !HolidayRepo.isMakeupWorkday(LocalDate.of(2026, 1, 5)),
        "2026-03-15 rest=" + HolidayRepo.isRestDay(LocalDate.of(2026, 3, 15)) +
            " 2026-01-05 rest=" + HolidayRepo.isRestDay(LocalDate.of(2026, 1, 5))
    )

    // ---------------- 9  coverage ----------------
    val years = HolidayRepo.coveredYears()
    check(
        "T9 ", "coveredYears() contains 2026, isYearUncovered(2030) == true",
        years.contains(2026) && HolidayRepo.isYearUncovered(2030) &&
            !HolidayRepo.isYearUncovered(2026) && years == years.sorted(),
        "coveredYears=$years isYearUncovered(2030)=" + HolidayRepo.isYearUncovered(2030)
    )
    check(
        "T9b", "coveredYears() == [2025, 2026] (2027 not published upstream)",
        years == listOf(2025, 2026),
        "coveredYears=$years"
    )

    // ---------------- 10  boundaries, must not throw ----------------
    var boundaryOk = true
    var boundaryDetail = StringBuilder()
    val probes = listOf(
        LocalDate.of(1900, 1, 31), LocalDate.of(1900, 1, 30), LocalDate.of(1900, 1, 1),
        LocalDate.of(1899, 12, 31), LocalDate.of(2026, 2, 17),
        LocalDate.of(2100, 12, 31), LocalDate.of(2101, 1, 1), LocalDate.of(2200, 6, 15),
        LocalDate.of(1970, 1, 1), LocalDate.of(2000, 2, 29)
    )
    for (p in probes) {
        try {
            val r = LunarCalendar.of(p)
            boundaryDetail.append(p).append("->").append(fmt(r)).append(" | ")
        } catch (e: Throwable) {
            boundaryOk = false
            boundaryDetail.append(p).append("->THREW ").append(e).append(" | ")
        }
    }
    val l1900 = LunarCalendar.of(LocalDate.of(1900, 1, 31))
    check(
        "T10", "boundaries never throw; 1900-01-31 == lunar 1900 1/1",
        boundaryOk && l1900 != null && l1900.lunarYear == 1900 && l1900.lunarMonth == 1 &&
            l1900.lunarDay == 1,
        boundaryDetail.toString()
    )
    check(
        "T10b", "out of range returns null: 1899-12-31, 1900-01-30, 2101-01-29",
        LunarCalendar.of(LocalDate.of(1899, 12, 31)) == null &&
            LunarCalendar.of(LocalDate.of(1900, 1, 30)) == null &&
            LunarCalendar.of(LocalDate.of(2101, 1, 29)) == null,
        "1899-12-31=" + fmt(LunarCalendar.of(LocalDate.of(1899, 12, 31))) +
            " 1900-01-30=" + fmt(LunarCalendar.of(LocalDate.of(1900, 1, 30))) +
            " 2101-01-29=" + fmt(LunarCalendar.of(LocalDate.of(2101, 1, 29)))
    )

    // ---------------- E1  springFestival against known dates ----------------
    eq("E1a", "springFestival(2025)", LocalDate.of(2025, 1, 29), LunarCalendar.springFestival(2025))
    eq("E1b", "springFestival(2026)", LocalDate.of(2026, 2, 17), LunarCalendar.springFestival(2026))
    eq("E1c", "springFestival(2027)", LocalDate.of(2027, 2, 6), LunarCalendar.springFestival(2027))
    eq("E1d", "springFestival(2028)", LocalDate.of(2028, 1, 26), LunarCalendar.springFestival(2028))
    eq("E1e", "springFestival(1900)", LocalDate.of(1900, 1, 31), LunarCalendar.springFestival(1900))
    eq("E1f", "springFestival(2101) out of range -> null", null, LunarCalendar.springFestival(2101))

    // ---------------- E2  leap month (2025 has run-6-yue) ----------------
    val leap = LunarCalendar.of(LocalDate.of(2025, 7, 25))
    check(
        "E2 ", "2025-07-25 == leap 6th month day 1, no festival",
        leap != null && leap.isLeapMonth && leap.lunarMonth == 6 && leap.lunarDay == 1 &&
            leap.festival == null && LunarCalendar.leapMonth(2025) == 6,
        fmt(leap) + " leapMonth(2025)=" + LunarCalendar.leapMonth(2025)
    )
    eq("E2b", "leapDays(2025) == 29", 29, LunarCalendar.leapDays(2025))
    eq("E2c", "yearDays(2025) == 384", 384, LunarCalendar.yearDays(2025))
    eq("E2d", "yearDays(2026) == 354", 354, LunarCalendar.yearDays(2026))

    // ---------------- E3  more festivals against known dates ----------------
    val l2025cx = LunarCalendar.of(LocalDate.of(2025, 1, 28))
    check("E3a", "2025-01-28 == chu xi", l2025cx != null && l2025cx.festival == CHUXI, fmt(l2025cx))
    val l2025m = LunarCalendar.of(LocalDate.of(2025, 10, 6))
    check(
        "E3b", "2025-10-06 == lunar 8/15 zhong qiu jie",
        l2025m != null && l2025m.lunarMonth == 8 && l2025m.lunarDay == 15 && l2025m.festival == MIDAUTUMN,
        fmt(l2025m)
    )
    val l2025d = LunarCalendar.of(LocalDate.of(2025, 5, 31))
    check(
        "E3c", "2025-05-31 == lunar 5/5 duan wu jie",
        l2025d != null && l2025d.lunarMonth == 5 && l2025d.lunarDay == 5 && l2025d.festival == DUANWU,
        fmt(l2025d)
    )
    val l2024m = LunarCalendar.of(LocalDate.of(2024, 9, 17))
    check(
        "E3d", "2024-09-17 == lunar 8/15 zhong qiu jie",
        l2024m != null && l2024m.lunarMonth == 8 && l2024m.lunarDay == 15 && l2024m.festival == MIDAUTUMN,
        fmt(l2024m)
    )
    // 2033 has the rare leap 11th month
    eq("E3e", "leapMonth(2033) == 11", 11, LunarCalendar.leapMonth(2033))
    eq("E3f", "leapMonth(1900) == 8", 8, LunarCalendar.leapMonth(1900))
    eq("E3g", "leapMonth(2026) == 0", 0, LunarCalendar.leapMonth(2026))

    // ---------------- E4  day names ----------------
    val dayNameCases = mapOf(
        LocalDate.of(2026, 2, 17) to D_CHUYI,      // 1
        LocalDate.of(2026, 3, 3) to D_SHIWU,       // 15
        LocalDate.of(2026, 2, 16) to D_NIANJIU     // 29 (last day of la yue)
    )
    var dnOk = true
    val dnDetail = StringBuilder()
    for ((d, expect) in dayNameCases) {
        val got = LunarCalendar.of(d)?.dayName
        if (got != expect) dnOk = false
        dnDetail.append(d).append("->").append(got).append("(want ").append(expect).append(") ")
    }
    // 30-day month check: lunar 2026 month 1 has 30 days -> 2026-03-18 is day 30
    val d30 = LunarCalendar.of(LocalDate.of(2026, 3, 18))
    if (LunarCalendar.monthDays(2026, 1) == 30) {
        if (d30 == null || d30.lunarDay != 30 || d30.dayName != D_SANSHI) dnOk = false
        dnDetail.append("2026-03-18->").append(fmt(d30))
    }
    check("E4 ", "day names chu-yi / shi-wu / nian-jiu / san-shi", dnOk, dnDetail.toString())
    // 20th day of a month -> er shi
    val d20 = LunarCalendar.of(LocalDate.of(2026, 2, 17).plusDays(19))
    check(
        "E4b", "20th lunar day name is er shi",
        d20 != null && d20.lunarDay == 20 && d20.dayName == D_ERSHI,
        fmt(d20)
    )

    // ---------------- E5  structural scan of the whole 1900..2100 table ----------------
    val counts = HashMap<String, Int>()
    var cur = LocalDate.of(1900, 1, 31)
    var lastDate: LocalDate? = null
    var scanThrew = ""
    var scannedDays = 0
    try {
        while (true) {
            val li = LunarCalendar.of(cur) ?: break
            counts["${li.lunarYear}|${li.lunarMonth}|${li.isLeapMonth}"] =
                (counts["${li.lunarYear}|${li.lunarMonth}|${li.isLeapMonth}"] ?: 0) + 1
            lastDate = cur
            scannedDays++
            cur = cur.plusDays(1)
        }
    } catch (e: Throwable) {
        scanThrew = e.toString()
    }
    var structOk = scanThrew.isEmpty()
    val structDetail = StringBuilder()
    var badSegments = 0
    for (y in 1900..2100) {
        val lm = LunarCalendar.leapMonth(y)
        var total = 0
        for (m in 1..12) {
            val n = counts["$y|$m|false"] ?: 0
            total += n
            if (n != LunarCalendar.monthDays(y, m)) {
                if (badSegments < 4) structDetail.append("bad m $y/$m got=$n want=${LunarCalendar.monthDays(y, m)}; ")
                badSegments++
                structOk = false
            }
            if (lm == m) {
                val ln = counts["$y|$m|true"] ?: 0
                total += ln
                if (ln != LunarCalendar.leapDays(y)) {
                    if (badSegments < 4) structDetail.append("bad leap $y/$m got=$ln want=${LunarCalendar.leapDays(y)}; ")
                    badSegments++
                    structOk = false
                }
            }
        }
        if (counts.containsKey("$y|0|false")) structOk = false
        if (counts.containsKey("$y|13|false")) structOk = false
        if (total != LunarCalendar.yearDays(y)) {
            if (badSegments < 4) structDetail.append("bad year $y got=$total want=${LunarCalendar.yearDays(y)}; ")
            badSegments++
            structOk = false
        }
        if (LunarCalendar.springFestival(y) == null) structOk = false
    }
    check(
        "E5 ", "full-table scan: every month length matches, no gaps, no throw",
        structOk,
        "scanned $scannedDays days, $scannedDays lunar entries, last=$lastDate " +
            "range ${LocalDate.of(1900, 1, 31)}..$lastDate threw=[$scanThrew] $structDetail"
    )
    check(
        "E5b", "scan range end: next day after last is null",
        LunarCalendar.of((lastDate ?: LocalDate.of(1900, 1, 31)).plusDays(1)) == null,
        "last=$lastDate next=" + fmt(LunarCalendar.of((lastDate ?: LocalDate.of(1900, 1, 31)).plusDays(1)))
    )
    check(
        "E5c", "springFestival(y) is the day of the first scanned entry of lunar year y (2026)",
        LunarCalendar.of(LunarCalendar.springFestival(2026)!!)?.let {
            it.lunarMonth == 1 && it.lunarDay == 1 && it.lunarYear == 2026
        } == true,
        "springFestival(2026)=" + LunarCalendar.springFestival(2026)
    )

    // ---------------- E6  robustness of loadFromJson ----------------
    HolidayRepo.loadFromJson("{ this is not json")
    val keptAfterGarbage = HolidayRepo.coveredYears().contains(2026) &&
        HolidayRepo.holidayOf(d2026ny)?.name == SPRING
    check(
        "E6 ", "corrupt json keeps previous snapshot (no crash, data survives)",
        keptAfterGarbage,
        "coveredYears=" + HolidayRepo.coveredYears()
    )
    HolidayRepo.loadFromJson("{}")
    check(
        "E6b", "empty json -> empty repo, holidayOf null, uncovered everywhere",
        HolidayRepo.coveredYears().isEmpty() && HolidayRepo.holidayOf(d2026ny) == null &&
            HolidayRepo.isYearUncovered(2026),
        "coveredYears=" + HolidayRepo.coveredYears()
    )
    HolidayRepo.loadFromJson("{\"years\":[2030],\"days\":[{\"date\":\"2030-01-01\",\"name\":\"X\",\"isOffDay\":true}]}")
    check(
        "E6c", "array-form days is accepted too (upstream holiday-cn shape)",
        HolidayRepo.holidayOf(LocalDate.of(2030, 1, 1))?.isOffDay == true &&
            HolidayRepo.isRestDay(LocalDate.of(2030, 1, 1)) &&
            HolidayRepo.coveredYears() == listOf(2030),
        "coveredYears=" + HolidayRepo.coveredYears() + " 2030-01-01=" +
            HolidayRepo.holidayOf(LocalDate.of(2030, 1, 1))
    )
    // restore the real data set before finishing
    run {
        val text = String(Files.readAllBytes(Paths.get(jsonPath)), Charsets.UTF_8)
        HolidayRepo.loadFromJson(text)
    }

    // ---------------- E7  data sanity for the merged file ----------------
    val days2025 = countHolidayDays(2025)
    val days2026 = countHolidayDays(2026)
    check(
        "E7 ", "merged data sanity: 2025 has 33 entries, 2026 has 39 entries",
        days2025 == 33 && days2026 == 39,
        "2025=$days2025 2026=$days2026"
    )
    check(
        "E7b", "2026 holiday names are the 7 statutory ones",
        setOf(
            HolidayRepo.holidayOf(LocalDate.of(2026, 1, 1))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 2, 17))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 4, 4))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 5, 1))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 6, 19))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 9, 25))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 10, 1))?.name
        ) == setOf(H_NEWYEAR, SPRING, H_QINGMING, H_LABOR, H_GUOQING, DUANWU, MIDAUTUMN),
        "names=" + listOfNotNull(
            HolidayRepo.holidayOf(LocalDate.of(2026, 1, 1))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 2, 17))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 4, 4))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 5, 1))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 6, 19))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 9, 25))?.name,
            HolidayRepo.holidayOf(LocalDate.of(2026, 10, 1))?.name
        )
    )
    // spot check the remaining traditional festivals resolve somewhere in 2026
    check(
        "E7c", "qi xi / chong yang / la ba / xiao nian appear in lunar year 2026",
        festivalsOfLunarYear(2026).containsAll(listOf(QIXI, CHONGYANG, LABAI, XIAONIAN)),
        "2026 festivals=" + festivalsOfLunarYear(2026)
    )
    check(
        "E7d", "2026 has 7 holiday festivals incl chun jie & chu xi",
        festivalsOfLunarYear(2026).containsAll(listOf(SPRING, CHUXI, LANTERN, DUANWU, MIDAUTUMN)),
        "2026 festivals=" + festivalsOfLunarYear(2026)
    )
    // month name for month 11 must be dong yue
    val dong = findLunarMonth(2026, 11)
    check("E7e", "lunar month 11 is named dong yue", dong == M_DONG, "found=$dong")

    // ---------------- summary ----------------
    sb.append("\n=== SUMMARY: PASS=").append(passCount).append(" FAIL=").append(failCount)
        .append(" TOTAL=").append(passCount + failCount).append(" ===\n")
    if (failCount > 0) {
        sb.append("RESULT: FAILED\n")
    } else {
        sb.append("RESULT: ALL PASSED\n")
    }

    Files.write(Paths.get(outPath), sb.toString().toByteArray(Charsets.UTF_8))
    println("PASS=$passCount FAIL=$failCount")
    if (failCount == 0) {
        println("RESULT: ALL PASSED")
    } else {
        println("RESULT: FAILED")
        kotlin.system.exitProcess(1)
    }
}

private fun d2026cyMinus1(d: LocalDate): LocalDate = d.minusDays(1)

private fun countHolidayDays(year: Int): Int {
    var n = 0
    var d = LocalDate.of(year, 1, 1)
    while (d.year == year) {
        if (HolidayRepo.holidayOf(d) != null) n++
        d = d.plusDays(1)
    }
    return n
}

private fun festivalsOfLunarYear(lunarYear: Int): List<String> {
    val out = LinkedHashSet<String>()
    var d = LunarCalendar.springFestival(lunarYear) ?: return emptyList()
    var guard = 0
    while (guard < 400) {
        val li = LunarCalendar.of(d)
        if (li == null || li.lunarYear != lunarYear) break
        if (li.festival != null) out.add(li.festival)
        d = d.plusDays(1)
        guard++
    }
    return out.toList()
}

private fun findLunarMonth(year: Int, month: Int): String? {
    var d = LunarCalendar.springFestival(year) ?: return null
    var guard = 0
    while (guard < 400) {
        val li = LunarCalendar.of(d)
        if (li == null) return null
        if (li.lunarYear == year && li.lunarMonth == month && !li.isLeapMonth) return li.monthName
        if (li.lunarYear > year) return null
        d = d.plusDays(1)
        guard++
    }
    return null
}
