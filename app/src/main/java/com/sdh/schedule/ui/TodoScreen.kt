@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sdh.schedule.R
import com.sdh.schedule.data.Priority
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.Todo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 记录页:便签 / 待办 两个分段(避免底部导航挤到 6 个) */
@Composable
fun RecordsScreen(
    notes: List<com.sdh.schedule.data.Note>,
    todos: List<Todo>,
    segment: Int,
    onSegmentChange: (Int) -> Unit,
    onEditNote: (com.sdh.schedule.data.Note) -> Unit,
    onCreateNote: () -> Unit,
    onEditTodo: (Todo) -> Unit,
    onCreateTodo: () -> Unit
) {

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("记录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(
                text = if (segment == 0) "待办" else "便签",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 分段切换
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SegButton("待办(${todos.count { !it.done }})", segment == 0) { onSegmentChange(0) }
            SegButton("便签(${notes.size})", segment == 1) { onSegmentChange(1) }
        }

        if (segment == 0) {
            TodoList(todos = todos, onEdit = onEditTodo, onCreate = onCreateTodo)
        } else {
            NotesScreen(notes = notes, onEdit = onEditNote, onCreate = onCreateNote)
        }
    }
}

@Composable
private fun SegButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private fun fmtDue(millis: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${t.monthValue}月${t.dayOfMonth}日"
    }
}

/** 待办清单 */
@Composable
fun TodoList(todos: List<Todo>, onEdit: (Todo) -> Unit, onCreate: () -> Unit) {
    val open = todos.filter { !it.done }
    val overdue = open.filter { it.isOverdue() }
    val dueToday = open.filter { it.hasDue && !it.isOverdue() && it.dueMillis < LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val rest = open - overdue.toSet() - dueToday.toSet()
    val done = todos.filter { it.done }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (todos.isEmpty()) {
            item {
                EmptyHint(
                    "还没有待办。\n\n点右下角「+」添加;从通知/图片/文件里识别到的「要做的事」(比如「准备好一寸照片」)会自动进这里。"
                )
            }
        }
        if (overdue.isNotEmpty()) {
            item { GroupTitle("已过期") }
            items(overdue) { t -> TodoRow(t, onEdit) }
        }
        if (dueToday.isNotEmpty()) {
            item { GroupTitle("今天/今天之前截止") }
            items(dueToday) { t -> TodoRow(t, onEdit) }
        }
        if (rest.isNotEmpty()) {
            item { GroupTitle("待办") }
            items(rest) { t -> TodoRow(t, onEdit) }
        }
        if (done.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已完成(${done.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { Repo.clearDoneTodos() }) { Text("清空") }
                }
            }
            items(done) { t -> TodoRow(t, onEdit) }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun TodoRow(todo: Todo, onEdit: (Todo) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选完成
            Icon(
                painter = painterResource(
                    if (todo.done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                ),
                contentDescription = if (todo.done) "标记为未完成" else "标记为完成",
                tint = if (todo.done) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { Repo.setTodoDone(todo.id, !todo.done) }
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).clickable { onEdit(todo) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (todo.priority == Priority.IMPORTANT) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(6.dp)
                                .background(Danger, RoundedCornerShape(50))
                        )
                    }
                    Text(
                        text = todo.title.ifBlank { "(无标题)" },
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                        color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                val meta = buildString {
                    if (todo.hasDue) {
                        append(if (todo.isOverdue()) "已过期 · " else "截止 ")
                        append(fmtDue(todo.dueMillis))
                    }
                    if (todo.source != com.sdh.schedule.data.EventSource.MANUAL) {
                        if (isNotEmpty()) append(" · ")
                        append(todo.source.label)
                    }
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (todo.isOverdue()) Danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = { Repo.deleteTodo(todo.id) }) { Text("删除") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp)
                .height(0.6.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/** 待办编辑对话框(字段少,用弹窗即可) */
@Composable
fun TodoEditDialog(
    original: Todo?,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(original?.title ?: "") }
    var note by remember { mutableStateOf(original?.note ?: "") }
    var important by remember { mutableStateOf(original?.priority == Priority.IMPORTANT) }
    // 0=无截止 1=今天 2=明天 3=本周末
    var dueChoice by remember {
        mutableStateOf(
            when {
                original == null || !original.hasDue -> 0
                else -> {
                    val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(original.dueMillis), ZoneId.systemDefault()).toLocalDate()
                    when (d) {
                        LocalDate.now() -> 1
                        LocalDate.now().plusDays(1) -> 2
                        else -> 3
                    }
                }
            }
        )
    }

    fun dueMillis(): Long = when (dueChoice) {
        1 -> LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        2 -> LocalDate.now().plusDays(1).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        3 -> {
            val today = LocalDate.now()
            val toSunday = (7 - today.dayOfWeek.value)
            today.plusDays(toSunday.toLong()).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        else -> 0L
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新建待办" else "编辑待办") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("要做什么") },
                    placeholder = { Text("例如:准备一寸照片") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(80.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("截止时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row {
                    listOf(0 to "无", 1 to "今天", 2 to "明天", 3 to "本周末").forEach { (v, label) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (dueChoice == v) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 6.dp).clickable { dueChoice = v }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dueChoice == v) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { important = !important },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(if (important) R.drawable.ic_check_circle else R.drawable.ic_circle_outline),
                        contentDescription = null,
                        tint = if (important) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("标记为重要", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = (original ?: Todo()).copy(
                    title = title.trim(),
                    note = note,
                    dueMillis = dueMillis(),
                    priority = if (important) Priority.IMPORTANT else Priority.NORMAL
                )
                if (t.title.isBlank()) return@TextButton
                if (t.id == 0L) Repo.addTodo(t) else Repo.updateTodo(t)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (original != null) {
                    TextButton(onClick = {
                        Repo.deleteTodo(original.id)
                        onDismiss()
                    }) { Text("删除", color = Danger) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
