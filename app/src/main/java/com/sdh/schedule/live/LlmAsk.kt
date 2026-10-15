package com.sdh.schedule.live

import android.content.Context
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.LlmParser

/**
 * 拿大模型回答一个"当场听到的问题"。
 *
 * 复用项目已有的 LLM 通道(LlmParser.parseTextRaw),所以:
 *   - 用你自己在设置里填的 Key / 接口地址;
 *   - 没配 Key 或没网时,返回一句人话说明,不崩。
 *
 * 提示词强调:**简单直接、给要点**,因为这是"边听边看"的场景,不是写论文。
 */
object LlmAsk {

    private val SYSTEM_PROMPT = """
你是一个"随身助教"。用户正在上课/开会/交流,你把当场听到的问题拿来问你。

要求:
1. **直接给答案**,先说结论,不要寒暄、不要复述问题;
2. 控制在 120 字以内,必要时用 2~4 条短句或要点;
3. 如果是需要计算/推理的,把关键步骤写一行;
4. 如果信息不足以回答,就说"信息不足,需要:xxx",不要编造;
5. 只用中文回答。
""".trimIndent()

    /** 同步调用(调用方自己放到 IO 线程/协程里)*/
    fun ask(context: Context, question: String, contextText: String = ""): String {
        val s = Repo.settings
        if (s.llmApiKey.isBlank()) return "还没配大模型 Key —— 去「设置 → 解析引擎」填一个就能用了"
        val user = buildString {
            if (contextText.isNotBlank()) {
                append("【刚才的上下文(可能不完整,仅供理解)】\n")
                append(contextText.take(800)).append("\n\n")
            }
            append("【要回答的问题】\n").append(question)
        }
        return try {
            val raw = LlmParser.parseTextRaw(context, s, SYSTEM_PROMPT, user)
            raw.trim().ifBlank { "大模型返回了空内容,再试一次?" }
        } catch (t: Throwable) {
            "请求失败:${t.message ?: "未知错误"}(检查网络或 Key)"
        }
    }
}

