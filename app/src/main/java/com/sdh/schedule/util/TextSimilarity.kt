package com.sdh.schedule.util

/**
 * 文本相似度工具(纯 Kotlin,可单测)。
 *
 * 为什么要它:同一条通知常常被反复推送,而每次的文本会有细微差别 ——
 * 例如「26级电子信息2班发来1条消息」和「26级电子信息2班发来2条消息」,
 * 或者末尾多了表情、多了"我正在输入…"之类。只按**完全相同**的文本去重会漏掉它们,
 * 结果就是同一个日程/待办被建了好几遍。
 *
 * 社区里"通知→日历"这类 App(AutoEvent、messageAIHelper 等)通行的做法是:
 *   **先规范化(去掉标点/空格/表情),再用相似度判重** —— 这里照这个思路实现。
 */
object TextSimilarity {

    /** 只保留汉字、字母、数字;去掉标点、空格、表情、数字差异之外的一切噪声 */
    fun normalize(s: String): String =
        s.filter { it.code in 0x4E00..0x9FA5 || it.isLetterOrDigit() }

    /**
     * 两句是否"基本是同一句"。
     * 判定:① 完全相同;② 短的那句是长的那句的子串(且够长 —— 短句太短时容易误判);
     *      ③ 字符重合率 ≥ [threshold]。
     */
    fun similar(a: String, b: String, threshold: Double = 0.8): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        // 短句要够长才算"包含",否则「知道了」会命中一大片
        if (shorter.length >= 8 && longer.contains(shorter)) return true
        if (longer.length > shorter.length * 3) return false   // 长度差太多,多半不是同一条
        val set = shorter.toSet()
        val hit = longer.count { it in set }
        return hit.toDouble() / longer.length >= threshold
    }
}
