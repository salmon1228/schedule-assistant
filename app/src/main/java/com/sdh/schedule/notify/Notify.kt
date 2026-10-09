package com.sdh.schedule.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.sdh.schedule.MainActivity
import com.sdh.schedule.R
import com.sdh.schedule.alarm.ActionReceiver
import com.sdh.schedule.alarm.AlarmActivity
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.Suggestion
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 所有通知的构建入口。
 * 提醒用的是 MessagingStyle —— Android 会把它渲染成「聊天消息」的样子,
 * 也就是 QQ/微信 那种出现在消息区的通知,而不是普通应用通知。
 */
object Notify {

    const val CH_REMINDER = "ch_reminder"
    /** 走闹钟音量的提醒渠道:手机静音/振动时也能响 */
    const val CH_REMINDER_LOUD = "ch_reminder_loud"
    /** 静音提醒渠道(只显示,不发声) */
    const val CH_REMINDER_SILENT = "ch_reminder_silent"
    const val CH_ALARM = "ch_alarm"
    const val CH_COUNTDOWN = "ch_countdown"
    const val CH_SUGGESTION = "ch_suggestion"

    const val ID_BASE_REMINDER = 1000
    const val ID_BASE_COUNTDOWN = 2000
    const val ID_BASE_SUGGESTION = 3000
    const val ID_BASE_ALARM = 4000
    const val ID_BASE_COURSE = 5000
    const val ID_BASE_TODO = 6000

    /** 当前设置下,提醒该用哪个渠道 */
    private fun reminderChannel(context: Context): String =
        when (com.sdh.schedule.data.Repo.settings.reminderSound) {
            com.sdh.schedule.data.SettingsStore.SOUND_SYSTEM -> CH_REMINDER
            com.sdh.schedule.data.SettingsStore.SOUND_SILENT -> CH_REMINDER_SILENT
            else -> CH_REMINDER_LOUD
        }

    /** 供自检页显示"当前生效的是哪条渠道" */
    fun activeReminderChannel(context: Context): String = reminderChannel(context)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "日程提醒(系统通知音)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "以聊天消息的形式提醒你,声音跟随系统通知音"
                enableVibration(true)
            }
        )
        // 关键渠道:音频属性用 USAGE_ALARM,于是走「闹钟音量」——
        // 手机静音或振动时,普通通知不会响,但这条会响。
        val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER_LOUD, "日程提醒(响铃·静音也响)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "用闹钟音量响铃,手机静音/振动时依然能听到"
                setSound(
                    alarmSound,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER_SILENT, "日程提醒(静音)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "只显示通知,不发出声音"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点后全屏响铃,不点不罢休"
                setSound(
                    alarmSound,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_COUNTDOWN, "倒计时提醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻通知栏,倒计时到日程开始"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUGGESTION, "发现新日程", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "从通知/图片中识别出可能的日程时提示你确认"
            }
        )
    }

    private fun safeNotify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        safeNotify(context, id, builder.build())
    }

    private fun safeNotify(context: Context, id: Int, notification: android.app.Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            android.util.Log.w("Notify", "通知被系统关闭,提醒发不出去,请到系统设置里允许本应用通知")
            return
        }
        try {
            nm.notify(id, notification)
        } catch (e: SecurityException) {
            // 没有 POST_NOTIFICATIONS 权限
            android.util.Log.w("Notify", "缺少通知权限,提醒发不出去", e)
        }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun fmtDateTime(millis: Long, allDay: Boolean): String {
        val zone = ZoneId.systemDefault()
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[ldt.dayOfWeek.value - 1]
        return if (allDay) {
            "%d月%d日 %s(全天)".format(ldt.monthValue, ldt.dayOfMonth, week)
        } else {
            "%d月%d日 %s %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, week, ldt.hour, ldt.minute)
        }
    }

    private fun openAppIntent(context: Context, eventId: Long): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_event_id", eventId)
        }
        return PendingIntent.getActivity(
            context, (700000 + eventId).toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 点「待确认」通知 → 直接打开 App 的「待确认」页(不用先在通知栏展开找按钮) */
    private fun openSuggestionsIntent(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_suggestions", true)
        }
        return PendingIntent.getActivity(
            context, 710777, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(
        context: Context,
        eventId: Long,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val i = Intent(context, ActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("sdh://action/$action/$eventId")
            putExtra(ActionReceiver.EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun assistant(): Person = Person.Builder().setName("日程助手").build()

    /**
     * 通知的大图标(彩色日历)。不显式设置的话,部分 ROM 会在通知栏退回系统默认占位图标。
     * 这里把矢量图渲染成 Bitmap,兼容性最好。
     */
    private fun largeIcon(context: Context): android.graphics.Bitmap? = try {
        val d = context.getDrawable(R.drawable.ic_launcher_large)
        if (d == null) null else {
            val size = 192
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(canvas)
            bmp
        }
    } catch (t: Throwable) {
        null
    }

    /** 聊天式提醒:正文像一条消息 */
    fun showReminder(context: Context, event: Event, occurrenceStart: Long) {
        ensureChannels(context)
        val whenText = fmtDateTime(occurrenceStart, event.allDay)
        val lines = StringBuilder()
        lines.append("⏰ ")
        lines.append(if (event.reminderMinutesBefore > 0) "${event.reminderMinutesBefore} 分钟后:" else "现在:")
        lines.append(event.title.ifBlank { "新日程" })
        lines.append("\n🕒 ").append(whenText)
        if (event.location.isNotBlank()) lines.append("\n📍 ").append(event.location)
        if (event.note.isNotBlank()) lines.append("\n📝 ").append(event.note)

        val me = assistant()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("日程助手")
            .addMessage(lines.toString(), System.currentTimeMillis(), me)

        val builder = NotificationCompat.Builder(context, reminderChannel(context))
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, event.id))
            .addAction(R.drawable.ic_action_snooze, "稍后10分钟", actionIntent(context, event.id, ActionReceiver.ACTION_SNOOZE, (800000 + event.id).toInt()))
            .addAction(R.drawable.ic_action_done, "标记完成", actionIntent(context, event.id, ActionReceiver.ACTION_DONE, (810000 + event.id).toInt()))

        if (event.alarmReminder) {
            val full = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(AlarmActivity.EXTRA_EVENT_ID, event.id)
                putExtra(AlarmActivity.EXTRA_TITLE, event.title)
                putExtra(AlarmActivity.EXTRA_WHEN_TEXT, whenText)
            }
            val fullPi = PendingIntent.getActivity(
                context, (820000 + event.id).toInt(), full,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullPi, true)
        }

        val contentText = buildString {
            append(whenText)
            if (event.location.isNotBlank()) append(" · ").append(event.location)
        }
        val n = builder.build()
        safeNotify(context, (ID_BASE_REMINDER + event.id).toInt(), n)
    }

    /** 闹钟式通知(配套全屏 Activity) */
    fun showAlarmNotification(context: Context, event: Event, whenText: String) {
        ensureChannels(context)
        val full = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(AlarmActivity.EXTRA_EVENT_ID, event.id)
            putExtra(AlarmActivity.EXTRA_TITLE, event.title)
            putExtra(AlarmActivity.EXTRA_WHEN_TEXT, whenText)
        }
        val fullPi = PendingIntent.getActivity(
            context, (820000 + event.id).toInt(), full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setContentTitle(event.title.ifBlank { "日程提醒" })
            .setContentText(whenText)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullPi)
            .setFullScreenIntent(fullPi, true)
            .addAction(R.drawable.ic_action_done, "关闭", actionIntent(context, event.id, ActionReceiver.ACTION_DISMISS_ALARM, (830000 + event.id).toInt()))
        val n = builder.build()
        safeNotify(context, (ID_BASE_ALARM + event.id).toInt(), n)
    }

    /** 常驻倒计时通知:距离日程开始还有多久 */
    fun showCountdown(context: Context, event: Event, occurrenceStart: Long) {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setContentTitle(event.title.ifBlank { "即将开始" })
            .setContentText("距离开始还有")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(occurrenceStart)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context, event.id))
        val n = builder.build()
        safeNotify(context, (ID_BASE_COUNTDOWN + event.id).toInt(), n)
    }

    fun cancelReminder(context: Context, eventId: Long) {
        cancel(context, (ID_BASE_REMINDER + eventId).toInt())
    }

    fun cancelCountdown(context: Context, eventId: Long) {
        cancel(context, (ID_BASE_COUNTDOWN + eventId).toInt())
    }

    fun cancelAlarm(context: Context, eventId: Long) {
        cancel(context, (ID_BASE_ALARM + eventId).toInt())
    }

    /**
     * 从通知/图片里识别出候选日程时的提示。
     *
     * 注意:**不用 MessagingStyle** —— 聊天式通知会被系统折叠成「会话」,
     * 收起状态下只显示笼统的一句、要点展开才看得到内容与按钮
     * (用户反馈过「要点一下展开才能选忽略/添加」)。
     * 这里改成普通通知 + BigTextStyle:收起时就能看到标题、时间和两个按钮。
     */
    fun showSuggestion(context: Context, suggestion: Suggestion, suggestionId: Long) {
        ensureChannels(context)
        val whenText = fmtDateTime(suggestion.startMillis, suggestion.allDay)
        val title = suggestion.title.ifBlank { "新日程" }
        val oneLine = buildString {
            append(whenText)
            if (suggestion.location.isNotBlank()) append(" · ").append(suggestion.location)
        }
        val detail = buildString {
            append("📌 ").append(title)
            append("\n🕒 ").append(whenText)
            if (suggestion.location.isNotBlank()) append("\n📍 ").append(suggestion.location)
            append("\n(来源:").append(suggestion.source.label)
            append(" · ").append(if (suggestion.engine == "llm") "大模型" else "本地规则")
            append(" · 置信度:").append((suggestion.confidence * 100).toInt()).append("%)")
        }

        val addIntent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_ACCEPT_SUGGESTION
            data = Uri.parse("sdh://suggestion/accept/$suggestionId")
            putExtra(ActionReceiver.EXTRA_SUGGESTION_ID, suggestionId)
        }
        val ignoreIntent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_REJECT_SUGGESTION
            data = Uri.parse("sdh://suggestion/reject/$suggestionId")
            putExtra(ActionReceiver.EXTRA_SUGGESTION_ID, suggestionId)
        }
        val addPi = PendingIntent.getBroadcast(
            context, (860000 + suggestionId).toInt(), addIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ignorePi = PendingIntent.getBroadcast(
            context, (870000 + suggestionId).toInt(), ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CH_SUGGESTION)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setContentTitle("待确认:$title")
            .setContentText(oneLine)
            .setSubText("点「加入日程」或「忽略」")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openSuggestionsIntent(context))
            .addAction(R.drawable.ic_action_done, "加入日程", addPi)
            .addAction(R.drawable.ic_action_close, "忽略", ignorePi)
        safeNotify(context, (ID_BASE_SUGGESTION + suggestionId).toInt(), builder)
    }

    fun cancelSuggestion(context: Context, suggestionId: Long) {
        cancel(context, (ID_BASE_SUGGESTION + suggestionId).toInt())
    }


    /**
     * 「立即测试提醒」:立刻发一条和真实提醒完全一样的通知
     * (同一个渠道 → 同样的声音/振动),并且**自己把声音播出来**。
     * 用来快速判断"是提醒没触发"还是"通知没声音"。
     */
    fun showTestReminder(context: Context) {
        ensureChannels(context)
        val now = System.currentTimeMillis()
        val fake = com.sdh.schedule.data.Event(
            id = 990001L,
            title = "测试提醒",
            location = "如果你看到这条通知,说明提醒链路是通的",
            startMillis = now + 5 * 60_000L,
            endMillis = now + 65 * 60_000L,
            reminderMinutesBefore = 5
        )
        showReminder(context, fake, fake.startMillis)
        Log.i("Notify", "已发送测试提醒(渠道=${reminderChannel(context)})")
    }

    /**
     * 「已加入待办」提示:待办不进「待确认」流程(多一行清单风险很低),
     * 直接加好并给一条带「撤销」的通知。
     */
    fun showTodoAdded(context: Context, todo: com.sdh.schedule.data.Todo, todoId: Long) {
        ensureChannels(context)
        val undoIntent = Intent(context, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_UNDO_TODO
            data = Uri.parse("sdh://todo/undo/$todoId")
            putExtra(ActionReceiver.EXTRA_TODO_ID, todoId)
        }
        val undoPi = PendingIntent.getBroadcast(
            context, (880000 + todoId).toInt(), undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sub = if (todo.hasDue) "截止 " + fmtDateTime(todo.dueMillis, false) else "在「记录 → 待办」里可以看到"
        val builder = NotificationCompat.Builder(context, CH_SUGGESTION)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setContentTitle("已加入待办:${todo.title}")
            .setContentText(sub)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "📝 ${todo.title}\n" +
                        (if (todo.hasDue) "⏰ 截止 " + fmtDateTime(todo.dueMillis, false) + "\n" else "") +
                        "来源:${todo.source.label}"
                )
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, 0L))
            .addAction(R.drawable.ic_action_close, "撤销", undoPi)
        safeNotify(context, (ID_BASE_TODO + todoId).toInt(), builder)
    }

    fun cancelTodoHint(context: Context, todoId: Long) {
        cancel(context, (ID_BASE_TODO + todoId).toInt())
    }

    /** 上课提醒:和日程提醒同款(聊天式 + 走闹钟音量) */    fun showCourseReminder(
        context: Context,
        course: com.sdh.schedule.data.Course,
        start: java.time.LocalTime?,
        end: java.time.LocalTime?
    ) {
        ensureChannels(context)
        val timeText = if (start != null && end != null) {
            "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
        } else "即将上课"

        val body = buildString {
            append("📚 ")
            append(if (course.reminderMinutes > 0) "${course.reminderMinutes} 分钟后上课:" else "现在上课:")
            append(course.name.ifBlank { "课程" })
            append("\n🕒 ").append(timeText)
            if (course.location.isNotBlank()) append("\n📍 ").append(course.location)
            if (course.teacher.isNotBlank()) append("\n👤 ").append(course.teacher)
        }

        val me = assistant()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("课程提醒")
            .addMessage(body, System.currentTimeMillis(), me)

        val builder = NotificationCompat.Builder(context, reminderChannel(context))
            .setSmallIcon(R.drawable.ic_stat_event)
            .setLargeIcon(largeIcon(context))
            .setColor(0xFF4361EE.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, 0L))

        val n = builder.build()
        safeNotify(context, (ID_BASE_COURSE + course.id).toInt(), n)
    }
}




