package com.sdh.schedule.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.ParseCoordinator
import java.util.concurrent.Executors

/**
 * 通知监听:把别的 App 发来的「大段文字通知」和「图片通知」自动变成日程。
 *
 * 过滤原则(宁缺毋滥,避免把群聊闲话做成日程):
 *   1. 只处理用户允许的包名;
 *   2. 常驻通知(音乐/下载)、分组摘要直接跳过;
 *   3. 文本太短且没有时间词/事件词 → 跳过;
 *   4. 同一条通知被系统反复更新时,内容没变就不重复处理。
 */
class ScheduleNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"

        /** 时间线索:命中其一才认为"可能有日程" */
        private val TIME_HINT = Regex(
            "\\d{1,2}\\s*[:：]\\s*\\d{2}" +          // 14:00
                "|\\d{1,2}\\s*点" +                    // 3点 / 9点半
                "|明天|后天|大后天|今晚|明早|明晚" +
                "|下周|本周|这周|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]" +
                "|\\d{1,2}月\\d{1,2}[日号]|\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}" +
                "|提前\\d|每天|每日|每周|每月|每年|工作日|隔天" +
                "|\\d+\\s*(分钟|小时|天|周)后"
        )

        /** 事件线索:命中其一才认为"像一件要办的事" */
        private val EVENT_HINT = Regex(
            "会议|开会|面试|考试|答辩|报名|截止|deadline|ddl|meeting|" +
                "体检|复诊|就诊|挂号|还款|缴费|交费|续费|房租|账单|" +
                "生日|纪念日|周年|结婚|乔迁|" +
                "出差|航班|机票|高铁|火车|车次|值机|登机|" +
                "上课|课程|培训|讲座|直播|签到|打卡|" +
                "聚餐|饭局|约会|见面|拜访|接机|送机|" +
                "提交|交材料|答辩|汇报|评审|验收|上线|发布|" +
                "办理|签约|面试|搬家|旅游|行程|安排|日程|提醒我",
            RegexOption.IGNORE_CASE
        )

        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "notif-parse").apply { isDaemon = true }
        }

        /**
         * 内置过滤名单:这些 App 的通知天然不是日程。
         * 最典型的是系统时钟 —— 它自己就是闹钟,它的通知被当成"新日程"提过一次(实测噪音)。
         */
        private val BUILTIN_SKIP = setOf(
            "com.android.deskclock",                 // 时钟(自己的闹钟/计时器)
            "com.android.updater",                   // 系统更新
            "com.miui.updater",
            "com.android.systemui",                  // 系统界面
            "com.miui.home",                         // 桌面
            "com.android.settings",                  // 设置
            "com.android.providers.downloads",       // 下载管理
            "com.android.providers.downloads.ui",
            "com.miui.securitycenter",               // 安全中心
            "com.miui.cleanmaster",
            "com.miui.systemAdSolution",             // 广告
            "com.miui.analytics",
            "com.android.vending",                   // 应用商店
            "com.xiaomi.market"
        )

        /** key -> 已处理过的文本指纹,避免通知更新导致重复解析 */
        private val handled = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > 300
        }

        @Volatile
        var connected: Boolean = false
            private set

        /** 系统里的"通知使用权"是否给了本应用 */
        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, ScheduleNotificationListener::class.java)
            return flat.split(':').any {
                ComponentName.unflattenFromString(it)?.let { c ->
                    c.packageName == cn.packageName && c.className == cn.className
                } == true
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Log.i(TAG, "通知监听已连接")
        runCatching { Repo.init(applicationContext) }
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
        Log.i(TAG, "通知监听已断开")
    }

    /** 记录"这条通知为什么没被解析",方便在「导入日志」里排查偶尔失灵的情况 */
    private fun skipLog(context: android.content.Context, content: NotifContent?, reason: String) {
        val c = content ?: return
        val text = c.fullText
        if (text.isBlank()) return
        runCatching {
            Repo.log(
                source = EventSource.NOTIFICATION_TEXT,
                sourcePackage = "${c.packageName}(${c.appLabel})",
                rawText = text,
                engine = "none",
                outcome = "跳过",
                note = reason
            )
        }
    }

    /** 黑/白名单判断发生在解析之前,这里只做得轻量的一次提取(拿不到就返回 null) */
    private fun content0(sbn: StatusBarNotification): NotifContent? =
        runCatching { NotifExtractor.extract(applicationContext, sbn) }.getOrNull()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        // 主线程不能干重活,立刻扔到后台线程
        executor.execute { runCatching { handle(s) }.onFailure { Log.e(TAG, "处理通知失败", it) } }
    }

    private fun handle(sbn: StatusBarNotification) {
        val context = applicationContext
        Repo.init(context)
        val settings = Repo.settings

        if (!settings.listenerSwitch) return
        if (sbn.packageName == packageName) return

        // 内置过滤:系统时钟自己就是闹钟、系统更新/桌面/设置这类通知天然不是日程
        if (settings.builtinFilter && sbn.packageName in BUILTIN_SKIP) {
            Log.v(TAG, "内置过滤,跳过:${sbn.packageName}")
            return
        }

        // 白/黑名单
        val black = settings.blacklistSet()
        if (black.contains(sbn.packageName)) {
            skipLog(context, content0(sbn), "在黑名单里,已跳过")
            return
        }
        val white = settings.whitelistSet()
        if (white.isNotEmpty() && !white.contains(sbn.packageName)) {
            skipLog(context, content0(sbn), "不在白名单里,已跳过")
            return
        }

        val content = NotifExtractor.extract(context, sbn) ?: return
        if (settings.ignoreOngoing && content.isOngoing && content.bitmap == null) {
            // 注意:**不要**写日志。音乐/运动/下载这类常驻通知每秒都在更新,
            // 之前一记就记出 4.4 万条(库被撑到 8.7MB),而它们本来就不是日程。
            Log.v(TAG, "常驻通知,跳过:${content.packageName}")
            return
        }
        if (content.isGroupSummary) {
            Log.v(TAG, "分组摘要,跳过:${content.packageName}")
            return
        }

        // 系统截图通知:只有开启了对应开关才处理,且必须带图
        val isSystemUi = sbn.packageName == "com.android.systemui"
        if (isSystemUi) {
            if (!settings.watchScreenshots) return
            if (content.bitmap == null && content.imageUri == null) return
        }

        val text = content.fullText
        val hasImage = content.bitmap != null || content.imageUri != null
        if (text.isBlank() && !hasImage) return

        // 内容没变就不重复处理
        val hash = content.textHash() + "|" + (content.imageUri?.toString() ?: "")
        synchronized(handled) {
            if (handled[content.key] == hash) return
            handled[content.key] = hash
        }

        // 文本太短又没有时间/事件线索 → 跳过(写进导入日志,方便查"为什么这条没识别")
        val tooShort = text.length < settings.minTextLength
        val timeHit = TIME_HINT.containsMatchIn(text)
        val eventHit = EVENT_HINT.containsMatchIn(text)
        if (!hasImage && (tooShort || !(timeHit || eventHit))) {
            skipLog(
                context, content,
                when {
                    tooShort && !(timeHit || eventHit) ->
                        "文本只有 ${text.length} 字,且没有时间/事件词(阈值为 ${settings.minTextLength} 字)"
                    !timeHit && !eventHit -> "没有识别到时间词或事件词"
                    else -> "文本太短(${text.length} 字)"
                }
            )
            return
        }
        // 有图但图只是头像/图标(没有文字也没有时间线索)时,只有在文本像日程时才处理
        if (hasImage && content.bitmap == null && content.imageUri != null && !(timeHit || eventHit) && tooShort) {
            skipLog(context, content, "图片取不到内容且文本太短")
            return
        }

        val bitmap: Bitmap? = content.bitmap ?: content.imageUri?.let { uri ->
            // 图片通知(聊天图片/截图)有时只给 content:// ,尝试自己读一次
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
        val source = if (bitmap != null) {
            EventSource.NOTIFICATION_IMAGE
        } else {
            EventSource.NOTIFICATION_TEXT
        }

        Log.i(TAG, "待解析通知: ${content.appLabel} / ${content.key} / 图=${bitmap != null}")
        ParseCoordinator.process(
            context = context,
            source = source,
            sourcePackage = "${content.packageName}(${content.appLabel})",
            text = text,
            bitmap = bitmap,
            imagePath = ""
        )
    }
}
