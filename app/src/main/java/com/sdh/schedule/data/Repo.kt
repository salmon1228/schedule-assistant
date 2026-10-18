package com.sdh.schedule.data

import android.content.Context
import com.sdh.schedule.alarm.CourseReminderScheduler
import com.sdh.schedule.alarm.ReminderScheduler
import com.sdh.schedule.notify.Notify
import com.sdh.schedule.util.Occurrence
import com.sdh.schedule.util.Recurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 全应用唯一的数据入口。启动时把数据全量读进内存(个人日程量级很小),
 * 写操作统一走这里,写完刷新 StateFlow 并同步提醒闹钟。
 */
object Repo {

    private var daoOrNull: EventDao? = null
    private val dao: EventDao get() = requireNotNull(daoOrNull) { "Repo.init() 未调用" }

    lateinit var settings: SettingsStore
        private set

    lateinit var appContext: Context
        private set

    init {
        // no-op
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions

    private val _logs = MutableStateFlow<List<ImportLog>>(emptyList())
    val logs: StateFlow<List<ImportLog>> = _logs

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    val todos: StateFlow<List<Todo>> = _todos

    val zone: ZoneId get() = ZoneId.systemDefault()

    @Synchronized
    fun init(context: Context) {
        if (daoOrNull != null) return
        val ctx = context.applicationContext
        appContext = ctx
        settings = SettingsStore(ctx)
        daoOrNull = EventDao(ctx)
        reload()
    }

    fun reload() {
        val d = dao
        _events.value = d.loadAllEvents()
        _suggestions.value = d.loadSuggestions()
        _logs.value = d.loadLogs()
        _notes.value = d.loadNotes()
        _courses.value = d.loadCourses()
        _todos.value = d.loadTodos()
    }

    // ---------------- 日程读写 ----------------

    fun addEvent(e: Event, scheduleReminder: Boolean = true): Long {
        val id = dao.insertEvent(e)
        val saved = e.copy(id = id)
        reload()
        if (scheduleReminder) ReminderScheduler.syncEvent(appContext, saved)
        return id
    }

    fun updateEvent(e: Event) {
        dao.updateEvent(e)
        // 先撤掉旧闹钟(时间可能变了),再按新时间重排
        ReminderScheduler.cancelEvent(appContext, e)
        reload()
        ReminderScheduler.syncEvent(appContext, e)
    }

    fun deleteEvent(id: Long) {
        val e = dao.getEvent(id)
        if (e != null) {
            ReminderScheduler.cancelEvent(appContext, e)
            // 通知栏里那条"倒计时提醒"不会自己消失,必须显式撤掉,否则会一直挂着
            Notify.cancelReminder(appContext, id)
            Notify.cancelCountdown(appContext, id)
            Notify.cancelAlarm(appContext, id)
        }
        dao.softDeleteEvent(id)
        reload()
    }

    fun setCompleted(id: Long, completed: Boolean) {
        val e = dao.getEvent(id) ?: return
        val updated = e.copy(completed = completed)
        dao.updateEvent(updated)
        if (completed) {
            ReminderScheduler.cancelEvent(appContext, e)
            Notify.cancelReminder(appContext, id)
            Notify.cancelCountdown(appContext, id)
            Notify.cancelAlarm(appContext, id)
        }
        reload()
    }

    /** 稍后提醒:直接把开始时间推后 minutes 分钟(用于通知里的"稍后提醒"按钮) */
    fun snooze(eventId: Long, minutes: Int) {
        val e = dao.getEvent(eventId) ?: return
        val delta = minutes * 60_000L
        val updated = e.copy(
            startMillis = e.startMillis + delta,
            endMillis = e.endMillis + delta,
            // 提醒时间相对新开始时间重新计算
            reminderMinutesBefore = 0
        )
        dao.updateEvent(updated)
        ReminderScheduler.cancelEvent(appContext, e)
        reload()
        ReminderScheduler.syncEvent(appContext, updated)
    }

    fun getEvent(id: Long): Event? = dao.getEvent(id)

    // ---------------- 查询 ----------------

    fun occurrencesOn(date: LocalDate): List<Occurrence> {
        val now = _events.value
        val out = ArrayList<Occurrence>()
        for (e in now) {
            // 含"前一天开始、跨天到今天还没结束"的实例
            out.addAll(Recurrence.occurrencesForDay(e, date, zone))
        }
        return out.sortedWith(compareBy({ it.event.allDay.not() }, { it.startMillis }))
    }

    fun occurrencesBetween(fromMs: Long, toMs: Long): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (e in _events.value) out.addAll(Recurrence.occurrencesBetween(e, fromMs, toMs, zone))
        return out.sortedBy { it.startMillis }
    }

    /** 从某天开始按天分组,用于"日程"列表页 */
    fun agenda(from: LocalDate, days: Int): List<Pair<LocalDate, List<Occurrence>>> {
        val out = ArrayList<Pair<LocalDate, List<Occurrence>>>()
        for (i in 0 until days) {
            val d = from.plusDays(i.toLong())
            out.add(d to occurrencesOn(d))
        }
        return out
    }

    /** 未来 n 天内所有发生实例(首页"即将到来"用) */
    fun upcoming(nowMs: Long = System.currentTimeMillis(), days: Int = 30): List<Occurrence> {
        val from = LocalDateTime.now(zone).toLocalDate()
        val toMs = nowMs + days * 86_400_000L
        return occurrencesBetween(nowMs, toMs).filter { it.endMillis >= nowMs }
    }

    fun pendingSuggestions(): List<Suggestion> =
        _suggestions.value.filter { it.status == Suggestion.STATUS_PENDING }

    // ---------------- 候选日程(待确认) ----------------

    fun addSuggestion(s: Suggestion): Long {
        val id = dao.insertSuggestion(s)
        reload()
        return id
    }

    /**
     * 同一内容在去重窗口内是否已提示过。
     *
     * 两步判断(参考社区里"通知→日历"这类 App 的做法):
     *  ① 完全相同 —— 直接查库(快)
     *  ② **规范化后相似** —— 同一条通知被反复推送时文本常有细微差别
     *     (「发来1条消息」/「发来2条消息」、末尾表情等),只比完全相同会漏,于是重复建日程。
     */
    fun isDuplicateText(sourceText: String): Boolean {
        val window = settings.dedupeWindowMinutes * 60_000L
        val since = System.currentTimeMillis() - window
        if (dao.hasRecentSuggestion(sourceText, since)) return true
        val norm = com.sdh.schedule.util.TextSimilarity.normalize(sourceText)
        if (norm.length < 8) return false
        return _suggestions.value.any { s ->
            s.createdAt >= since &&
                com.sdh.schedule.util.TextSimilarity.normalize(s.sourceText).length >= 8 &&
                com.sdh.schedule.util.TextSimilarity.similar(norm, s.sourceText)
        }
    }

    fun acceptSuggestion(id: Long): Long? {
        val s = _suggestions.value.firstOrNull { it.id == id } ?: return null
        val eventId = addEvent(s.toEvent(settings.defaultAccount, settings.defaultReminderMinutes))
        dao.updateSuggestionStatus(id, Suggestion.STATUS_ACCEPTED)
        reload()
        return eventId
    }

    fun rejectSuggestion(id: Long) {
        dao.updateSuggestionStatus(id, Suggestion.STATUS_REJECTED)
        reload()
    }

    /** 只改状态、不建日程(用于"编辑后加入":交给编辑页去创建,避免重复建两条) */
    fun markSuggestionHandled(id: Long, status: String) {
        dao.updateSuggestionStatus(id, status)
        reload()
    }

    fun deleteSuggestion(id: Long) {
        dao.deleteSuggestion(id)
        reload()
    }

    fun clearHandledSuggestions() {
        for (s in _suggestions.value.filter { it.status != Suggestion.STATUS_PENDING }) {
            dao.deleteSuggestion(s.id)
        }
        reload()
    }

    // ---------------- 导入日志 ----------------

    fun log(
        source: EventSource,
        sourcePackage: String,
        rawText: String,
        engine: String,
        outcome: String,
        note: String = ""
    ) {
        dao.insertLog(
            ImportLog(
                source = source,
                sourcePackage = sourcePackage,
                rawText = rawText.take(2000),
                engine = engine,
                outcome = outcome,
                note = note
            )
        )
        _logs.value = dao.loadLogs()
    }

    fun clearLogs() {
        dao.clearLogs()
        _logs.value = emptyList()
    }

    /** 把所有未来日程的提醒闹钟重排一遍(开机 / 权限变更 / 时区变更后调用) */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        var scheduled = 0
        var noReminder = 0
        var cleaned = 0
        for (e in _events.value) {
            // 已完成 / 已经结束的日程,顺手把还挂在通知栏里的提醒与倒计时撤掉
            if (e.completed || e.endMillis < now - 60_000L) {
                Notify.cancelReminder(appContext, e.id)
                Notify.cancelCountdown(appContext, e.id)
                Notify.cancelAlarm(appContext, e.id)
                cleaned++
            }
            if (!e.hasReminder) {
                noReminder++
                continue
            }
            try {
                ReminderScheduler.syncEvent(appContext, e, reference = now)
                scheduled++
            } catch (t: Throwable) {
                android.util.Log.e("Repo", "重排提醒失败: ${e.title}", t)
            }
        }
        android.util.Log.i(
            "Repo",
            "重排提醒完成:日历共 ${_events.value.size} 条,参与调度 $scheduled 条,无提醒 $noReminder 条,清理过期通知 $cleaned 条"
        )
        // 课程提醒也一起重排(开机 / 打开 App 后)
        runCatching { CourseReminderScheduler.reschedule(appContext, now) }
    }

    // ---------------- 便签 ----------------

    fun addNote(n: Note): Long {
        val id = dao.insertNote(n)
        _notes.value = dao.loadNotes()
        return id
    }

    fun updateNote(n: Note) {
        dao.updateNote(n)
        _notes.value = dao.loadNotes()
    }

    fun deleteNote(id: Long) {
        dao.softDeleteNote(id)
        _notes.value = dao.loadNotes()
    }

    fun getNote(id: Long): Note? = _notes.value.firstOrNull { it.id == id }

    // ---------------- 课程 ----------------

    fun addCourse(c: Course): Long {
        val id = dao.insertCourse(c)
        _courses.value = dao.loadCourses()
        CourseReminderScheduler.reschedule(appContext)
        return id
    }

    fun updateCourse(c: Course) {
        dao.updateCourse(c)
        _courses.value = dao.loadCourses()
        CourseReminderScheduler.reschedule(appContext)
    }

    fun deleteCourse(id: Long) {
        dao.deleteCourse(id)
        _courses.value = dao.loadCourses()
        CourseReminderScheduler.reschedule(appContext)
    }

    /** 某天的课(按节次排序),用于课程表与上课提醒 */
    fun coursesOn(date: java.time.LocalDate): List<Course> {
        val week = Course.weekOf(date, settings.semesterStartDate())
        if (week <= 0) return emptyList()
        val dow = date.dayOfWeek.value
        return _courses.value
            .filter { it.weekday == dow && it.activeInWeek(week) }
            .sortedBy { it.startSlot }
    }

    /** 今天/本周的课程(整周,用于课表格子) */
    fun coursesInWeek(week: Int): List<Course> =
        _courses.value.filter { it.activeInWeek(week) }.sortedWith(compareBy({ it.weekday }, { it.startSlot }))

    /** 当前是第几周 */
    fun currentWeek(): Int = Course.weekOf(java.time.LocalDate.now(), settings.semesterStartDate())

    // ---------------- 待办 ----------------

    fun addTodo(t: Todo): Long {
        val id = dao.insertTodo(t)
        _todos.value = dao.loadTodos()
        return id
    }

    fun updateTodo(t: Todo) {
        dao.updateTodo(t)
        _todos.value = dao.loadTodos()
    }

    fun setTodoDone(id: Long, done: Boolean) {
        val t = _todos.value.firstOrNull { it.id == id } ?: return
        updateTodo(t.copy(done = done, doneAt = if (done) System.currentTimeMillis() else 0L))
    }

    fun deleteTodo(id: Long) {
        dao.softDeleteTodo(id)
        _todos.value = dao.loadTodos()
    }

    fun getTodo(id: Long): Todo? = _todos.value.firstOrNull { it.id == id }

    /** 待办去重:同一标题在最近一段时间内是否已经加过 */
    fun hasRecentTodo(title: String, withinMillis: Long = 12 * 3600_000L): Boolean =
        dao.hasRecentTodo(title, System.currentTimeMillis() - withinMillis)

    /** 未完成的待办(按截止时间排序) */
    fun openTodos(): List<Todo> = _todos.value.filter { !it.done && !it.deleted }

    /** 今天到期 / 已过期的未完成待办 */
    fun todayTodos(): List<Todo> {
        val end = java.time.LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return openTodos().filter { it.hasDue && it.dueMillis < end }
    }

    /** 清理已完成的待办 */
    fun clearDoneTodos() {
        _todos.value.filter { it.done }.forEach { dao.softDeleteTodo(it.id) }
        _todos.value = dao.loadTodos()
    }
}
