package com.sdh.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify
import com.sdh.schedule.util.SelfCheck
import java.io.File

/**
 * 诊断广播(只读诊断,不改数据)。
 *
 * 用途:手机连着电脑时,一条命令就能把自检结果拿到手,不用截图:
 *   adb shell am broadcast -a com.sdh.schedule.action.SELF_CHECK -n com.sdh.schedule/.alarm.DiagReceiver
 *   adb exec-out run-as com.sdh.schedule cat files/selfcheck.txt
 *
 * 另外还支持发一条测试提醒:
 *   adb shell am broadcast -a com.sdh.schedule.action.TEST_REMINDER -n com.sdh.schedule/.alarm.DiagReceiver
 */
class DiagReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SELF_CHECK = "com.sdh.schedule.action.SELF_CHECK"
        const val ACTION_TEST_REMINDER = "com.sdh.schedule.action.TEST_REMINDER"
        const val ACTION_TEST_SUGGESTION = "com.sdh.schedule.action.TEST_SUGGESTION"

        /** 清理诊断产生的一切测试数据(测试用「待确认」、自检临时待办等) */
        const val ACTION_CLEAN_DIAG = "com.sdh.schedule.action.CLEAN_DIAG"

        /** 导出备份到 files/backup-check.json(验证导出功能,不动用户数据) */
        const val ACTION_EXPORT_BACKUP = "com.sdh.schedule.action.EXPORT_BACKUP"

        /** 把上面那份备份再导回来(验证恢复 + 判重:应当一条都不新增) */
        const val ACTION_IMPORT_BACKUP = "com.sdh.schedule.action.IMPORT_BACKUP"

        const val BACKUP_FILE = "backup-check.json"
        const val REPORT_FILE = "selfcheck.txt"
        private const val TAG = "Diag"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ACTION_SELF_CHECK -> {
                        val report = SelfCheck.report(appContext)
                        runCatching {
                            File(appContext.filesDir, REPORT_FILE).writeText(report, Charsets.UTF_8)
                        }
                        for (line in report.lines()) Log.i(TAG, line)
                    }

                    ACTION_TEST_REMINDER -> {
                        Notify.showTestReminder(appContext)
                        Log.i(TAG, "已按要求发送测试提醒")
                    }

                    // 造一条"待确认"通知,用来验证通知栏收起状态下的显示效果
                    ACTION_TEST_SUGGESTION -> {
                        Repo.init(appContext)
                        val now = System.currentTimeMillis()
                        val s = com.sdh.schedule.data.Suggestion(
                            title = "项目评审会",
                            location = "望京SOHO A座 301",
                            startMillis = now + 90 * 60_000L,
                            endMillis = now + 150 * 60_000L,
                            confidence = 0.92,
                            engine = "llm",
                            source = com.sdh.schedule.data.EventSource.NOTIFICATION_TEXT,
                            sourcePackage = "diag"
                        )
                        val id = Repo.addSuggestion(s)
                        Notify.showSuggestion(appContext, s, id)
                        Log.i(TAG, "已按要求发送测试用「待确认」通知 id=$id")
                    }

                    // 把诊断过程产生的测试数据清干净(测试待确认 + 自检临时待办)
                    ACTION_CLEAN_DIAG -> {
                        Repo.init(appContext)
                        var removed = 0
                        for (s in Repo.suggestions.value) {
                            if (s.sourcePackage == "diag" || s.title == "项目评审会") {
                                Repo.rejectSuggestion(s.id)
                                Notify.cancelSuggestion(appContext, s.id)
                                removed++
                            }
                        }
                        for (t in Repo.todos.value) {
                            if (t.sourcePackage == "diag" || t.title.startsWith("__自检")) {
                                Repo.deleteTodo(t.id)
                                removed++
                            }
                        }
                        // 占位课:名字就是默认值「课程」的,都是误点空格子建出来的空壳
                        for (c in Repo.courses.value.toList()) {
                            if (c.name == "课程" || c.name.startsWith("__")) {
                                Repo.deleteCourse(c.id)
                                removed++
                            }
                        }
                        Log.i(TAG, "已清理诊断测试数据 $removed 条")
                    }

                    // 导出备份到 files/backup-check.json
                    ACTION_EXPORT_BACKUP -> {
                        val json = com.sdh.schedule.util.Backup.exportJson(appContext)
                        File(appContext.filesDir, BACKUP_FILE).writeText(json, Charsets.UTF_8)
                        Log.i(TAG, "已导出备份:${json.length} 字符 → files/$BACKUP_FILE")
                    }

                    // 把刚才那份备份导回来,验证判重(正常情况下一条都不该新增)
                    ACTION_IMPORT_BACKUP -> {
                        val f = File(appContext.filesDir, BACKUP_FILE)
                        if (!f.exists()) {
                            Log.w(TAG, "没有找到备份文件,先跑 EXPORT_BACKUP")
                        } else {
                            val r = com.sdh.schedule.util.Backup.importFromJson(
                                appContext, f.readText(Charsets.UTF_8)
                            )
                            Log.i(TAG, "恢复结果:${r.getOrElse { "失败:${it.message}" }}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "诊断广播执行失败", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
