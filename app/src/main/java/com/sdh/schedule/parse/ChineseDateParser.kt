package com.sdh.schedule.parse

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * 重复规则。
 */
enum class RepeatKind { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

/**
 * 解析结果。纯数据类，不含任何 Android 依赖。
 */
data class ParsedEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val repeat: RepeatKind,
    val repeatWeekday: Int,          // WEEKLY 时用，1=周一..7=周日，否则 0
    val location: String,            // 没提到就空串
    val reminderMinutesBefore: Int,  // -1 = 文本没提提醒
    val confidence: Double,          // 0.0~1.0
    val notes: String,               // 正文里剩下的补充信息
    val matched: List<String>,       // 命中的关键片段，给 UI 展示“为什么这么解析”
    val sourceText: String
)

/**
 * 中文自然语言时间/日程解析引擎。
 *
 * 设计约束：
 * - 纯 Kotlin，只用标准库 + `java.time`，不引用 Android SDK / 三方库；
 * - 纯函数、无状态、线程安全：所有可变对象都在单次调用内部创建；
 * - 不读取系统时间、不读取系统默认时区，`nowMillis` 与 [ZoneId] 全部由参数传入。
 */
object ChineseDateParser {

    /** 解析出最可能的一个日程；文本里完全看不出时间信息时返回 null。 */
    fun parse(text: String, nowMillis: Long, zone: ZoneId): ParsedEvent? =
        parseAll(text, nowMillis, zone).firstOrNull()

    /** 一段长文本里可能包含多个日程，逐个解析；没有则返回空列表。 */
    fun parseAll(text: String, nowMillis: Long, zone: ZoneId): List<ParsedEvent> {
        if (text.isBlank()) return emptyList()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val norm = normalize(text)
        val units = splitUnits(norm)

        val built = ArrayList<Built>()
        var anchor: LocalDate? = null
        for (unit in units) {
            val parser = UnitParser(text, norm, unit.first, unit.second, now, zone, anchor)
            val b = parser.build()
            if (b == null) {
                // 没有时间信息的片段：当作上一个事件的补充备注
                val leftover = norm.substring(unit.first, unit.second).trim()
                if (built.isNotEmpty() && leftover.isNotEmpty()) {
                    val last = built[built.size - 1]
                    built[built.size - 1] = last.withExtraNotes(leftover)
                }
            } else {
                built.add(b)
                anchor = b.dateValue
            }
        }

        // 合并「只有日期」+「继承日期的纯时刻」两个片段（例：9月13日，21:30 项目评审）
        val out = ArrayList<ParsedEvent>()
        var i = 0
        while (i < built.size) {
            val cur = built[i]
            if (cur.allDayOnly && i + 1 < built.size) {
                val nxt = built[i + 1]
                if (nxt.inherited && nxt.hasExactTime) {
                    out.add(mergeTwo(cur, nxt, text))
                    i += 2
                    continue
                }
            }
            out.add(cur.event)
            i++
        }
        return out
    }

    // ------------------------------------------------------------------
    // 内部：单条日程的构建结果
    // ------------------------------------------------------------------

    private fun mergeTwo(cur: Built, nxt: Built, raw: String): ParsedEvent {
        val title = mergeTitle(cur.event.title, nxt.event.title)
        val src = if (nxt.segEnd in 0..raw.length && cur.segStart in 0..nxt.segEnd) {
            raw.substring(cur.segStart, nxt.segEnd).trim()
        } else nxt.event.sourceText
        val repeat = if (cur.event.repeat != RepeatKind.NONE) cur.event.repeat else nxt.event.repeat
        val repeatWeekday = if (cur.event.repeatWeekday != 0) cur.event.repeatWeekday else nxt.event.repeatWeekday
        val location = if (cur.event.location.isNotEmpty()) cur.event.location else nxt.event.location
        val reminder = if (cur.event.reminderMinutesBefore != -1) cur.event.reminderMinutesBefore
        else nxt.event.reminderMinutesBefore
        return ParsedEvent(
            title = title,
            startMillis = nxt.event.startMillis,
            endMillis = nxt.event.endMillis,
            allDay = false,
            repeat = repeat,
            repeatWeekday = repeatWeekday,
            location = location,
            reminderMinutesBefore = reminder,
            confidence = confidenceOf(cur.cclass, true, false, repeat),
            notes = joinNotes(cur.event.notes, nxt.event.notes),
            matched = (cur.event.matched + nxt.event.matched).distinct(),
            sourceText = src
        )
    }

    private fun mergeTitle(a: String, b: String): String {
        val list = listOf(a, b).filter { it.isNotEmpty() && it != FALLBACK_TITLE }
        return if (list.isEmpty()) FALLBACK_TITLE else list.joinToString(" ")
    }

    private fun joinNotes(a: String, b: String): String =
        listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    // ------------------------------------------------------------------
    // 归一化 / 切分
    // ------------------------------------------------------------------

    /** 长度保持不变的归一化：全角转半角、URL/电话/表情替换成等长空格。 */
    internal fun normalize(src: String): String {
        val sb = StringBuilder(src.length)
        for (c in src) {
            if (c.code < 0x20 && c != '\n') {
                sb.append(' ')
                continue
            }
            val m: Char = when (c) {
                in '０'..'９' -> '0' + (c - '０')
                '：' -> ':'
                '；' -> ';'
                '，' -> ','
                '。' -> '.'
                '？' -> '?'
                '！' -> '!'
                '（' -> '('
                '）' -> ')'
                '【' -> '['
                '】' -> ']'
                '｛' -> '{'
                '｝' -> '}'
                '／' -> '/'
                '－', '—', '–', '─', '━', 'ー' -> '-'
                '～' -> '~'
                '．' -> '.'
                '　', '\t', '\r' -> ' '
                else -> c
            }
            sb.append(m)
        }
        var s = sb.toString()
        s = RE_URL.replace(s) { " ".repeat(it.value.length) }
        s = RE_PHONE_A.replace(s) { " ".repeat(it.value.length) }
        s = RE_PHONE_B.replace(s) { " ".repeat(it.value.length) }
        s = RE_EMOJI.replace(s) { " ".repeat(it.value.length) }
        return s
    }

    /** 先按硬分隔符切句，再按逗号切分句。返回的是归一化串上的下标区间。 */
    internal fun splitUnits(norm: String): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        val hard = charArrayOf('\n', '。', '！', '!', '？', '?', '；', ';', '|')
        val soft = charArrayOf('，', ',', '、')

        fun pushTrimmed(s0: Int, e0: Int) {
            var s = s0
            var e = e0
            while (s < e && norm[s].isWhitespace()) s++
            while (e > s && norm[e - 1].isWhitespace()) e--
            if (e > s) res.add(s to e)
        }

        fun splitSoft(s0: Int, e0: Int) {
            var last = s0
            var k = s0
            while (k < e0) {
                if (soft.contains(norm[k])) {
                    pushTrimmed(last, k)
                    last = k + 1
                }
                k++
            }
            pushTrimmed(last, e0)
        }

        var start = 0
        var i = 0
        while (i < norm.length) {
            if (hard.contains(norm[i])) {
                var s = start
                var e = i
                while (s < e && norm[s].isWhitespace()) s++
                while (e > s && norm[e - 1].isWhitespace()) e--
                if (e > s) splitSoft(s, e)
                start = i + 1
            }
            i++
        }
        var s = start
        var e = norm.length
        while (s < e && norm[s].isWhitespace()) s++
        while (e > s && norm[e - 1].isWhitespace()) e--
        if (e > s) splitSoft(s, e)
        return res
    }
}

// ======================================================================
// 常量与工具
// ======================================================================

private const val FALLBACK_TITLE = "新日程"
private const val TITLE_MAX = 60
private val ALLDAY_END: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)

private val RE_URL = Regex("""(?i)\bhttps?://\S+|(?i)\bwww\.\S+""")
private val RE_PHONE_A = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
private val RE_PHONE_B = Regex("""(?<!\d)(?:\d{3,4}-\d{7,8}|400-?\d{3}-?\d{4})(?!\d)""")
private val RE_EMOJI = Regex(
    """[\x{1F000}-\x{1FAFF}\x{1F1E6}-\x{1F1FF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}\x{2190}-\x{21FF}\x{FE0F}\x{200D}\x{20E3}]"""
)

private val RE_REMINDER_AHEAD = Regex(
    """提\s*前\s*(半|[0-9]{1,3}|[一二两三四五六七八九十]{1,3})\s*(?:个)?\s*(小时|钟头|分钟|分|天|日|周|星期|礼拜|个月)\s*(?:提醒|通知|提示|叫我)?|(?:提醒|通知|提示)\s*我?\s*提\s*前\s*(半|[0-9]{1,3}|[一二两三四五六七八九十]{1,3})\s*(?:个)?\s*(小时|钟头|分钟|分|天|日|周|星期|礼拜|个月)\s*"""
)
private val RE_REMINDER_ONTIME = Regex("""(准时|到点|正点)\s*(?:提醒|通知|提示)?""")
private val RE_REMINDER_VAGUE_A = Regex("""(提醒我|叫我一声|提醒一下|提醒)""")
private val RE_REMINDER_VAGUE_B = Regex("""(记得|别忘记|别忘了|别忘)""")

private val RE_REP_WEEKDAYS = Regex(
    """(?:每\s*(?:个)?\s*工作日|工作日|(?:每\s*(?:个)?\s*)?周\s*[一1]\s*(?:到|至|-|~)\s*(?:周)?\s*[五5]|(?:每\s*(?:个)?\s*)?星期\s*[一1]\s*(?:到|至|-|~)\s*(?:星期)?\s*[五5]|(?:每\s*(?:个)?\s*)?礼拜\s*[一1]\s*(?:到|至|-|~)\s*(?:礼拜)?\s*[五5])"""
)
private val RE_REP_WEEKLY = Regex("""(?:每|每逢)\s*(?:个)?\s*(?:周|星期|礼拜)([一二三四五六日天1-7])""")
private val RE_REP_DAILY = Regex(
    """(?:每|每逢)\s*(?:天|日)|天天(?=\s*(?:\d|[一二两三四五六七八九十]|上午|下午|晚上|早上|早晨|中午|凌晨|傍晚|晚间|夜里|晚|早))"""
)
private val RE_REP_MONTHLY = Regex(
    """(?:每|每逢)\s*(?:个)?\s*月\s*(?:的)?\s*(\d{1,2}|[一二三四五六七八九十]{1,3})\s*[号日]"""
)
private val RE_REP_MONTHLY_BARE = Regex("""(?:每|每逢)\s*(?:个)?\s*月""")
private val RE_REP_YEARLY = Regex(
    """(?:每|每逢)\s*(?:个)?\s*年\s*(\d{1,2}|[一二三四五六七八九十]{1,3})\s*月\s*(\d{1,2}|[一二三四五六七八九十]{1,3})\s*[日号]?"""
)
private val RE_REP_YEARLY_BARE = Regex("""(?:每|每逢)\s*(?:个)?\s*年""")

private val RE_REL_HOUR = Regex("""(半|\d{1,2}|[一二两三四五六七八九十]{1,3})\s*(?:个)?\s*(?:小时|钟头)\s*(?:以)?后""")
private val RE_REL_MIN = Regex("""(半|\d{1,3}|[一二两三四五六七八九十]{1,3})\s*(?:分钟|分)\s*(?:以)?后""")

private val RE_CLOCK_COLON = Regex("""(?<!\d)(\d{1,2})\s*:\s*(\d{2})(?:\s*:\s*(\d{2}))?(?!\d)""")
private val RE_CLOCK_CN = Regex("""(?<!\d)(\d{1,2}|[一二两三四五六七八九十]{1,3})\s*[点时時]\s*(半|一刻|三刻|\d{1,2}\s*分?)?""")
private val RE_RANGE_LINK = Regex("""^\s*(?:到|至|~|-|—|–|to|止)\s*$""")

private val RE_DUR_N_HALF = Regex("""(?:(?:持续|时长|用时|一共|大约|大概)\s*)?(\d|[一二两三四五六七八九十])\s*个半\s*(?:小时|钟头)""")
private val RE_DUR_HOUR = Regex(
    """(?:(?:持续|时长|用时|一共|大约|大概)\s*)?(\d+(?:\.\d+)?|[一二两三四五六七八九十]{1,3}|半)\s*个?\s*(?:小时|钟头)\s*(?:(\d{1,2})\s*分(?:钟)?)?"""
)
private val RE_DUR_MIN = Regex("""(?:(?:持续|时长|用时|一共|大约|大概)\s*)?(\d+(?:\.\d+)?|[一二两三四五六七八九十]{1,3}|半)\s*个?\s*(?:分钟|分)""")

private val RE_REL_DAY = Regex("""(?<!\d)(\d+|[一二两三四五六七八九十]{1,3})\s*天\s*(?:以)?后""")
private val RE_REL_WEEK = Regex("""(\d+|[一二两三四五六七八九十]{1,3})\s*(?:个)?\s*(?:周|星期|礼拜)\s*(?:以)?后""")
private val RE_REL_MONTH = Regex("""(\d+|[一二两三四五六七八九十]{1,3})\s*(?:个)?\s*月\s*(?:以)?后""")
private val RE_NEXT_MONTH = Regex(
    """下\s*(?:个)?\s*月\s*(?:的)?\s*(?:(\d{1,2}|[一二三四五六七八九十]{1,3})\s*[号日])?"""
)
private val RE_DATE_YMD = Regex("""(?<!\d)(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?""")
private val RE_DATE_YMD2 = Regex("""(?<!\d)(\d{4})\s*[-/.]\s*(\d{1,2})\s*[-/.]\s*(\d{1,2})(?!\d)""")
private val RE_DATE_MD = Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?""")
private val RE_DATE_MD_CN = Regex("""(?<!\d)([一二三四五六七八九十]{1,3})\s*月\s*([一二三四五六七八九十]{1,3})\s*[日号]""")
private val RE_DATE_SLASH = Regex("""(?<![\d/])(\d{1,2})\s*/\s*(\d{1,2})(?![\d/])""")
private val RE_WEEKDAY = Regex("""(下下|下|上|这|本|该)?\s*(?:个)?\s*(?:周|星期|礼拜)([一二三四五六日天1-7])""")
private val RE_WEEK_BARE = Regex("""(下下|下)\s*(?:个)?\s*(?:周|星期|礼拜)(?![一二三四五六日天1-7])""")
private val RE_WEEKEND = Regex("""(下下|下|这|本)?\s*周末""")
private val RE_DAY_ONLY = Regex("""(?<!\d)(\d{1,2})\s*[号日](?![号日])""")

private val RE_LOC_MARK = Regex("""(?:地点|地址|位置)\s*[:：是]?\s*([^\s,，。;；、!！?？]{2,25})""")
private val RE_LOC_AT = Regex("""@\s*([^\s,，。;；、!！?？@]{2,25})""")
private val RE_LOC_ZAI = Regex(
    """(?<![现正所存实自还也终由关对至等限鉴])[在于]\s*([^\s,，。;；、!！?？:：\-~～]{1,25}?)(?=(?:吃饭|聚餐|开会|会议|上课|面试|见面|碰头|集合|出发|看电影|看病|健身|加班|值班|考试|培训|出差|约会|聊天|讨论|签到|报到|打球|跑步|游泳|唱歌|逛街|购物|理发|体检|交|提交|报名|参加|举行|举办|吃|喝|玩|做|去|看|忙|见|办|聚|走|买|取|送|开|[的了吗呢啊]|[\s,，。;；、!！?？])|\z)"""
)
private val RE_NOTE = Regex(
    """(?:备注|注释|温馨提示)\s*[:：]?\s*([^,，。;；!！?？]{1,80})|(?:说明|注意)\s*[:：]\s*([^,，。;；!！?？]{1,80})"""
)
private val RE_PREFIX_BRACKET = Regex("""^[\[【][^\]】]{1,20}[\]】]\s*""")
private val RE_PREFIX_COLON = Regex("""^([^\s:：,，。;；!！?？]{1,4})\s*[:：]\s*""")

private val CN_DIGITS: Map<Char, Int> = mapOf(
    '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
)

private val TITLE_FILLERS = listOf(
    "提醒我", "记得提醒", "帮我记一下", "帮我记", "帮我", "帮忙", "提醒一下", "记得", "别忘了",
    "别忘记", "别忘", "叫我一声", "我要", "我想", "记一下", "记下", "麻烦"
)

private val PREFIX_BLACKLIST = setOf(
    "开会", "会议", "面试", "聚餐", "培训", "通知", "备注", "说明", "主题", "标题", "内容",
    "项目", "活动", "约会", "考试", "体检", "日程", "安排", "提醒", "重要", "会议通知", "上午",
    "下午", "晚上", "中午", "早上", "今天", "明天", "后天"
)

private val TRIM_CHARS: CharArray = charArrayOf(
    ',', '，', '.', '。', ';', '；', ':', '：', '!', '！', '?', '？', '、', '-', '—', '~', '～',
    '"', '\'', '“', '”', '‘', '’', '(', ')', '（', '）', '[', ']', '【', '】', '·', '…', '《', '》',
    '<', '>', ' '
)

private enum class PeriodKind { MORNING, NOON, AFTERNOON, EVENING, NIGHT }

private enum class WMode { NONE, BARE, THIS_WEEK, NEXT1, NEXT2, PREV }

private enum class CClass { NONE, EXPLICIT, REL_DAY, WEEKDAY, REPEAT, INHERITED }

private class Hit(val pos: Int, val len: Int)

private class PMark(val from: Int, val at: Int, val kind: PeriodKind, val defaultTime: LocalTime)

private class Clock(
    val start: Int,
    val end: Int,
    val rawHour: Int,
    val minute: Int,
    val period: PeriodKind?,
    val fromCnForm: Boolean
)

private class WordEntry(
    val text: String,
    val dayOffset: Int?,
    val period: PeriodKind?,
    val periodDefault: LocalTime?
)

private class Built(
    val event: ParsedEvent,
    val segStart: Int,
    val segEnd: Int,
    val cclass: CClass,
    val hasExactTime: Boolean,
    val allDayOnly: Boolean,
    val inherited: Boolean,
    val dateValue: LocalDate
) {
    fun withExtraNotes(extra: String): Built = Built(
        event = event.copy(notes = joinNotesStatic(event.notes, extra)),
        segStart = segStart, segEnd = segEnd, cclass = cclass, hasExactTime = hasExactTime,
        allDayOnly = allDayOnly, inherited = inherited, dateValue = dateValue
    )
}

private fun joinNotesStatic(a: String, b: String): String =
    listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

private fun confidenceOf(cclass: CClass, hasExactTime: Boolean, vague: Boolean, repeat: RepeatKind): Double {
    val raw = when {
        vague -> 0.45
        hasExactTime -> when (cclass) {
            CClass.EXPLICIT -> 0.95
            CClass.REL_DAY -> 0.90
            CClass.WEEKDAY -> 0.85
            CClass.REPEAT -> if (repeat == RepeatKind.DAILY || repeat == RepeatKind.WEEKDAYS) 0.88 else 0.85
            CClass.INHERITED -> 0.75
            CClass.NONE -> 0.60
        }
        else -> when (cclass) {
            CClass.EXPLICIT -> 0.68
            CClass.NONE -> 0.50
            else -> 0.65
        }
    }
    return (raw * 100).roundToInt() / 100.0
}

private fun cnNumber(s: String): Int? {
    val t = s.trim()
    if (t.isEmpty()) return null
    if (t.all { it in '0'..'9' }) return t.toIntOrNull()
    if (t == "半") return null
    val ten = t.indexOf('十')
    if (ten >= 0) {
        val head = t.substring(0, ten)
        val tail = t.substring(ten + 1)
        val h = if (head.isEmpty()) 1 else (CN_DIGITS[head.first()] ?: return null)
        val tl = if (tail.isEmpty()) 0 else (CN_DIGITS[tail.first()] ?: return null)
        return h * 10 + tl
    }
    if (t.length == 1) return CN_DIGITS[t[0]]
    return CN_DIGITS[t.last()]
}

private fun weekdayOf(c: Char): Int = when (c) {
    '一', '1' -> 1
    '二', '2' -> 2
    '三', '3' -> 3
    '四', '4' -> 4
    '五', '5' -> 5
    '六', '6' -> 6
    '日', '天', '7' -> 7
    else -> 0
}

private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
    if (month !in 1..12 || day !in 1..31) return null
    return try {
        LocalDate.of(year, month, day)
    } catch (e: RuntimeException) {
        null
    }
}

private fun digitsOf(s: String): Int? {
    val d = s.filter { it in '0'..'9' }
    return if (d.isEmpty()) null else d.toIntOrNull()
}

// ======================================================================
// 单片段解析器
// ======================================================================

private class UnitParser(
    private val raw: String,
    private val norm: String,
    private val segStart: Int,
    private val segEnd: Int,
    private val now: ZonedDateTime,
    private val zone: ZoneId,
    private val inheritDate: LocalDate?
) {
    private val u: String = norm.substring(segStart, segEnd)
    private val marked = BooleanArray(u.length)
    private val hits = ArrayList<Hit>()
    private val periodMarks = ArrayList<PMark>()

    private val today: LocalDate = now.toLocalDate()
    private val isoDow: Int = today.dayOfWeek.value

    private var reminder = -1
    private var reminderSet = false

    private var repeatKind = RepeatKind.NONE
    private var repeatWeekday = 0
    private var repeatMonthDay = 0
    private var repeatYearMonth = 0
    private var repeatYearDay = 0

    private var calDate: LocalDate? = null
    private var dateWordOffset: Int? = null
    private var weekdayTarget = 0
    private var weekdayMode = WMode.NONE
    private var relDays: Int? = null
    private var relWeeks: Int? = null
    private var relMonths: Int? = null
    private var nextMonthDay = 0
    private var hasNextMonth = false
    private var hasDateSignal = false

    private var startTime: LocalTime? = null
    private var endTime: LocalTime? = null
    private var endPeriod: PeriodKind? = null
    private var endRawHour = -1
    private var durationMinutes: Int? = null
    private var relDateTime: LocalDateTime? = null
    private var periodDefault: LocalTime? = null

    private var location = ""
    private var noteText = ""

    // ---------------- 小工具 ----------------

    private fun markRange(a: Int, b: Int) {
        for (i in a until b) if (i in marked.indices) marked[i] = true
        hits.add(Hit(a, b - a))
    }

    private fun markMatch(m: MatchResult) = markRange(m.range.first, m.range.last + 1)

    private fun isFree(r: IntRange): Boolean {
        for (i in r) {
            if (i !in marked.indices || marked[i]) return false
        }
        return true
    }

    // ---------------- 主流程 ----------------

    fun build(): Built? {
        if (u.isBlank()) return null
        stepReminder()
        stepRepeat()
        stepWords()
        stepRelativeTime()
        stepClocks()
        stepDuration()
        stepDates()
        stepLocation()
        stepNote()

        val st = startTime
        val rel = relDateTime
        val pd = periodDefault
        val hasTimeSignal = st != null || rel != null || pd != null
        if (!hasTimeSignal && !hasDateSignal) return null

        val date = resolveDate() ?: today
        if (date.isBefore(today)) return null

        var allDay = false
        var startLdt: LocalDateTime
        var endLdt: LocalDateTime
        if (rel != null) {
            startLdt = rel
            endLdt = startLdt.plusMinutes((durationMinutes ?: 60).toLong())
        } else if (st != null) {
            startLdt = LocalDateTime.of(date, st)
            if (!hasDateSignal && inheritDate == null && !startLdt.isAfter(now.toLocalDateTime())) {
                startLdt = startLdt.plusDays(1)
            }
            endLdt = computeEnd(startLdt)
        } else if (pd != null) {
            startLdt = LocalDateTime.of(date, pd)
            if (!hasDateSignal && inheritDate == null && !startLdt.isAfter(now.toLocalDateTime())) {
                startLdt = startLdt.plusDays(1)
            }
            endLdt = startLdt.plusMinutes((durationMinutes ?: 60).toLong())
        } else {
            allDay = true
            startLdt = date.atStartOfDay()
            endLdt = LocalDateTime.of(date, ALLDAY_END)
        }

        val cclass = classifyDate()
        val hasExactTime = st != null || rel != null
        val vague = !hasExactTime && pd != null
        val confidence = confidenceOf(cclass, hasExactTime, vague, repeatKind)

        val leftover = buildLeftover()
        val prefixRes = stripPrefix(leftover)
        val title = cleanTitle(prefixRes.second)
        val notes = joinNotesStatic(noteText.trim(), prefixRes.first.trim())

        val matched = hits.sortedBy { it.pos }
            .map { raw.substring(segStart + it.pos, segStart + it.pos + it.len).trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val event = ParsedEvent(
            title = title,
            startMillis = startLdt.atZone(zone).toInstant().toEpochMilli(),
            endMillis = endLdt.atZone(zone).toInstant().toEpochMilli(),
            allDay = allDay,
            repeat = repeatKind,
            repeatWeekday = if (repeatKind == RepeatKind.WEEKLY) repeatWeekday else 0,
            location = location,
            reminderMinutesBefore = reminder,
            confidence = confidence,
            notes = notes,
            matched = matched,
            sourceText = raw.substring(segStart, segEnd).trim()
        )
        return Built(
            event = event,
            segStart = segStart,
            segEnd = segEnd,
            cclass = cclass,
            hasExactTime = hasExactTime,
            allDayOnly = allDay,
            inherited = !hasDateSignal && inheritDate != null,
            dateValue = date
        )
    }

    private fun computeEnd(startLdt: LocalDateTime): LocalDateTime {
        val et = endTime
        val dur = durationMinutes
        return when {
            et != null -> {
                var e = LocalDateTime.of(startLdt.toLocalDate(), et)
                if (!e.isAfter(startLdt)) {
                    val n = endPeriod
                    e = if (n == PeriodKind.NIGHT || (n == null && endRawHour in 0..6)) {
                        e.plusDays(1)
                    } else {
                        val alt = e.plusHours(12)
                        if (alt.isAfter(startLdt)) alt else e.plusDays(1)
                    }
                }
                e
            }
            dur != null -> startLdt.plusMinutes(dur.toLong())
            else -> startLdt.plusMinutes(60)
        }
    }

    // ---------------- 提醒 ----------------

    private fun stepReminder() {
        RE_REMINDER_AHEAD.findAll(u).forEach { m ->
            if (!isFree(m.range)) return@forEach
            val amount = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[3]
            val unit = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[4]
            val n: Double = if (amount == "半") 0.5 else (cnNumber(amount)?.toDouble() ?: return@forEach)
            val mins = when (unit) {
                "小时", "钟头" -> (n * 60).toInt()
                "分钟", "分" -> n.toInt()
                "天", "日" -> (n * 1440).toInt()
                "周", "星期", "礼拜" -> (n * 10080).toInt()
                "个月" -> (n * 43200).toInt()
                else -> return@forEach
            }
            if (mins > 0) {
                reminder = mins
                reminderSet = true
                markMatch(m)
            }
        }
        if (!reminderSet) {
            RE_REMINDER_ONTIME.findAll(u).forEach { m ->
                if (isFree(m.range) && !reminderSet) {
                    reminder = 0
                    reminderSet = true
                    markMatch(m)
                }
            }
        }
        RE_REMINDER_VAGUE_A.findAll(u).forEach { m ->
            if (isFree(m.range)) {
                if (!reminderSet) reminder = -1
                markMatch(m)
            }
        }
        RE_REMINDER_VAGUE_B.findAll(u).forEach { m ->
            if (isFree(m.range)) {
                if (!reminderSet) reminder = -1
                markMatch(m)
            }
        }
    }

    // ---------------- 重复 ----------------

    private fun stepRepeat() {
        RE_REP_WEEKDAYS.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                repeatKind = RepeatKind.WEEKDAYS
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_REP_WEEKLY.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                val wd = weekdayOf(m.groupValues[1].first())
                if (wd in 1..7) {
                    repeatKind = RepeatKind.WEEKLY
                    repeatWeekday = wd
                    hasDateSignal = true
                    markMatch(m)
                }
            }
        }
        RE_REP_DAILY.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                repeatKind = RepeatKind.DAILY
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_REP_MONTHLY.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                val d = cnNumber(m.groupValues[1])
                if (d != null && d in 1..31) {
                    repeatKind = RepeatKind.MONTHLY
                    repeatMonthDay = d
                    hasDateSignal = true
                    markMatch(m)
                }
            }
        }
        RE_REP_MONTHLY_BARE.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                repeatKind = RepeatKind.MONTHLY
                repeatMonthDay = today.dayOfMonth
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_REP_YEARLY.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                val mo = cnNumber(m.groupValues[1])
                val d = cnNumber(m.groupValues[2])
                if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                    repeatKind = RepeatKind.YEARLY
                    repeatYearMonth = mo
                    repeatYearDay = d
                    hasDateSignal = true
                    markMatch(m)
                }
            }
        }
        RE_REP_YEARLY_BARE.findAll(u).forEach { m ->
            if (isFree(m.range) && repeatKind == RepeatKind.NONE) {
                repeatKind = RepeatKind.YEARLY
                repeatYearMonth = today.monthValue
                repeatYearDay = today.dayOfMonth
                hasDateSignal = true
                markMatch(m)
            }
        }
    }

    // ---------------- 日期词 / 时段词 ----------------

    private fun stepWords() {
        var i = 0
        while (i < u.length) {
            if (marked[i]) {
                i++
                continue
            }
            var found: WordEntry? = null
            val maxLen = minOf(4, u.length - i)
            var len = maxLen
            while (len >= 1) {
                val piece = u.substring(i, i + len)
                val e = WORD_TABLE_BY_LEN[len]?.get(piece)
                if (e != null && isFree(i until i + len)) {
                    found = e
                    break
                }
                len--
            }
            val entry = found
            if (entry == null) {
                i++
                continue
            }
            markRange(i, i + entry.text.length)
            val off = entry.dayOffset
            if (off != null) {
                if (dateWordOffset == null) dateWordOffset = off
                hasDateSignal = true
            }
            val pk = entry.period
            if (pk != null) {
                periodMarks.add(PMark(i, i + entry.text.length, pk, entry.periodDefault ?: LocalTime.of(9, 0)))
                if (periodDefault == null) periodDefault = entry.periodDefault
            }
            i += entry.text.length
        }
    }

    // ---------------- 相对时间：N小时后 / N分钟后 ----------------

    private fun stepRelativeTime() {
        RE_REL_HOUR.findAll(u).forEach { m ->
            if (!isFree(m.range)) return@forEach
            val rawN = m.groupValues[1]
            val mins = if (rawN == "半") 30 else ((cnNumber(rawN) ?: return@forEach) * 60)
            if (mins in 1..(24 * 60 * 30)) {
                relDateTime = now.toLocalDateTime().plusMinutes(mins.toLong())
                hasDateSignal = true
                markMatch(m)
            }
        }
        if (relDateTime == null) {
            RE_REL_MIN.findAll(u).forEach { m ->
                if (!isFree(m.range)) return@forEach
                val rawN = m.groupValues[1]
                val mins = if (rawN == "半") 30 else (cnNumber(rawN) ?: return@forEach)
                if (mins in 1..(24 * 60 * 30)) {
                    relDateTime = now.toLocalDateTime().plusMinutes(mins.toLong())
                    hasDateSignal = true
                    markMatch(m)
                }
            }
        }
    }

    // ---------------- 时刻 ----------------

    private fun stepClocks() {
        val list = ArrayList<Clock>()
        RE_CLOCK_COLON.findAll(u).forEach { m ->
            if (!isFree(m.range)) return@forEach
            val h = digitsOf(m.groupValues[1]) ?: return@forEach
            val mi = digitsOf(m.groupValues[2]) ?: 0
            if (h in 0..23 && mi in 0..59) {
                list.add(Clock(m.range.first, m.range.last + 1, h, mi, null, false))
            }
        }
        RE_CLOCK_CN.findAll(u).forEach { m ->
            if (!isFree(m.range)) return@forEach
            val h = cnNumber(m.groupValues[1]) ?: return@forEach
            val suffix = m.groupValues[2]
            val mi = when {
                suffix.isEmpty() -> 0
                suffix == "半" -> 30
                suffix == "一刻" -> 15
                suffix == "三刻" -> 45
                else -> digitsOf(suffix) ?: 0
            }
            if (h in 0..24 && mi in 0..59) {
                list.add(Clock(m.range.first, m.range.last + 1, h, mi, null, true))
            }
        }
        if (list.isEmpty()) return
        list.sortBy { it.start }

        // 去掉重叠（保留先出现的）
        val kept = ArrayList<Clock>()
        for (c in list) {
            if (kept.isEmpty() || c.start >= kept[kept.size - 1].end) kept.add(c)
        }

        // 解析每个时刻的时间段（取它前面最近的一个时段词）
        val resolved = ArrayList<Pair<Clock, Int>>()
        for (c in kept) {
            val period = nearestPeriod(c.start)
            val hour = mapHour(c.rawHour, period, c.fromCnForm)
            resolved.add(c to hour)
        }

        // 区间：A到B / A-B（中间的时段词如「今晚11点到凌晨1点」的「凌晨」不算连接词）
        var rangeFound = false
        for (i in 0 until resolved.size - 1) {
            val a = resolved[i].first
            val b = resolved[i + 1].first
            if (b.start < a.end) continue
            val gap = rangeGap(a.end, b.start)
            if (RE_RANGE_LINK.matches(gap)) {
                val ha = resolved[i].second
                val hb = resolved[i + 1].second
                startTime = LocalTime.of(ha % 24, a.minute)
                endTime = LocalTime.of(hb % 24, b.minute)
                endPeriod = nearestPeriod(b.start)
                endRawHour = b.rawHour
                rangeFound = true
                markGapConnector(a.end, b.start)
                break
            }
        }
        if (!rangeFound) {
            val first = resolved[0]
            startTime = LocalTime.of(first.second % 24, first.first.minute)
        }
        for ((c, _) in resolved) markRange(c.start, c.end)
    }

    /** 只把区间连接词本身（到/至/-/~）记为命中，中间夹着的时段词（凌晨）单独算。 */
    private fun markGapConnector(from: Int, to: Int) {
        var i = from
        while (i < to) {
            if (inPeriodMark(i)) {
                i++
                continue
            }
            val s = i
            while (i < to && !inPeriodMark(i)) i++
            if (i > s) markRange(s, i)
        }
    }

    private fun inPeriodMark(idx: Int): Boolean {
        for (p in periodMarks) {
            if (idx >= p.from && idx < p.at) return true
        }
        return false
    }

    /** 区间连接词判定：把中间的时段词（凌晨/上午…）当作空白，只看「到/至/-/~」等连接词。 */
    private fun rangeGap(from: Int, to: Int): String {
        if (from >= to) return ""
        val sb = StringBuilder()
        for (i in from until to) {
            sb.append(if (inPeriodMark(i)) ' ' else u[i])
        }
        return sb.toString()
    }

    private fun nearestPeriod(at: Int): PeriodKind? {
        var best: PMark? = null
        for (p in periodMarks) {
            if (p.at <= at && (best == null || p.at > best.at)) best = p
        }
        return best?.kind
    }

    private fun mapHour(hour: Int, period: PeriodKind?, fromCnForm: Boolean): Int = when (period) {
        PeriodKind.MORNING, PeriodKind.NIGHT -> if (hour == 12) 0 else hour
        PeriodKind.NOON -> when {
            hour == 12 -> 12
            hour == 11 -> 11
            hour in 1..9 -> hour + 12
            else -> hour
        }
        PeriodKind.AFTERNOON -> if (hour < 12) hour + 12 else hour
        PeriodKind.EVENING -> when {
            hour < 12 -> hour + 12
            hour == 12 -> 0
            else -> hour
        }
        null -> if (fromCnForm && hour in 1..5) hour + 12 else hour
    }

    // ---------------- 时长 ----------------

    private fun stepDuration() {
        var done = false
        RE_DUR_N_HALF.findAll(u).forEach { m ->
            if (!isFree(m.range) || done) return@forEach
            val n = cnNumber(m.groupValues[1]) ?: return@forEach
            durationMinutes = (n * 60 + 30)
            markMatch(m)
            done = true
        }
        if (!done) {
            RE_DUR_HOUR.findAll(u).forEach { m ->
                if (!isFree(m.range) || done) return@forEach
                val head = m.groupValues[1]
                val base = if (head == "半") 30 else (cnNumber(head)?.let { it * 60 } ?: return@forEach)
                val extra = digitsOf(m.groupValues[2]) ?: 0
                val total = if (head == "半") base else base + extra
                if (total in 1..(24 * 60 * 31)) {
                    durationMinutes = total
                    markMatch(m)
                    done = true
                }
            }
        }
        if (!done) {
            RE_DUR_MIN.findAll(u).forEach { m ->
                if (!isFree(m.range) || done) return@forEach
                val head = m.groupValues[1]
                val total = if (head == "半") 30 else (cnNumber(head) ?: return@forEach)
                if (total in 1..(24 * 60 * 31)) {
                    durationMinutes = total
                    markMatch(m)
                    done = true
                }
            }
        }
    }

    // ---------------- 日期 ----------------

    private fun stepDates() {
        RE_REL_DAY.findAll(u).forEach { m ->
            if (!isFree(m.range) || relDays != null) return@forEach
            val n = cnNumber(m.groupValues[1]) ?: return@forEach
            if (n in 1..3660) {
                relDays = n
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_REL_WEEK.findAll(u).forEach { m ->
            if (!isFree(m.range) || relWeeks != null) return@forEach
            val n = cnNumber(m.groupValues[1]) ?: return@forEach
            if (n in 1..520) {
                relWeeks = n
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_REL_MONTH.findAll(u).forEach { m ->
            if (!isFree(m.range) || relMonths != null) return@forEach
            val n = cnNumber(m.groupValues[1]) ?: return@forEach
            if (n in 1..120) {
                relMonths = n
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_NEXT_MONTH.findAll(u).forEach { m ->
            if (!isFree(m.range) || hasNextMonth) return@forEach
            val dRaw = m.groupValues[1]
            nextMonthDay = if (dRaw.isEmpty()) 0 else (cnNumber(dRaw) ?: 0)
            hasNextMonth = true
            hasDateSignal = true
            markMatch(m)
        }
        RE_DATE_YMD.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val y = digitsOf(m.groupValues[1]) ?: return@forEach
            val mo = digitsOf(m.groupValues[2]) ?: return@forEach
            val d = digitsOf(m.groupValues[3]) ?: return@forEach
            val dt = safeDate(y, mo, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_DATE_YMD2.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val y = digitsOf(m.groupValues[1]) ?: return@forEach
            val mo = digitsOf(m.groupValues[2]) ?: return@forEach
            val d = digitsOf(m.groupValues[3]) ?: return@forEach
            val dt = safeDate(y, mo, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_DATE_MD.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val mo = digitsOf(m.groupValues[1]) ?: return@forEach
            val d = digitsOf(m.groupValues[2]) ?: return@forEach
            val dt = rollingMonthDay(mo, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_DATE_MD_CN.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val mo = cnNumber(m.groupValues[1]) ?: return@forEach
            val d = cnNumber(m.groupValues[2]) ?: return@forEach
            val dt = rollingMonthDay(mo, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_DATE_SLASH.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val mo = digitsOf(m.groupValues[1]) ?: return@forEach
            val d = digitsOf(m.groupValues[2]) ?: return@forEach
            val dt = rollingMonthDay(mo, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_DAY_ONLY.findAll(u).forEach { m ->
            if (!isFree(m.range) || calDate != null) return@forEach
            val d = digitsOf(m.groupValues[1]) ?: return@forEach
            val dt = rollingMonthDay(today.monthValue, d)
            if (dt != null) {
                calDate = dt
                hasDateSignal = true
                markMatch(m)
            }
        }
        RE_WEEKDAY.findAll(u).forEach { m ->
            if (!isFree(m.range) || weekdayMode != WMode.NONE) return@forEach
            val wd = weekdayOf(m.groupValues[2].first())
            if (wd !in 1..7) return@forEach
            val prefix = m.groupValues[1]
            weekdayTarget = wd
            weekdayMode = when (prefix) {
                "" -> WMode.BARE
                "这", "本", "该" -> WMode.THIS_WEEK
                "下" -> WMode.NEXT1
                "下下" -> WMode.NEXT2
                "上" -> WMode.PREV
                else -> WMode.BARE
            }
            hasDateSignal = true
            markMatch(m)
        }
        RE_WEEK_BARE.findAll(u).forEach { m ->
            if (!isFree(m.range) || weekdayMode != WMode.NONE) return@forEach
            weekdayTarget = 1
            weekdayMode = if (m.groupValues[1] == "下下") WMode.NEXT2 else WMode.NEXT1
            hasDateSignal = true
            markMatch(m)
        }
        RE_WEEKEND.findAll(u).forEach { m ->
            if (!isFree(m.range) || weekdayMode != WMode.NONE) return@forEach
            val prefix = m.groupValues[1]
            weekdayTarget = 6
            weekdayMode = if (prefix == "下" || prefix == "下下") WMode.NEXT1 else WMode.THIS_WEEK
            hasDateSignal = true
            markMatch(m)
        }
    }

    private fun rollingMonthDay(month: Int, day: Int): LocalDate? {
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear < today) {
            safeDate(today.year + 1, month, day) ?: thisYear
        } else thisYear
    }

    // ---------------- 地点 ----------------

    private fun stepLocation() {
        RE_LOC_MARK.findAll(u).forEach { m ->
            if (!isFree(m.range) || location.isNotEmpty()) return@forEach
            val v = m.groupValues[1].trim(' ', ':', '：', ',', '，', '。', '.')
            if (v.length >= 2) {
                location = v
                markMatch(m)
            }
        }
        RE_LOC_AT.findAll(u).forEach { m ->
            if (!isFree(m.range) || location.isNotEmpty()) return@forEach
            val v = m.groupValues[1].trim(' ', ':', '：', ',', '，', '。', '.')
            if (v.length >= 2) {
                location = v
                markMatch(m)
            }
        }
        RE_LOC_ZAI.findAll(u).forEach { m ->
            if (!isFree(m.range) || location.isNotEmpty()) return@forEach
            val v = m.groupValues[1].trim(' ', ':', '：', ',', '，', '。', '.')
            if (v.length >= 2 || v == "家") {
                location = v
                markMatch(m)
            }
        }
    }

    // ---------------- 备注 ----------------

    private fun stepNote() {
        RE_NOTE.findAll(u).forEach { m ->
            if (!isFree(m.range) || noteText.isNotEmpty()) return@forEach
            val v = if (m.groupValues[1].isNotEmpty()) m.groupValues[1].trim() else m.groupValues[2].trim()
            if (v.isNotEmpty()) {
                noteText = v
                markMatch(m)
            }
        }
    }

    // ---------------- 日期解析 ----------------

    private fun classifyDate(): CClass {
        if (calDate != null || relDays != null || relWeeks != null || relMonths != null || hasNextMonth) {
            return CClass.EXPLICIT
        }
        if (relDateTime != null) return CClass.EXPLICIT
        if (dateWordOffset != null) return CClass.REL_DAY
        if (weekdayMode != WMode.NONE && weekdayTarget in 1..7) return CClass.WEEKDAY
        if (repeatKind != RepeatKind.NONE) return CClass.REPEAT
        if (inheritDate != null) return CClass.INHERITED
        return CClass.NONE
    }

    private fun timePassedToday(): Boolean {
        val st = startTime ?: return false
        return !LocalDateTime.of(today, st).isAfter(now.toLocalDateTime())
    }

    private fun resolveDate(): LocalDate? {
        calDate?.let { return it }
        relDays?.let { return today.plusDays(it.toLong()) }
        relWeeks?.let { return today.plusDays(7L * it) }
        relMonths?.let { return today.plusMonths(it.toLong()) }
        if (hasNextMonth) {
            val base = today.plusMonths(1)
            return if (nextMonthDay > 0) {
                val d = minOf(nextMonthDay, base.lengthOfMonth())
                base.withDayOfMonth(d)
            } else base
        }
        if (weekdayMode != WMode.NONE && weekdayTarget in 1..7) {
            val monday = today.minusDays((isoDow - 1).toLong())
            val cand = when (weekdayMode) {
                WMode.BARE -> monday.plusDays((weekdayTarget - 1).toLong())
                WMode.THIS_WEEK -> monday.plusDays((weekdayTarget - 1).toLong())
                WMode.NEXT1 -> monday.plusDays((7 + weekdayTarget - 1).toLong())
                WMode.NEXT2 -> monday.plusDays((14 + weekdayTarget - 1).toLong())
                WMode.PREV -> monday.minusDays((7 - weekdayTarget + 1).toLong())
                WMode.NONE -> today
            }
            var c = cand
            if (c < today && (weekdayMode == WMode.BARE || weekdayMode == WMode.THIS_WEEK)) c = c.plusDays(7)
            return c
        }
        dateWordOffset?.let { return today.plusDays(it.toLong()) }
        return when (repeatKind) {
            RepeatKind.WEEKLY -> {
                val monday = today.minusDays((isoDow - 1).toLong())
                var c = monday.plusDays((repeatWeekday - 1).toLong())
                if (c < today) c = c.plusDays(7)
                if (c == today && timePassedToday()) c = c.plusDays(7)
                c
            }
            RepeatKind.MONTHLY -> {
                val day = if (repeatMonthDay in 1..31) repeatMonthDay else today.dayOfMonth
                val thisMonth = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
                if (thisMonth > today || (thisMonth == today && !timePassedToday())) thisMonth
                else {
                    val nm = today.plusMonths(1)
                    nm.withDayOfMonth(minOf(day, nm.lengthOfMonth()))
                }
            }
            RepeatKind.YEARLY -> {
                val y = safeDate(today.year, repeatYearMonth, repeatYearDay)
                if (y != null && y >= today) y
                else safeDate(today.year + 1, repeatYearMonth, repeatYearDay) ?: today
            }
            RepeatKind.DAILY -> if (timePassedToday()) today.plusDays(1) else today
            RepeatKind.WEEKDAYS -> {
                var c = today
                while (c.dayOfWeek.value > 5) c = c.plusDays(1)
                if (c == today && timePassedToday()) {
                    c = c.plusDays(1)
                    while (c.dayOfWeek.value > 5) c = c.plusDays(1)
                }
                c
            }
            else -> inheritDate ?: today
        }
    }

    // ---------------- 标题 / 备注 ----------------

    private fun buildLeftover(): String {
        val sb = StringBuilder(u.length)
        for (i in u.indices) sb.append(if (marked[i]) ' ' else u[i])
        var s = sb.toString()
        s = Regex("""\s+""").replace(s, " ")
        s = RE_CJK_SPACE.replace(s, "")
        return s.trim()
    }

    private fun stripPrefix(s: String): Pair<String, String> {
        var body = s
        var prefix = ""
        val m1 = RE_PREFIX_BRACKET.find(body)
        if (m1 != null && m1.range.first == 0) {
            val rest = body.substring(m1.range.last + 1)
            if (rest.isNotBlank()) {
                prefix = m1.value.trim()
                body = rest
            }
        }
        val m2 = RE_PREFIX_COLON.find(body)
        if (m2 != null && m2.range.first == 0) {
            val name = m2.groupValues[1]
            val rest = body.substring(m2.range.last + 1)
            if (rest.isNotBlank() && name !in PREFIX_BLACKLIST && name.none { it.isDigit() }) {
                prefix = listOf(prefix, m2.value.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                body = rest
            }
        }
        return prefix to body.trim()
    }

    private fun cleanTitle(s: String): String {
        var t = s.trim()
        var changed = true
        while (changed) {
            changed = false
            for (f in TITLE_FILLERS) {
                if (t.startsWith(f)) {
                    t = t.substring(f.length).trim()
                    changed = true
                }
            }
        }
        t = t.trim(*TRIM_CHARS)
        t = Regex("""\s+""").replace(t, " ").trim()
        if (t.isEmpty()) return FALLBACK_TITLE
        return if (t.length > TITLE_MAX) t.substring(0, TITLE_MAX) else t
    }
}

private val RE_CJK_SPACE = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])""")

// ======================================================================
// 中文日期/时段词表（按长度倒序匹配）
// ======================================================================

private val WORD_LIST: List<WordEntry> = listOf(
    // 日期词（含隐含时段）
    WordEntry("大前天", -3, null, null),
    WordEntry("大后天", 3, null, null),
    WordEntry("今天早上", 0, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("今天上午", 0, PeriodKind.MORNING, LocalTime.of(9, 0)),
    WordEntry("今天中午", 0, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("今天下午", 0, PeriodKind.AFTERNOON, LocalTime.of(14, 0)),
    WordEntry("今天晚上", 0, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("明天早上", 1, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("明天上午", 1, PeriodKind.MORNING, LocalTime.of(9, 0)),
    WordEntry("明天中午", 1, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("明天下午", 1, PeriodKind.AFTERNOON, LocalTime.of(14, 0)),
    WordEntry("明天晚上", 1, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("后天早上", 2, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("后天中午", 2, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("后天下午", 2, PeriodKind.AFTERNOON, LocalTime.of(14, 0)),
    WordEntry("后天晚上", 2, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("今天", 0, null, null),
    WordEntry("今日", 0, null, null),
    WordEntry("今儿", 0, null, null),
    WordEntry("本日", 0, null, null),
    WordEntry("明天", 1, null, null),
    WordEntry("明日", 1, null, null),
    WordEntry("明儿", 1, null, null),
    WordEntry("明个", 1, null, null),
    WordEntry("后天", 2, null, null),
    WordEntry("昨天", -1, null, null),
    WordEntry("昨日", -1, null, null),
    WordEntry("前天", -2, null, null),
    WordEntry("今早", 0, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("今晨", 0, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("今晚", 0, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("今夜", 0, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("明早", 1, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("明晨", 1, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("明晚", 1, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("昨晚", -1, PeriodKind.EVENING, LocalTime.of(19, 0)),
    // 纯时段词
    WordEntry("上午", null, PeriodKind.MORNING, LocalTime.of(9, 0)),
    WordEntry("早上", null, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("早晨", null, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("清晨", null, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("一早", null, PeriodKind.MORNING, LocalTime.of(8, 0)),
    WordEntry("凌晨", null, PeriodKind.NIGHT, LocalTime.of(6, 0)),
    WordEntry("半夜", null, PeriodKind.NIGHT, LocalTime.of(0, 0)),
    WordEntry("深夜", null, PeriodKind.NIGHT, LocalTime.of(0, 0)),
    WordEntry("中午", null, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("晌午", null, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("正午", null, PeriodKind.NOON, LocalTime.of(12, 0)),
    WordEntry("下午", null, PeriodKind.AFTERNOON, LocalTime.of(14, 0)),
    WordEntry("傍晚", null, PeriodKind.EVENING, LocalTime.of(18, 0)),
    WordEntry("晚上", null, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("晚间", null, PeriodKind.EVENING, LocalTime.of(19, 0)),
    WordEntry("夜里", null, PeriodKind.EVENING, LocalTime.of(21, 0)),
    WordEntry("夜晚", null, PeriodKind.EVENING, LocalTime.of(21, 0))
)

private val WORD_TABLE_BY_LEN: Map<Int, Map<String, WordEntry>> =
    WORD_LIST.groupBy { it.text.length }.mapValues { (_, v) -> v.associateBy { it.text } }
