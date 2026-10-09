import com.sdh.schedule.parse.TodoExtractor
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
private val now: Long = LocalDateTime.of(2026, 9, 19, 14, 0).atZone(zone).toInstant().toEpochMilli()
private val log = StringBuilder()
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; log.append("[PASS] ").append(name) }
    else { fail++; log.append("[FAIL] ").append(name).append("  -> ").append(detail) }
    log.append('\n')
}

private fun titles(text: String): List<String> = TodoExtractor.extract(text, now, zone).map { it.title }

fun main() {
    // ---- 1. 用户给的原话(核心用例) ----
    run {
        val text = "明天会发一张表格下来需要我们填写，准备好一寸照片"
        val got = titles(text)
        log.append("输入: ").append(text).append('\n')
        log.append("抽出: ").append(got.joinToString(" | ")).append('\n')
        check("原话:抽出了待办", got.isNotEmpty(), got.toString())
        check(
            "原话:包含「准备一寸照片」相关项",
            got.any { it.contains("一寸照片") },
            got.toString()
        )
    }

    // ---- 2. 只有"记得…" ----
    run {
        val got = titles("记得把身份证带上")
        check("记得:抽出待办", got.any { it.contains("身份证") }, got.toString())
    }

    // ---- 3. 有明确时间的句子不该变待办 ----
    run {
        val got = titles("明天下午3点在A301开会")
        check("有时间的日程:不抽成待办", got.isEmpty(), got.toString())
    }

    // ---- 4. 日程 + 待办 同时存在 ----
    run {
        val got = titles("明天上午9点开会。记得打印三份材料")
        check("混合文本:抽出「打印」待办", got.any { it.contains("打印") }, got.toString())
        check("混合文本:开会不抽成待办", got.none { it.contains("开会") }, got.toString())
    }

    // ---- 5. 重复句去重 ----
    run {
        val got = titles("准备好一寸照片，准备好一寸照片")
        check("重复句:只留一条", got.size == 1, got.toString())
    }

    // ---- 6. 太短的片段忽略 ----
    run {
        val got = titles("记得")
        check("太短:不抽", got.isEmpty(), got.toString())
    }

    // ---- 7. 截止时间推断 ----
    run {
        val items = TodoExtractor.extract("明天之前记得交材料", now, zone)
        if (items.isEmpty()) {
            check("截止推断:抽出待办", false, "empty")
        } else {
            val due = items.first().dueMillis
            val dueDate = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(due), zone).toLocalDate()
            check("截止推断:明天(9-20)", dueDate.toString() == "2026-09-20", dueDate.toString())
        }
    }

    // ---- 8. 通知类句子不误判 ----
    run {
        val got = titles("[群通知]今天的课程已发布，请注意查收")
        check("通知口吻:不抽成待办(或至少不含课程)", got.none { it.contains("课程") }, got.toString())
    }

    // ---- 9. 纯闲聊不误判 ----
    run {
        val got = titles("今天天气不错啊")
        check("闲聊:不抽", got.isEmpty(), got.toString())
    }

    // ---- 10. 营销/预警文案不误判(用户真机上被误抽的真实例子) ----
    run {
        val noise = listOf(
            "确认收货（如未收到可延长收货或联系商家）",
            "确认商品收货（48小时后自动确认）",
            "注意防范雷电，尽量避免户外活动",
            "注意防范雷暴大风（今天傍晚到前半夜）",
            "尽量减少私家车自驾前往",
            "您有一张优惠券即将过期，记得使用",
            "点击查看详情，立即参与活动",
            "限时特价，错过再等一年",
            "您的账单已出，请及时查看",
            "存储空间不足，建议清理垃圾"
        )
        var bad = 0
        for (t in noise) {
            val got = titles(t)
            if (got.isNotEmpty()) {
                bad++
                log.append("  [噪声漏网] 「").append(t).append("」 → ").append(got.joinToString(" | ")).append('\n')
            }
        }
        check("营销/预警文案全部被过滤(${noise.size} 条)", bad == 0, "漏了 $bad 条")
    }

    // ---- 11. 真待办仍然要抽得到(不能过滤过头) ----
    run {
        val real = listOf(
            "明天会发一张表格下来需要我们填写，准备好一寸照片" to "一寸照片",
            "记得把身份证带上" to "身份证",
            "请假需补家长知情同意书拍照发给赵极瀚" to "家长知情同意书",
            "登记国庆假期离返校情况" to "离返校",
            "检查并停用无3C认证的充电宝及其他电器" to "充电宝"
        )
        var missed = 0
        for ((text, key) in real) {
            val got = titles(text)
            if (got.none { it.contains(key) }) {
                missed++
                log.append("  [该抽没抽] 「").append(text).append("」 → ").append(got.joinToString(" | ")).append('\n')
            }
        }
        check("真待办仍然抽得到(${real.size} 条)", missed == 0, "漏了 $missed 条")
    }

    // ---- 12. 一条消息最多抽 2 条 ----
    run {
        val got = titles("记得带身份证；准备好两张照片；打印报名表；记得交材料；记得盖章")
        check("单条上限 ≤ 2", got.size <= 2, got.toString())
    }

    // ---- 10. 多件待办 ----
    run {
        val got = titles("记得带身份证；准备好两张照片；打印报名表")
        check("多件待办:至少 2 条", got.size >= 2, got.toString())
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

