package com.sdh.schedule.live

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 「实时听写」的共享状态。
 *
 * 为什么用单例对象:转写跑在前台服务里,界面可能被切走/重建,
 * 状态放这里,回来看还在(和项目里 Repo 的做法一致)。
 */
object LiveState {

    /** 问答条目:检测到的问题 + 大模型给的回答 */
    data class Qa(
        val question: String,
        val answer: String = "",
        val at: Long = System.currentTimeMillis(),
        /** 正在请求大模型 */
        val thinking: Boolean = false,
        /** 出错信息(没配 Key、断网等)*/
        val error: String = ""
    )

    /** 是否正在听 */
    val running = MutableStateFlow(false)

    /** 已经定稿的整句(按时间顺序)*/
    val sentences = MutableStateFlow<List<String>>(emptyList())

    /** 正在说的半句(实时刷新)*/
    val partial = MutableStateFlow("")

    /** 检测到的问题与回答 */
    val qa = MutableStateFlow<List<Qa>>(emptyList())

    /** 状态提示(没有语音识别服务、权限问题等)*/
    val status = MutableStateFlow("")

    fun reset() {
        sentences.value = emptyList()
        partial.value = ""
        qa.value = emptyList()
        status.value = ""
    }

    /** 整段文字(用于给大模型当上下文)*/
    fun transcriptText(limit: Int = 1200): String =
        sentences.value.takeLast(40).joinToString("。").takeLast(limit)
}
