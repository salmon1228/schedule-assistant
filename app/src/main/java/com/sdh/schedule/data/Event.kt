package com.sdh.schedule.data

import com.sdh.schedule.parse.RepeatKind

/** 日程类型:对应"创建日程"页顶部的四个 Tab */
enum class EventType(val label: String) {
    SCHEDULE("日程"),
    BIRTHDAY("生日"),
    ANNIVERSARY("纪念日"),
    COUNTDOWN("倒数日");

    companion object {
        fun fromName(name: String?): EventType =
            entries.firstOrNull { it.name == name } ?: SCHEDULE
    }
}

/** 日程来源,便于追溯"这条日程是从哪条通知/图片来的" */
enum class EventSource(val label: String) {
    MANUAL("手动创建"),
    NOTIFICATION_TEXT("通知文本"),
    NOTIFICATION_IMAGE("通知图片"),
    SHARE_TEXT("分享文本"),
    SHARE_IMAGE("分享图片"),
    IMPORT("导入");

    companion object {
        fun fromName(name: String?): EventSource =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

enum class Priority(val label: String) {
    NORMAL("普通"),
    IMPORTANT("重要");

    companion object {
        fun fromName(name: String?): Priority =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * 一条日程。字段基本对齐小米日历的"新建日程"表单:
 * 全天事件 / 开始时间 / 结束时间 / 重复 / 提醒 / 闹钟提醒 / 倒计时提醒 / 备注 / 日历账户 / 时区
 *
 * 注:提醒全部在本机完成(通知 / 闹钟 / 倒计时),不涉及任何短信与运营商。
 */
data class Event(
    val id: Long = 0L,
    val title: String = "",
    val note: String = "",
    val location: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val type: EventType = EventType.SCHEDULE,
    val repeat: RepeatKind = RepeatKind.NONE,
    val repeatWeekday: Int = 0,
    val repeatUntilMillis: Long = 0L,
    /** 提前多少分钟提醒;-1 表示不提醒 */
    val reminderMinutesBefore: Int = 10,
    /** 闹钟提醒:到点全屏响铃 */
    val alarmReminder: Boolean = false,
    /** 倒计时提醒:常驻通知栏倒计时 */
    val countdownReminder: Boolean = false,
    val account: String = "本地日历",
    val timeZone: String = "GMT+08:00 北京",
    val colorIndex: Int = 0,
    val priority: Priority = Priority.NORMAL,
    val source: EventSource = EventSource.MANUAL,
    val sourceText: String = "",
    val sourcePackage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val completed: Boolean = false
) {
    val hasReminder: Boolean get() = reminderMinutesBefore >= 0 || alarmReminder || countdownReminder

    /** 提醒触发的绝对时间 */
    val reminderAtMillis: Long
        get() = if (reminderMinutesBefore < 0) -1L else (startMillis - reminderMinutesBefore * 60_000L)

    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/** 从通知/图片/分享里解析出来、等待用户确认的候选日程 */
data class Suggestion(
    val id: Long = 0L,
    val title: String = "",
    val note: String = "",
    val location: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val repeat: RepeatKind = RepeatKind.NONE,
    val repeatWeekday: Int = 0,
    val reminderMinutesBefore: Int = -1,
    val confidence: Double = 0.0,
    /** 实际使用的解析引擎:rule / llm */
    val engine: String = "rule",
    val source: EventSource = EventSource.NOTIFICATION_TEXT,
    val sourceText: String = "",
    val sourcePackage: String = "",
    val imagePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** PENDING / ACCEPTED / REJECTED */
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
    }

    fun toEvent(account: String = "本地日历", defaultReminderMinutes: Int = 10): Event = Event(
        title = title,
        note = note,
        location = location,
        startMillis = startMillis,
        endMillis = endMillis,
        allDay = allDay,
        repeat = repeat,
        repeatWeekday = repeatWeekday,
        reminderMinutesBefore = if (reminderMinutesBefore < 0) defaultReminderMinutes else reminderMinutesBefore,
        account = account,
        source = source,
        sourceText = sourceText,
        sourcePackage = sourcePackage
    )
}

/** 导入日志:每条被解析过的通知/图片都留痕,方便回看"为什么没建成日程" */
data class ImportLog(
    val id: Long = 0L,
    val atMillis: Long = System.currentTimeMillis(),
    val source: EventSource = EventSource.NOTIFICATION_TEXT,
    val sourcePackage: String = "",
    val rawText: String = "",
    val engine: String = "",
    val outcome: String = "",
    val note: String = ""
)
