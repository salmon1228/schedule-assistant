package com.sdh.schedule.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.EventType
import com.sdh.schedule.data.Note
import com.sdh.schedule.data.Priority
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.Todo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 备份 / 恢复。
 *
 * 为什么需要:所有数据都在一个 SQLite 文件里,MIUI 清理应用数据、换手机、
 * 或者误删就会**全部丢失**,而之前连导出都没有。
 *
 * 备份内容:日程 / 便签 / 课程 / 待办。
 * **不导出**:导入日志(纯诊断,而且很大)、大模型 API Key(密钥不写进备份文件)。
 */
object Backup {

    private const val TAG = "Backup"
    private const val FORMAT = "sdh-schedule-backup"
    private const val VERSION = 1

    /** 生成备份 JSON 文本 */
    fun exportJson(context: Context): String {
        Repo.init(context)
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("exportedAtText", nowText())

        val events = JSONArray()
        for (e in Repo.events.value.filter { !it.deleted }) {
            events.put(
                JSONObject().apply {
                    put("title", e.title)
                    put("note", e.note)
                    put("location", e.location)
                    put("start", e.startMillis)
                    put("end", e.endMillis)
                    put("allDay", e.allDay)
                    put("type", e.type.name)
                    put("repeat", e.repeat.name)
                    put("repeatWeekday", e.repeatWeekday)
                    put("repeatUntil", e.repeatUntilMillis)
                    put("reminderMinutesBefore", e.reminderMinutesBefore)
                    put("alarmReminder", e.alarmReminder)
                    put("countdownReminder", e.countdownReminder)
                    put("colorIndex", e.colorIndex)
                    put("priority", e.priority.name)
                    put("completed", e.completed)
                }
            )
        }
        root.put("events", events)

        val notes = JSONArray()
        for (n in Repo.notes.value.filter { !it.deleted }) {
            notes.put(
                JSONObject().apply {
                    put("title", n.title)
                    put("content", n.content)
                    put("pinned", n.pinned)
                    put("colorIndex", n.colorIndex)
                    put("createdAt", n.createdAt)
                    put("updatedAt", n.updatedAt)
                }
            )
        }
        root.put("notes", notes)

        val courses = JSONArray()
        for (c in Repo.courses.value) {
            courses.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("location", c.location)
                    put("weekday", c.weekday)
                    put("startSlot", c.startSlot)
                    put("slotCount", c.slotCount)
                    put("startWeek", c.startWeek)
                    put("endWeek", c.endWeek)
                    put("weekParity", c.weekParity)
                    put("colorIndex", c.colorIndex)
                    put("reminderMinutes", c.reminderMinutes)
                    put("enabled", c.enabled)
                    put("note", c.note)
                }
            )
        }
        root.put("courses", courses)

        val todos = JSONArray()
        for (t in Repo.todos.value.filter { !it.deleted }) {
            todos.put(
                JSONObject().apply {
                    put("title", t.title)
                    put("note", t.note)
                    put("done", t.done)
                    put("due", t.dueMillis)
                    put("priority", t.priority.name)
                    put("colorIndex", t.colorIndex)
                    put("createdAt", t.createdAt)
                    put("doneAt", t.doneAt)
                }
            )
        }
        root.put("todos", todos)

        return root.toString(2)
    }

    /** 建议的文件名 */
    fun suggestedFileName(): String {
        val t = LocalDateTime.now()
        return "日程助手备份_%04d%02d%02d_%02d%02d.json".format(
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute
        )
    }

    /** 导出到 SAF 给的 Uri */
    fun writeTo(context: Context, uri: Uri): Result<String> = runCatching {
        val json = exportJson(context)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("打不开目标文件")
        val counts = "日程 ${Repo.events.value.count { !it.deleted }} · " +
            "便签 ${Repo.notes.value.count { !it.deleted }} · " +
            "课程 ${Repo.courses.value.size} · " +
            "待办 ${Repo.todos.value.count { !it.deleted }}"
        Log.i(TAG, "已导出备份:$counts")
        counts
    }

    /** 数据统计(用于导出前提示) */
    fun stats(context: Context): String {
        Repo.init(context)
        return "日程 ${Repo.events.value.count { !it.deleted }} · " +
            "便签 ${Repo.notes.value.count { !it.deleted }} · " +
            "课程 ${Repo.courses.value.size} · " +
            "待办 ${Repo.todos.value.count { !it.deleted }}"
    }

    /** 从备份恢复(追加式:按标题+时间判重,不会覆盖现有数据) */
    fun importFrom(context: Context, uri: Uri): Result<String> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("读不到文件")
        importFromJson(context, text).getOrThrow()
    }

    /** 从备份文本恢复(便于自测与内部调用) */
    fun importFromJson(context: Context, text: String): Result<String> = runCatching {
        Repo.init(context)
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("这不是本应用导出的备份文件")
        }

        var newEvents = 0
        var newNotes = 0
        var newCourses = 0
        var newTodos = 0

        // 日程:标题 + 开始时间 相同就认为已存在
        val existingEvents = Repo.events.value.map { it.title + "@" + it.startMillis }.toHashSet()
        val events = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until events.length()) {
            val o = events.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val start = o.optLong("start")
            if (title.isBlank() || start <= 0L) continue
            if (!existingEvents.add(title + "@" + start)) continue
            Repo.addEvent(
                Event(
                    title = title,
                    note = o.optString("note"),
                    location = o.optString("location"),
                    startMillis = start,
                    endMillis = o.optLong("end", start + 3600_000L),
                    allDay = o.optBoolean("allDay", false),
                    type = runCatching { EventType.valueOf(o.optString("type", "SCHEDULE")) }.getOrDefault(EventType.SCHEDULE),
                    repeat = runCatching { com.sdh.schedule.parse.RepeatKind.valueOf(o.optString("repeat", "NONE")) }
                        .getOrDefault(com.sdh.schedule.parse.RepeatKind.NONE),
                    repeatWeekday = o.optInt("repeatWeekday", 0),
                    repeatUntilMillis = o.optLong("repeatUntil", 0L),
                    reminderMinutesBefore = o.optInt("reminderMinutesBefore", 10),
                    alarmReminder = o.optBoolean("alarmReminder", false),
                    countdownReminder = o.optBoolean("countdownReminder", false),
                    colorIndex = o.optInt("colorIndex", 0),
                    priority = runCatching { Priority.valueOf(o.optString("priority", "NORMAL")) }.getOrDefault(Priority.NORMAL),
                    completed = o.optBoolean("completed", false),
                    source = EventSource.MANUAL
                )
            )
            newEvents++
        }

        // 便签:标题 + 内容 相同就跳过
        val existingNotes = Repo.notes.value.map { it.title + "@" + it.content }.toHashSet()
        val notes = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notes.length()) {
            val o = notes.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val content = o.optString("content")
            if (!existingNotes.add(title + "@" + content)) continue
            Repo.addNote(
                Note(
                    title = title,
                    content = content,
                    pinned = o.optBoolean("pinned", false),
                    colorIndex = o.optInt("colorIndex", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
            newNotes++
        }

        // 课程:名称 + 星期 + 节次 相同就跳过
        val existingCourses = Repo.courses.value.map { it.name + "@" + it.weekday + "@" + it.startSlot }.toHashSet()
        val courses = root.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until courses.length()) {
            val o = courses.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val key = name + "@" + o.optInt("weekday", 1) + "@" + o.optInt("startSlot", 1)
            if (!existingCourses.add(key)) continue
            Repo.addCourse(
                Course(
                    name = name,
                    teacher = o.optString("teacher"),
                    location = o.optString("location"),
                    weekday = o.optInt("weekday", 1),
                    startSlot = o.optInt("startSlot", 1),
                    slotCount = o.optInt("slotCount", 2),
                    startWeek = o.optInt("startWeek", 1),
                    endWeek = o.optInt("endWeek", 16),
                    weekParity = o.optInt("weekParity", 0),
                    colorIndex = o.optInt("colorIndex", 0),
                    reminderMinutes = o.optInt("reminderMinutes", 10),
                    enabled = o.optBoolean("enabled", true),
                    note = o.optString("note")
                )
            )
            newCourses++
        }

        // 待办:标题相同且都没完成就跳过
        val existingTodos = Repo.todos.value.map { it.title }.toHashSet()
        val todos = root.optJSONArray("todos") ?: JSONArray()
        for (i in 0 until todos.length()) {
            val o = todos.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            if (!existingTodos.add(title)) continue
            Repo.addTodo(
                Todo(
                    title = title,
                    note = o.optString("note"),
                    done = o.optBoolean("done", false),
                    dueMillis = o.optLong("due", 0L),
                    priority = runCatching { Priority.valueOf(o.optString("priority", "NORMAL")) }.getOrDefault(Priority.NORMAL),
                    colorIndex = o.optInt("colorIndex", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
            newTodos++
        }

        val msg = "新增 日程 $newEvents · 便签 $newNotes · 课程 $newCourses · 待办 $newTodos"
        Log.i(TAG, "恢复完成:$msg")
        msg
    }

    private fun nowText(): String {
        val t = LocalDateTime.now(ZoneId.systemDefault())
        return "%04d-%02d-%02d %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    /** 顺手报告一下数据库文件大小(排查看日志是否又涨) */
    fun dbSizeText(context: Context): String {
        val f = File(context.getDatabasePath("schedule.db").absolutePath)
        val kb = if (f.exists()) f.length() / 1024 else 0
        return if (kb > 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB"
    }
}

