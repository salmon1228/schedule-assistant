package com.sdh.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify

/**
 * 开机 / 应用更新 / 系统时间变化后重排所有提醒。
 * Android 重启会清空 AlarmManager 里的闹钟,不重排就会漏提醒。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                val appContext = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        Repo.init(appContext)
                        Notify.ensureChannels(appContext)
                        Repo.rescheduleAll()
                        Log.i("BootReceiver", "已重排全部提醒")
                    } catch (t: Throwable) {
                        Log.e("BootReceiver", "重排提醒失败", t)
                    } finally {
                        runCatching { pending.finish() }
                    }
                }.start()
            }
        }
    }
}
