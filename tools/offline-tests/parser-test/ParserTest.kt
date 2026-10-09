import com.sdh.schedule.parse.ChineseDateParser
import com.sdh.schedule.parse.ParsedEvent
import com.sdh.schedule.parse.RepeatKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

// ---------------------------------------------------------------------------
// 固定测试基准：now = 2026-09-13T21:31+08:00（星期日），绝不使用系统当前时间
// ---------------------------------------------------------------------------
private val ZONE = ZoneId.of("Asia/Shanghai")
private val ZONE_NY = ZoneId.of("America/New_York")
private val NOW: Long = ZonedDateTime.of(2026, 9, 13, 21, 31, 0, 0, ZONE).toInstant().toEpochMilli()

private val log = StringBuilder()
private var passCount = 0
private var failCount = 0
private var caseNo = 0

private fun startStr(e: ParsedEvent?, z: ZoneId = ZONE): String =
    if (e == null) "null" else Instant.ofEpochMilli(e.startMillis).atZone(z).toLocalDateTime().toString()

private fun endStr(e: ParsedEvent?, z: ZoneId = ZONE): String =
    if (e == null) "null" else Instant.ofEpochMilli(e.endMillis).atZone(z).toLocalDateTime().toString()

private fun describe(e: ParsedEvent?): String {
    if (e == null) return "null"
    val t = if (e.allDay) "${startStr(e)} 全天(至 ${endStr(e)})" else "${startStr(e)} ~ ${endStr(e)}"
    val rep = if (e.repeatWeekday != 0) "${e.repeat}(${e.repeatWeekday})" else "${e.repeat}"
    return "时间=$t | 标题=${e.title} | 地点=${e.location.ifEmpty { "-" }} | 重复=$rep | 提醒=${e.reminderMinutesBefore} | conf=${e.confidence} | 备注=${e.notes.ifEmpty { "-" }} | 命中=${e.matched}"
}

private class Checker(val label: String, val text: String, val ev: ParsedEvent?) {
    private val problems = ArrayList<String>()

    fun isNull(): Checker {
        if (ev != null) problems.add("期望 null，实际=${describe(ev)}")
        return this
    }

    fun notNull(): Checker {
        if (ev == null) problems.add("期望非 null，实际=null")
        return this
    }

    fun start(exp: String): Checker {
        if (ev == null) problems.add("start 期望=$exp 实际=null（解析为 null）")
        else if (startStr(ev) != exp) problems.add("start 期望=$exp 实际=${startStr(ev)}")
        return this
    }

    fun end(exp: String): Checker {
        if (ev == null) problems.add("end 期望=$exp 实际=null（解析为 null）")
        else if (endStr(ev) != exp) problems.add("end 期望=$exp 实际=${endStr(ev)}")
        return this
    }

    fun title(exp: String): Checker {
        if (ev == null) problems.add("标题 期望=$exp 实际=null")
        else if (ev.title != exp) problems.add("标题 期望=$exp 实际=${ev.title}")
        return this
    }

    fun titleHas(sub: String): Checker {
        if (ev == null) problems.add("标题应含「$sub」实际=null")
        else if (!ev.title.contains(sub)) problems.add("标题应含「$sub」实际=${ev.title}")
        return this
    }

    fun titleNotHas(sub: String): Checker {
        if (ev != null && ev.title.contains(sub)) problems.add("标题不应含「$sub」实际=${ev.title}")
        return this
    }

    fun titleLen(max: Int): Checker {
        if (ev != null && ev.title.length > max) problems.add("标题长度应<=$max 实际=${ev.title.length}")
        return this
    }

    fun location(exp: String): Checker {
        if (ev == null) problems.add("地点 期望=$exp 实际=null")
        else if (ev.location != exp) problems.add("地点 期望=$exp 实际=${ev.location.ifEmpty { "(空)" }}")
        return this
    }

    fun repeat(exp: RepeatKind): Checker {
        if (ev == null) problems.add("重复 期望=$exp 实际=null")
        else if (ev.repeat != exp) problems.add("重复 期望=$exp 实际=${ev.repeat}")
        return this
    }

    fun weekday(exp: Int): Checker {
        if (ev == null) problems.add("repeatWeekday 期望=$exp 实际=null")
        else if (ev.repeatWeekday != exp) problems.add("repeatWeekday 期望=$exp 实际=${ev.repeatWeekday}")
        return this
    }

    fun reminder(exp: Int): Checker {
        if (ev == null) problems.add("提醒 期望=$exp 实际=null")
        else if (ev.reminderMinutesBefore != exp) problems.add("提醒 期望=$exp 实际=${ev.reminderMinutesBefore}")
        return this
    }

    fun allDay(exp: Boolean): Checker {
        if (ev == null) problems.add("allDay 期望=$exp 实际=null")
        else if (ev.allDay != exp) problems.add("allDay 期望=$exp 实际=${ev.allDay}")
        return this
    }

    fun conf(exp: Double): Checker {
        if (ev == null) problems.add("confidence 期望=$exp 实际=null")
        else if (ev.confidence != exp) problems.add("confidence 期望=$exp 实际=${ev.confidence}")
        return this
    }

    fun confIn(lo: Double, hi: Double): Checker {
        if (ev == null) problems.add("confidence 应在 $lo..$hi 实际=null")
        else if (ev.confidence < lo || ev.confidence > hi) problems.add("confidence 应在 $lo..$hi 实际=${ev.confidence}")
        return this
    }

    fun notesHas(sub: String): Checker {
        if (ev == null) problems.add("备注应含「$sub」实际=null")
        else if (!ev.notes.contains(sub)) problems.add("备注应含「$sub」实际=${ev.notes.ifEmpty { "(空)" }}")
        return this
    }

    fun notesEmpty(): Checker {
        if (ev != null && ev.notes.isNotEmpty()) problems.add("备注应为空 实际=${ev.notes}")
        return this
    }

    fun matchedHas(sub: String): Checker {
        if (ev == null) problems.add("matched 应含「$sub」实际=null")
        else if (ev.matched.none { it.contains(sub) }) problems.add("matched 应含「$sub」实际=${ev.matched}")
        return this
    }

    fun source(exp: String): Checker {
        if (ev == null) problems.add("sourceText 期望=$exp 实际=null")
        else if (ev.sourceText != exp) problems.add("sourceText 期望=$exp 实际=${ev.sourceText}")
        return this
    }

    fun report() {
        caseNo++
        val ok = problems.isEmpty()
        if (ok) passCount++ else failCount++
        log.append(if (ok) "[PASS] " else "[FAIL] ").append(caseNo).append(" ").append(label).append('\n')
        log.append("       输入: ").append(text).append('\n')
        log.append("       输出: ").append(describe(ev)).append('\n')
        if (!ok) {
            for (p in problems) log.append("       !! ").append(p).append('\n')
        }
    }
}

private fun check(label: String, text: String, block: Checker.() -> Unit) {
    val ev = ChineseDateParser.parse(text, NOW, ZONE)
    val c = Checker(label, text, ev)
    c.block()
    c.report()
}

private fun checkAll(label: String, text: String, block: (List<ParsedEvent>, StringBuilder) -> Unit) {
    caseNo++
    val list = ChineseDateParser.parseAll(text, NOW, ZONE)
    val problems = StringBuilder()
    block(list, problems)
    val ok = problems.isEmpty()
    if (ok) passCount++ else failCount++
    log.append(if (ok) "[PASS] " else "[FAIL] ").append(caseNo).append(" ").append(label).append('\n')
    log.append("       输入: ").append(text.replace("\n", "\\n")).append('\n')
    log.append("       输出: ").append(list.size).append(" 个事件\n")
    for (e in list) log.append("         - ").append(describe(e)).append('\n')
    if (!ok) for (p in problems.toString().split('\n')) if (p.isNotEmpty()) log.append("       !! ").append(p).append('\n')
}

fun main() {
    // 故意把 JVM 默认时区设成 UTC：如果引擎偷偷用了 ZoneId.systemDefault()，断言必然失败
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    log.append("中文日程解析引擎自测报告\n")
    log.append("固定 now = 2026-09-13T21:31+08:00 (星期日)，测试时区 = ").append(ZONE).append('\n')
    log.append("JVM 默认时区被强制设为 = ").append(TimeZone.getDefault().id).append('\n')
    log.append("=".repeat(100)).append('\n')

    // ---------------- 题目要求的 10 个用例 ----------------
    check("C01 明天14:00开会", "明天14:00开会") {
        notNull().start("2026-09-14T14:00").end("2026-09-14T15:00").title("开会")
            .allDay(false).repeat(RepeatKind.NONE).weekday(0).location("").reminder(-1).conf(0.90)
    }
    check("C02 明天下午3点半和张总在望京SOHO吃饭", "明天下午3点半和张总在望京SOHO吃饭") {
        notNull().start("2026-09-14T15:30").end("2026-09-14T16:30").location("望京SOHO")
            .titleHas("张总").titleHas("吃饭").conf(0.90)
    }
    check("C03 下周三上午10点 部门周会 提前15分钟提醒", "下周三上午10点 部门周会 提前15分钟提醒") {
        notNull().start("2026-09-16T10:00").end("2026-09-16T11:00").reminder(15)
            .repeat(RepeatKind.NONE).title("部门周会").conf(0.85)
    }
    check("C04 2026年9月13日 21:30 到 22:30 项目评审", "2026年9月13日 21:30 到 22:30 项目评审") {
        notNull().start("2026-09-13T21:30").end("2026-09-13T22:30").title("项目评审").conf(0.95)
    }
    check("C05 每周一 9:00 晨会", "每周一 9:00 晨会") {
        notNull().repeat(RepeatKind.WEEKLY).weekday(1).start("2026-09-14T09:00").end("2026-09-14T10:00")
            .title("晨会").conf(0.85)
    }
    check("C06 后天中午12点半体检", "后天中午12点半体检") {
        notNull().start("2026-09-15T12:30").end("2026-09-15T13:30").title("体检").conf(0.90)
    }
    check("C07 10月1日 国庆假期", "10月1日 国庆假期") {
        notNull().allDay(true).start("2026-10-01T00:00").end("2026-10-01T23:59:59.999")
            .titleHas("国庆").conf(0.68)
    }
    check("C08 3天后 15:00 交材料", "3天后 15:00 交材料") {
        notNull().start("2026-09-16T15:00").end("2026-09-16T16:00").title("交材料").conf(0.95)
    }
    check("C09 周五下午2点到4点 面试", "周五下午2点到4点 面试") {
        notNull().start("2026-09-18T14:00").end("2026-09-18T16:00").title("面试").conf(0.85)
    }
    check("C10 完全无时间信息 → null", "文本里完全没有任何时间信息的一句话") {
        isNull()
    }

    // ---------------- 题目额外点名要求 ----------------
    check("E01 每月5号 还信用卡", "每月5号 还信用卡") {
        notNull().repeat(RepeatKind.MONTHLY).allDay(true).start("2026-10-05T00:00")
            .title("还信用卡").conf(0.65)
    }
    check("E02 今晚11点到凌晨1点 赶工", "今晚11点到凌晨1点 赶工") {
        notNull().start("2026-09-13T23:00").end("2026-09-14T01:00").title("赶工").conf(0.90)
    }
    check("E03 明早 8点半 出发", "明早 8点半 出发") {
        notNull().start("2026-09-14T08:30").end("2026-09-14T09:30").title("出发").conf(0.90)
    }

    // ---------------- 时间表达 ----------------
    check("T01 12 小时制换算：晚上11点", "晚上11点提醒我吃药") {
        notNull().start("2026-09-13T23:00").reminder(-1).title("吃药").conf(0.60)
    }
    check("T02 上午12点 → 00:00", "上午12点交班") {
        notNull().start("2026-09-14T00:00").end("2026-09-14T01:00").conf(0.60)
    }
    check("T03 中午12点 → 12:00", "明天中午12点吃饭") {
        notNull().start("2026-09-14T12:00").title("吃饭")
    }
    check("T04 凌晨1点 → 01:00", "凌晨1点看球") {
        notNull().start("2026-09-14T01:00").conf(0.60)
    }
    check("T05 下午3点（无日期，已过则明天）", "下午3点开会") {
        notNull().start("2026-09-14T15:00").conf(0.60).title("开会")
    }
    check("T06 15:00（无日期，已过则明天）", "15:00开会") {
        notNull().start("2026-09-14T15:00").conf(0.60)
    }
    check("T07 9点一刻", "9点一刻开会") {
        notNull().start("2026-09-14T09:15")
    }
    check("T08 9点三刻", "9点三刻开会") {
        notNull().start("2026-09-14T09:45")
    }
    check("T09 9点30分", "明天9点30分 汇报") {
        notNull().start("2026-09-14T09:30").title("汇报")
    }
    check("T10 19点", "明天19点 散步") {
        notNull().start("2026-09-14T19:00").title("散步")
    }
    check("T11 14:00:00", "明天14:00:00 签到") {
        notNull().start("2026-09-14T14:00").title("签到")
    }
    check("T12 早上8点15", "明天早上8点15 出发") {
        notNull().start("2026-09-14T08:15")
    }
    check("T13 9点到10点半", "明天9点到10点半 面试") {
        notNull().start("2026-09-14T09:00").end("2026-09-14T10:30")
    }
    check("T14 14:00-15:30 横杠区间", "明天14:00-15:30 项目评审") {
        notNull().start("2026-09-14T14:00").end("2026-09-14T15:30")
    }
    check("T15 晚上10点到12点（跨到次日 00:00）", "明天晚上10点到12点 值班") {
        notNull().start("2026-09-14T22:00").end("2026-09-15T00:00")
    }
    check("T16 持续2小时", "明天下午3点开会 持续2小时") {
        notNull().start("2026-09-14T15:00").end("2026-09-14T17:00").title("开会")
    }
    check("T17 1个半小时", "明天下午3点开会 1个半小时") {
        notNull().start("2026-09-14T15:00").end("2026-09-14T16:30")
    }
    check("T18 90分钟", "明天上午10点开会 90分钟") {
        notNull().start("2026-09-14T10:00").end("2026-09-14T11:30")
    }
    check("T19 半小时", "明天上午10点开会 半小时") {
        notNull().start("2026-09-14T10:00").end("2026-09-14T10:30")
    }
    check("T20 两个小时", "明天上午10点开会 两个小时") {
        notNull().start("2026-09-14T10:00").end("2026-09-14T12:00")
    }
    check("T21 傍晚默认18:00", "明天傍晚 遛狗") {
        notNull().start("2026-09-14T18:00").conf(0.45).title("遛狗")
    }
    check("T22 模糊时段「明天下午」", "明天下午 开会") {
        notNull().start("2026-09-14T14:00").conf(0.45).title("开会")
    }
    check("T23 模糊时段「下午」（已过则明天）", "下午 开会") {
        notNull().start("2026-09-14T14:00").conf(0.45)
    }
    check("T24 明晚默认19:00", "明晚 看电影") {
        notNull().start("2026-09-14T19:00").end("2026-09-14T20:00").conf(0.45).title("看电影")
    }
    check("T25 今晚默认19:00（今天已过也保持今天）", "今晚 聚餐") {
        notNull().start("2026-09-13T19:00").conf(0.45).title("聚餐")
    }
    check("T26 中午默认12:00（无日期，已过则明天）", "中午 吃饭") {
        notNull().start("2026-09-14T12:00").conf(0.45)
    }
    check("T27 上午默认09:00（无日期，已过则明天）", "上午 开会") {
        notNull().start("2026-09-14T09:00").conf(0.45)
    }
    check("T28 晚上默认19:00（无日期，已过则明天）", "晚上 跑步") {
        notNull().start("2026-09-14T19:00").conf(0.45)
    }
    check("T29 凌晨默认06:00（无日期，已过则明天）", "凌晨 出发") {
        notNull().start("2026-09-14T06:00").conf(0.45)
    }
    check("T30 星期1 写法", "星期1 9:00 开会") {
        notNull().start("2026-09-14T09:00").conf(0.85)
    }
    check("T31 礼拜天 写法（今天就是周日 → 今天）", "礼拜天 15:00 打球") {
        notNull().start("2026-09-13T15:00").conf(0.85)
    }
    check("T32 下个星期三 写法", "下个星期三 9点 会议") {
        notNull().start("2026-09-16T09:00").conf(0.85)
    }
    check("T33 下下周 裸写法", "下下周 9点 开会") {
        notNull().start("2026-09-21T09:00")
    }
    check("T34 下下周周一", "下下周一 9点 开会") {
        notNull().start("2026-09-21T09:00").conf(0.85)
    }
    check("T35 周末（今天是周日 → 下一个周六）", "周末 15:00 看展") {
        notNull().start("2026-09-19T15:00").conf(0.85)
    }
    check("T36 地点是XXX", "明天10点 地点是公司 开会") {
        notNull().location("公司").title("开会")
    }
    check("T37 N天以后 写法", "5天以后 8点 出发") {
        notNull().start("2026-09-18T08:00").conf(0.95)
    }
    check("T38 每日 写法", "每日 18:00 复盘") {
        notNull().repeat(RepeatKind.DAILY).start("2026-09-14T18:00").conf(0.88)
    }
    check("T39 空白文本 → null", "   ") {
        isNull()
    }

    // ---------------- 日期表达 ----------------
    check("D01 今天（时刻已过也保持今天）", "今天下午3点开会") {
        notNull().start("2026-09-13T15:00").title("开会")
    }
    check("D02 大后天", "大后天 10点 开会") {
        notNull().start("2026-09-16T10:00").conf(0.90)
    }
    check("D03 2026-09-20 短横线日期", "2026-09-20 10:00 体检") {
        notNull().start("2026-09-20T10:00").conf(0.95)
    }
    check("D04 9/20 斜杠日期", "9/20 15:30 会议") {
        notNull().start("2026-09-20T15:30").conf(0.95)
    }
    check("D05 9月13号（今天）", "9月13号 19:00 加班") {
        notNull().start("2026-09-13T19:00").conf(0.95)
    }
    check("D06 下个月3号", "下个月3号 交房租") {
        notNull().allDay(true).start("2026-10-03T00:00").title("交房租").conf(0.68)
    }
    check("D07 两天后（中文数字）", "两天后 9点 出发") {
        notNull().start("2026-09-15T09:00").conf(0.95)
    }
    check("D08 2周后", "2周后 10点 复盘") {
        notNull().start("2026-09-27T10:00")
    }
    check("D09 2小时后", "2小时后 打电话") {
        notNull().start("2026-09-13T23:31").end("2026-09-14T00:31").title("打电话").conf(0.95)
    }
    check("D10 30分钟后", "30分钟后 开会") {
        notNull().start("2026-09-13T22:01")
    }
    check("D11 裸「周六」= 下一个周六", "周六 19:00 电影") {
        notNull().start("2026-09-19T19:00").conf(0.85)
    }
    check("D12 裸「周日」= 今天（就是周日）", "周日 20:00 聚餐") {
        notNull().start("2026-09-13T20:00").conf(0.85)
    }
    check("D13 下周日", "下周日 14:00 羽毛球") {
        notNull().start("2026-09-20T14:00").conf(0.85)
    }
    check("D14 下周五", "下周五 18:00 聚餐") {
        notNull().start("2026-09-18T18:00")
    }
    check("D15 昨天 → null", "昨天15:00开会") {
        isNull()
    }
    check("D16 上周三 → null", "上周三 10点开会") {
        isNull()
    }
    check("D17 明年不支持的「本周三」按过去处理", "本周三 10点开会") {
        notNull().start("2026-09-16T10:00")
    }
    check("D18 只有日期无时刻 → 全天", "明天 交材料") {
        notNull().allDay(true).start("2026-09-14T00:00").end("2026-09-14T23:59:59.999").conf(0.65)
    }

    // ---------------- 重复 ----------------
    check("R01 每天", "每天 7:00 跑步") {
        notNull().repeat(RepeatKind.DAILY).start("2026-09-14T07:00").conf(0.88)
    }
    check("R02 每个工作日", "每个工作日 8:30 打卡") {
        notNull().repeat(RepeatKind.WEEKDAYS).start("2026-09-14T08:30").conf(0.88)
    }
    check("R03 周一至周五", "周一至周五 9:00 上班") {
        notNull().repeat(RepeatKind.WEEKDAYS).start("2026-09-14T09:00")
    }
    check("R04 每周三", "每周三上午9点 例会") {
        notNull().repeat(RepeatKind.WEEKLY).weekday(3).start("2026-09-16T09:00")
    }
    check("R05 每年3月5日", "每年3月5日 年会") {
        notNull().repeat(RepeatKind.YEARLY).allDay(true).start("2027-03-05T00:00").title("年会").conf(0.65)
    }
    check("R06 每月5号上午10点", "每月5号上午10点 还款") {
        notNull().repeat(RepeatKind.MONTHLY).start("2026-10-05T10:00").end("2026-10-05T11:00").conf(0.85)
    }
    check("R07 每逢周一", "每逢周一 8:00 晨跑") {
        notNull().repeat(RepeatKind.WEEKLY).weekday(1).start("2026-09-14T08:00")
    }

    // ---------------- 提醒 ----------------
    check("M01 提前半小时", "明天9点开会 提前半小时提醒") {
        notNull().reminder(30).start("2026-09-14T09:00")
    }
    check("M02 提前1天", "明天9点开会 提前1天提醒") {
        notNull().reminder(1440)
    }
    check("M03 提前2天", "明天9点开会 提前2天提醒") {
        notNull().reminder(2880)
    }
    check("M04 提前一周", "明天9点开会 提前一周提醒") {
        notNull().reminder(10080)
    }
    check("M05 提前1小时", "明天9点开会 提前1小时提醒") {
        notNull().reminder(60)
    }
    check("M06 提前十五分钟（中文数字）", "明天9点开会 提前十五分钟提醒") {
        notNull().reminder(15)
    }
    check("M07 到点提醒 → 0", "明天9点开会 到点提醒") {
        notNull().reminder(0)
    }
    check("M08 准时提醒 → 0", "明天9点开会 准时提醒") {
        notNull().reminder(0)
    }
    check("M09 只说「记得提醒我」→ -1", "明天8点 记得提醒我买牛奶") {
        notNull().reminder(-1).title("买牛奶").start("2026-09-14T08:00")
    }
    check("M10 没说提醒 → -1", "明天9点开会") {
        notNull().reminder(-1)
    }

    // ---------------- 地点 / 标题 / 备注 ----------------
    check("L01 在会议室301", "明天10点在会议室301开会") {
        notNull().location("会议室301").title("开会")
    }
    check("L02 地点:望京SOHO", "明天10点 地点:望京SOHO 开会") {
        notNull().location("望京SOHO").title("开会")
    }
    check("L03 @公司", "明天10点 @公司 开会") {
        notNull().location("公司").title("开会")
    }
    check("L04 于北大东门", "明天10点于北大东门集合") {
        notNull().location("北大东门").title("集合")
    }
    check("L05 在家", "明天10点在家开会") {
        notNull().location("家")
    }
    check("P01 只有时间没标题 → 兜底", "明天15:00") {
        notNull().title("新日程")
    }
    check("P02 【通知前缀】进备注", "【会议通知】明天下午3点开会") {
        notNull().title("开会").notesHas("会议通知").start("2026-09-14T15:00")
    }
    check("P03 聊天前缀「张三:」进备注", "张三: 明天下午3点开会") {
        notNull().title("开会").notesHas("张三")
    }
    check("P04 备注标记", "明天10点开会 备注:带身份证") {
        notNull().title("开会").notesHas("带身份证")
    }
    check("P05 标题截断 60 字", "明天15:00 开会讨论明年的产品路线图以及市场推广计划还有人员招聘安排和预算审批流程等其他事项的安排说明") {
        notNull().titleLen(60)
    }
    check("P06 无备注时 notes 为空", "明天14:00 项目评审") {
        notNull().notesEmpty().title("项目评审")
    }
    check("P07 口头语清理", "帮我记一下 明天15:00 记得 交材料") {
        notNull().title("交材料")
    }
    check("P08 「记得提醒我」口头语清理", "明天8点 记得提醒我买牛奶") {
        notNull().reminder(-1).title("买牛奶")
    }
    check("P09 每天（带时刻）", "天天9点跑步") {
        notNull().repeat(RepeatKind.DAILY).start("2026-09-14T09:00")
    }

    // ---------------- 噪声清理 ----------------
    check("N01 URL/电话/表情清理", "10月1日 国庆假期 https://example.com/x 13800138000 🎉") {
        notNull().title("国庆假期").titleNotHas("http").titleNotHas("138").titleNotHas("🎉")
    }
    check("N02 换行变空格", "明天14:00 项目\n评审") {
        notNull().titleNotHas("\n")
    }
    check("N03 全角数字与冒号", "明天１４：００开会") {
        notNull().start("2026-09-14T14:00").title("开会")
    }

    // ---------------- 其它 ----------------
    check("O01 时区独立（America/New_York）", "明天14:00开会") {
        notNull()
    }
    run {
        caseNo++
        val evNy = ChineseDateParser.parse("明天14:00开会", NOW, ZONE_NY)
        val problems = ArrayList<String>()
        if (evNy == null) problems.add("纽约时区解析为 null")
        else if (startStr(evNy, ZONE_NY) != "2026-09-14T14:00") {
            problems.add("纽约本地时间应为 2026-09-14T14:00 实际=${startStr(evNy, ZONE_NY)}")
        } else if (evNy.startMillis == ChineseDateParser.parse("明天14:00开会", NOW, ZONE)?.startMillis) {
            problems.add("不同时区不应产生相同毫秒时间戳")
        }
        val ok = problems.isEmpty()
        if (ok) passCount++ else failCount++
        log.append(if (ok) "[PASS] " else "[FAIL] ").append(caseNo).append(" O01b 纽约时区毫秒戳校验\n")
        log.append("       输出: NY本地=").append(startStr(evNy, ZONE_NY)).append(" millis=").append(evNy?.startMillis).append('\n')
        for (p in problems) log.append("       !! ").append(p).append('\n')
    }
    run {
        caseNo++
        val a = ChineseDateParser.parse("下周三上午10点 部门周会 提前15分钟提醒", NOW, ZONE)
        val b = ChineseDateParser.parse("下周三上午10点 部门周会 提前15分钟提醒", NOW, ZONE)
        val ok = a == b && a != null
        if (ok) passCount++ else failCount++
        log.append(if (ok) "[PASS] " else "[FAIL] ").append(caseNo).append(" O02 纯函数：同样入参两次结果完全相等\n")
        log.append("       输出: a=").append(describe(a)).append('\n')
        log.append("             b=").append(describe(b)).append('\n')
    }

    // ---------------- parseAll ----------------
    checkAll("A01 多行聊天记录", "明天上午10点开会\n后天下午2点面试") { list, p ->
        if (list.size != 2) p.append("期望 2 个事件，实际 ${list.size}")
        if (list.isNotEmpty() && startStr(list[0]) != "2026-09-14T10:00") p.append("第1个 start 期望 2026-09-14T10:00 实际 ${startStr(list[0])}")
        if (list.size > 1 && startStr(list[1]) != "2026-09-15T14:00") p.append("第2个 start 期望 2026-09-15T14:00 实际 ${startStr(list[1])}")
        if (list.size > 1 && list[1].title != "面试") p.append("第2个标题期望 面试 实际 ${list[1].title}")
    }
    checkAll("A02 逗号分隔两件事（第二件继承日期）", "明天上午10点开会，下午3点见客户") { list, p ->
        if (list.size != 2) p.append("期望 2 个事件，实际 ${list.size}")
        if (list.size > 1 && startStr(list[1]) != "2026-09-14T15:00") p.append("第2个 start 期望 2026-09-14T15:00 实际 ${startStr(list[1])}")
        if (list.size > 1 && list[1].title != "见客户") p.append("第2个标题期望 见客户 实际 ${list[1].title}")
    }
    checkAll("A03 无时间片段并入备注", "明天14:00开会，记得带电脑") { list, p ->
        if (list.size != 1) p.append("期望 1 个事件，实际 ${list.size}")
        if (list.isNotEmpty() && !list[0].notes.contains("电脑")) p.append("备注应含 电脑 实际 ${list[0].notes}")
        if (list.isNotEmpty() && list[0].title != "开会") p.append("标题期望 开会 实际 ${list[0].title}")
    }
    checkAll("A04 日期与时刻被逗号切开也能合并", "9月13日，21:30 项目评审") { list, p ->
        if (list.size != 1) p.append("期望 1 个事件，实际 ${list.size}")
        if (list.isNotEmpty() && startStr(list[0]) != "2026-09-13T21:30") p.append("start 期望 2026-09-13T21:30 实际 ${startStr(list[0])}")
        if (list.isNotEmpty() && list[0].allDay) p.append("不应是全天")
        if (list.isNotEmpty() && list[0].title != "项目评审") p.append("标题期望 项目评审 实际 ${list[0].title}")
    }
    checkAll("A05 完全无时间 → 空列表", "随便聊聊天气不错啊") { list, p ->
        if (list.isNotEmpty()) p.append("期望空列表，实际 ${list.size}")
    }
    checkAll("A05b 只有「今天」两字信息 → 全天事件（不瞎猜时刻）", "随便聊聊今天天气不错啊") { list, p ->
        if (list.size != 1) p.append("期望 1 个全天事件，实际 ${list.size}")
        if (list.isNotEmpty() && !list[0].allDay) p.append("应为全天")
        if (list.isNotEmpty() && startStr(list[0]) != "2026-09-13T00:00") p.append("start 期望 2026-09-13T00:00 实际 ${startStr(list[0])}")
    }
    checkAll("A06 四个事件混合", "明天9点开会\n后天中午12点半体检\n每周一 9:00 晨会\n10月1日 国庆假期") { list, p ->
        if (list.size != 4) p.append("期望 4 个事件，实际 ${list.size}")
        if (list.size != 4) return@checkAll
        if (list[2].repeat != RepeatKind.WEEKLY || list[2].repeatWeekday != 1) p.append("第3个应为 WEEKLY(1)")
        if (!list[3].allDay) p.append("第4个应为全天")
    }

    // ---------------- 汇总 ----------------
    log.append("=".repeat(100)).append('\n')
    log.append("总计: ").append(passCount + failCount).append(" 用例，通过 ").append(passCount)
        .append("，失败 ").append(failCount).append('\n')
    log.append(if (failCount == 0) "ALL TESTS PASSED\n" else "SOME TESTS FAILED\n")

    val out = File("result.txt")
    out.writeText(log.toString(), Charsets.UTF_8)
    println("通过 " + passCount + " / " + (passCount + failCount) + "，结果已写入 " + out.absolutePath)
    if (failCount > 0) {
        println("有失败用例，详见 result.txt")
    }
}

