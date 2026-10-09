package com.sdh.schedule.parse

/**
 * 节次时间表工具(纯 Kotlin,可单测)。
 *
 * 血泪教训(两次都栽在这里):
 *  1. **不能"合并"旧设置** —— 用户旧设置可能本身就是坏的(上一版导入写坏的),
 *     合并只会把垃圾一起留下来:实测 10 节被并成 20 条,
 *     出现 08:00/08:20、11:45/11:50 这种重复节次,课程序号全错,
 *     结果「英语1 15:15 的课被标成第 6 节(11:45)」→ 用户反馈"我下午的课呢?"
 *  2. **导入 ics 时应该以 ics 为准替换**(ics 是从真实课表推导出来的,更可信),
 *     而不是跟旧值求并集。用户想微调可以用 课程表 → 设置 → 节次时间 手动改。
 *
 * 所以:导入时 **替换**,但保留一条底线 —— 推导结果太少(<3 节)时不动原设置。
 */
object SlotTimes {

    /** 两个节次开始时间相差多少分钟内算"同一节"(用于去重) */
    private const val SAME_SLOT_MINUTES = 8

    /** 解析 "08:20-09:05,09:10-09:55" → [(开始, 结束)] */
    fun parse(text: String): List<Pair<String, String>> =
        text.split(',', '，')
            .mapNotNull { seg ->
                val parts = seg.trim().split('-', '–', '—')
                if (parts.size != 2) return@mapNotNull null
                val s = parts[0].trim().take(5)
                val e = parts[1].trim().take(5)
                if (!isTime(s) || !isTime(e)) null else s to e
            }

    private fun isTime(s: String): Boolean =
        s.length >= 4 && s.contains(':') &&
            s.substringBefore(':').toIntOrNull() != null &&
            s.substringAfter(':').take(2).toIntOrNull() != null

    private fun minutes(t: String): Int {
        val h = t.substringBefore(':').toIntOrNull() ?: 0
        val m = t.substringAfter(':').take(2).toIntOrNull() ?: 0
        return h * 60 + m
    }

    /** 现有节次的数量 */
    fun count(text: String): Int = parse(text).size

    /** 是否含重复节次(两个开始时间相差 ≤8 分钟) */
    fun hasDuplicates(text: String): Boolean {
        val mins = parse(text).map { minutes(it.first) }.sorted()
        return mins.zipWithNext { a, b -> b - a }.any { it in 0..SAME_SLOT_MINUTES }
    }

    /**
     * 导入 ics / 备份时的处理:**以新推导的为准替换**(不再与旧值合并)。
     * 推导结果太少(<3 节)时返回原值,避免把设置弄没了。
     */
    fun applyImport(existing: String, derived: List<Pair<Int, String>>): String {
        val newRanges = derived.sortedBy { it.first }.mapNotNull { (_, range) ->
            val parts = range.split('-')
            if (parts.size != 2) return@mapNotNull null
            val s = parts[0].trim().take(5)
            val e = parts[1].trim().take(5)
            if (!isTime(s) || !isTime(e)) null else s to e
        }
        if (newRanges.size < 3) return existing
        return newRanges.joinToString(",") { "${it.first}-${it.second}" }
    }

    /** 去掉重复节次(保留较早的那个),用于修已经写坏的设置 */
    fun dedupe(text: String): String {
        val list = parse(text).sortedBy { minutes(it.first) }
        val out = ArrayList<Pair<String, String>>()
        for (p in list) {
            val prev = out.lastOrNull()
            if (prev != null && minutes(p.first) - minutes(prev.first) <= SAME_SLOT_MINUTES) continue
            out.add(p)
        }
        return out.joinToString(",") { "${it.first}-${it.second}" }
    }
}
