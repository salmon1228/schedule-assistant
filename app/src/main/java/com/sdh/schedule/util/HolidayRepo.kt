package com.sdh.schedule.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.TreeSet

/** 法定节假日/调休信息 */
data class HolidayInfo(
    val date: LocalDate,
    val name: String,             // 元旦/春节/清明节/劳动节/端午节/中秋节/国庆节
    val isOffDay: Boolean         // true = 放假;false = 调休上班
)

/**
 * 中国法定节假日仓库。
 *
 * 数据来自打包在 assets/holidays.json 的静态文件(运行时**不访问网络**),
 * 格式:
 * ```
 * {
 *   "years": [2025, 2026, 2027],
 *   "days": {
 *     "2026-01-01": {"name": "元旦", "isOffDay": true},
 *     "2026-01-04": {"name": "元旦", "isOffDay": false}
 *   }
 * }
 * ```
 * 为兼容上游 holiday-cn 的原始格式,`days` 也接受数组形式
 * (`[{"date":"..","name":"..","isOffDay":true}, ...]`)。
 *
 * 线程安全:内部状态只在 [init] / [loadFromJson] 中整体替换,读取端无锁(volatile 引用 + 不可变快照)。
 */
object HolidayRepo {

    private const val ASSET_NAME = "holidays.json"

    /** 不可变快照:日期 -> 节假日/调休信息 */
    @Volatile
    private var days: Map<LocalDate, HolidayInfo> = emptyMap()

    /** 不可变快照:数据覆盖到的年份(升序) */
    @Volatile
    private var years: List<Int> = emptyList()

    /**
     * 应用启动时调用一次,载入打包的 assets/holidays.json。
     * 重复调用安全;解析失败时保留已有数据且不抛异常。
     */
    fun init(context: Context) {
        val json = try {
            context.assets.open(ASSET_NAME).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return
        }
        loadFromJson(json)
    }

    /**
     * 纯逻辑入口(不依赖 Android,供 JVM 单元测试直接调用):
     * 解析 JSON 文本并整体替换内存数据。
     */
    @Synchronized
    fun loadFromJson(json: String) {
        val parsed = HashMap<LocalDate, HolidayInfo>()
        val yearSet = TreeSet<Int>()
        try {
            val root = JSONObject(json)

            val yearsNode = root.optJSONArray("years")
            if (yearsNode != null) {
                for (i in 0 until yearsNode.length()) {
                    val y = yearsNode.optInt(i, Int.MIN_VALUE)
                    if (y != Int.MIN_VALUE) yearSet.add(y)
                }
            }

            val daysObj = root.optJSONObject("days")
            if (daysObj != null) {
                val keys = daysObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = daysObj.optJSONObject(key) ?: continue
                    putDay(parsed, key, item.optString("name", ""), item.optBoolean("isOffDay", false))
                }
            } else {
                val daysArr: JSONArray? = root.optJSONArray("days")
                if (daysArr != null) {
                    for (i in 0 until daysArr.length()) {
                        val item = daysArr.optJSONObject(i) ?: continue
                        putDay(
                            parsed,
                            item.optString("date", ""),
                            item.optString("name", ""),
                            item.optBoolean("isOffDay", false)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // 数据损坏:保留旧快照,绝不影响调用方
            return
        }

        for (d in parsed.keys) yearSet.add(d.year)

        days = Collections.unmodifiableMap(parsed)
        years = Collections.unmodifiableList(ArrayList(yearSet))
    }

    private fun putDay(
        target: MutableMap<LocalDate, HolidayInfo>,
        dateText: String,
        name: String,
        isOffDay: Boolean
    ) {
        if (name.isEmpty()) return
        val date = parseDate(dateText) ?: return
        target[date] = HolidayInfo(date, name, isOffDay)
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 该日期是否为法定节假日或调休上班日;普通日子返回 null */
    fun holidayOf(date: LocalDate): HolidayInfo? = days[date]

    /** 农历 + 传统节日;等价于 LunarCalendar.of(date) */
    fun lunarOf(date: LocalDate): LunarInfo? = LunarCalendar.of(date)

    /** 是否休息日:法定放假,或(周末且不是调休上班) */
    fun isRestDay(date: LocalDate): Boolean {
        val info = days[date]
        if (info != null) return info.isOffDay
        return isWeekend(date)
    }

    /** 是否调休上班日 */
    fun isMakeupWorkday(date: LocalDate): Boolean {
        val info = days[date] ?: return false
        return !info.isOffDay
    }

    /** 数据覆盖到的年份(升序),例如 listOf(2025, 2026, 2027) */
    fun coveredYears(): List<Int> = years

    /** 数据未覆盖该年份时返回 true(界面可提示"节假日数据未收录该年") */
    fun isYearUncovered(year: Int): Boolean = !years.contains(year)

    private fun isWeekend(date: LocalDate): Boolean = when (date.dayOfWeek.value) {
        6, 7 -> true
        else -> false
    }
}
