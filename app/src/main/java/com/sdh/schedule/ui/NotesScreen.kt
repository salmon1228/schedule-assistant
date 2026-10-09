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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdh.schedule.data.Note
import com.sdh.schedule.data.Repo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 便签颜色(浅色背景,便于区分) */
private val NoteColors = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF6E5), Color(0xFFEAF4FF), Color(0xFFEAFBF0),
    Color(0xFFFFEDF2), Color(0xFFF3EDFF), Color(0xFFF5F6F8)
)

private fun noteTime(millis: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "今天 %02d:%02d".format(t.hour, t.minute)
        today.minusDays(1) -> "昨天 %02d:%02d".format(t.hour, t.minute)
        else -> "%d月%d日".format(t.monthValue, t.dayOfMonth)
    }
}

/** 便签列表 */
@Composable
fun NotesScreen(notes: List<Note>, onEdit: (Note) -> Unit, onCreate: () -> Unit) {
    val pinned = notes.filter { it.pinned }
    val others = notes.filterNot { it.pinned }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 20.dp, bottom = 100.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)) {
                Text("便签", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (notes.isEmpty()) "随手记点什么 —— 点右下角 + 新建" else "共 ${notes.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (pinned.isNotEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GroupTitle("置顶")
            }
            items(pinned) { n -> NoteCard(n, onEdit) }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    GroupTitle("全部便签")
                }
            }
            items(others) { n -> NoteCard(n, onEdit) }
        }
        if (notes.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                EmptyHint("还没有便签。\n\n便签和日程相互独立:日程用来安排时间,便签用来记事。\n也可以把收到的文字「分享 → 日程助手」,再手动存成便签。")
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onEdit: (Note) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = NoteColors[note.colorIndex.coerceIn(0, NoteColors.size - 1)],
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clickable { onEdit(note) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "已置顶",
                        tint = Orange,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = note.title.ifBlank { "(无标题)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 2
                )
            }
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 5
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = noteTime(note.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/** 便签编辑页(全屏) */
@Composable
fun NoteEditScreen(original: Note?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(original?.title ?: "") }
    var content by remember { mutableStateOf(original?.content ?: "") }
    var pinned by remember { mutableStateOf(original?.pinned ?: false) }
    var colorIndex by remember { mutableStateOf(original?.colorIndex ?: 0) }
    var showDelete by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        val n = (original ?: Note()).copy(
            title = title.trim().ifBlank {
                content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(20).orEmpty()
            },
            content = content,
            pinned = pinned,
            colorIndex = colorIndex
        )
        if (n.id == 0L) Repo.addNote(n) else Repo.updateNote(n)
        onDismiss()
    }

    BackHandler { save() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NoteColors[colorIndex.coerceIn(0, NoteColors.size - 1)])
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { save() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "返回", modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { pinned = !pinned }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "置顶",
                    tint = if (pinned) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = save, modifier = Modifier.size(48.dp)) {
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
                .padding(horizontal = 12.dp)
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("内容") },
                        placeholder = { Text("随手写点什么…") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("便签颜色", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        NoteColors.forEachIndexed { i, c ->
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(28.dp)
                                    .background(c, RoundedCornerShape(50))
                                    .clickable { colorIndex = i }
                            ) {
                                if (i == colorIndex) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0x224361EE), RoundedCornerShape(50))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (original != null) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDelete = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("删除便签", color = Danger)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除便签") },
            text = { Text("确定删除这条便签吗?") },
            confirmButton = {
                TextButton(onClick = {
                    original?.let { Repo.deleteNote(it.id) }
                    showDelete = false
                    onDismiss()
                }) { Text("删除", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
}
