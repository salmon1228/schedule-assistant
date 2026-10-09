import com.sdh.schedule.data.Course
import java.io.File

private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

private fun c(name: String, colorIndex: Int = 0) = Course(name = name, colorIndex = colorIndex)

fun main() {
    val palette = 7

    // ---- 1. 同名 → 同色(用户要求:相同课程颜色一样)----
    run {
        val a = c("高等数学G1").autoColorIndex(palette)
        val b = c("高等数学G1").autoColorIndex(palette)
        check("同名课程颜色一致", a == b, "$a vs $b")
        // 真实场景:同一门课在多个时间段(不同 id / 不同节次),颜色必须一样
        val t1 = Course(name = "线性代数与空间解析几何", weekday = 3, startSlot = 10)
        val t2 = Course(name = "线性代数与空间解析几何", weekday = 7, startSlot = 1)
        check("同课不同时间段颜色一致", t1.autoColorIndex(palette) == t2.autoColorIndex(palette),
            "${t1.autoColorIndex(palette)} vs ${t2.autoColorIndex(palette)}")
    }

    // ---- 2. 颜色落在调色板范围内 ----
    run {
        val names = listOf(
            "高等数学G1", "线性代数与空间解析几何", "C语言程序设计1", "体育-羽毛球",
            "工程识图", "金工实习", "思想道德与法治", "形势与政策1", "英语1",
            "大学生心理健康教育", "电子信息类学科导论", "电子线路实验", "军事理论", "大学物理"
        )
        var bad = 0
        for (n in names) {
            val i = c(n).autoColorIndex(palette)
            if (i < 0 || i >= palette) { bad++; log.append("  越界: $n -> $i\n") }
        }
        check("${names.size} 门课的自动配色都在范围内", bad == 0, "越界 $bad 个")
        val distinct = names.map { c(it).autoColorIndex(palette) }.toSet().size
        log.append("  配色分布:${names.size} 门课用到 $distinct 种颜色\n")
        check("配色有区分度(≥4 种)", distinct >= 4, "只有 $distinct 种")
    }

    // ---- 3. 手动选色优先 ----
    run {
        val manual = c("高等数学G1", colorIndex = 3)
        check("手动选过颜色:用 3", manual.effectiveColorIndex(palette) == 3, "${manual.effectiveColorIndex(palette)}")
        val auto = c("高等数学G1", colorIndex = 0)
        check("没选过(0):自动配色", auto.effectiveColorIndex(palette) == auto.autoColorIndex(palette),
            "${auto.effectiveColorIndex(palette)}")
        val over = c("某课", colorIndex = 99)
        check("手动值越界:被夹到范围内", over.effectiveColorIndex(palette) in 0 until palette,
            "${over.effectiveColorIndex(palette)}")
    }

    // ---- 4. 空名字不崩 ----
    run {
        val i = c("").autoColorIndex(palette)
        check("空名字也能取色且不崩", i in 0 until palette, "$i")
        check("调色板只有 1 色时不崩", c("任意").autoColorIndex(1) == 0)
    }

    // ---- 5. 本周判定(非本周要半透明,靠这个函数)----
    run {
        val odd = Course(name = "电子线路实验", startWeek = 5, endWeek = 12)
        check("第4周:非本周", !odd.activeInWeek(4))
        check("第5周:本周", odd.activeInWeek(5))
        val single = Course(name = "单周课", weekParity = 1)
        check("单周课:第1周上", single.activeInWeek(1))
        check("单周课:第2周非本周", !single.activeInWeek(2))
        val disabled = Course(name = "停用课", enabled = false)
        check("停用的课:任何周都不上", !disabled.activeInWeek(1))
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

