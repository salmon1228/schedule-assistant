package com.sdh.schedule.live

/**
 * 从实时转写的文字里判断"这句是不是一个提问"。
 *
 * 纯 Kotlin、不依赖 Android —— 方便离线单测(`tools/offline-tests/live-test`)。
 *
 * 设计原则:**宁缺毋滥**。老师的陈述句、口头语、广告词都不该被当成问题去打扰大模型
 * (每问一次都要花 API 调用)。
 */
object QuestionDetector {

    /** 疑问词/疑问句式 */
    private val QUESTION_WORDS = listOf(
        "什么", "为什么", "怎么", "怎样", "如何", "哪", "哪个", "哪些", "哪里", "哪儿",
        "谁", "何时", "什么时候", "多少", "几", "是不是", "有没有", "能不能", "可不可以",
        "对不对", "好不好", "行不行", "是否", "请问", "吗", "呢", "嘛"
    )

    /** 这些句子即使带问号也不算"需要回答的问题"(点名、口头禅、设备提示) */
    private val NOT_A_QUESTION = listOf(
        "点名", "签到", "听到了吗", "听见了吗", "能听见", "能听到", "听得到吗", "听得到不",
        "声音大不大", "声音清楚", "看到我的屏幕", "卡不卡", "麦克风", "网速",
        "大家注意", "安静一下", "手机静音", "哪位同学没来", "都到了吗"
    )

    /** 明显是噪音(太短、纯符号、纯数字) */
    private fun isNoise(s: String): Boolean {
        if (s.length < 4) return true
        val han = s.count { it.code in 0x4E00..0x9FA5 }
        return han < 2
    }

    /**
     * 判断一句话是不是提问。
     * @param text 一句话(已经去掉首尾空白)
     */
    fun isQuestion(text: String): Boolean {
        val t = text.trim()
        if (isNoise(t)) return false
        if (NOT_A_QUESTION.any { t.contains(it) }) return false
        if (t.endsWith("?") || t.endsWith("?")) return true
        return QUESTION_WORDS.any { t.contains(it) }
    }

    /**
     * 对一段(可能是不断增长/重复的)转写内容,找出**新的**问题。
     *
     * 实时语音识别会反复吐同一句(partial results),所以这里:
     *   ① 去掉和"刚问过的"重复的(相似度 ≥ 0.8 视为同一句)
     *   ② 只保留问句
     *   ③ 太长的不当问题(超过 60 字多半是念材料)
     */
    fun pickNewQuestions(
        text: String,
        alreadyAsked: List<String>,
        maxLen: Int = 60
    ): List<String> {
        val sentences = splitSentences(text)
        val out = ArrayList<String>()
        for (s in sentences) {
            val t = s.trim()
            if (t.length > maxLen) continue
            if (!isQuestion(t)) continue
            if (alreadyAsked.any { similar(it, t) }) continue
            if (out.any { similar(it, t) }) continue
            out.add(t)
        }
        return out
    }

    /** 按中英文标点断句 */
    fun splitSentences(text: String): List<String> =
        text.split('。', '!', '?', '?', '!', ';', ';', '\n', ',', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 两句是否"基本是同一句"(用于去重,避免同一个问题反复问) */
    fun similar(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        if (longer.contains(shorter) && shorter.length >= 6) return true
        // 简单的字符重合率
        val set = shorter.toSet()
        val hit = longer.count { it in set }
        return hit.toDouble() / longer.length >= 0.8
    }

    private fun normalize(s: String): String =
        s.filter { it.code in 0x4E00..0x9FA5 || it.isLetterOrDigit() }
}
