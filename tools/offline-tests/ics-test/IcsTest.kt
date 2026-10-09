import com.sdh.schedule.parse.IcsImport
import com.sdh.schedule.parse.SlotTimes
import java.io.File

private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

/**
 * 用**用户学校的真实作息**构造 ics(数据来自 WakeUp 截图 + 工程库里的课程明细):
 *   1 08:20-09:05   2 09:10-09:55   3 10:05-10:50   4 10:55-11:40   5 11:45-12:30
 *   6 13:30-14:15   7 14:20-15:05   8 15:15-16:00   9 16:05-16:50
 *  10 18:15-19:00  11 19:05-19:50  12 19:55-20:40
 * 学期第一周周一 = 2026-09-14
 *
 * ⚠️ 这个测试是为了钉住一个真实 bug:上一版节次表被"合并"污染成 20 条重复,
 *    导致「英语1(15:15)被标成第 6 节(11:45)」—— 用户反馈"我下午的课呢?"
 */
private fun ev(
    summary: String, location: String, desc: String,
    date: String, start: String, end: String, rrule: String
) = "BEGIN:VEVENT\n" +
    "UID:$summary$date$start\n" +
    "SUMMARY:$summary\n" +
    "LOCATION:$location\n" +
    "DESCRIPTION:$desc\n" +
    "DTSTART;TZID=Asia/Shanghai:${date}T${start}00\n" +
    "DTEND;TZID=Asia/Shanghai:${date}T${end}00\n" +
    "RRULE:$rrule\n" +
    "END:VEVENT\n"

private val ICS = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//WakeUp//Course//CN\n" +
    // 周一
    ev("电子信息类学科导论", "B308", "教师:黄小芳", "20260914", "1055", "1140", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=MO") +
    ev("高等数学G1(必修)", "B101", "教师:韩敬伟", "20260914", "1330", "1505", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=MO") +
    ev("英语1(必修)", "T511", "教师:陈迎晓", "20260914", "1515", "1600", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=MO") +
    // 周二
    ev("大学生心理健康教育", "E112", "教师:朱华燕", "20260915", "1515", "1600", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=TU") +
    // 周三
    ev("电子线路实习", "格致楼318-320(电子线路综合实验室3)", "教师:林海云\\n周次:5-11周", "20260916", "1005", "1140", "FREQ=WEEKLY;BYDAY=WE") +
    ev("线性代数与空间解析几何", "B101", "教师:马祖强", "20260916", "1515", "1600", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=WE") +
    // 周四
    ev("C语言程序设计1", "B108", "教师:张锦明", "20260917", "0820", "0905", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=TH") +
    ev("思想道德与法治", "E210", "教师:陈飞", "20260917", "0910", "1050", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=TH") +
    ev("金工实习", "格致楼110(工程训练中心)", "教师:韦祎", "20260917", "1330", "1505", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=TH") +
    // 周五
    ev("形势与政策1", "B404", "教师:卢钰奋\\n周次:12-16周", "20260918", "0820", "0905", "FREQ=WEEKLY;BYDAY=FR") +
    ev("体育-羽毛球", "体育馆二楼羽毛球场", "教师:徐敏", "20260918", "0910", "0955", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=FR") +
    ev("工程识图(必修)", "B207", "教师:陈为政", "20260918", "1515", "1600", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=FR") +
    ev("晚自习", "B112", "", "20260918", "1815", "1950", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=FR") +
    // 周日
    ev("线性代数与空间解析几何", "B101", "教师:马祖强", "20260920", "0820", "0905", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=SU") +
    ev("高等数学G1(必修)", "B101", "教师:韩敬伟", "20260920", "1055", "1140", "FREQ=WEEKLY;UNTIL=20270110T235959Z;BYDAY=SU") +
    "END:VCALENDAR\n"

fun main() {
    val r = IcsImport.parse(ICS)
    log.append("解析: ").append(r.note).append('\n')
    log.append("节次表: ").append(r.slotTimes.joinToString("  ") { "${it.first}:${it.second}" }).append('\n')
    log.append("课程: ").append(r.courses.joinToString(" | ") {
        "周${it.weekday}第${it.startSlot}节(+)${it.name}"
    }).append('\n')

    // ---- 核心:节次表不能有重复/幻觉 ----
    check(
        "节次表没有重复项(相邻不<8 分钟)",
        !SlotTimes.hasDuplicates(r.slotTimes.joinToString(",") { it.second }),
        r.slotTimes.joinToString { it.second }
    )
    check("节次表规模合理(5~14 节)", r.slotTimes.size in 5..14, "${r.slotTimes.size}")

    // ---- 核心:下午的课必须落在下午那一行(用户反馈的问题)----
    fun slotTimeOf(name: String, weekday: Int): String? {
        val c = r.courses.firstOrNull { it.name.contains(name) && it.weekday == weekday } ?: return null
        return r.slotTimes.firstOrNull { it.first == c.startSlot }?.second?.substringBefore("-")
    }
    check("英语1(周一)落在 15:15 那一行", slotTimeOf("英语1", 1) == "15:15", slotTimeOf("英语1", 1) ?: "null")
    check("大学生心理健康教育(周二)落在 15:15", slotTimeOf("大学生心理", 2) == "15:15", slotTimeOf("大学生心理", 2) ?: "null")
    check("工程识图(周五)落在 15:15", slotTimeOf("工程识图", 5) == "15:15", slotTimeOf("工程识图", 5) ?: "null")
    check("高等数学G1(周一)落在 13:30", slotTimeOf("高等数学", 1) == "13:30", slotTimeOf("高等数学", 1) ?: "null")
    check("金工实习(周四)落在 13:30", slotTimeOf("金工实习", 4) == "13:30", slotTimeOf("金工实习", 4) ?: "null")
    check("晚自习(周五)落在 18:15", slotTimeOf("晚自习", 5) == "18:15", slotTimeOf("晚自习", 5) ?: "null")
    check("C语言(周四)落在 08:20", slotTimeOf("C语言", 4) == "08:20", slotTimeOf("C语言", 4) ?: "null")
    check("电子线路实习(周三)落在 10:05", slotTimeOf("电子线路", 3) == "10:05", slotTimeOf("电子线路", 3) ?: "null")

    // ---- 连上几节(45 分钟/节:13:30-15:05 = 2 节)----
    run {
        val math = r.courses.firstOrNull { it.name.contains("高等数学") && it.weekday == 1 }
        check("高等数学(周一)连上 2 节", math?.slotCount == 2, "${math?.slotCount}")
        val eng = r.courses.firstOrNull { it.name.contains("英语1") }
        check("英语1 是 1 节(45 分钟)", eng?.slotCount == 1, "${eng?.slotCount}")
        val zixi = r.courses.firstOrNull { it.name.contains("晚自习") }
        check("晚自习 18:15-19:50 连上 2 节", zixi?.slotCount == 2, "${zixi?.slotCount}")
    }

    // ---- 周次 / 教师 / 教室 ----
    run {
        val lab = r.courses.firstOrNull { it.name.contains("电子线路") }
        check("电子线路实习:周次取自描述 5-11", lab?.startWeek == 5 && lab?.endWeek == 11, "${lab?.startWeek}-${lab?.endWeek}")
        check("电子线路实习:教室含格致楼", lab?.location?.contains("格致楼") == true, lab?.location ?: "null")
        check("电子线路实习:教师 林海云", lab?.teacher == "林海云", lab?.teacher ?: "null")
        val pol = r.courses.firstOrNull { it.name.contains("形势与政策") }
        check("形势与政策1:周次 12-16", pol?.startWeek == 12 && pol?.endWeek == 16, "${pol?.startWeek}-${pol?.endWeek}")
        val math = r.courses.firstOrNull { it.name.contains("高等数学") }
        check("高等数学:UNTIL(第17周周日)推出 endWeek=17", math?.endWeek == 17, "${math?.endWeek}")
        check("课程名去掉了(必修)", r.courses.none { it.name.contains("必修") })
    }

    // ---- 学期起始 = 最早那节课所在周的周一 ----
    check("学期起始 = 2026-09-14", r.semesterStart == "2026-09-14", r.semesterStart ?: "null")

    // ---- 非 ics 不误判 ----
    check("普通文本不误判成 ics", !IcsImport.looksLikeIcs("高等数学 周一 1-2节"))

    // ---- 节次表导入策略:以 ics 为准替换,不再与坏值合并 ----
    run {
        val broken = "08:00-09:50,08:20-09:55,09:40-11:10,10:05-12:30,10:10-14:20,11:45-13:15,11:50-13:20"
        val applied = SlotTimes.applyImport(broken, r.slotTimes)
        check(
            "导入时以 ics 为准替换(不再是并集)",
            SlotTimes.count(applied) == r.slotTimes.size,
            "${SlotTimes.count(applied)} vs ${r.slotTimes.size}"
        )
        check("替换后没有重复项", !SlotTimes.hasDuplicates(applied), applied)
        check(
            "坏设置去重后条目变少",
            SlotTimes.count(SlotTimes.dedupe(broken)) < SlotTimes.count(broken),
            "${SlotTimes.count(SlotTimes.dedupe(broken))} vs ${SlotTimes.count(broken)}"
        )
        check("推导结果太少(<3)时不动原设置", SlotTimes.applyImport(broken, listOf(1 to "08:00-08:45")) == broken)
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


