package com.sdh.schedule.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.sdh.schedule.parse.RepeatKind

/**
 * 纯 SQLite(不引 Room,避免注解处理器,构建更快更稳)。
 * 个人日程量级很小,查询策略是「全部读进内存 + 在 Kotlin 里做重复规则展开」。
 *
 * 取值一律用列名(而不是下标),以后加字段不会错位。
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "schedule.db"
        /** v2 新增 notes / courses;v3 新增 todos;v4 压缩导入日志 */
        const val DB_VERSION = 4

        /** 导入日志最多保留多少条(超了删最旧的) */
        const val LOG_KEEP = 400

        /** 超过这个条数才开始裁剪,避免每次写入都去 delete */
        const val LOG_TRIM_TRIGGER = 500

        const val T_EVENT = "events"
        const val T_SUGGESTION = "suggestions"
        const val T_LOG = "import_log"
        const val T_NOTE = "notes"
        const val T_COURSE = "courses"
        const val T_TODO = "todos"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_EVENT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                start_ms INTEGER NOT NULL DEFAULT 0,
                end_ms INTEGER NOT NULL DEFAULT 0,
                all_day INTEGER NOT NULL DEFAULT 0,
                type TEXT NOT NULL DEFAULT 'SCHEDULE',
                repeat TEXT NOT NULL DEFAULT 'NONE',
                repeat_weekday INTEGER NOT NULL DEFAULT 0,
                repeat_until INTEGER NOT NULL DEFAULT 0,
                reminder_min INTEGER NOT NULL DEFAULT 10,
                alarm_reminder INTEGER NOT NULL DEFAULT 0,
                countdown_reminder INTEGER NOT NULL DEFAULT 0,
                account TEXT NOT NULL DEFAULT '本地日历',
                time_zone TEXT NOT NULL DEFAULT '',
                color_index INTEGER NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'NORMAL',
                source TEXT NOT NULL DEFAULT 'MANUAL',
                source_text TEXT NOT NULL DEFAULT '',
                source_package TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_event_start ON $T_EVENT(start_ms)")
        db.execSQL("CREATE INDEX idx_event_deleted ON $T_EVENT(deleted)")

        db.execSQL(
            """
            CREATE TABLE $T_SUGGESTION (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                start_ms INTEGER NOT NULL DEFAULT 0,
                end_ms INTEGER NOT NULL DEFAULT 0,
                all_day INTEGER NOT NULL DEFAULT 0,
                repeat TEXT NOT NULL DEFAULT 'NONE',
                repeat_weekday INTEGER NOT NULL DEFAULT 0,
                reminder_min INTEGER NOT NULL DEFAULT -1,
                confidence REAL NOT NULL DEFAULT 0,
                engine TEXT NOT NULL DEFAULT 'rule',
                source TEXT NOT NULL DEFAULT 'NOTIFICATION_TEXT',
                source_text TEXT NOT NULL DEFAULT '',
                source_package TEXT NOT NULL DEFAULT '',
                image_path TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sug_status ON $T_SUGGESTION(status)")

        db.execSQL(
            """
            CREATE TABLE $T_LOG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at_ms INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT '',
                source_package TEXT NOT NULL DEFAULT '',
                raw_text TEXT NOT NULL DEFAULT '',
                engine TEXT NOT NULL DEFAULT '',
                outcome TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        createNoteTable(db)
        createCourseTable(db)
        createTodoTable(db)
    }

    private fun createTodoTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_TODO (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                done INTEGER NOT NULL DEFAULT 0,
                due_ms INTEGER NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'NORMAL',
                color_index INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'MANUAL',
                source_text TEXT NOT NULL DEFAULT '',
                source_package TEXT NOT NULL DEFAULT '',
                related_event_id INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                done_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    private fun createNoteTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_NOTE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0,
                color_index INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    private fun createCourseTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_COURSE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL DEFAULT '',
                teacher TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                weekday INTEGER NOT NULL DEFAULT 1,
                start_slot INTEGER NOT NULL DEFAULT 1,
                slot_count INTEGER NOT NULL DEFAULT 2,
                start_week INTEGER NOT NULL DEFAULT 1,
                end_week INTEGER NOT NULL DEFAULT 16,
                week_parity INTEGER NOT NULL DEFAULT 0,
                color_index INTEGER NOT NULL DEFAULT 0,
                reminder_min INTEGER NOT NULL DEFAULT 10,
                enabled INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 注意:不能删表重建 —— 那会把用户已有的日程/便签清空。
        // 升级只做"补齐新表",已有数据全部保留。
        if (oldVersion < 2) {
            createNoteTable(db)
            createCourseTable(db)
        }
        if (oldVersion < 3) {
            createTodoTable(db)
        }
        if (oldVersion < 4) {
            // v4:导入日志曾经无限增长(实测 4.5 万条 / 8.7MB),这里一次性裁剪 + 回收空间
            runCatching { trimLogs(db, LOG_KEEP) }
            runCatching { db.execSQL("VACUUM") }
        }
    }

    /** 只保留最近的 [keep] 条导入日志,返回删掉的行数 */
    private fun trimLogs(db: SQLiteDatabase, keep: Int): Int {
        val keepId = db.rawQuery("SELECT id FROM $T_LOG ORDER BY id DESC LIMIT 1 OFFSET ?", arrayOf((keep - 1).toString()))
        var threshold = -1L
        keepId.use { c -> if (c.moveToFirst()) threshold = c.getLong(0) }
        if (threshold < 0L) return 0
        return db.delete(T_LOG, "id < ?", arrayOf(threshold.toString()))
    }
}

/** 取值辅助:按列名取,缺失时给默认值 */
private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.bool(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) == 1
private fun Cursor.dbl(name: String): Double = getDouble(getColumnIndexOrThrow(name))

/** events / suggestions / import_log 的读写 */
class EventDao(context: Context) {

    private val helper = DbHelper(context.applicationContext)

    /**
     * 写操作统一走这里。
     * 数据库文件若被外部删除/移动(系统「清除数据」、备份还原、adb 手动删库),
     * 已打开的连接会变成只读并持续报 SQLITE_READONLY_DBMOVED —— 这里自动重开连接重试一次,
     * 让 App 自愈而不是每次写入都失败。
     */
    private fun <T> write(block: (SQLiteDatabase) -> T): T {
        return try {
            block(helper.writableDatabase)
        } catch (e: SQLiteException) {
            val msg = e.message.orEmpty()
            val recoverable = e is android.database.sqlite.SQLiteReadOnlyDatabaseException ||
                msg.contains("readonly", ignoreCase = true) ||
                msg.contains("malformed", ignoreCase = true) ||
                msg.contains("no such table", ignoreCase = true)
            if (!recoverable) throw e
            android.util.Log.w("EventDao", "数据库连接异常($msg),重开连接后重试")
            runCatching { helper.close() }
            block(helper.writableDatabase)
        }
    }

    // ---------------- events ----------------

    private fun cursorToEvent(c: Cursor): Event = Event(
        id = c.long("id"),
        title = c.str("title"),
        note = c.str("note"),
        location = c.str("location"),
        startMillis = c.long("start_ms"),
        endMillis = c.long("end_ms"),
        allDay = c.bool("all_day"),
        type = EventType.fromName(c.str("type")),
        repeat = runCatching { RepeatKind.valueOf(c.str("repeat")) }.getOrDefault(RepeatKind.NONE),
        repeatWeekday = c.int("repeat_weekday"),
        repeatUntilMillis = c.long("repeat_until"),
        reminderMinutesBefore = c.int("reminder_min"),
        alarmReminder = c.bool("alarm_reminder"),
        countdownReminder = c.bool("countdown_reminder"),
        account = c.str("account"),
        timeZone = c.str("time_zone"),
        colorIndex = c.int("color_index"),
        priority = Priority.fromName(c.str("priority")),
        source = EventSource.fromName(c.str("source")),
        sourceText = c.str("source_text"),
        sourcePackage = c.str("source_package"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
        deleted = c.bool("deleted"),
        completed = c.bool("completed")
    )

    private fun eventToValues(e: Event): ContentValues = ContentValues().apply {
        put("title", e.title)
        put("note", e.note)
        put("location", e.location)
        put("start_ms", e.startMillis)
        put("end_ms", e.endMillis)
        put("all_day", if (e.allDay) 1 else 0)
        put("type", e.type.name)
        put("repeat", e.repeat.name)
        put("repeat_weekday", e.repeatWeekday)
        put("repeat_until", e.repeatUntilMillis)
        put("reminder_min", e.reminderMinutesBefore)
        put("alarm_reminder", if (e.alarmReminder) 1 else 0)
        put("countdown_reminder", if (e.countdownReminder) 1 else 0)
        put("account", e.account)
        put("time_zone", e.timeZone)
        put("color_index", e.colorIndex)
        put("priority", e.priority.name)
        put("source", e.source.name)
        put("source_text", e.sourceText)
        put("source_package", e.sourcePackage)
        put("created_at", e.createdAt)
        put("updated_at", System.currentTimeMillis())
        put("deleted", if (e.deleted) 1 else 0)
        put("completed", if (e.completed) 1 else 0)
    }

    fun insertEvent(e: Event): Long =
        write { db -> db.insert(DbHelper.T_EVENT, null, eventToValues(e)) }

    fun updateEvent(e: Event): Int =
        write { db -> db.update(DbHelper.T_EVENT, eventToValues(e), "id = ?", arrayOf(e.id.toString())) }

    fun softDeleteEvent(id: Long): Int {
        val v = ContentValues().apply {
            put("deleted", 1)
            put("updated_at", System.currentTimeMillis())
        }
        return write { db -> db.update(DbHelper.T_EVENT, v, "id = ?", arrayOf(id.toString())) }
    }

    fun hardDeleteEvent(id: Long): Int =
        write { db -> db.delete(DbHelper.T_EVENT, "id = ?", arrayOf(id.toString())) }

    fun loadAllEvents(): List<Event> {
        val out = ArrayList<Event>()
        helper.readableDatabase.query(
            DbHelper.T_EVENT, null, "deleted = 0", null, null, null, "start_ms ASC"
        ).use { c -> while (c.moveToNext()) out.add(cursorToEvent(c)) }
        return out
    }

    fun getEvent(id: Long): Event? {
        helper.readableDatabase.query(
            DbHelper.T_EVENT, null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { c -> return if (c.moveToFirst()) cursorToEvent(c) else null }
    }

    // ---------------- suggestions ----------------

    private fun cursorToSuggestion(c: Cursor): Suggestion = Suggestion(
        id = c.long("id"),
        title = c.str("title"),
        note = c.str("note"),
        location = c.str("location"),
        startMillis = c.long("start_ms"),
        endMillis = c.long("end_ms"),
        allDay = c.bool("all_day"),
        repeat = runCatching { RepeatKind.valueOf(c.str("repeat")) }.getOrDefault(RepeatKind.NONE),
        repeatWeekday = c.int("repeat_weekday"),
        reminderMinutesBefore = c.int("reminder_min"),
        confidence = c.dbl("confidence"),
        engine = c.str("engine"),
        source = EventSource.fromName(c.str("source")),
        sourceText = c.str("source_text"),
        sourcePackage = c.str("source_package"),
        imagePath = c.str("image_path"),
        createdAt = c.long("created_at"),
        status = c.str("status")
    )

    fun insertSuggestion(s: Suggestion): Long {
        val v = ContentValues().apply {
            put("title", s.title)
            put("note", s.note)
            put("location", s.location)
            put("start_ms", s.startMillis)
            put("end_ms", s.endMillis)
            put("all_day", if (s.allDay) 1 else 0)
            put("repeat", s.repeat.name)
            put("repeat_weekday", s.repeatWeekday)
            put("reminder_min", s.reminderMinutesBefore)
            put("confidence", s.confidence)
            put("engine", s.engine)
            put("source", s.source.name)
            put("source_text", s.sourceText)
            put("source_package", s.sourcePackage)
            put("image_path", s.imagePath)
            put("created_at", s.createdAt)
            put("status", s.status)
        }
        return write { db -> db.insert(DbHelper.T_SUGGESTION, null, v) }
    }

    fun loadSuggestions(status: String? = null): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val sel = if (status == null) null else "status = ?"
        val args = if (status == null) null else arrayOf(status)
        helper.readableDatabase.query(
            DbHelper.T_SUGGESTION, null, sel, args, null, null, "created_at DESC"
        ).use { c -> while (c.moveToNext()) out.add(cursorToSuggestion(c)) }
        return out
    }

    fun updateSuggestionStatus(id: Long, status: String): Int {
        val v = ContentValues().apply { put("status", status) }
        return write { db -> db.update(DbHelper.T_SUGGESTION, v, "id = ?", arrayOf(id.toString())) }
    }

    fun deleteSuggestion(id: Long): Int =
        write { db -> db.delete(DbHelper.T_SUGGESTION, "id = ?", arrayOf(id.toString())) }

    /** 去重:同一份文本最近是否已经产生过待确认记录 */
    fun hasRecentSuggestion(sourceText: String, sinceMillis: Long): Boolean {
        helper.readableDatabase.query(
            DbHelper.T_SUGGESTION, arrayOf("id"),
            "source_text = ? AND created_at >= ?", arrayOf(sourceText, sinceMillis.toString()),
            null, null, null, "1"
        ).use { c -> return c.moveToFirst() }
    }

    // ---------------- import log ----------------

    fun insertLog(log: ImportLog): Long {
        val v = ContentValues().apply {
            put("at_ms", log.atMillis)
            put("source", log.source.name)
            put("source_package", log.sourcePackage)
            put("raw_text", log.rawText)
            put("engine", log.engine)
            put("outcome", log.outcome)
            put("note", log.note)
        }
        return write { db ->
            val id = db.insert(DbHelper.T_LOG, null, v)
            // 顺带裁剪:超过触发线才删,避免每次写入都做 delete
            val count = db.rawQuery("SELECT COUNT(*) FROM ${DbHelper.T_LOG}", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (count > DbHelper.LOG_TRIM_TRIGGER) {
                db.execSQL(
                    "DELETE FROM ${DbHelper.T_LOG} WHERE id NOT IN " +
                        "(SELECT id FROM ${DbHelper.T_LOG} ORDER BY id DESC LIMIT ${DbHelper.LOG_KEEP})"
                )
            }
            id
        }
    }

    fun loadLogs(limit: Int = 200): List<ImportLog> {
        val out = ArrayList<ImportLog>()
        helper.readableDatabase.query(
            DbHelper.T_LOG, null, null, null, null, null, "at_ms DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImportLog(
                        id = c.long("id"),
                        atMillis = c.long("at_ms"),
                        source = EventSource.fromName(c.str("source")),
                        sourcePackage = c.str("source_package"),
                        rawText = c.str("raw_text"),
                        engine = c.str("engine"),
                        outcome = c.str("outcome"),
                        note = c.str("note")
                    )
                )
            }
        }
        return out
    }

    fun clearLogs() {
        write { db -> db.delete(DbHelper.T_LOG, null, null) }
    }

    // ---------------- notes(便签) ----------------

    private fun cursorToNote(c: Cursor) = Note(
        id = c.long("id"),
        title = c.str("title"),
        content = c.str("content"),
        pinned = c.bool("pinned"),
        colorIndex = c.int("color_index"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
        deleted = c.bool("deleted")
    )

    private fun noteToValues(n: Note) = ContentValues().apply {
        put("title", n.title)
        put("content", n.content)
        put("pinned", if (n.pinned) 1 else 0)
        put("color_index", n.colorIndex)
        put("created_at", n.createdAt)
        put("updated_at", System.currentTimeMillis())
        put("deleted", if (n.deleted) 1 else 0)
    }

    fun insertNote(n: Note): Long = write { db -> db.insert(DbHelper.T_NOTE, null, noteToValues(n)) }

    fun updateNote(n: Note): Int =
        write { db -> db.update(DbHelper.T_NOTE, noteToValues(n), "id = ?", arrayOf(n.id.toString())) }

    fun softDeleteNote(id: Long): Int {
        val v = ContentValues().apply {
            put("deleted", 1)
            put("updated_at", System.currentTimeMillis())
        }
        return write { db -> db.update(DbHelper.T_NOTE, v, "id = ?", arrayOf(id.toString())) }
    }

    fun loadNotes(): List<Note> {
        val out = ArrayList<Note>()
        helper.readableDatabase.query(
            DbHelper.T_NOTE, null, "deleted = 0", null, null, null,
            "pinned DESC, updated_at DESC"
        ).use { c -> while (c.moveToNext()) out.add(cursorToNote(c)) }
        return out
    }

    // ---------------- courses(课程表) ----------------

    private fun cursorToCourse(c: Cursor) = Course(
        id = c.long("id"),
        name = c.str("name"),
        teacher = c.str("teacher"),
        location = c.str("location"),
        weekday = c.int("weekday"),
        startSlot = c.int("start_slot"),
        slotCount = c.int("slot_count"),
        startWeek = c.int("start_week"),
        endWeek = c.int("end_week"),
        weekParity = c.int("week_parity"),
        colorIndex = c.int("color_index"),
        reminderMinutes = c.int("reminder_min"),
        enabled = c.bool("enabled"),
        note = c.str("note")
    )

    private fun courseToValues(x: Course) = ContentValues().apply {
        put("name", x.name)
        put("teacher", x.teacher)
        put("location", x.location)
        put("weekday", x.weekday)
        put("start_slot", x.startSlot)
        put("slot_count", x.slotCount)
        put("start_week", x.startWeek)
        put("end_week", x.endWeek)
        put("week_parity", x.weekParity)
        put("color_index", x.colorIndex)
        put("reminder_min", x.reminderMinutes)
        put("enabled", if (x.enabled) 1 else 0)
        put("note", x.note)
    }

    fun insertCourse(x: Course): Long = write { db -> db.insert(DbHelper.T_COURSE, null, courseToValues(x)) }

    fun updateCourse(x: Course): Int =
        write { db -> db.update(DbHelper.T_COURSE, courseToValues(x), "id = ?", arrayOf(x.id.toString())) }

    fun deleteCourse(id: Long): Int =
        write { db -> db.delete(DbHelper.T_COURSE, "id = ?", arrayOf(id.toString())) }

    fun loadCourses(): List<Course> {
        val out = ArrayList<Course>()
        helper.readableDatabase.query(
            DbHelper.T_COURSE, null, null, null, null, null, "weekday ASC, start_slot ASC"
        ).use { c -> while (c.moveToNext()) out.add(cursorToCourse(c)) }
        return out
    }

    // ---------------- todos(待办) ----------------

    private fun cursorToTodo(c: Cursor) = Todo(
        id = c.long("id"),
        title = c.str("title"),
        note = c.str("note"),
        done = c.bool("done"),
        dueMillis = c.long("due_ms"),
        priority = Priority.fromName(c.str("priority")),
        colorIndex = c.int("color_index"),
        source = EventSource.fromName(c.str("source")),
        sourceText = c.str("source_text"),
        sourcePackage = c.str("source_package"),
        relatedEventId = c.long("related_event_id"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
        doneAt = c.long("done_at"),
        deleted = c.bool("deleted")
    )

    private fun todoToValues(t: Todo) = ContentValues().apply {
        put("title", t.title)
        put("note", t.note)
        put("done", if (t.done) 1 else 0)
        put("due_ms", t.dueMillis)
        put("priority", t.priority.name)
        put("color_index", t.colorIndex)
        put("source", t.source.name)
        put("source_text", t.sourceText)
        put("source_package", t.sourcePackage)
        put("related_event_id", t.relatedEventId)
        put("created_at", t.createdAt)
        put("updated_at", System.currentTimeMillis())
        put("done_at", t.doneAt)
        put("deleted", if (t.deleted) 1 else 0)
    }

    fun insertTodo(t: Todo): Long = write { db -> db.insert(DbHelper.T_TODO, null, todoToValues(t)) }

    fun updateTodo(t: Todo): Int =
        write { db -> db.update(DbHelper.T_TODO, todoToValues(t), "id = ?", arrayOf(t.id.toString())) }

    fun softDeleteTodo(id: Long): Int {
        val v = ContentValues().apply {
            put("deleted", 1)
            put("updated_at", System.currentTimeMillis())
        }
        return write { db -> db.update(DbHelper.T_TODO, v, "id = ?", arrayOf(id.toString())) }
    }

    fun loadTodos(): List<Todo> {
        val out = ArrayList<Todo>()
        helper.readableDatabase.query(
            DbHelper.T_TODO, null, "deleted = 0", null, null, null,
            "done ASC, due_ms ASC, created_at DESC"
        ).use { c -> while (c.moveToNext()) out.add(cursorToTodo(c)) }
        return out
    }

    /** 去重:最近是否已经加过同名待办(避免同一条通知反复加) */
    fun hasRecentTodo(title: String, sinceMillis: Long): Boolean {
        helper.readableDatabase.query(
            DbHelper.T_TODO, arrayOf("id"),
            "title = ? AND created_at >= ? AND deleted = 0", arrayOf(title, sinceMillis.toString()),
            null, null, null, "1"
        ).use { c -> return c.moveToFirst() }
    }
}
