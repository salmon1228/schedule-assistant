package com.sdh.schedule

import android.app.Application
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify
import com.sdh.schedule.parse.SlotTimes
import com.sdh.schedule.util.HolidayRepo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 农历 / 节假日数据(打包在 assets 里,离线可用)
        runCatching { HolidayRepo.init(this) }
        Repo.init(this)
        // 自我修复:历史版本曾把节次时间写坏(出现 08:00 和 08:20 这种重复节次,
        // 会让课表格子错位、下午的课跑到上午),启动时发现重复就自动去重
        runCatching {
            val st = Repo.settings.slotTimes
            if (SlotTimes.hasDuplicates(st)) {
                Repo.settings.slotTimes = SlotTimes.dedupe(st)
            }
        }
        Notify.ensureChannels(this)
        // 重启/更新后 AlarmManager 里的闹钟会丢,这里补齐
        runCatching { Repo.rescheduleAll() }
    }
}
