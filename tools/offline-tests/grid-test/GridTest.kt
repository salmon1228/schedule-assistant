import com.sdh.schedule.parse.TimetableGridParser
import com.sdh.schedule.parse.TimetableGridParser.Block
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
 * 造一张"课表网格"的坐标数据,模拟 OCR 出来的文字块。
 * 布局:表头 y=40(星期一…星期日),左侧节次列 x=20(第1节…第6节),每格 200x150。
 */
private fun cell(col: Int, row: Int, text: String): Block {
    val x = 60 + col * 200
    val y = 100 + row * 150
    return Block(text = text, left = x, top = y, right = x + 180, bottom = y + 120)
}

private fun header(col: Int, name: String) = Block(name, 60 + col * 200, 20, 60 + col * 200 + 180, 70, )
private fun slotRow(row: Int, name: String) = Block(name, 5, 100 + row * 150, 55, 100 + row * 150 + 120)

private fun buildBasicGrid(): List<Block> {
    val b = ArrayList<Block>()
    // 表头(星期一 … 星期日)
    val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    names.forEachIndexed { i, n -> b.add(header(i, n)) }
    // 节次列
    (1..6).forEach { r -> b.add(slotRow(r - 1, "第${r}节")) }
    // 周一 1-2 节:高等数学(一个跨两行的格子 → 两个文字块)
    b.add(cell(0, 0, "高等数学G1"))
    b.add(cell(0, 0, "1101 邵牧好 1-16周"))
    // 周三 3 节:线性代数(单行)
    b.add(cell(2, 2, "线性代数与空间解析几何 马铭阳 B101 1-16周"))
    // 周五 5 节:体育
    b.add(cell(4, 4, "体育-羽毛球 徐锐 体育馆2楼 1-16周"))
    return b
}

fun main() {
    // ---- 1. 基本网格:星期/节次是否落对 ----
    run {
        val r = TimetableGridParser.parse(buildBasicGrid())
        log.append("识别结果: ").append(r.map { "周${it.weekday}第${it.startSlot}节:${it.name}" }.joinToString(" | ")).append('\n')
        check("网格:识别出 3 门课", r.size == 3, "实际 ${r.size}")
        val math = r.firstOrNull { it.name.contains("高等数学") }
        check("高等数学 -> 周一", math?.weekday == 1, "weekday=${math?.weekday}")
        check("高等数学 -> 第1节", math?.startSlot == 1, "slot=${math?.startSlot}")
        check("高等数学 -> 老师 邵牧好", math?.teacher == "邵牧好", math?.teacher ?: "null")
        check("高等数学 -> 地点 1101", math?.location == "1101", math?.location ?: "null")

        val linear = r.firstOrNull { it.name.contains("线性代数") }
        check("线性代数 -> 周三", linear?.weekday == 3, "weekday=${linear?.weekday}")
        check("线性代数 -> 第3节", linear?.startSlot == 3, "slot=${linear?.startSlot}")
        check("线性代数 -> 地点 B101", linear?.location == "B101", linear?.location ?: "null")

        val pe = r.firstOrNull { it.name.contains("体育") }
        check("体育 -> 周五", pe?.weekday == 5, "weekday=${pe?.weekday}")
        check("体育 -> 第5节", pe?.startSlot == 5, "slot=${pe?.startSlot}")
    }

    // ---- 2. 跨行的格子 → 连上几节 ----
    run {
        val b = ArrayList<Block>()
        listOf("星期一", "星期二", "星期三").forEachIndexed { i, n -> b.add(header(i, n)) }
        (1..6).forEach { r -> b.add(slotRow(r - 1, "第${r}节")) }
        // 一个高块(占 2 行高度)
        b.add(Block("大学物理\n实验楼B203 1-8周", 60, 100, 240, 100 + 150 * 2 - 30))
        val r = TimetableGridParser.parse(b)
        val c = r.firstOrNull()
        check("跨行:识别出 1 门", r.size == 1, "实际 ${r.size}")
        check("跨行:第1节起", c?.startSlot == 1, "slot=${c?.startSlot}")
        check("跨行:连上 2 节", c?.slotCount == 2, "span=${c?.slotCount}")
        check("跨行:地点 实验楼B203", c?.location?.contains("实验楼") == true, c?.location ?: "null")
    }

    // ---- 3. 单双周 ----
    run {
        val b = ArrayList<Block>()
        listOf("星期一", "星期二", "星期三").forEachIndexed { i, n -> b.add(header(i, n)) }
        (1..6).forEach { r -> b.add(slotRow(r - 1, "第${r}节")) }
        b.add(cell(1, 1, "英语听力 3-9周 单周 外语楼302"))
        val r = TimetableGridParser.parse(b)
        val c = r.firstOrNull()
        check("单双周:parity=1", c?.weekParity == 1, "parity=${c?.weekParity}")
        check("单双周:3-9周", c?.startWeek == 3 && c?.endWeek == 9, "${c?.startWeek}-${c?.endWeek}")
        check("单双周:周二第2节", c?.weekday == 2 && c?.startSlot == 2, "周${c?.weekday}第${c?.startSlot}节")
    }

    // ---- 4. 时间行代替节次行(08:00 这种)也不能崩 ----
    run {
        val b = ArrayList<Block>()
        listOf("星期一", "星期二", "星期三").forEachIndexed { i, n -> b.add(header(i, n)) }
        listOf("08:00", "08:55", "10:00", "10:55", "14:00").forEachIndexed { r, t -> b.add(slotRow(r, t)) }
        b.add(cell(0, 0, "高等数学 A301 张老师"))
        val r = TimetableGridParser.parse(b)
        check("时间行:仍能识别出课程", r.isNotEmpty(), "空")
    }

    // ---- 5. 无关图片不误判(没有星期表头) ----
    run {
        val b = listOf(
            Block("今天天气不错", 10, 10, 200, 40),
            Block("会议纪要", 10, 100, 200, 130),
            Block("2026年9月", 10, 200, 200, 230),
            Block("打篮球", 10, 300, 200, 330),
            Block("吃饭", 10, 400, 200, 430),
            Block("睡觉", 10, 500, 200, 530)
        )
        val r = TimetableGridParser.parse(b)
        check("无课表结构:不误判", r.isEmpty(), r.toString())
    }

    // ---- 6. 同一门课重复画在相邻格子 → 合并 ----
    run {
        val b = ArrayList<Block>()
        listOf("星期一", "星期二", "星期三").forEachIndexed { i, n -> b.add(header(i, n)) }
        (1..6).forEach { r -> b.add(slotRow(r - 1, "第${r}节")) }
        b.add(cell(0, 0, "高等数学 1101"))
        b.add(cell(0, 1, "高等数学 1101"))
        val r = TimetableGridParser.parse(b)
        val c = r.firstOrNull()
        check("重复格:合并成 1 门", r.size == 1, "实际 ${r.size}")
        check("重复格:连上 2 节", c?.slotCount == 2, "span=${c?.slotCount}")
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

