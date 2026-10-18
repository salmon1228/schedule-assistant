package com.sdh.schedule.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 全局设置。写操作会自增 [revision],Compose 侧 collect 它即可自动刷新界面。
 */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _revision.value = _revision.value + 1
    }

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int): Int = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    private fun flt(key: String, def: Float): Float = sp.getFloat(key, def)

    // ---------------- 解析引擎 ----------------

    /**
     * AUTO(默认)= 优先云端大模型,只有「没配 Key / 当前离线 / API 请求失败」才回退本地规则;
     * LLM_ONLY / LOCAL_ONLY 为强制模式。
     */
    var llmMode: String
        get() = str(K_LLM_MODE, MODE_AUTO)
        set(v) = edit { putString(K_LLM_MODE, v) }

    /** OpenAI 兼容接口,例如 https://api.deepseek.com/v1 , https://dashscope.aliyuncs.com/compatible-mode/v1 */
    var llmBaseUrl: String
        get() = str(K_LLM_BASE, "https://api.deepseek.com/v1")
        set(v) = edit { putString(K_LLM_BASE, v.trim()) }

    var llmApiKey: String
        get() = str(K_LLM_KEY, "")
        set(v) = edit { putString(K_LLM_KEY, v.trim()) }

    var llmModel: String
        get() = str(K_LLM_MODEL, "deepseek-chat")
        set(v) = edit { putString(K_LLM_MODEL, v.trim()) }

    /** 识别图片用的大模型;留空表示不支持图片(此时图片依赖本地 OCR) */
    var llmVisionModel: String
        get() = str(K_LLM_VISION, "")
        set(v) = edit { putString(K_LLM_VISION, v.trim()) }

    var llmTimeoutSeconds: Int
        get() = int(K_LLM_TIMEOUT, 25)
        set(v) = edit { putInt(K_LLM_TIMEOUT, v) }

    /** 本地 OCR(ML Kit 中文离线识别) */
    var ocrEnabled: Boolean
        get() = bool(K_OCR, true)
        set(v) = edit { putBoolean(K_OCR, v) }

    val llmConfigured: Boolean get() = llmApiKey.isNotBlank() && llmBaseUrl.isNotBlank()

    // ---------------- 通知监听 ----------------

    /** 用户开关(与系统里的"通知使用权"是两回事,这里只是软开关) */
    var listenerSwitch: Boolean
        get() = bool(K_LISTENER_SWITCH, true)
        set(v) = edit { putBoolean(K_LISTENER_SWITCH, v) }

    /** 只有文本长度达到该值才认为是"大段文字" */
    var minTextLength: Int
        get() = int(K_MIN_LEN, 12)
        set(v) = edit { putInt(K_MIN_LEN, v) }

    /** 常驻通知(音乐播放器、下载进度等)直接忽略 */
    var ignoreOngoing: Boolean
        get() = bool(K_IGNORE_ONGOING, true)
        set(v) = edit { putBoolean(K_IGNORE_ONGOING, v) }

    /** 白名单包名,逗号分隔;为空表示不限制 */
    var packageWhitelist: String
        get() = str(K_WL, "")
        set(v) = edit { putString(K_WL, v) }

    /** 黑名单包名,逗号分隔 */
    var packageBlacklist: String
        get() = str(K_BL, "")
        set(v) = edit { putString(K_BL, v) }

    fun whitelistSet(): Set<String> = splitPackages(packageWhitelist)
    fun blacklistSet(): Set<String> = splitPackages(packageBlacklist)

    private fun splitPackages(raw: String): Set<String> =
        raw.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 同一条文本多久内不重复提示(分钟) */
    var dedupeWindowMinutes: Int
        get() = int(K_DEDUPE, 180)
        set(v) = edit { putInt(K_DEDUPE, v) }

    /** 置信度高于该值时直接建日程(不再进"待确认") */
    var autoCreate: Boolean
        get() = bool(K_AUTO_CREATE, false)
        set(v) = edit { putBoolean(K_AUTO_CREATE, v) }

    var autoCreateThreshold: Float
        get() = flt(K_AUTO_TH, 0.85f)
        set(v) = edit { putFloat(K_AUTO_TH, v) }

    /** 解析出候选日程时,给一条"聊天式"通知提示 */
    var notifySuggestion: Boolean
        get() = bool(K_NOTIFY_SUGGESTION, true)
        set(v) = edit { putBoolean(K_NOTIFY_SUGGESTION, v) }

    /**
     * 内置过滤:系统时钟(它自己就是闹钟)、系统更新、桌面、设置、安全中心这类
     * 通知天然不是日程,直接忽略。实测它们会污染「待确认」(比如时钟的"闹钟"被当成日程)。
     */
    var builtinFilter: Boolean
        get() = bool(K_BUILTIN_FILTER, true)
        set(v) = edit { putBoolean(K_BUILTIN_FILTER, v) }

    /** 学校教务系统的网址(内置浏览器导入课表时用,记住上次填的) */
    var eduSystemUrl: String
        get() = str(K_EDU_URL, "")
        set(v) = edit { putString(K_EDU_URL, v.trim()) }


    /**
     * 待办只从聊天/工作类通知里抽(钉钉/微信/QQ/短信/飞书…)。
     * 实测放开抽取时:建了 104 条,用户删掉 72 条 —— 京东"确认收货"、天气"注意防范雷电"
     * 这类营销/预警文案会被误当成待办。默认收紧。
     */
    var todoOnlyFromChatApps: Boolean
        get() = bool(K_TODO_CHAT_ONLY, true)
        set(v) = edit { putBoolean(K_TODO_CHAT_ONLY, v) }

    /** 同一个 App 每天最多自动加几条待办(0 = 不限) */
    var todoDailyCapPerApp: Int
        get() = int(K_TODO_DAILY_CAP, 5)
        set(v) = edit { putInt(K_TODO_DAILY_CAP, v) }

    /** 监听截图目录:截图后自动尝试识别(需相册读取权限) */
    var watchScreenshots: Boolean
        get() = bool(K_WATCH_SHOT, false)
        set(v) = edit { putBoolean(K_WATCH_SHOT, v) }

    /** 消息里带链接时,联网抓取网页正文一起解析(报名页/公告里的时间常常只在网页里) */
    var fetchLinks: Boolean
        get() = bool(K_FETCH_LINKS, true)
        set(v) = edit { putBoolean(K_FETCH_LINKS, v) }

    // ---------------- 提醒铃声 / AI 判断提醒时间 ----------------

    /**
     * 提醒声音:
     *   system = 跟随系统通知音(走通知音量,就是微信/QQ 那种普通提示音)—— **默认**
     *   alarm  = 用闹钟音(走闹钟音量,静音/振动下也能响,适合必须叫醒你的日程)
     *   silent = 只显示通知,不发声
     *
     * 默认给 system:用户预期就是"来消息噔一声",而闹钟音依赖系统闹钟铃声文件,
     * 有些机器上那个文件是失效的媒体库 URI,反而一声不响。
     */
    var reminderSound: String
        get() = str(K_REMIND_SOUND, SOUND_SYSTEM)
        set(v) = edit { putString(K_REMIND_SOUND, v) }

    /** 让大模型按任务类型自动判断提醒时间(原文没写提醒时) */
    var aiDecideReminder: Boolean
        get() = bool(K_AI_REMIND, true)
        set(v) = edit { putBoolean(K_AI_REMIND, v) }

    // ---------------- 课程表 ----------------

    /** 学期第一周的周一(epochDay);0 = 未设置,按本周一算 */
    var semesterStartEpochDay: Long
        get() = sp.getLong(K_SEMESTER, 0L)
        set(v) = edit { putLong(K_SEMESTER, v) }

    fun semesterStartDate(): LocalDate {
        val d = semesterStartEpochDay
        return if (d > 0) LocalDate.ofEpochDay(d) else LocalDate.now().with(DayOfWeek.MONDAY)
    }

    /** 每天各节次的起止时间,逗号分隔,如 "08:00-08:45,08:55-09:40" */
    var slotTimes: String
        get() = str(K_SLOT_TIMES, DEFAULT_SLOT_TIMES)
        set(v) = edit { putString(K_SLOT_TIMES, v.trim()) }

    /** 时间表名称(为将来"多套作息"留位,现在只有一套) */
    var slotTimesName: String
        get() = str(K_SLOT_NAME, "默认")
        set(v) = edit { putString(K_SLOT_NAME, v.trim().ifBlank { "默认" }) }

    /** 是否"固定上课时长":改了开始时间,结束时间自动 = 开始 + 时长 */
    var slotFixedDuration: Boolean
        get() = bool(K_SLOT_FIXED, true)
        set(v) = edit { putBoolean(K_SLOT_FIXED, v) }

    /** 每节课时长(分钟),配合"固定上课时长"用 */
    var slotDurationMinutes: Int
        get() = int(K_SLOT_DURATION, 45)
        set(v) = edit { putInt(K_SLOT_DURATION, v.coerceIn(10, 180)) }

    /** 解析节次时间;为空或格式不对时回退默认 */
    fun slotList(): List<Pair<LocalTime, LocalTime>> {
        val list = slotTimes.split(',').mapNotNull { item ->
            val parts = item.trim().split('-')
            if (parts.size != 2) return@mapNotNull null
            val s = parseTime(parts[0])
            val e = parseTime(parts[1])
            if (s == null || e == null) null else s to e
        }
        return list.ifEmpty {
            DEFAULT_SLOT_TIMES.split(',').mapNotNull { item ->
                val parts = item.trim().split('-')
                if (parts.size != 2) return@mapNotNull null
                val s = parseTime(parts[0]); val e = parseTime(parts[1])
                if (s == null || e == null) null else s to e
            }
        }
    }

    /** 上课前自动提醒总开关 */
    var courseReminderEnabled: Boolean
        get() = bool(K_COURSE_REMIND, true)
        set(v) = edit { putBoolean(K_COURSE_REMIND, v) }

    /** 上课提醒默认提前量(分钟) */
    var courseDefaultReminder: Int
        get() = int(K_COURSE_REMIND_MIN, 10)
        set(v) = edit { putInt(K_COURSE_REMIND_MIN, v) }

    private fun parseTime(s: String): LocalTime? = runCatching {
        val p = s.trim().split(':')
        LocalTime.of(p[0].trim().toInt(), p.getOrElse(1) { "0" }.trim().toInt())
    }.getOrNull()

    // ---------------- 新建日程的默认值 ----------------

    var defaultReminderMinutes: Int
        get() = int(K_DEF_REMIND, 10)
        set(v) = edit { putInt(K_DEF_REMIND, v) }

    var defaultAlarmReminder: Boolean
        get() = bool(K_DEF_ALARM, false)
        set(v) = edit { putBoolean(K_DEF_ALARM, v) }

    var defaultCountdownReminder: Boolean
        get() = bool(K_DEF_COUNTDOWN, false)
        set(v) = edit { putBoolean(K_DEF_COUNTDOWN, v) }

    var defaultAccount: String
        get() = str(K_DEF_ACCOUNT, "本地日历")
        set(v) = edit { putString(K_DEF_ACCOUNT, v) }

    /** 通知权限/后台白名单向导是否已完成 */
    var permissionWizardDone: Boolean
        get() = bool(K_WIZARD, false)
        set(v) = edit { putBoolean(K_WIZARD, v) }

    var darkMode: String
        get() = str(K_DARK, "system")
        set(v) = edit { putString(K_DARK, v) }

    companion object {
        const val MODE_AUTO = "AUTO"
        const val MODE_LLM_ONLY = "LLM_ONLY"
        const val MODE_LOCAL_ONLY = "LOCAL_ONLY"

        const val SOUND_SYSTEM = "system"
        const val SOUND_ALARM = "alarm"
        const val SOUND_SILENT = "silent"

        /** 默认节次时间(常见高校作息:上午 4 节、下午 4 节、晚上 4 节) */
        const val DEFAULT_SLOT_TIMES =
            "08:00-08:45,08:55-09:40,10:00-10:45,10:55-11:40," +
                "14:00-14:45,14:55-15:40,16:00-16:45,16:55-17:40," +
                "19:00-19:45,19:55-20:40,20:50-21:35,21:45-22:30"

        private const val K_LLM_MODE = "llm_mode"
        private const val K_LLM_BASE = "llm_base"
        private const val K_LLM_KEY = "llm_key"
        private const val K_LLM_MODEL = "llm_model"
        private const val K_LLM_VISION = "llm_vision"
        private const val K_LLM_TIMEOUT = "llm_timeout"
        private const val K_OCR = "ocr_enabled"
        private const val K_LISTENER_SWITCH = "listener_switch"
        private const val K_MIN_LEN = "min_text_len"
        private const val K_IGNORE_ONGOING = "ignore_ongoing"
        private const val K_WL = "pkg_whitelist"
        private const val K_BL = "pkg_blacklist"
        private const val K_DEDUPE = "dedupe_minutes"
        private const val K_AUTO_CREATE = "auto_create"
        private const val K_AUTO_TH = "auto_create_threshold"
        private const val K_NOTIFY_SUGGESTION = "notify_suggestion"
        private const val K_WATCH_SHOT = "watch_screenshots"
        private const val K_FETCH_LINKS = "fetch_links"
        private const val K_REMIND_SOUND = "reminder_sound"
        private const val K_AI_REMIND = "ai_decide_reminder"
        private const val K_SEMESTER = "semester_start_epoch_day"
        private const val K_SLOT_NAME = "slot_name"
        private const val K_SLOT_FIXED = "slot_fixed"
        private const val K_SLOT_DURATION = "slot_duration"
        private const val K_SLOT_TIMES = "slot_times"
        private const val K_COURSE_REMIND = "course_reminder_enabled"
        private const val K_COURSE_REMIND_MIN = "course_reminder_minutes"
        private const val K_DEF_REMIND = "default_reminder"
        private const val K_DEF_ALARM = "default_alarm"
        private const val K_DEF_COUNTDOWN = "default_countdown"
        private const val K_DEF_ACCOUNT = "default_account"
        private const val K_WIZARD = "wizard_done"
        private const val K_DARK = "dark_mode"
        private const val K_EDU_URL = "edu_system_url"
        private const val K_BUILTIN_FILTER = "builtin_filter"
        private const val K_TODO_CHAT_ONLY = "todo_chat_only"
        private const val K_TODO_DAILY_CAP = "todo_daily_cap"
        private const val K_EDU_DESKTOP = "edu_desktop_mode"
    }
}


