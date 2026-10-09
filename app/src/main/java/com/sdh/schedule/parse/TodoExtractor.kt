package com.sdh.schedule.parse

import com.sdh.schedule.data.ParsedTodo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 从一段文字里抽「待办事项」。
 *
 * 与日程的区别:
 *   「明天下午3点开会」   → 日程(有明确开始时间)
 *   「准备好一寸照片」     → 待办(只是要做的一件事,没有开始时间)
 *
 * 典型场景:群里老师说「明天会发一张表格下来需要我们填写,准备好一寸照片」
 *   → 日程:明天的事情;待办:准备一寸照片
 *
 * 纯 Kotlin、无 Android 依赖,可单元测试。
 */
object TodoExtractor {

    /** 任务动词/提示词:命中就认为这一句是在"交代一件事" */
    private val TASK_HINTS = listOf(
        "记得", "别忘", "不要忘", "务必", "一定要", "需要", "还需要", "请", "麻烦",
        "准备", "准备好", "备好", "带上", "带好", "领取", "打印", "复印", "填写", "填好",
        "提交", "上交", "交", "上传", "下载", "报名", "缴费", "交费", "购买", "买", "充值",
        "预约", "挂号", "联系", "回复", "确认", "核对", "整理", "复习", "预习", "完成",
        "做完", "做完", "做一下", "办理", "申请", "寄", "发一份", "拍", "拍照", "截图", "保存",
        // 补:实测用户真实待办里出现、但原列表漏掉的动词
        "登记", "统计", "汇报", "申报", "检查", "排查", "更换", "停用", "处理", "补交"
    )

    /**
     * 强噪声:整段文字里出现这些,就**整条都不抽**。
     * 因为噪声词和任务词常常落在不同句子里,例如
     * 「您有一张优惠券即将过期,记得使用」——第二句单看像待办,其实整条都是营销。
     */
    private val STRONG_NOISE = listOf(
        "确认收货", "延长收货", "联系商家", "联系客服", "申请售后", "运费险",
        "优惠券", "满减", "秒杀", "抢购", "限时特价", "立即购买", "加入购物车", "包邮",
        "账单已出", "还款成功", "到账", "入账", "消费提醒", "扣款",
        "注意防范", "预警", "尽量减少", "尽量避免", "谨防", "恶劣天气",
        "立即参与", "点击查看", "点击领取", "敬请期待", "开通会员", "清理垃圾", "安全检测",
        "版本更新", "应用更新", "存储空间不足"
    )

    /**
     * 强噪声的正则版:噪声词中间常夹字(「确认**商品**收货」「您有一张**优惠券**即将过期」),
     * 单纯 contains 会漏,这里用正则兜住。
     */
    private val STRONG_NOISE_REGEX = listOf(
        Regex("确认.{0,6}收货"),
        Regex("收货.{0,6}确认"),
        Regex("延长.{0,4}收货"),
        Regex(".{0,6}优惠券"),
        Regex(".{0,4}满减"),
        Regex(".{0,6}立即参与"),
        Regex(".{0,6}点击(查看|领取)"),
        Regex(".{0,6}账单已出"),
        Regex(".{0,8}注意防范"),
        Regex(".{0,6}预警"),
        Regex(".{0,6}清理垃圾"),
        Regex(".{0,6}存储空间不足")
    )

    /** 抽出来只是个泛泛动词的,不要(比如「查看」「使用」「点击」) */
    private val GENERIC_TITLE = setOf(
        "查看", "使用", "点击", "点击查看", "下载", "打开", "参与", "关注", "了解",
        "知道", "注意", "及时查看", "及时使用", "查看详情", "详情", "更多", "活动"
    )

    /** 明显是日程/通知的句子,不要抽成待办(有时间点或纯通知口吻) */
    private val EVENT_ONLY_HINTS = listOf(
        "会议", "开会", "上课", "考试", "航班", "高铁", "面试", "体检", "聚餐", "约会",
        "签到", "打卡", "直播", "讲座"
    )

    /**
     * 营销 / 预警 / 通知模板类文案:命中就把整句丢掉。
     *
     * 实测(用户真机数据):放开抽取时建了 104 条待办、其中 72 条被删,
     * 典型误报是「确认收货(如未收到可延长收货或联系商家)」「注意防范雷电」
     * 「尽量减少私家车自驾前往」这类电商/天气话术。
     */
    private val NOISE_PATTERNS = listOf(
        // 电商 / 物流营销
        "确认收货", "延长收货", "联系商家", "联系客服", "申请售后", "极速退款", "运费险",
        "优惠", "满减", "领券", "优惠券", "红包", "秒杀", "抢购", "限时", "折扣", "特价",
        "包邮", "加入购物车", "立即购买", "查看物流", "评价晒单",
        // 天气 / 灾害预警
        "注意防范", "预警", "尽量减少", "尽量避免", "谨防", "防范", "恶劣天气",
        "雷暴", "雷电", "暴雨", "高温", "寒潮", "大风",
        // 系统 / 运营话术
        "点击查看", "点击领取", "立即参与", "快来", "敬请期待", "更多优惠", "推送设置",
        "已为您", "为您推荐", "猜你喜欢", "积分", "签到得", "任务中心", "开通会员",
        "版本更新", "新版本", "应用更新", "存储空间不足", "清理垃圾", "安全检测",
        // 账单类(不是"要做的事")
        "账单已出", "还款成功", "到账", "入账", "消费提醒", "扣款"
    )

    /** 句末语气词,做标题时去掉 */
    private val TAIL_NOISE = listOf("哦", "呀", "啊", "吧", "呢", "了", "哈", "谢谢", "谢啦", "~")

    /** 断句用的分隔符(全角/半角都算);用转义写,避免全角看起来一样却匹配不到 */
    private val SEPARATORS = charArrayOf(
        '\n', '\r',
        '\u3002', // 。
        '\uFF0C', // ,
        '\uFF1B', // ;
        '\uFF01', // !
        '\uFF1F', // ?
        '\u3001', // 、
        '.', ',', ';', '!', '?'
    )

    fun extract(text: String, nowMillis: Long, zone: ZoneId): List<ParsedTodo> {
        if (text.isBlank()) return emptyList()
        // 整条文字里出现强噪声词 → 整条都不抽(营销/预警/系统话术)
        if (STRONG_NOISE.any { text.contains(it) }) return emptyList()
        if (STRONG_NOISE_REGEX.any { it.containsMatchIn(text) }) return emptyList()

        val out = ArrayList<ParsedTodo>()
        val seen = HashSet<String>()

        for (segment in splitSegments(text)) {
            val seg = segment.trim().trimStart('-', '*', '\u2022', ' ', '\t')
            if (seg.length < 3 || seg.length > 80) continue
            // 明显是"安排"口吻的句子不当待办
            if (EVENT_ONLY_HINTS.any { seg.contains(it) }) continue
            // 营销 / 预警 / 通知模板:整句丢掉
            if (NOISE_PATTERNS.any { seg.contains(it) }) continue

            // 找到任务动词;没有就不当待办(避免把纯日程/闲聊算成待办)
            val hit = TASK_HINTS.firstOrNull { seg.contains(it) } ?: continue
            val hitIndex = seg.indexOf(hit)
            // 从任务动词开始截取,前面那截只用来推断截止时间
            val taskPhrase = seg.substring(hitIndex)
            val beforePhrase = seg.substring(0, hitIndex)

            val title = cleanTitle(taskPhrase, hit)
            if (title.length < 2) continue
            // 只是个泛泛动词的不要(「查看」「使用」这类)
            if (title in GENERIC_TITLE) continue
            if (!seen.add(title)) continue

            val strength = when (hit) {
                "\u8bb0\u5f97", "\u522b\u5fd8", "\u52a1\u5fc5", "\u4e00\u5b9a\u8981" -> 0.8
                "\u51c6\u5907", "\u51c6\u5907\u597d", "\u5e26\u4e0a", "\u586b\u5199", "\u63d0\u4ea4", "\u4e0a\u4ea4",
                "\u6253\u5370", "\u9886\u53d6", "\u8d2d\u4e70" -> 0.75
                "\u8bf7", "\u9ebb\u70e6", "\u9700\u8981", "\u8fd8\u9700\u8981" -> 0.7
                else -> 0.6
            }
            out.add(
                ParsedTodo(
                    title = title,
                    dueMillis = inferDue(beforePhrase.ifBlank { seg }, nowMillis, zone),
                    note = "",
                    confidence = strength,
                    matched = listOf(hit),
                    sourceText = text
                )
            )
            if (out.size >= MAX_PER_TEXT) break
        }
        return out
    }

    /** 一条通知最多抽几条待办(以前是 5,实测容易刷屏;一条消息通常就一件事) */
    const val MAX_PER_TEXT = 2

    /** 拆句:换行、句号、分号、逗号、问号、叹号都当作分隔 */
    private fun splitSegments(text: String): List<String> =
        text.replace("\r\n", "\n")
            .split(*SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 标题开头常见的"人称呼唤/情态词",要去掉 */
    private val PREFIX_WORDS = listOf(
        "那么", "所以", "然后", "另外", "还有", "最后", "首先", "其次",
        "记得", "别忘", "不要忘", "别忘了", "务必", "一定要", "千万",
        "请大家", "请", "麻烦", "拜托",
        "需要", "还需要", "还需", "应该", "可以", "得", "要", "必须", "及时", "尽快", "提前",
        "大家", "我们", "你们", "你", "各位", "同学们", "同学们", "把", "将"
    )

    /** 生成干净的标题:循环去掉引导词、语气词 */
    private fun cleanTitle(segment: String, hit: String): String {
        var s = segment.trim()
        var changed = true
        var guard = 0
        while (changed && s.length > 2 && guard < 6) {
            changed = false
            for (w in PREFIX_WORDS) {
                if (s.startsWith(w) && s.length > w.length + 1) {
                    s = s.substring(w.length).trim()
                    changed = true
                    break
                }
            }
            guard++
        }
        // 去掉结尾的语气词
        for (noise in TAIL_NOISE) {
            if (s.endsWith(noise) && s.length > noise.length + 2) s = s.dropLast(noise.length)
        }
        // 口语化的动词统一一下
        s = s.replace("准备好", "准备")
        s = s.replace("带好", "带").replace("填好", "填写").replace("做完", "完成")
        s = s.trim().trimStart(':', '：', '~', ' ').trimEnd(':', '：', '~', ' ')
        if (s.length > 40) s = s.take(40)
        return s
    }

    /** 待办的截止时间:句子里如果有"明天/周五/9月20日"这类日期,就作为截止日 */
    private fun inferDue(segment: String, nowMillis: Long, zone: ZoneId): Long {
        val m = Regex("(明天|后天|大后天|今天|本周[一二三四五六日天]|这周[一二三四五六日天]|下周[一二三四五六日天]|周[一二三四五六日天]|"
            + "\\d{1,2}月\\d{1,2}[日号]|\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2})").find(segment) ?: return 0L
        val parsed = ChineseDateParser.parse(m.value + " 12:00", nowMillis, zone) ?: return 0L
        // 全天的截止日,取当天 23:59
        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsed.startMillis), zone).toLocalDate()
        return date.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }
}
