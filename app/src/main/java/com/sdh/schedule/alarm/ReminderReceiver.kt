package com.sdh.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify

/** 提醒时间到了:发通知 / 响铃 / 倒计时,并把重复日程的下一次排上 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
        val occurrenceStart = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE_START, 0L)
        if (eventId <= 0L) return

        val appContext = context.applicationContext
        // 广播接收器不能做耗时操作,交给自己开的线程,并用 goAsync 保活
        val pending = goAsync()
        Thread {
            try {
                fire(appContext, eventId, occurrenceStart)
            } catch (t: Throwable) {
                Log.e("ReminderReceiver", "提醒触发失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    private fun fire(context: Context, eventId: Long, occurrenceStart: Long) {
        Repo.init(context)
        Notify.ensureChannels(context)
        val e: Event = Repo.getEvent(eventId) ?: return
        if (e.completed || e.deleted) return

        val start = if (occurrenceStart > 0L) occurrenceStart else e.startMillis
        Log.i("ReminderReceiver", "触发提醒: 「${e.title}」start=${timeText(start)} 闹钟=${e.alarmReminder} 倒计时=${e.countdownReminder}")
        Notify.showReminder(context, e, start)

        if (e.alarmReminder) {
            Notify.showAlarmNotification(context, e, timeText(start))
            // 部分系统在后台不允许直接拉起 Activity,用通知的 fullScreenIntent 兜底
            runCatching {
                val i = Intent(context, AlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(AlarmActivity.EXTRA_EVENT_ID, eventId)
                    putExtra(AlarmActivity.EXTRA_TITLE, e.title)
                    putExtra(AlarmActivity.EXTRA_WHEN_TEXT, timeText(start))
                }
                context.startActivity(i)
            }
        }

        if (e.countdownReminder) {
            Notify.showCountdown(context, e, start)
        }

        // 重复日程:排下一次
        ReminderScheduler.syncNext(context, e, start)
    }

    private fun timeText(millis: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val ldt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
        return "%d月%d日 %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
    }
}

