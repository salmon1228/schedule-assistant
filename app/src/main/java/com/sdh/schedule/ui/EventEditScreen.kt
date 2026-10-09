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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.EventType
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.ParseCoordinator
import com.sdh.schedule.parse.RepeatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun ldtOf(millis: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

/** 小米日历那样的日期时间文案:2026年9月13日周日 21:30 */
private fun fullDateTimeText(millis: Long, allDay: Boolean): String {
    val t = ldtOf(millis)
    val w = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[t.dayOfWeek.value - 1]
    val datePart = "${t.year}年${t.monthValue}月${t.dayOfMonth}日$w"
    return if (allDay) datePart else "$datePart %02d:%02d".format(t.hour, t.minute)
}

@Composable
fun EventEditScreen(
    original: Event?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isNew = original == null

    val now = remember { LocalDateTime.now() }
    val baseStart = remember {
        if (original != null) original.startMillis
        else now.plusMinutes(((30 - now.minute % 30) % 30).toLong()).withSecond(0).withNano(0)
            .atZone(zone).toInstant().toEpochMilli()
    }
    val baseEnd = remember {
        if (original != null) original.endMillis else baseStart + 60 * 60_000L
    }

    var title by remember { mutableStateOf(original?.title ?: "") }
    var note by remember { mutableStateOf(original?.note ?: "") }
    var location by remember { mutableStateOf(original?.location ?: "") }
    var allDay by remember { mutableStateOf(original?.allDay ?: false) }
    var startMillis by remember { mutableStateOf(baseStart) }
    var endMillis by remember { mutableStateOf(baseEnd) }
    var repeat by remember { mutableStateOf(original?.repeat ?: RepeatKind.NONE) }
    var repeatWeekday by remember {
        mutableStateOf(
            if ((original?.repeatWeekday ?: 0) in 1..7) original!!.repeatWeekday
            else ldtOf(baseStart).dayOfWeek.value
        )
    }
    var reminderMinutes by remember { mutableStateOf(original?.reminderMinutesBefore ?: Repo.settings.defaultReminderMinutes) }
    var alarmReminder by remember { mutableStateOf(original?.alarmReminder ?: Repo.settings.defaultAlarmReminder) }
    var countdownReminder by remember { mutableStateOf(original?.countdownReminder ?: Repo.settings.defaultCountdownReminder) }
    var account by remember { mutableStateOf(original?.account ?: Repo.settings.defaultAccount) }
    var timeZoneText by remember { mutableStateOf(original?.timeZone ?: "(GMT+8:00) 北京") }
    var type by remember { mutableStateOf(original?.type ?: EventType.SCHEDULE) }
    var completed by remember { mutableStateOf(original?.completed ?: false) }
    var showCustomReminder by remember { mutableStateOf(false) }

    var smartInput by remember { mutableStateOf("") }
    var parseHint by remember { mutableStateOf("") }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showRepeatDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showZoneDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun shiftEndKeepingDuration(newStart: Long) {
        val dur = (endMillis - startMillis).coerceAtLeast(0L)
        startMillis = newStart
        endMillis = newStart + (if (dur > 0) dur else 60 * 60_000L)
    }

    // 保存逻辑(顶栏的 ✓ 和返回手势都用它)
    val saveAction: () -> Unit = {
        val t = title.ifBlank { smartInput.ifBlank { "新日程" } }
        val fixedEnd = if (endMillis <= startMillis) startMillis + 60 * 60_000L else endMillis
        val e = Event(
            id = original?.id ?: 0L,
            title = t.take(60),
            note = note,
            location = location,
            startMillis = startMillis,
            endMillis = fixedEnd,
            allDay = allDay,
            type = type,
            repeat = repeat,
            repeatWeekday = repeatWeekday,
            reminderMinutesBefore = reminderMinutes,
            alarmReminder = alarmReminder,
            countdownReminder = countdownReminder,
            account = account,
            timeZone = timeZoneText,
            colorIndex = original?.colorIndex ?: 0,
            source = original?.source ?: EventSource.MANUAL,
            sourceText = original?.sourceText ?: "",
            sourcePackage = original?.sourcePackage ?: "",
            completed = completed,
            createdAt = original?.createdAt ?: System.currentTimeMillis()
        )
        if (isNew) Repo.addEvent(e) else Repo.updateEvent(e)
        onSaved()
    }

    // 侧滑返回/返回键 = 返回上一级(而不是关掉整个 App)
    BackHandler { onDismiss() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 顶部避开状态栏、底部避开手势条,否则 ✕/✓ 会被状态栏盖住点不到
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // 固定顶栏:✕ 与 ✓
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = saveAction, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "保存",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
        Text(
            text = if (isNew) "创建日程" else "编辑日程",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
        )

        // 类型 Tab:日程 / 生日 / 纪念日 / 倒数日
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TypeTab("日程", Icons.Default.DateRange, type == EventType.SCHEDULE) { type = EventType.SCHEDULE }
            TypeTab("生日", Icons.Default.Face, type == EventType.BIRTHDAY) {
                type = EventType.BIRTHDAY
                allDay = true
                repeat = RepeatKind.YEARLY
                if (reminderMinutes < 0) reminderMinutes = Repo.settings.defaultReminderMinutes
            }
            TypeTab("纪念日", Icons.Default.Star, type == EventType.ANNIVERSARY) {
                type = EventType.ANNIVERSARY
                allDay = true
                repeat = RepeatKind.YEARLY
            }
            TypeTab("倒数日", Icons.Default.Refresh, type == EventType.COUNTDOWN) {
                type = EventType.COUNTDOWN
                if (repeat == RepeatKind.NONE) repeat = RepeatKind.YEARLY
            }
        }

        // 智能输入框
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = smartInput,
                    onValueChange = { smartInput = it },
                    placeholder = { Text("试着输入「明天14:00开会」", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = smartInput.trim()
                        if (text.isEmpty()) return@Button
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) { ParseCoordinator.parseText(context, text) }
                            val parsed = outcome.events.firstOrNull()
                            if (parsed == null) {
                                parseHint = "没识别出时间信息,请补充或手动填写"
                            } else {
                                parsed.matched.forEach { }
                                title = parsed.title
                                note = parsed.notes
                                location = parsed.location
                                allDay = parsed.allDay
                                startMillis = parsed.startMillis
                                endMillis = parsed.endMillis
                                repeat = parsed.repeat
                                if (parsed.repeatWeekday in 1..7) repeatWeekday = parsed.repeatWeekday
                                if (parsed.reminderMinutesBefore >= 0) reminderMinutes = parsed.reminderMinutesBefore
                                parseHint = "已填充识别结果(引擎:" +
                                    (if (outcome.engine == "llm") "大模型" else "本地规则") + ")" +
                                    if (outcome.note.isNotBlank()) " · ${outcome.note}" else ""
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("识别") }
            }
            if (parseHint.isNotBlank()) {
                Text(
                    text = parseHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        // 标题 / 地点
        SectionCard {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                placeholder = { Text("必填") },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("地点") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
        }

        // 时间区:全天 / 开始 / 结束
        SectionCard {
            SwitchRow(
                title = "全天事件",
                checked = allDay,
                onCheckedChange = { on ->
                    allDay = on
                    if (on) {
                        val d = ldtOf(startMillis).toLocalDate()
                        startMillis = d.atStartOfDay(zone).toInstant().toEpochMilli()
                        endMillis = d.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
                    } else {
                        val d = ldtOf(startMillis).toLocalDate()
                        startMillis = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                        endMillis = d.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                    }
                }
            )
            SettingRow(
                title = "开始时间",
                value = fullDateTimeText(startMillis, allDay),
                onClick = { showStartPicker = true }
            )
            SettingRow(
                title = "结束时间",
                value = fullDateTimeText(endMillis, allDay),
                showDivider = false,
                onClick = { showEndPicker = true }
            )
        }

        // 重复
        SectionCard {
            SettingRow(
                title = "重复",
                value = repeatLabel(repeat, repeatWeekday),
                showDivider = false,
                onClick = { showRepeatDialog = true }
            )
        }

        // 提醒
        SectionCard {
            SettingRow(
                title = "提醒",
                value = reminderLabel(reminderMinutes),
                onClick = { showReminderDialog = true }
            )
            SwitchRow(
                title = "闹钟提醒",
                subtitle = "到点全屏响铃,不点不罢休",
                checked = alarmReminder,
                onCheckedChange = { alarmReminder = it; if (it) reminderMinutes = if (reminderMinutes < 0) 0 else reminderMinutes }
            )
            SwitchRow(
                title = "倒计时提醒",
                subtitle = "常驻通知栏显示倒计时",
                checked = countdownReminder,
                showDivider = false,
                onCheckedChange = { countdownReminder = it }
            )
        }

        // 备注
        SectionCard {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                placeholder = { Text("请输入备注") },
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(96.dp),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // 完成状态(仅编辑已有日程时显示)
        if (!isNew) {
            SectionCard {
                SwitchRow(
                    title = "标记为已完成",
                    subtitle = "完成后不再提醒;也可以在列表里点右侧圆圈快速切换",
                    checked = completed,
                    showDivider = false,
                    onCheckedChange = { completed = it }
                )
            }
        }

        // 账户 / 时区
        SectionCard {
            SettingRow(title = "日历账户", value = account, onClick = { showAccountDialog = true })
            SettingRow(
                title = "时区",
                value = timeZoneText,
                showDivider = false,
                onClick = { showZoneDialog = true }
            )
        }

        if (!isNew) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showDeleteConfirm = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("删除日程", color = Danger)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        } // 内层滚动区结束
    } // 外层 Column 结束

    // ---- 各种对话框 ----
    if (showStartPicker) {
        DateTimePickerDialog(
            initialMillis = startMillis,
            onDismiss = { showStartPicker = false },
            onPicked = { picked ->
                shiftEndKeepingDuration(picked)
                showStartPicker = false
            }
        )
    }
    if (showEndPicker) {
        DateTimePickerDialog(
            initialMillis = endMillis,
            onDismiss = { showEndPicker = false },
            onPicked = { picked ->
                endMillis = if (picked <= startMillis) startMillis + 60 * 60_000L else picked
                showEndPicker = false
            }
        )
    }

    if (showRepeatDialog) {
        RepeatDialog(
            current = repeat,
            currentWeekday = repeatWeekday,
            onDismiss = { showRepeatDialog = false },
            onPicked = { r, wd ->
                repeat = r
                if (wd in 1..7) repeatWeekday = wd
                showRepeatDialog = false
            }
        )
    }

    if (showReminderDialog) {
        // -2 是"自定义…"的哨兵值
        val presets = listOf(-1, 0, 5, 10, 15, 30, 60, 120, 1440, 2880, -2)
        OptionDialog(
            title = "提醒",
            options = presets.map { v ->
                val label = if (v == -2) "自定义…" else reminderLabel(v)
                label to (v == reminderMinutes)
            },
            onDismiss = { showReminderDialog = false },
            onPick = { index ->
                val value = presets.getOrNull(index) ?: -1
                showReminderDialog = false
                if (value == -2) {
                    showCustomReminder = true
                } else {
                    reminderMinutes = value
                }
            }
        )
    }

    if (showCustomReminder) {
        var text by remember { mutableStateOf(if (reminderMinutes > 0) reminderMinutes.toString() else "15") }
        AlertDialog(
            onDismissRequest = { showCustomReminder = false },
            title = { Text("自定义提醒") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
                        label = { Text("提前多少分钟") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "举例:3 = 提前3分钟;45 = 提前45分钟;60 = 提前1小时;1440 = 提前1天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { v ->
                        if (v in 0..(60 * 24 * 30)) reminderMinutes = v
                    }
                    showCustomReminder = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCustomReminder = false }) { Text("取消") } }
        )
    }

    if (showAccountDialog) {
        val accounts = listOf("本地日历", "工作", "个人", "家庭")
        OptionDialog(
            title = "日历账户",
            options = accounts.map { it to (it == account) },
            onDismiss = { showAccountDialog = false },
            onPick = { i -> account = accounts[i]; showAccountDialog = false }
        )
    }

    if (showZoneDialog) {
        val zones = listOf(
            "(GMT+8:00) 北京",
            "(GMT+9:00) 东京",
            "(GMT+0:00) 伦敦",
            "(GMT-5:00) 纽约",
            "(GMT-8:00) 洛杉矶"
        )
        OptionDialog(
            title = "时区",
            options = zones.map { it to (it == timeZoneText) },
            onDismiss = { showZoneDialog = false },
            onPick = { i -> timeZoneText = zones[i]; showZoneDialog = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除日程") },
            text = { Text("确定删除「${title.ifBlank { "该日程" }}」吗?") },
            confirmButton = {
                TextButton(onClick = {
                    original?.let { Repo.deleteEvent(it.id) }
                    showDeleteConfirm = false
                    onSaved()
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TypeTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

fun repeatLabel(repeat: RepeatKind, weekday: Int): String = when (repeat) {
    RepeatKind.NONE -> "永不"
    RepeatKind.DAILY -> "每天"
    RepeatKind.WEEKDAYS -> "每个工作日"
    RepeatKind.WEEKLY -> "每周${arrayOf("一", "二", "三", "四", "五", "六", "日")[(weekday.coerceIn(1, 7)) - 1]}"
    RepeatKind.MONTHLY -> "每月"
    RepeatKind.YEARLY -> "每年"
}

fun reminderLabel(minutes: Int): String = when {
    minutes < 0 -> "不提醒"
    minutes == 0 -> "准时"
    minutes < 60 -> "${minutes}分钟前"
    minutes % 1440 == 0 -> "${minutes / 1440}天前"
    minutes % 60 == 0 -> "${minutes / 60}小时前"
    else -> "${minutes}分钟前"
}

/** 先选日期再选时间 */
@Composable
private fun DateTimePickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    val initial = ldtOf(initialMillis)
    var pickedDate by remember { mutableStateOf(initial.toLocalDate()) }
    var showTime by remember { mutableStateOf(false) }
    val dpState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    )

    if (!showTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val sel = dpState.selectedDateMillis
                    if (sel != null) {
                        pickedDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(sel), ZoneId.of("UTC")).toLocalDate()
                    }
                    showTime = true
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        ) {
            DatePicker(state = dpState)
        }
    } else {
        val tpState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择时间") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = tpState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = pickedDate.atTime(tpState.hour, tpState.minute)
                        .atZone(zone).toInstant().toEpochMilli()
                    onPicked(result)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("上一步") }
            }
        )
    }
}

@Composable
private fun RepeatDialog(
    current: RepeatKind,
    currentWeekday: Int,
    onDismiss: () -> Unit,
    onPicked: (RepeatKind, Int) -> Unit
) {
    var weekday by remember { mutableStateOf(currentWeekday.coerceIn(1, 7)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重复") },
        text = {
            Column {
                val items = listOf(
                    RepeatKind.NONE to "永不",
                    RepeatKind.DAILY to "每天",
                    RepeatKind.WEEKDAYS to "每个工作日(周一至周五)",
                    RepeatKind.WEEKLY to "每周",
                    RepeatKind.MONTHLY to "每月",
                    RepeatKind.YEARLY to "每年"
                )
                items.forEach { (kind, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (kind == RepeatKind.WEEKLY) {
                                    onPicked(kind, weekday)
                                } else {
                                    onPicked(kind, 0)
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (kind == current) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (current == RepeatKind.WEEKLY || true) {
                    Spacer(Modifier.height(6.dp))
                    Text("每周几:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        (1..7).forEach { wd ->
                            val label = arrayOf("一", "二", "三", "四", "五", "六", "日")[wd - 1]
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (weekday == wd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clickable {
                                        weekday = wd
                                        onPicked(RepeatKind.WEEKLY, wd)
                                    }
                            ) {
                                Text(
                                    label,
                                    color = if (weekday == wd) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun OptionDialog(
    title: String,
    options: List<Pair<String, Boolean>>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, (label, selected) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
