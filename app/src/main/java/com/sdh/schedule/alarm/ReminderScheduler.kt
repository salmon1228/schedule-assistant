package com.sdh.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.sdh.schedule.data.Event
import com.sdh.schedule.util.Recurrence
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒调度。每个日程同时只会挂一个「下一次发生」的精确闹钟;
 * 闹钟触发后再排下一次(见 ReminderReceiver),所以重复日程也能一直响下去。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    const val ACTION_REMIND = "com.sdh.schedule.action.REMIND"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_OCCURRENCE_START = "occurrence_start"

    /** 把某个日程的提醒闹钟对齐到「下一次发生」 */
    fun syncEvent(context: Context, e: Event, reference: Long = System.currentTimeMillis()) {
        if (!e.hasReminder || e.completed) {
            Log.d(TAG, "跳过「${e.title}」:无提醒设置或已完成")
            cancelEvent(context, e)
            return
        }
        val zone = ZoneId.systemDefault()
        // 从头开始找:第一次发生时间可能还没到
        val firstStart = e.startMillis
        val nextStart: Long? = when {
            firstStart > reference -> firstStart
            else -> Recurrence.nextOccurrenceStart(e, reference, zone)
        }
        if (nextStart == null) {
            Log.d(TAG, "跳过「${e.title}」:找不到未来的发生时间")
            cancelEvent(context, e)
            return
        }
        scheduleAt(context, e, nextStart)
    }

    /** 提醒触发时间:优先级 提前提醒 < 闹钟提醒 < 开始时间 */
    fun triggerAt(e: Event, occurrenceStart: Long): Long? {
        var trigger: Long? = null
        if (e.reminderMinutesBefore >= 0) {
            trigger = occurrenceStart - e.reminderMinutesBefore * 60_000L
        }
        if (e.alarmReminder || e.countdownReminder) {
            val startTrigger = occurrenceStart
            trigger = if (trigger == null) startTrigger else minOf(trigger, startTrigger)
        }
        return trigger
    }

    private fun scheduleAt(context: Context, e: Event, occurrenceStart: Long) {
        val trigger = triggerAt(e, occurrenceStart)
            ?: run {
                Log.d(TAG, "跳过「${e.title}」:算不出提醒时间")
                return
            }
        val now = System.currentTimeMillis()

        // 提醒时间已经过去的情况:
        // 用户常这么建日程 ——「1分钟后提醒我」「现在就提醒」(默认提醒是提前10分钟,提醒时间早就过了),
        // 直接跳过的话用户会以为"设了没用"。判断标准放宽成:
        //   一次性日程 + **日程还没结束** → 立刻补一条提醒;
        //   重复日程 → 只跳过今天这一次,下次照常排。
        if (trigger < now - 60_000L) {
            val oneOff = e.repeat == com.sdh.schedule.parse.RepeatKind.NONE
            val duration = (e.endMillis - e.startMillis).coerceAtLeast(60_000L)
            val notEnded = occurrenceStart + duration > now
            if (oneOff && notEnded) {
                Log.i(TAG, "「${e.title}」提醒时间已过(${fmt(trigger)}),但日程还没结束 → 立即提醒")
                scheduleAlarm(context, e, occurrenceStart, now + 2_000L)
            } else {
                Log.d(TAG, "跳过「${e.title}」:提醒时间已过去(${fmt(trigger)})且日程已结束")
            }
            return
        }
        val effective = if (trigger < now) now else trigger
        scheduleAlarm(context, e, occurrenceStart, effective)
    }

    /** 真正往 AlarmManager 里写闹钟 */
    private fun scheduleAlarm(context: Context, e: Event, occurrenceStart: Long, effective: Long) {
        val pi = buildPendingIntent(context, e, occurrenceStart)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没有精确闹钟权限时退化为不精确提醒(仍会响,只是时间可能有偏差)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effective, pi)
                Log.w(TAG, "无精确闹钟权限,已退化为不精确提醒: ${e.title}")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, effective, pi)
            }
            Log.i(TAG, "已安排提醒: 「${e.title}」-> ${fmt(effective)}")
        } catch (t: Throwable) {
            Log.e(TAG, "设置闹钟失败: ${e.title}", t)
            runCatching { am.set(AlarmManager.RTC_WAKEUP, effective, pi) }
        }
    }

    /** 闹钟触发后,把重复日程的下一次排上(只排严格晚于本次的那一次) */
    fun syncNext(context: Context, e: Event, firedOccurrenceStart: Long) {
        if (e.repeat == com.sdh.schedule.parse.RepeatKind.NONE) return
        val next = Recurrence.nextOccurrenceStartStrict(e, firedOccurrenceStart, ZoneId.systemDefault())
            ?: return
        scheduleAt(context, e, next)
    }

    fun cancelEvent(context: Context, e: Event) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = buildPendingIntent(context, e, e.startMillis)
        am.cancel(pi)
        pi.cancel()
    }

    private fun fmt(millis: Long): String {
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return "%d-%02d-%02d %02d:%02d".format(ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
    }

    private fun buildPendingIntent(context: Context, e: Event, occurrenceStart: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            data = Uri.parse("sdh://reminder/${e.id}")
            putExtra(EXTRA_EVENT_ID, e.id)
            putExtra(EXTRA_OCCURRENCE_START, occurrenceStart)
        }
        return PendingIntent.getBroadcast(
            context,
            e.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 供界面显示"下次提醒时间" */
    fun nextReminderText(e: Event, reference: Long = System.currentTimeMillis()): String? {
        val zone = ZoneId.systemDefault()
        val start = Recurrence.nextOccurrenceStart(e, reference, zone) ?: return null
        val trigger = triggerAt(e, start) ?: return null
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(trigger), zone)
        return "%d月%d日 %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
    }
}
