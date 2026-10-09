package com.sdh.schedule.data

/**
 * 待办(任务)。
 * 和日程的区别:日程有明确的开始时间,待办只是"要做的一件事",可能只有一个截止时间,甚至没有时间。
 * 例:群里老师说「明天会发一张表格下来需要我们填写,准备好一寸照片」
 *     → 日程:明天交表格;待办:准备一寸照片
 */
data class Todo(
    val id: Long = 0L,
    val title: String = "",
    val note: String = "",
    val done: Boolean = false,
    /** 截止时间;0 = 没有截止 */
    val dueMillis: Long = 0L,
    val priority: Priority = Priority.NORMAL,
    val colorIndex: Int = 0,
    val source: EventSource = EventSource.MANUAL,
    val sourceText: String = "",
    val sourcePackage: String = "",
    /** 关联的日程 id(可选) */
    val relatedEventId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val doneAt: Long = 0L,
    val deleted: Boolean = false
) {
    val hasDue: Boolean get() = dueMillis > 0L

    /** 是否已过期(有截止、未完成、截止时间已过) */
    fun isOverdue(now: Long = System.currentTimeMillis()): Boolean =
        hasDue && !done && dueMillis < now
}

/** 解析出来的待办(还没落库) */
data class ParsedTodo(
    val title: String,
    val dueMillis: Long = 0L,
    val note: String = "",
    val confidence: Double = 0.7,
    /** 命中的关键词,便于 UI 展示"为什么这么解析" */
    val matched: List<String> = emptyList(),
    val sourceText: String = ""
)
