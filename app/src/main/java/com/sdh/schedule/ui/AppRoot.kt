package com.sdh.schedule.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdh.schedule.R
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.EventType
import com.sdh.schedule.data.Note
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.Todo
import com.sdh.schedule.notify.ScheduleNotificationListener
import com.sdh.schedule.parse.ParseCoordinator
import com.sdh.schedule.parse.RepeatKind
import com.sdh.schedule.util.Occurrence
import com.sdh.schedule.util.Recurrence
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun ldt(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

private fun hhmm(millis: Long): String {
    val t = ldt(millis)
    return "%02d:%02d".format(t.hour, t.minute)
}

private fun weekdayCn(date: LocalDate): String =
    arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]

private fun dateTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

/** 日程时间描述:全天 / 14:00-15:00 / 14:00 */
fun timeLabel(o: Occurrence): String =
    if (o.event.allDay) "全天" else "${hhmm(o.startMillis)} - ${hhmm(o.endMillis)}"

@Composable
fun AppRoot(navTarget: NavTarget? = null, onNavHandled: () -> Unit = {}) {
    val context = LocalContext.current
    Repo.init(context)
    val settings = Repo.settings
    // 让设置变化能触发重组
    val revision by settings.revision.collectAsState()
    val events by Repo.events.collectAsState()
    val suggestions by Repo.suggestions.collectAsState()
    val notes by Repo.notes.collectAsState()
    val courses by Repo.courses.collectAsState()
    val todos by Repo.todos.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var editTarget by remember { mutableStateOf<Event?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    // 便签 / 待办 / 课程表 / 待确认 的子页面
    var editNote by remember { mutableStateOf<Note?>(null) }
    var creatingNote by remember { mutableStateOf(false) }
    var editTodo by remember { mutableStateOf<Todo?>(null) }
    var creatingTodo by remember { mutableStateOf(false) }
    var recordsSegment by remember { mutableStateOf(0) }
    var editCourse by remember { mutableStateOf<Course?>(null) }
    var courseInit by remember { mutableStateOf(1 to 1) }
    var creatingCourse by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var showTimetableSettings by remember { mutableStateOf(false) }
    var showSelfCheck by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    /** 日程页的两种视图:0 = 列表(默认),1 = 月历 */
    var agendaView by remember { mutableStateOf(0) }
    var showCourseImport by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 从通知点进来(冷启动和热启动都走这里)—— 处理一次后告诉 Activity 清掉
    LaunchedEffect(navTarget) {
        when (val target = navTarget) {
            is NavTarget.OpenEvent -> {
                Repo.getEvent(target.eventId)?.let { editTarget = it }
                onNavHandled()
            }
            NavTarget.OpenSuggestions -> {
                showSuggestions = true
                onNavHandled()
            }
            null -> Unit
        }
    }

    val listenerOn = remember(revision) { ScheduleNotificationListener.isEnabled(context) }
    val pendingCount = suggestions.count { it.status == "PENDING" }
    val inSubScreen = creating || editTarget != null || creatingNote || editNote != null ||
        creatingCourse || editCourse != null || showWizard || showSuggestions ||
        showTimetableSettings || showSelfCheck || creatingTodo || editTodo != null ||
        showSettings || showCourseImport

    // 返回手势:子页面先返回上一级,不在「日程」页时先回到「日程」页
    BackHandler(enabled = !inSubScreen && tab != 0) { tab = 0 }

    if (creating || editTarget != null) {
        EventEditScreen(
            original = editTarget,
            onDismiss = { creating = false; editTarget = null },
            onSaved = { creating = false; editTarget = null }
        )
        return
    }

    if (creatingNote || editNote != null) {
        NoteEditScreen(
            original = editNote,
            onDismiss = { creatingNote = false; editNote = null }
        )
        return
    }

    if (creatingCourse || editCourse != null) {
        CourseEditScreen(
            original = editCourse,
            initialWeekday = courseInit.first,
            initialSlot = courseInit.second,
            onDismiss = { creatingCourse = false; editCourse = null }
        )
        return
    }

    if (showSuggestions) {
        BackHandler { showSuggestions = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            SuggestionsScreen(
                suggestions = suggestions,
                onEdit = { ev -> showSuggestions = false; editTarget = ev }
            )
        }
        return
    }

    if (showTimetableSettings) {
        TimetableSettingsScreen(onBack = { showTimetableSettings = false })
        return
    }

    if (showSelfCheck) {
        SelfCheckScreen(onBack = { showSelfCheck = false })
        return
    }


    if (showSettings) {
        BackHandler { showSettings = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            SettingsScreen(
                onBack = { showSettings = false },
                onOpenWizard = { showWizard = true },
                pendingCount = pendingCount,
                onOpenSuggestions = { showSuggestions = true },
                onOpenTimetableSettings = { showTimetableSettings = true },
                onOpenSelfCheck = { showSelfCheck = true }
            )
        }
        return
    }

    if (showWizard) {
        PermissionWizardScreen(onBack = { showWizard = false })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 当前在哪个页,「+」就新建什么(记录页的待办/便签、课程表页的课程),否则弹新建·导入面板
                    when (tab) {
                        2 -> if (recordsSegment == 0) creatingTodo = true else creatingNote = true
                        1 -> {
                            courseInit = 1 to 1
                            creatingCourse = true
                        }
                        else -> showAddSheet = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
        },
        bottomBar = {
            // 只留 3 个:日程(含月历) / 课程表 / 记录。设置挪到日程页右上角齿轮。
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("日程") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text("课程表") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    label = { Text("记录") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                // 日程页内部再分「列表 / 月历」两种视图
                0 -> if (agendaView == 1) {
                    MonthScreen(
                        events = events,
                        onEdit = { editTarget = it },
                        onOpenSettings = { showSettings = true },
                        onSwitchToList = { agendaView = 0 }
                    )
                } else {
                    AgendaScreen(
                        events = events,
                        listenerOn = listenerOn,
                        pendingCount = pendingCount,
                        onOpenWizard = { showWizard = true },
                        onOpenSuggestions = { showSuggestions = true },
                        onOpenSettings = { showSettings = true },
                        onSwitchToMonth = { agendaView = 1 },
                        onEdit = { editTarget = it }
                    )
                }
                1 -> TimetableScreen(
                    courses = courses,
                    onEditCourse = { c ->
                        courseInit = c.weekday to c.startSlot
                        editCourse = c
                    },
                    onCreateCourse = { wd, slot ->
                        courseInit = wd to slot
                        creatingCourse = true
                    },
                    onOpenSettings = { showTimetableSettings = true },
                    onImportCourses = { showCourseImport = true }
                )
                else -> RecordsScreen(
                    notes = notes,
                    todos = todos,
                    segment = recordsSegment,
                    onSegmentChange = { recordsSegment = it },
                    onEditNote = { editNote = it },
                    onCreateNote = { creatingNote = true },
                    onEditTodo = { editTodo = it },
                    onCreateTodo = { creatingTodo = true }
                )
            }
        }
    }

    // 右下角「+」弹出的操作面板:新建 / 相册 / 拍照 / 文件 / 粘贴文字 都在这里
    if (showAddSheet) {
        AddSheet(
            onDismiss = { showAddSheet = false },
            onManualCreate = { creating = true },
            onNewNote = { creatingNote = true },
            onResult = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
        )
    }

    // 待办编辑弹窗
    if (creatingTodo || editTodo != null) {
        TodoEditDialog(
            original = editTodo,
            onDismiss = { creatingTodo = false; editTodo = null }
        )
    }

    // 导入课程表:钉钉/学校平台截图 → 自动识别成课表(这个是底部弹层,叠着显示是对的)
    if (showCourseImport) {
        CourseImportSheet(
            onDismiss = { showCourseImport = false },
            onDone = { msg ->
                showCourseImport = false
                scope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }
}

// ---------------- 日程列表 ----------------

@Composable
private fun AgendaScreen(
    events: List<Event>,
    listenerOn: Boolean,
    pendingCount: Int,
    onOpenWizard: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchToMonth: () -> Unit,
    onEdit: (Event) -> Unit
) {
    val context = LocalContext.current
    val days = remember(events) { Repo.agenda(LocalDate.now(), 90) }

    // 主次分明:今天 + 明天完整展开,之后的收进「之后」,过去的收进「更早」
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    val nearDays = remember(days) {
        days.filter { !it.first.isBefore(today) && !it.first.isAfter(tomorrow) && it.second.isNotEmpty() }
    }
    val laterDays = remember(days) {
        days.filter { it.first.isAfter(tomorrow) && it.second.isNotEmpty() }
    }
    val pastDays = remember(days) {
        days.filter { it.first.isBefore(today) && it.second.isNotEmpty() }
    }
    val laterTotal = remember(laterDays) { laterDays.sumOf { it.second.size } }
    val pastTotal = remember(pastDays) { pastDays.sumOf { it.second.size } }
    var showLater by remember { mutableStateOf(false) }
    var showPast by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateTitle(today),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = weekdayCn(today) +
                            (com.sdh.schedule.util.CalendarLabels.dateSuffix(today)?.let { " · $it" } ?: "") +
                            // 以前这里显示"解析引擎:xxx"(开发向信息,对用户没用),
                            // 改成今天/明天的安排概况 —— 一眼知道要不要点开看
                            run {
                                val t = nearDays.firstOrNull { it.first == today }?.second?.size ?: 0
                                val tmr = nearDays.firstOrNull { it.first == tomorrow }?.second?.size ?: 0
                                when {
                                    t == 0 && tmr == 0 -> " · 今明两天都没有安排"
                                    t == 0 -> " · 今天没安排,明天 $tmr 项"
                                    tmr == 0 -> " · 今天 $t 项"
                                    else -> " · 今天 $t 项 · 明天 $tmr 项"
                                }
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 月历视图切换
                androidx.compose.material3.IconButton(onClick = onSwitchToMonth) {
                    Icon(Icons.Default.DateRange, contentDescription = "月历视图")
                }
                // 设置入口(从底部导航挪到这里)
                androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }
        // 待确认入口(原来在底部导航,现在放在日程页顶部,带数量)
        if (pendingCount > 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { onOpenSuggestions() },
                    shape = RoundedCornerShape(14.dp),
                    color = BlueSoft
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = BlueDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "有 $pendingCount 条待确认日程",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                "从通知/图片/文件里识别出来的,点这里确认",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = BlueDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 通知监听没开时的提醒条(导入入口已收进右下角 + 号)
        if (!listenerOn) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Orange.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenWizard() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "通知监听未开启",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "开启后才能自动从微信/短信等通知里生成日程 → 点这里去开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        val hasAny = nearDays.isNotEmpty() || laterDays.isNotEmpty() || pastDays.isNotEmpty()
        if (!hasAny) {
            item { EmptyHint("今明两天没有安排。点右下角 + 新建,或去设置开启通知监听让 App 自动帮你排。") }
        }

        // ① 今天 / 明天:完整展开
        items(nearDays) { (date, occs) -> DaySection(date, occs, onEdit) }

        // ② 今天之前的:折叠
        if (pastTotal > 0) {
            item {
                CollapsibleHeader(
                    title = "更早",
                    subtitle = "$pastTotal 条 · 已过去",
                    expanded = showPast,
                    onClick = { showPast = !showPast }
                )
            }
            if (showPast) {
                items(pastDays) { (date, occs) -> DaySection(date, occs, onEdit) }
            }
        }

        // ③ 后天及以后:折叠,一眼看清主次
        if (laterTotal > 0) {
            item {
                CollapsibleHeader(
                    title = "之后",
                    subtitle = "$laterTotal 条 · 后天起",
                    expanded = showLater,
                    onClick = { showLater = !showLater }
                )
            }
            if (showLater) {
                items(laterDays) { (date, occs) -> DaySection(date, occs, onEdit) }
            }
        }

        item { Spacer(Modifier.height(90.dp)) }
    }
}

/** 折叠分组标题 */
@Composable
private fun CollapsibleHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 某一天的日程分组 */
@Composable
private fun DaySection(date: LocalDate, occs: List<Occurrence>, onEdit: (Event) -> Unit) {
    if (occs.isEmpty()) return
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateTitle(date),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = weekdayCn(date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val holidayTag = remember(date) { com.sdh.schedule.util.CalendarLabels.dateSuffix(date) }
            val isOff = remember(date) {
                com.sdh.schedule.util.CalendarLabels.forDate(date).kind ==
                    com.sdh.schedule.util.CalendarLabels.Kind.OFF_DAY
            }
            if (holidayTag != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = holidayTag,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOff) Danger else Orange
                )
            }
        }
        SectionCard {
            occs.forEachIndexed { index, occ ->
                EventCardRow(
                    occ = occ,
                    onEdit = { onEdit(occ.event) },
                    onToggleComplete = {
                        Repo.setCompleted(occ.event.id, !occ.event.completed)
                    },
                    showDivider = index != occs.lastIndex
                )
            }
        }
    }
}

@Composable
private fun EventCardRow(
    occ: Occurrence,
    onEdit: () -> Unit,
    onToggleComplete: () -> Unit,
    showDivider: Boolean
) {
    val e = occ.event
    val now = System.currentTimeMillis()
    val isPast = occ.endMillis < now
    val done = e.completed
    val color = Palette[e.colorIndex.coerceIn(0, Palette.size - 1)]
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onEdit() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(if (done) MaterialTheme.colorScheme.outline else color, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (e.title.isBlank()) "(无标题)" else e.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done || isPast) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeLabel(occ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (e.location.isNotBlank()) {
                        Text(
                            text = "  ·  ${e.location}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.type != EventType.SCHEDULE) Chip(e.type.label, color)
                    if (e.repeat != RepeatKind.NONE) {
                        val repeatText = when (e.repeat) {
                            RepeatKind.DAILY -> "每天"
                            RepeatKind.WEEKDAYS -> "工作日"
                            RepeatKind.WEEKLY -> "每周${Recurrence.weekdayLabel(if (e.repeatWeekday in 1..7) e.repeatWeekday else ldt(e.startMillis).dayOfWeek.value)}"
                            RepeatKind.MONTHLY -> "每月"
                            RepeatKind.YEARLY -> "每年"
                            else -> "重复"
                        }
                        Chip(repeatText, Green)
                    }
                    if (e.alarmReminder) Chip("闹钟", Danger)
                    if (e.countdownReminder) Chip("倒计时", Orange)
                    if (e.source != com.sdh.schedule.data.EventSource.MANUAL) Chip(e.source.label, BlueDark)
                }
            }
            // 右侧「完成」按钮:点一下切换完成/未完成
            Icon(
                painter = painterResource(
                    if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                ),
                contentDescription = if (done) "标记为未完成" else "标记为已完成",
                tint = if (done) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onToggleComplete() }
            )
        }
        if (showDivider) {
            Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(MaterialTheme.colorScheme.outline))
        }
    }
}

// ---------------- 月历 ----------------

@Composable
private fun MonthScreen(
    events: List<Event>,
    onEdit: (Event) -> Unit,
    onOpenSettings: () -> Unit = {},
    onSwitchToList: () -> Unit = {}
) {
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }

    val firstDay = month.atDay(1)
    val startOffset = firstDay.dayOfWeek.value - 1 // 周一为 0
    val daysInMonth = month.lengthOfMonth()
    val rows = ((startOffset + daysInMonth) + 6) / 7

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (runCatching { com.sdh.schedule.util.HolidayRepo.isYearUncovered(month.year) }.getOrDefault(false)) {
                Text(
                    text = "该年节假日数据未收录",
                    style = MaterialTheme.typography.bodySmall,
                    color = Orange
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Default.KeyboardArrowLeft, "上个月",
                modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(6.dp)
            )
            Icon(
                Icons.Default.KeyboardArrowRight, "下个月",
                modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(6.dp)
            )
            androidx.compose.material3.IconButton(onClick = onSwitchToList) {
                Icon(Icons.Default.List, contentDescription = "列表视图")
            }
            androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        SectionCard {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                    Text(
                        text = w,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    for (c in 0 until 7) {
                        val dayIndex = r * 7 + c - startOffset + 1
                        if (dayIndex in 1..daysInMonth) {
                            val date = month.atDay(dayIndex)
                            val occs = remember(events, date) { Repo.occurrencesOn(date) }
                            DayCell(
                                date = date,
                                isSelected = date == selected,
                                isToday = date == LocalDate.now(),
                                count = occs.size,
                                dotColors = occs.take(3).map { Palette[it.event.colorIndex.coerceIn(0, Palette.size - 1)] },
                                modifier = Modifier.weight(1f),
                                onClick = { selected = date }
                            )
                        } else {
                            Box(modifier = Modifier.weight(1f).height(58.dp))
                        }
                    }
                }
            }
        }

        val selOccs = remember(events, selected) { Repo.occurrencesOn(selected) }
        GroupTitle(
            buildString {
                append("${selected.monthValue}月${selected.dayOfMonth}日 ${weekdayCn(selected)}")
                com.sdh.schedule.util.CalendarLabels.dateSuffix(selected)?.let {
                    append(" · ").append(it)
                }
            }
        )
        if (selOccs.isEmpty()) {
            EmptyHint("这天没有安排")
        } else {
            SectionCard {
                selOccs.forEachIndexed { i, occ ->
                    EventCardRow(
                        occ = occ,
                        onEdit = { onEdit(occ.event) },
                        onToggleComplete = { Repo.setCompleted(occ.event.id, !occ.event.completed) },
                        showDivider = i != selOccs.lastIndex
                    )
                }
            }
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    count: Int,
    dotColors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 节假日 / 农历(逻辑抽在 CalendarLabels 里,方便单元测试)
    val label = remember(date) { com.sdh.schedule.util.CalendarLabels.forDate(date) }
    val subText = label.text
    val subColor = when {
        isSelected -> Color.White
        label.kind == com.sdh.schedule.util.CalendarLabels.Kind.MAKEUP -> Orange
        label.kind == com.sdh.schedule.util.CalendarLabels.Kind.OFF_DAY -> Danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val isOffDay = label.kind == com.sdh.schedule.util.CalendarLabels.Kind.OFF_DAY

    Box(
        modifier = modifier.height(58.dp).padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(
                    when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isToday -> BlueSoft
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(10.dp)
                )
                .clickable { onClick() },
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSelected -> Color.White
                    isToday -> MaterialTheme.colorScheme.primary
                    isOffDay -> Danger
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (subText.isNotBlank()) {
                Text(
                    text = subText.take(4),
                    fontSize = 9.sp,
                    color = subColor,
                    maxLines = 1
                )
            } else {
                Spacer(Modifier.height(11.dp))
            }
            if (count > 0) {
                Row {
                    dotColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 0.5.dp)
                                .size(4.dp)
                                .background(if (isSelected) Color.White else c, CircleShape)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}


