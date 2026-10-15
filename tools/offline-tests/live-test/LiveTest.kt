import com.sdh.schedule.live.QuestionDetector
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
    // ---- 1. 疑问句要能认出来 ----
    run {
        val yes = listOf(
            "这道题的答案是什么",
            "为什么这里要用递归",
            "怎么做才能把复杂度降到 O(n)",
            "请问第三题的第二个条件是什么意思",
            "这个结论对吗?",
            "什么情况下会死锁",
            "数组和链表的区别有哪些",
            "这句话是错的吧,对不对"
        )
        for (q in yes) check("问句:「$q」", QuestionDetector.isQuestion(q), "被判成非问句")
    }

    // ---- 2. 陈述句/口头语不能误判(否则每次都白花一次大模型调用)----
    run {
        val no = listOf(
            "我们来看下一章的内容",
            "这个公式请大家记一下",
            "好,现在开始上课",
            "我来点名,听到的答个到",       // 点名不该当成问题
            "大家能听见我说话吗",           // 设备检查类
            "嗯",                          // 太短
            "123",                         // 纯数字
            "下面我讲三个重点"
        )
        for (q in no) check("非问句:「$q」", !QuestionDetector.isQuestion(q), "被误判成问句")
    }

    // ---- 3. 实时转写会反复吐同一句,不能重复提问 ----
    run {
        val asked = listOf("什么是快速排序")
        val text = "什么是快速排序。那什么是快速排序呢"
        val fresh = QuestionDetector.pickNewQuestions(text, asked)
        check("已经问过的句子不再重复问", fresh.none { it.contains("快速排序") }, fresh.toString())
    }

    run {
        val fresh = QuestionDetector.pickNewQuestions("什么是快速排序。那什么是快速排序呢", emptyList())
        check("同一句在一次里也只算一次", fresh.size == 1, "实际 ${fresh.size}: $fresh")
    }

    // ---- 4. 相似度去重 ----
    run {
        check("同一句判为相似", QuestionDetector.similar("为什么这里要加锁", "为什么这里要加锁?"))
        check("包含关系判为相似", QuestionDetector.similar("死锁的四个条件", "请问死锁的四个条件是什么"))
        check("不同问题不相似", !QuestionDetector.similar("什么是快速排序", "进程和线程的区别"))
    }

    // ---- 5. 一句话里多个问题都要抓到 ----
    run {
        val text = "什么是进程?为什么要用线程?线程和进程的区别有哪些?"
        val fresh = QuestionDetector.pickNewQuestions(text, emptyList())
        check("一句话里的多个问题都抓到", fresh.size >= 2, "实际 ${fresh.size}: $fresh")
    }

    // ---- 6. 断句 ----
    run {
        val s = QuestionDetector.splitSentences("第一句。第二句!第三句?第四句\n第五句")
        check("按中英文标点断句", s.size == 5, "实际 ${s.size}: $s")
    }

    // ---- 7. 太长的不当问题(多半是在念材料)----
    run {
        val long = "这是一段很长的材料" + "内容包括很多细节".repeat(8) + "请问这样对吗"
        val fresh = QuestionDetector.pickNewQuestions(long, emptyList())
        check("超长句不当成问题", fresh.isEmpty(), fresh.toString())
    }

    val out = File("result.txt")
    val header = "总计 ${pass + fail} 用例,通过 $pass,失败 $fail\n" + "=".repeat(60) + "\n"
    out.writeText(
        header + log + if (fail == 0) "ALL TESTS PASSED\n" else "SOME TESTS FAILED\n",
        Charsets.UTF_8
    )
    println("total=${pass + fail} pass=$pass fail=$fail")
}
