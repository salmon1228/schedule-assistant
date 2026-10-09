package com.sdh.schedule.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify
import com.sdh.schedule.notify.ScheduleNotificationListener
import com.sdh.schedule.parse.ChineseDateParser
import com.sdh.schedule.parse.TodoExtractor
import java.time.LocalDate
import java.time.ZoneId

/**
 * 功能自检:把"各项功能是否正常"集中检查一遍。
 * 界面(设置 → 功能自检)和 adb 诊断广播都用这一份逻辑,保证两边结果一致。
 */
object SelfCheck {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    fun run(context: Context): List<Check> {
        val out = ArrayList<Check>()
        val zone = ZoneId.systemDefault()

        // 1. 节假日数据
        runCatching {
            val years = HolidayRepo.coveredYears()
            var off = 0
            var makeup = 0
            val y = years.maxOrNull() ?: 0
            if (y > 0) {
                var d = LocalDate.of(y, 1, 1)
                while (d.year == y) {
                    val h = HolidayRepo.holidayOf(d)
                    if (h != null) {
                        if (h.isOffDay) off++ else makeup++
                    }
                    d = d.plusDays(1)
                }
            }
            out.add(
                Check(
                    "节假日数据(离线)",
                    years.isNotEmpty(),
                    if (years.isEmpty()) "没加载到数据,检查 assets/holidays.json"
                    else "覆盖 ${years.joinToString("/")};$y 年放假 $off 天、调休 $makeup 天"
                )
            )
        }.onFailure { out.add(Check("节假日数据(离线)", false, "${it.message}")) }

        // 2. 农历算法
        runCatching {
            val sf = LunarCalendar.of(LocalDate.of(2026, 2, 17))
            val ok = sf?.festival == "春节" && sf.lunarMonth == 1 && sf.lunarDay == 1
            out.add(
                Check(
                    "农历算法", ok,
                    "2026-02-17 → ${sf?.monthName}${sf?.dayName} · ${sf?.festival}"
                )
            )
        }.onFailure { out.add(Check("农历算法", false, "${it.message}")) }

        // 3. 月历显示规则
        runCatching {
            val nationalDay = CalendarLabels.forDate(LocalDate.of(2026, 10, 1))
            val today = CalendarLabels.forDate(LocalDate.now())
            out.add(
                Check(
                    "月历节假日显示",
                    nationalDay.kind == CalendarLabels.Kind.OFF_DAY,
                    "2026-10-01 → ${nationalDay.text}(放假);今天 → ${today.text}"
                )
            )
        }.onFailure { out.add(Check("月历节假日显示", false, "${it.message}")) }

        // 4. 数据库
        runCatching {
            Repo.init(context)
            var todoOk = false
            runCatching {
                val id = Repo.addTodo(
                    com.sdh.schedule.data.Todo(
                        title = "__自检临时项__",
                        sourceText = "selfcheck"
                    )
                )
                todoOk = Repo.getTodo(id) != null
                Repo.deleteTodo(id)
            }
            out.add(
                Check(
                    "数据库(6 张表 + 待办可读写)",
                    todoOk,
                    "日程 ${Repo.events.value.size} · 待确认 ${Repo.suggestions.value.size} · " +
                        "便签 ${Repo.notes.value.size} · 课程 ${Repo.courses.value.size} · 待办 ${Repo.todos.value.size}"
                )
            )
        }.onFailure { out.add(Check("数据库(6 张表 + 待办可读写)", false, "${it.message}")) }

        // 5. 通知渠道(显示"当前生效"的那条,方便一眼确认是哪种铃声)
        runCatching {
            Notify.ensureChannels(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val active = Notify.activeReminderChannel(context)
            val ch = nm?.getNotificationChannel(active)
            val soundName = when {
                ch?.sound == null -> "无(不发声)"
                ch.sound.toString().contains("alarm_alert") -> "系统闹钟音(静音/振动也响)"
                ch.sound.toString().contains("notification_sound") -> "系统通知音(和微信QQ一样)"
                else -> ch.sound.toString()
            }
            out.add(
                Check(
                    "提醒铃声(当前生效)",
                    ch != null,
                    "渠道=$active 重要度=${ch?.importance} 声音=$soundName"
                )
            )
        }.onFailure { out.add(Check("提醒铃声(当前生效)", false, "${it.message}")) }

        // 6. 通知权限
        runCatching {
            val on = NotificationManagerCompat.from(context).areNotificationsEnabled()
            val imp = context.getSystemService(NotificationManager::class.java)
                ?.let { nm -> if (Build.VERSION.SDK_INT >= 24) nm.areNotificationsEnabled() else true } ?: true
            out.add(Check("通知权限", on && imp, if (on) "已允许" else "被系统关闭,提醒发不出去"))
        }

        // 7. 通知使用权
        runCatching {
            val on = ScheduleNotificationListener.isEnabled(context)
            out.add(Check("通知使用权(自动识别日程)", on, if (on) "已开启" else "未开启,无法自动读取其他 App 的通知"))
        }

        // 8. 精确闹钟
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am?.canScheduleExactAlarms() == true else true
            out.add(Check("精确闹钟权限", ok, if (ok) "已允许(准点提醒)" else "未允许,提醒时间可能被系统推迟"))
        }

        // 9. 全屏通知权限(闹钟弹窗需要;MIUI 上常被忽略)
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            val ok = if (Build.VERSION.SDK_INT >= 34) nm?.canUseFullScreenIntent() ?: true else true
            out.add(
                Check(
                    "全屏通知权限(闹钟弹窗)",
                    ok,
                    if (ok) "已允许"
                    else "未允许:「闹钟提醒」不会全屏弹出(去 MIUI 应用权限里开「全屏通知」);普通提醒不受影响"
                )
            )
        }

        // 10. 数据库体积 / 日志条数(日志曾经疯涨到 4.5 万条、库 8.7MB)
        runCatching {
            val size = Backup.dbSizeText(context)
            val logCount = Repo.logs.value.size
            out.add(
                Check(
                    "数据库体积 / 日志条数",
                    logCount <= com.sdh.schedule.data.DbHelper.LOG_TRIM_TRIGGER,
                    "$size;导入日志 $logCount 条(上限保留 ${com.sdh.schedule.data.DbHelper.LOG_KEEP})"
                )
            )
        }

        // 11. 待办抽取(示例句)
        runCatching {
            val text = "明天会发一张表格下来需要我们填写，准备好一寸照片"
            val titles = TodoExtractor.extract(text, System.currentTimeMillis(), zone).map { it.title }
            out.add(
                Check(
                    "待办抽取(示例句)",
                    titles.any { it.contains("一寸照片") },
                    "→ ${if (titles.isEmpty()) "没抽出来" else titles.joinToString(" | ")}"
                )
            )
        }.onFailure { out.add(Check("待办抽取(示例句)", false, "${it.message}")) }

        // 12. 日程解析(示例句)
        runCatching {
            val parsed = ChineseDateParser.parse(
                "明天下午3点半和张总在望京SOHO吃饭", System.currentTimeMillis(), zone
            )
            out.add(
                Check(
                    "日程解析(示例句)",
                    parsed != null,
                    parsed?.let { "→ 「${it.title}」${it.location}" } ?: "没解析出来"
                )
            )
        }.onFailure { out.add(Check("日程解析(示例句)", false, "${it.message}")) }

        // 13. 课程表与上课提醒
        runCatching {
            val today = Repo.coursesOn(LocalDate.now())
            out.add(
                Check(
                    "课程表与上课提醒",
                    true,
                    "共 ${Repo.courses.value.size} 门课;今天 ${today.size} 节" +
                        if (today.isNotEmpty()) "(${today.joinToString("、") { it.name }})" else ""
                )
            )
        }.onFailure { out.add(Check("课程表与上课提醒", false, "${it.message}")) }

        return out
    }

    /** 纯文本报告(写进文件,供 adb 直接读取) */
    fun report(context: Context): String {
        val checks = run(context)
        val ok = checks.count { it.ok }
        val sb = StringBuilder()
        sb.append("功能自检 ").append(ok).append("/").append(checks.size).append(" 通过\n")
        sb.append("设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append("(SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("时间 ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())).append('\n')
        sb.append("=".repeat(60)).append('\n')
        for (c in checks) {
            sb.append(if (c.ok) "[通过] " else "[警告] ").append(c.name)
            if (c.detail.isNotBlank()) sb.append("  —  ").append(c.detail)
            sb.append('\n')
        }
        return sb.toString()
    }
}


