import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Note
import java.io.File
import java.time.LocalDate

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

private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

fun main() {
    // 学期第一周的周一 = 2026-03-02
    val semester = d(2026, 3, 2)

    // ---- 1. 周次计算 ----
    check("周次:学期第一天是第1周", Course.weekOf(d(2026, 3, 2), semester) == 1, "${Course.weekOf(d(2026, 3, 2), semester)}")
    check("周次:同一周的周日仍是第1周", Course.weekOf(d(2026, 3, 8), semester) == 1, "${Course.weekOf(d(2026, 3, 8), semester)}")
    check("周次:次周周一=第2周", Course.weekOf(d(2026, 3, 9), semester) == 2, "${Course.weekOf(d(2026, 3, 9), semester)}")
    check("周次:第16周周一", Course.weekOf(semester.plusWeeks(15), semester) == 16, "${Course.weekOf(semester.plusWeeks(15), semester)}")
    check("周次:学期开始前 <=0", Course.weekOf(d(2026, 2, 23), semester) <= 0, "${Course.weekOf(d(2026, 2, 23), semester)}")
    check("周次:学期开始前一周 =0", Course.weekOf(semester.minusWeeks(1), semester) == 0, "${Course.weekOf(semester.minusWeeks(1), semester)}")
    check("周次:跨年也算得对(2027-02-22)", Course.weekOf(d(2027, 2, 22), semester) == 52, "${Course.weekOf(d(2027, 2, 22), semester)}")

    // ---- 2. 每周课 ----
    val weekly = Course(name = "高数", weekday = 1, startSlot = 1, slotCount = 2, startWeek = 1, endWeek = 16, weekParity = 0)
    check("每周:第1周上课", weekly.activeInWeek(1))
    check("每周:第16周上课", weekly.activeInWeek(16))
    check("每周:第17周不上", !weekly.activeInWeek(17))
    check("每周:第0周不上", !weekly.activeInWeek(0))

    // ---- 3. 单周 ----
    val odd = weekly.copy(weekParity = 1)
    check("单周:第1周上", odd.activeInWeek(1))
    check("单周:第2周不上", !odd.activeInWeek(2))
    check("单周:第3周上", odd.activeInWeek(3))
    check("单周:第15周上", odd.activeInWeek(15))
    check("单周:第16周不上", !odd.activeInWeek(16))

    // ---- 4. 双周 ----
    val even = weekly.copy(weekParity = 2)
    check("双周:第2周上", even.activeInWeek(2))
    check("双周:第1周不上", !even.activeInWeek(1))
    check("双周:第16周上", even.activeInWeek(16))

    // ---- 5. 周次范围 + 单双周 叠加 ----
    val ranged = Course(name = "选修", startWeek = 3, endWeek = 9, weekParity = 1)
    check("范围+单周:第2周不上(范围外)", !ranged.activeInWeek(2))
    check("范围+单周:第3周上", ranged.activeInWeek(3))
    check("范围+单周:第4周不上(双周)", !ranged.activeInWeek(4))
    check("范围+单周:第9周上", ranged.activeInWeek(9))
    check("范围+单周:第10周不上(范围外)", !ranged.activeInWeek(10))

    // ---- 6. 停用 ----
    val disabled = weekly.copy(enabled = false)
    check("停用:第1周也不上", !disabled.activeInWeek(1))

    // ---- 7. 文案 ----
    check("文案:每周", weekly.parityLabel == "每周", weekly.parityLabel)
    check("文案:单周", odd.parityLabel == "单周", odd.parityLabel)
    check("文案:周次范围", ranged.weekLabel == "第3-9周 单周", ranged.weekLabel)

    // ---- 8. 便签:摘要与分享 ----
    val longText = """

        第一行标题
        第二行内容
    """.trimIndent()
    val n1 = Note.fromShare(longText)
    check("便签:标题取第一行", n1.title == "第一行标题", n1.title)
    check("便签:正文保留全文", n1.content.contains("第二行内容"), n1.content)

    val n2 = Note.fromShare("这是一个超过二十个字符的超长第一行标题内容用于测试截断行为")
    check("便签:超长第一行截断到20字", n2.title.length == 20, "${n2.title.length}")

    val n3 = Note(title = "T", content = "\n\n  正文摘要行  \n第二行")
    check("便签:preview 取首个非空行并去空白", n3.preview == "正文摘要行", "'${n3.preview}'")

    val n4 = Note(content = "")
    check("便签:空内容 preview 为空", n4.preview.isEmpty())

    val out = File("result.txt")
    out.parentFile?.mkdirs()
    val header = "总计 ${pass + fail} 用例,通过 $pass,失败 $fail\n" + "=".repeat(60) + "\n"
    out.writeText(
        header + log.toString() + if (fail == 0) "ALL TESTS PASSED\n" else "SOME TESTS FAILED\n",
        Charsets.UTF_8
    )
    println("total=${pass + fail} pass=$pass fail=$fail")
}

