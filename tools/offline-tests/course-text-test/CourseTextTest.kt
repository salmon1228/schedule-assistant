import com.sdh.schedule.parse.CourseTextParser
import java.io.File

private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

fun main() {
    // ---- 1. 单行标准格式 ----
    run {
        val r = CourseTextParser.parse("高等数学 周一 1-2节 1-16周 A301 张老师")
        check("单行:识别出 1 门", r.size == 1, r.toString())
        val c = r.firstOrNull()
        check("单行:课程名=高等数学", c?.name == "高等数学", c?.name ?: "null")
        check("单行:周一", c?.weekday == 1, "${c?.weekday}")
        check("单行:第1节起连上2节", c?.startSlot == 1 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
        check("单行:1-16周", c?.startWeek == 1 && c?.endWeek == 16, "${c?.startWeek}-${c?.endWeek}")
        check("单行:地点 A301", c?.location == "A301", c?.location ?: "null")
        check("单行:老师 张老师", c?.teacher == "张老师", c?.teacher ?: "null")
    }

    // ---- 2. 星期在前 ----
    run {
        val r = CourseTextParser.parse("周二 3-4节 大学物理 实验楼B203")
        val c = r.firstOrNull()
        check("星期在前:周二", c?.weekday == 2, "${c?.weekday}")
        check("星期在前:第3节起连上2节", c?.startSlot == 3 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
        check("星期在前:课程名=大学物理", c?.name == "大学物理", c?.name ?: "null")
        check("星期在前:地点含实验楼", c?.location?.contains("实验楼") == true, c?.location ?: "null")
    }

    // ---- 3. 单双周 + 周次范围 ----
    run {
        val r = CourseTextParser.parse("周三 5-6节 英语听力 3-9周 单周 外语楼302")
        val c = r.firstOrNull()
        check("单周:parity=1", c?.weekParity == 1, "${c?.weekParity}")
        check("单周:3-9周", c?.startWeek == 3 && c?.endWeek == 9, "${c?.startWeek}-${c?.endWeek}")
        check("单周:第5节起连上2节", c?.startSlot == 5 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
    }

    // ---- 4. 双周 ----
    run {
        val r = CourseTextParser.parse("周五 9-10节 体育 1-16周 双周 体育馆")
        val c = r.firstOrNull()
        check("双周:parity=2", c?.weekParity == 2, "${c?.weekParity}")
        check("双周:第9节起连上2节", c?.startSlot == 9 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
    }

    // ---- 5. 多行课表 ----
    run {
        val text = """
            高等数学 周一 1-2节 1-16周 A301 张老师
            大学物理 周二 3-4节 1-8周 实验楼B203
            英语 周三 5-6节 1-16周 单周 外语楼302
            程序设计 周四 7-8节 1-12周 计算机楼401
        """.trimIndent()
        val r = CourseTextParser.parse(text)
        check("多行:识别出 4 门", r.size == 4, "实际 ${r.size}: ${r.map { it.name }}")
        check("多行:星期各不相同", r.map { it.weekday }.toSet().size == 4, r.map { it.weekday }.toString())
    }

    // ---- 6. 无关文字不误判 ----
    run {
        val r = CourseTextParser.parse("今天天气不错\n请注意查收通知\n2026年9月29日")
        check("无关文字:不抽课程", r.isEmpty(), r.toString())
    }

    // ---- 7. 表头之类不误判 ----
    run {
        val r = CourseTextParser.parse("课程表 周次 时间 节次\n上午 下午 晚上")
        check("表头:不抽课程", r.isEmpty(), r.toString())
    }

    // ---- 8. 没有节次信息时给默认 ----
    run {
        val r = CourseTextParser.parse("形势与政策 周五 1-16周 A101")
        val c = r.firstOrNull()
        check("无节次:仍能识别出课程", c != null, r.toString())
        check("无节次:默认第1节连上2节", c?.startSlot == 1 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
    }

    // ---- 9. 只有一门课时也要正确 ----
    run {
        val r = CourseTextParser.parse("军事理论 周日 11-12节 1-4周 阶梯教室")
        val c = r.firstOrNull()
        check("周日:weekday=7", c?.weekday == 7, "${c?.weekday}")
        check("第11节起连上2节", c?.startSlot == 11 && c?.slotCount == 2, "${c?.startSlot}/${c?.slotCount}")
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

