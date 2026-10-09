package com.sdh.schedule.ui

/**
 * 从通知点进来时要跳转的目标。
 *
 * 注意:App 已经在后台时,点通知走的是 onNewIntent(**不会**重新 onCreate),
 * 所以跳转目标必须以"变化的值"的形式传进来,由界面消费一次后清掉 ——
 * 只靠 onCreate 读一次 extra 的话,热启动点通知会什么都不发生。
 */
sealed interface NavTarget {
    /** 打开某条日程的编辑页 */
    data class OpenEvent(val eventId: Long) : NavTarget

    /** 打开「待确认」页 */
    data object OpenSuggestions : NavTarget
}
