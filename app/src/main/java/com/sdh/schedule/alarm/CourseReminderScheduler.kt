package com.sdh.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒调度。
 *
 * 策略:课程是"按周次 + 星期几 + 第几节"的规则,但学期第几周依赖学期开始日期,
 * 容易算错,所以采用最稳的做法 —— **只为"今天"的课排精确闹钟**,并且:
 *   - 开机 / 打开 App / 改课程 / 跨天后,重新为当天排;
 *   - 每次排完,顺带排一个"明天 00:05"的闹钟,用于第二天自动续排(见 [CourseDayReceiver])。
 * 这样即使某天没打开 App,提醒也不会漏。
 */
object CourseReminderScheduler {

    private const val TAG = "CourseReminder"

    const val ACTION_COURSE_REMIND = "com.sdh.schedule.action.COURSE_REMIND"
    const val ACTION_COURSE_DAY_SWITCH = "com.sdh.schedule.action.COURSE_DAY_SWITCH"
    const val EXTRA_COURSE_ID = "course_id"

    /** 为今天(以及必要的明天)的课重排提醒 */
    fun reschedule(context: Context, reference: Long = System.currentTimeMillis()) {
        val ctx = context.applicationContext
        try {
            Repo.init(ctx)
            if (!Repo.settings.courseReminderEnabled) {
                Log.i(TAG, "上课提醒已关闭,跳过")
                return
            }
            val zone = ZoneId.systemDefault()
            val today = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(reference), zone).toLocalDate()

            var count = 0
            for (day in listOf(today, today.plusDays(1))) {
                count += scheduleForDay(ctx, day, reference)
            }
            Log.i(TAG, "上课提醒已排期:今天与明天共 $count 节")
        } catch (t: Throwable) {
            Log.e(TAG, "排上课提醒失败", t)
        }
        scheduleDaySwitch(context, System.currentTimeMillis())
    }

    /** 给某一天的课排提醒,返回排上的节数 */
    private fun scheduleForDay(context: Context, date: LocalDate, reference: Long): Int {
        val zone = ZoneId.systemDefault()
        val slots = Repo.settings.slotList()
        val courses = Repo.coursesOn(date)
        if (courses.isEmpty()) return 0

        val am = context.getSystemService(AlarmManager::class.java) ?: return 0
        var n = 0
        for (course in courses) {
            if (course.reminderMinutes < 0) continue
            val slotIndex = course.startSlot - 1
            val startTime: LocalTime = slots.getOrNull(slotIndex)?.first ?: continue
            val trigger = date.atTime(startTime).atZone(zone).toInstant().toEpochMilli() -
                course.reminderMinutes * 60_000L
            // 已经过去超过 1 分钟的就不补了
            if (trigger < reference - 60_000L) continue

            val pi = pendingIntent(context, course, date)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                n++
                Log.i(TAG, "已安排上课提醒:${course.name} -> ${fmt(trigger)}")
            } catch (t: Throwable) {
                Log.e(TAG, "安排上课提醒失败:${course.name}", t)
            }
        }
        return n
    }

    /** 每天 00:05 触发一次,用于把"当天"的课重新排期 */
    private fun scheduleDaySwitch(context: Context, reference: Long) {
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(reference), zone)
            .toLocalDate().plusDays(1).atTime(0, 5).atZone(zone).toInstant().toEpochMilli()
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, CourseDayReceiver::class.java).apply {
            action = ACTION_COURSE_DAY_SWITCH
            data = Uri.parse("sdh://course/day")
        }
        val pi = PendingIntent.getBroadcast(
            context, 99001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, tomorrow, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, tomorrow, pi)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "排次日续期闹钟失败", t)
        }
    }

    private fun pendingIntent(context: Context, course: Course, date: LocalDate): PendingIntent {
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = ACTION_COURSE_REMIND
            // 同一天同一门课用同一个 PendingIntent
            data = Uri.parse("sdh://course/${course.id}/${date}")
            putExtra(EXTRA_COURSE_ID, course.id)
        }
        return PendingIntent.getBroadcast(
            context,
            (course.id * 31 + date.dayOfYear).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        for (course in Repo.courses.value) {
            for (dayOffset in 0..1) {
                val date = LocalDate.now().plusDays(dayOffset.toLong())
                val pi = pendingIntent(context, course, date)
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    private fun fmt(millis: Long): String {
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return "%d-%02d-%02d %02d:%02d".format(ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
    }
}

/** 到点提醒:发一条和日程一样的聊天式通知 */
class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CourseReminderScheduler.ACTION_COURSE_REMIND) return
        val courseId = intent.getLongExtra(CourseReminderScheduler.EXTRA_COURSE_ID, -1L)
        if (courseId <= 0L) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Repo.init(appContext)
                val course = Repo.courses.value.firstOrNull { it.id == courseId } ?: return@Thread
                val slots = Repo.settings.slotList()
                val start = slots.getOrNull(course.startSlot - 1)?.first
                val end = if (course.slotCount > 1) {
                    slots.getOrNull(course.startSlot + course.slotCount - 2)?.second
                } else {
                    slots.getOrNull(course.startSlot - 1)?.second
                }
                Log.i("CourseReminder", "触发上课提醒:${course.name} @${course.location}")
                Notify.showCourseReminder(appContext, course, start, end)
            } catch (t: Throwable) {
                Log.e("CourseReminder", "上课提醒失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}

/** 每天 00:05 把当天的课续排一次 */
class CourseDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CourseReminderScheduler.ACTION_COURSE_DAY_SWITCH) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                CourseReminderScheduler.reschedule(appContext)
            } catch (t: Throwable) {
                Log.e("CourseDayReceiver", "续排失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}



