package com.sdh.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify

/** 通知栏按钮:稍后提醒 / 标记完成 / 关闭闹钟 / 候选日程 加入或忽略 */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SNOOZE = "com.sdh.schedule.action.SNOOZE"
        const val ACTION_DONE = "com.sdh.schedule.action.DONE"
        const val ACTION_DISMISS_ALARM = "com.sdh.schedule.action.DISMISS_ALARM"
        const val ACTION_ACCEPT_SUGGESTION = "com.sdh.schedule.action.ACCEPT_SUGGESTION"
        const val ACTION_REJECT_SUGGESTION = "com.sdh.schedule.action.REJECT_SUGGESTION"
        const val ACTION_UNDO_TODO = "com.sdh.schedule.action.UNDO_TODO"
        const val ACTION_STOP_ALARM_ACTIVITY = "com.sdh.schedule.action.STOP_ALARM_ACTIVITY"

        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SUGGESTION_ID = "suggestion_id"
        const val EXTRA_TODO_ID = "todo_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                handle(appContext, intent)
            } catch (t: Throwable) {
                Log.e("ActionReceiver", "处理通知动作失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        Repo.init(context)
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val suggestionId = intent.getLongExtra(EXTRA_SUGGESTION_ID, -1L)

        when (intent.action) {
            ACTION_SNOOZE -> {
                if (eventId <= 0L) return
                Repo.snooze(eventId, 10)
                Notify.cancelReminder(context, eventId)
            }

            ACTION_DONE -> {
                if (eventId <= 0L) return
                Repo.setCompleted(eventId, true)
                Notify.cancelReminder(context, eventId)
                Notify.cancelCountdown(context, eventId)
                Notify.cancelAlarm(context, eventId)
            }

            ACTION_DISMISS_ALARM -> {
                if (eventId <= 0L) return
                Notify.cancelAlarm(context, eventId)
                // 通知 AlarmActivity 自己关掉
                context.sendBroadcast(
                    Intent(ACTION_STOP_ALARM_ACTIVITY).setPackage(context.packageName)
                )
            }

            ACTION_ACCEPT_SUGGESTION -> {
                if (suggestionId <= 0L) return
                Repo.acceptSuggestion(suggestionId)
                Notify.cancelSuggestion(context, suggestionId)
            }

            ACTION_REJECT_SUGGESTION -> {
                if (suggestionId <= 0L) return
                Repo.rejectSuggestion(suggestionId)
                Notify.cancelSuggestion(context, suggestionId)
            }

            // 「撤销」刚自动加入的待办
            ACTION_UNDO_TODO -> {
                val todoId = intent.getLongExtra(EXTRA_TODO_ID, -1L)
                if (todoId <= 0L) return
                Repo.deleteTodo(todoId)
                Notify.cancelTodoHint(context, todoId)
            }
        }
    }
}

