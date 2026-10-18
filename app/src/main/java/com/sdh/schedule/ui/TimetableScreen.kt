@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Repo
import java.time.LocalDate

private val WeekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private const val CELL_HEIGHT = 54

/** 课程表:一周 7 天 × N 节 的格子视图 */
@Composable
fun TimetableScreen(
    courses: List<Course>,
    onEditCourse: (Course) -> Unit,
    onCreateCourse: (weekday: Int, slot: Int) -> Unit,
    onOpenSettings: () -> Unit,
    onImportCourses: () -> Unit = {}
) {
    val settings = Repo.settings
    val revision by settings.revision.collectAsState()
    val slots = remember(revision) { settings.slotList() }
    val currentWeek = remember(revision) { Repo.currentWeek() }
    var week by remember(revision) { mutableStateOf(if (currentWeek > 0) currentWeek else 1) }
    val todayDow = LocalDate.now().dayOfWeek.value

    val weekCourses = remember(courses, week) { courses.filter { it.activeInWeek(week) } }
    // 全部课程(不过滤周次):非本周的也要画出来,只是半透明
    val dayCourses = remember(courses) { courses }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部:周次切换
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("课程表", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "第 $week 周" + if (week == currentWeek) "(本周)" else "" +
                            " · 本周 ${weekCourses.size} 节 / 共 ${courses.size} 门课",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { week = (week - 1).coerceAtLeast(1) }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一周")
                }
                IconButton(onClick = { week += 1 }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下一周")
                }
                TextButton(onClick = onImportCourses) { Text("导入") }
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }
        }

        if (courses.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                EmptyHint(
                    "还没有课程。\n\n最准的办法:点右上角「导入」→「文件」,选 WakeUp课程表 导出的 .ics 文件," +
                        "课程名/老师/教室/星期/节次/周次都能精确还原。\n没有导出文件时也可以截图识别,或点右下角「+」手动加一门。"
                )
            }
            return@Column
        }

        // 表头:星期
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Box(modifier = Modifier.width(38.dp))
            WeekdayNames.forEachIndexed { i, name ->
                val dow = i + 1
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (dow == todayDow) FontWeight.Bold else FontWeight.Normal,
                        color = if (dow == todayDow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 表体
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 节次列
                Column(modifier = Modifier.width(38.dp)) {
                    slots.forEachIndexed { index, (start, _) ->
                        Box(
                            modifier = Modifier.height(CELL_HEIGHT.dp).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "%02d:%02d".format(start.hour, start.minute).take(5),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                // 7 天
                for (dow in 1..7) {
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        var slot = 0
                        while (slot < slots.size) {
                            // 用 dayCourses(不过滤周次):非本周的课也画出来,只是半透明 + 标「非本周」
                            val c = dayCourses.firstOrNull { it.weekday == dow && it.startSlot - 1 == slot }
                            if (c != null) {
                                CourseBlock(
                                    course = c,
                                    heightDp = (c.slotCount.coerceAtLeast(1) * CELL_HEIGHT - 4),
                                    // 这门课在这一周是否要上
                                    activeThisWeek = c.activeInWeek(week),
                                    onClick = { onEditCourse(c) }
                                )
                                slot += c.slotCount.coerceAtLeast(1)
                            } else {
                                Box(
                                    modifier = Modifier
                                        .height((CELL_HEIGHT - 4).dp)
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onCreateCourse(dow, slot + 1) }
                                )
                                slot++
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
private fun CourseBlock(
    course: Course,
    heightDp: Int,
    activeThisWeek: Boolean,
    onClick: () -> Unit
) {
    val color = courseColorOf(course)
    // 非本周:整体半透明(和 WakeUp 那种「非本周」效果一致,一眼能看出这周不用去上)
    val blockAlpha = if (activeThisWeek) 0.16f else 0.07f
    val textAlpha = if (activeThisWeek) 1f else 0.45f
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = blockAlpha),
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp)) {
            Text(
                text = course.name.ifBlank { "课程" },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = textAlpha),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (course.location.isNotBlank()) {
                Text(
                    text = course.location,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!activeThisWeek) {
                Text(
                    text = "非本周",
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 课程颜色。
 *
 * **同一门课(同名)永远同一个颜色** —— 按名字哈希取色,这样重复的课(同名的不同时间段)
 * 看起来是一套颜色,一眼能认出"这是同一门课"。
 * 如果用户在某门课里**手动选过颜色**(colorIndex > 0),则尊重手动选择。
 * (colorIndex = 0 视为"自动")
 */
internal fun courseColorOf(course: Course): androidx.compose.ui.graphics.Color =
    Palette[course.effectiveColorIndex(Palette.size).coerceIn(0, Palette.size - 1)]

/** 课程编辑页 */
@Composable
fun CourseEditScreen(
    original: Course?,
    initialWeekday: Int = 1,
    initialSlot: Int = 1,
    onDismiss: () -> Unit
) {
    val settings = Repo.settings
    val slotCount = remember { settings.slotList().size }

    var name by remember { mutableStateOf(original?.name ?: "") }
    var teacher by remember { mutableStateOf(original?.teacher ?: "") }
    var location by remember { mutableStateOf(original?.location ?: "") }
    var weekday by remember { mutableStateOf(original?.weekday ?: initialWeekday) }
    var startSlot by remember { mutableStateOf(original?.startSlot ?: initialSlot) }
    var spanSlots by remember { mutableStateOf(original?.slotCount ?: 2) }
    var startWeek by remember { mutableStateOf(original?.startWeek ?: 1) }
    var endWeek by remember { mutableStateOf(original?.endWeek ?: 16) }
    var parity by remember { mutableStateOf(original?.weekParity ?: 0) }
    var colorIndex by remember { mutableStateOf(original?.colorIndex ?: 0) }
    var reminder by remember { mutableStateOf(original?.reminderMinutes ?: settings.courseDefaultReminder) }
    var enabled by remember { mutableStateOf(original?.enabled ?: true) }
    var showDelete by remember { mutableStateOf(false) }
    var nameMissing by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        // 不再用默认名「课程」兜底 —— 那会在误点空格子时留下一条空壳课程(实测发生过)
        if (name.isBlank()) {
            nameMissing = true
        } else {
            val c = (original ?: Course()).copy(
                name = name.trim(),
                teacher = teacher.trim(),
                location = location.trim(),
                weekday = weekday.coerceIn(1, 7),
                startSlot = startSlot.coerceIn(1, slotCount),
                slotCount = spanSlots.coerceIn(1, 6),
                startWeek = startWeek.coerceAtLeast(1),
                endWeek = endWeek.coerceAtLeast(startWeek),
                weekParity = parity,
                colorIndex = colorIndex,
                reminderMinutes = reminder,
                enabled = enabled
            )
            if (c.id == 0L) Repo.addCourse(c) else Repo.updateCourse(c)
            onDismiss()
        }
    }

    // 返回手势:没填名字就当作放弃(不要偷偷建一条空壳)
    BackHandler {
        if (original == null && name.isBlank()) onDismiss() else save()
    }

    if (nameMissing) {
        AlertDialog(
            onDismissRequest = { nameMissing = false },
            title = { Text("还没填课程名称") },
            text = { Text("填个名字再保存;如果只是误点了空格子,点「放弃」即可。") },
            confirmButton = { TextButton(onClick = { nameMissing = false }) { Text("去填名字") } },
            dismissButton = {
                TextButton(onClick = {
                    nameMissing = false
                    onDismiss()
                }) { Text("放弃") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(26.dp))
            }
            Text(
                text = if (original == null) "添加课程" else "编辑课程",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = save, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Check, contentDescription = "保存",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            SectionCard {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("课程名称") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
                OutlinedTextField(
                    value = teacher, onValueChange = { teacher = it },
                    label = { Text("老师") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("上课地点") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
                )
            }

            SectionCard {
                OptionRow("星期", "周${WeekdayNames[weekday - 1]}") { weekday = it }
                OptionRow("开始节次", "第 $startSlot 节") { startSlot = it }
                OptionRow("连上几节", "$spanSlots 节") { spanSlots = it }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("周次范围", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "第 $startWeek - $endWeek 周 · ${if (parity == 1) "单周" else if (parity == 2) "双周" else "每周"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { if (startWeek > 1) startWeek -= 1 }) { Text("-") }
                    Text("$startWeek", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { if (startWeek < endWeek) startWeek += 1 }) { Text("+") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { if (endWeek > startWeek) endWeek -= 1 }) { Text("-") }
                    Text("$endWeek", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { if (endWeek < 30) endWeek += 1 }) { Text("+") }
                }
                OptionRow(
                    "单双周",
                    when (parity) {
                        1 -> "单周"
                        2 -> "双周"
                        else -> "每周"
                    }
                ) { parity = it }
                SwitchRow(
                    title = "启用这门课",
                    subtitle = "关闭后不出现在课程表,也不提醒",
                    checked = enabled,
                    showDivider = false,
                    onCheckedChange = { enabled = it }
                )
            }

            SectionCard {
                SettingRow(
                    title = "上课前提醒",
                    value = if (reminder < 0) "不提醒" else reminderLabel(reminder),
                    onClick = { showDelete = false }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(-1, 0, 5, 10, 15, 30, 60).forEach { m ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (reminder == m) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp).clickable { reminder = m }
                        ) {
                            Text(
                                text = if (m < 0) "不提醒" else if (m == 0) "准时" else "${m}分",
                                fontSize = 12.sp,
                                color = if (reminder == m) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "上课提醒会提前发一条聊天式通知。若要「静音也响」,到 设置 → 提醒 → 提醒铃声 里选「闹钟音」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            SectionCard {
                Text(
                    "颜色",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp)
                )
                Row(modifier = Modifier.padding(16.dp)) {
                    Palette.forEachIndexed { i, c ->
                        Box(
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(28.dp)
                                .background(c, RoundedCornerShape(50))
                                .clickable { colorIndex = i }
                        ) {
                            if (i == colorIndex) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0x33FFFFFF), RoundedCornerShape(50))
                                )
                            }
                        }
                    }
                }
            }

            if (original != null) {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDelete = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("删除这门课", color = Danger)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除课程") },
            text = { Text("确定删除「${original?.name ?: "这门课"}」吗?") },
            confirmButton = {
                TextButton(onClick = {
                    original?.let { Repo.deleteCourse(it.id) }
                    showDelete = false
                    onDismiss()
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
}

/** 选项行(点击弹出数字/选项选择) */
@Composable
private fun OptionRow(title: String, value: String, onPick: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    SettingRow(title = title, value = value, onClick = { show = true })
    if (show) {
        val options = when (title) {
            "星期" -> (1..7).map { it to "周${WeekdayNames[it - 1]}" }
            "开始节次" -> (1..12).map { it to "第 $it 节" }
            else -> (1..6).map { it to "连上 $it 节" }
        }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (v, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(v); show = false }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            if (value == label) {
                                Icon(
                                    Icons.Default.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("取消") } }
        )
    }
}

/** 课程表设置页(学期开始 + 节次时间) */
@Composable
fun TimetableSettingsScreen(onBack: () -> Unit) {
    val settings = Repo.settings
    val revision by settings.revision.collectAsState()
    val slots = remember(revision) { settings.slotList() }
    val startDate = remember(revision) { settings.semesterStartDate() }
    var showEditSlots by remember { mutableStateOf(false) }

    // 「编辑时间表」整页(参考同类课表 App:固定时长联动 + 起止都能改 + 添加节次)
    if (showEditSlots) {
        EditTimetableScreen(onBack = { showEditSlots = false })
        return
    }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回") }
            Text("课程表设置", style = MaterialTheme.typography.titleLarge)
        }

        GroupTitle("学期")
        SectionCard {
            SettingRow(
                title = "学期第一周的周一",
                subtitle = "周次按这个日期计算;当前第 ${Repo.currentWeek()} 周",
                value = "%d年%d月%d日".format(startDate.year, startDate.monthValue, startDate.dayOfMonth),
                showArrow = false
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = {
                    settings.semesterStartEpochDay = startDate.minusWeeks(1).toEpochDay()
                    Repo.rescheduleAll()
                }) { Text("提前一周") }
                TextButton(onClick = {
                    settings.semesterStartEpochDay = startDate.plusWeeks(1).toEpochDay()
                    Repo.rescheduleAll()
                }) { Text("推后一周") }
                TextButton(onClick = {
                    settings.semesterStartEpochDay = LocalDate.now()
                        .with(java.time.DayOfWeek.MONDAY).toEpochDay()
                    Repo.rescheduleAll()
                }) { Text("设为本周") }
            }
        }

        GroupTitle("上课提醒")
        SectionCard {
            SwitchRow(
                title = "上课前自动提醒",
                subtitle = "每天按当天课表排精确提醒(聊天式通知)",
                checked = settings.courseReminderEnabled,
                onCheckedChange = {
                    settings.courseReminderEnabled = it
                    Repo.rescheduleAll()
                }
            )
            SettingRow(
                title = "默认提前量",
                subtitle = "新建课程时的默认值,可在每门课里单独改",
                value = reminderLabel(settings.courseDefaultReminder),
                showDivider = false,
                showArrow = false
            )
        }

        GroupTitle("节次时间")
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showEditSlots = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("编辑时间表", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "共 ${slots.size} 节 · " + slots.take(3).joinToString("  ") { (s, e) ->
                            "%02d:%02d-%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
                        } + " …",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("编辑", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}



