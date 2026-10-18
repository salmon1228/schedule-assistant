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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.SettingsStore

/**
 * 「编辑时间表」——参考同类课表 App 的设计重做:
 *
 *  • **固定上课时长** 开关 + **每节课时长(分钟)**:打开后改开始时间,结束时间自动跟着算
 *  • 每节课的**起止时间都能手改**(旧版只有一个多行文本框,手写 "08:00-08:45" 很容易写错)
 *  • **＋添加** 一节 / 每节可删
 *  • **时间表名称**(为将来"多套作息"留位)
 *  • 明确提示「24 小时制」
 *
 * 保存时写回 `settings.slotTimes`(格式 "HH:mm-HH:mm,..." 保持不变,不影响已有解析)
 */
@Composable
fun EditTimetableScreen(onBack: () -> Unit) {
    val settings = Repo.settings

    // 用可变行列表编辑(每行 = 开始/结束),保存时再拼回字符串
    //
    // 注意:早期从 .ics 导入时,算出来的**开始时间**是对的(课表格子显示正常),
    // 但部分**结束时间**有偏差(如第2节 09:10-10:00,实际应为 09:10-09:55)。
    // 这个页面把它暴露出来了 —— 所以:若「固定上课时长」开着,打开时就用
    // "开始 + 时长" 把结束时间统一校正一遍(保存后即覆盖旧数据)。
    var rows by remember {
        mutableStateOf(
            run {
                val stored = settings.slotList().map { (s, e) ->
                    "%02d:%02d".format(s.hour, s.minute) to "%02d:%02d".format(e.hour, e.minute)
                }
                if (settings.slotFixedDuration) {
                    val d = settings.slotDurationMinutes
                    stored.map { (s, _) -> s to shiftTime(s, d) }
                } else stored
            }
        )
    }
    var name by remember { mutableStateOf(settings.slotTimesName) }
    var fixed by remember { mutableStateOf(settings.slotFixedDuration) }
    var duration by remember { mutableStateOf(settings.slotDurationMinutes) }
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }   // (行号, 是否编辑开始)
    var editDuration by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }

    BackHandler { onBack() }

    fun addMinutes(t: String, mins: Int): String = shiftTime(t, mins)

    /** 打开"固定时长"或改时长后,把所有结束时间按开始时间重算 */
    fun applyFixedDuration() {
        if (!fixed) return
        rows = rows.map { (s, _) -> s to addMinutes(s, duration) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回")
            }
            Text(
                "编辑时间表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                settings.slotTimes = rows.joinToString(",") { (s, e) -> "$s-$e" }
                settings.slotTimesName = name.ifBlank { "默认" }
                settings.slotFixedDuration = fixed
                settings.slotDurationMinutes = duration
                Repo.rescheduleAll()
                savedHint = "已保存,共 ${rows.size} 节"
            }) { Text("保存") }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            if (savedHint.isNotBlank()) {
                Text(
                    savedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // ---- 时间表名称 ----
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("时间表名称", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("默认") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.width(150.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---- 固定上课时长 ----
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("固定上课时长", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "修改上课时间会跟随联动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = fixed, onCheckedChange = {
                        fixed = it
                        if (it) applyFixedDuration()
                    })
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editDuration = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("每节课时长(分钟)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "$duration 分钟",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---- 节次列表 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "请注意是 24 小时制!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val lastEnd = rows.lastOrNull()?.second ?: "08:00"
                    val start = addMinutes(lastEnd, 5)
                    rows = rows + (start to addMinutes(start, duration))
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }

            SectionCard {
                rows.forEachIndexed { index, (start, end) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "第 ${index + 1} 节课",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TimeChip(start) { editing = index to true }
                        Text("-", modifier = Modifier.padding(horizontal = 4.dp))
                        TimeChip(end) { editing = index to false }
                        IconButton(onClick = {
                            rows = rows.toMutableList().also { it.removeAt(index) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (rows.isEmpty()) {
                    Text(
                        "还没有节次,点上面的「添加」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = {
                // 恢复成内置的 12 节作息(直接解析常量,不读当前设置)
                rows = Regex("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})")
                    .findAll(SettingsStore.DEFAULT_SLOT_TIMES)
                    .map { m ->
                        val a = m.groupValues[1]
                        val b = m.groupValues[2]
                        (if (a.length == 4) "0$a" else a) to (if (b.length == 4) "0$b" else b)
                    }
                    .toList()
            }) { Text("恢复默认 12 节") }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ---- 时间编辑弹窗 ----
    editing?.let { (index, isStart) ->
        val cur = rows.getOrNull(index)?.let { if (isStart) it.first else it.second } ?: "08:00"
        TimeInputDialog(
            title = if (isStart) "第 ${index + 1} 节课 · 开始时间" else "第 ${index + 1} 节课 · 结束时间",
            initial = cur,
            onDismiss = { editing = null },
            onConfirm = { newTime ->
                rows = rows.toMutableList().also { list ->
                    val (s, e) = list[index]
                    list[index] = if (isStart) {
                        // 固定时长时,结束时间自动跟着算
                        newTime to (if (fixed) addMinutes(newTime, duration) else e)
                    } else {
                        s to newTime
                    }
                }
                editing = null
            }
        )
    }

    // ---- 每节课时长弹窗 ----
    if (editDuration) {
        TimeInputDialog(
            title = "每节课时长(分钟)",
            initial = duration.toString(),
            isDuration = true,
            onDismiss = { editDuration = false },
            onConfirm = { v ->
                val n = v.toIntOrNull()?.coerceIn(10, 180)
                if (n != null) {
                    duration = n
                    applyFixedDuration()
                }
                editDuration = false
            }
        )
    }
}

/** "08:20" + 45 分钟 → "09:05";越界夹到 23:59。顶层函数:初始化 state 时也要用。 */
private fun shiftTime(t: String, mins: Int): String {
    val h = t.substringBefore(':').toIntOrNull() ?: 0
    val m = t.substringAfter(':').take(2).toIntOrNull() ?: 0
    val total = (h * 60 + m + mins).coerceIn(0, 23 * 60 + 59)
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 时间/数字输入弹窗(24 小时制,手打比转盘快) */
@Composable
private fun TimeInputDialog(
    title: String,
    initial: String,
    isDuration: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val valid = if (isDuration) {
        value.toIntOrNull()?.let { it in 10..180 } == true
    } else {
        Regex("^\\d{1,2}:\\d{2}$").matches(value)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    placeholder = { Text(if (isDuration) "45" else "08:20") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isDuration) KeyboardType.Number else KeyboardType.Ascii
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isDuration) "范围 10~180 分钟" else "格式 HH:mm,例如 08:20(24 小时制)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = valid) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


