package com.sdh.schedule.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdh.schedule.data.Event
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.Suggestion
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 「待确认」页:所有从通知/图片里识别出来的候选日程都在这,确认后才进日程表 */
@Composable
fun SuggestionsScreen(
    suggestions: List<Suggestion>,
    onEdit: (Event) -> Unit
) {
    val context = LocalContext.current
    remembered()
    val pending = suggestions.filter { it.status == Suggestion.STATUS_PENDING }
    val handledCount = suggestions.size - pending.size

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                Text("待确认日程", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "共 ${pending.size} 条待确认" + if (handledCount > 0) ",已处理 $handledCount 条" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (pending.isEmpty()) {
            item {
                EmptyHint(
                    "暂时没有待确认的日程。\n\n点右下角「+」可导入图片 / PDF / Word / 文字自动识别,或开启「通知使用权」让 App 自动从微信、短信等通知里识别(也可设置为高置信度自动加入)。"
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        pending.forEach { Repo.rejectSuggestion(it.id) }
                    }) { Text("全部忽略") }
                }
            }
        }
        items(pending) { s ->
            SuggestionCard(
                suggestion = s,
                onAccept = { Repo.acceptSuggestion(s.id) },
                onReject = { Repo.rejectSuggestion(s.id) },
                onEdit = {
                    // 只标记为已处理,真正的日程由编辑页保存时创建(避免重复建两条)
                    Repo.markSuggestionHandled(s.id, Suggestion.STATUS_ACCEPTED)
                    onEdit(
                        s.toEvent(Repo.settings.defaultAccount, Repo.settings.defaultReminderMinutes)
                            .copy(id = 0L, title = s.title)
                    )
                }
            )
        }
        if (handledCount > 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(onClick = { Repo.clearHandledSuggestions() }) { Text("清理已处理的记录") }
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun remembered() {
    // 占位:保持与其它屏幕一致的重组行为
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(suggestion.startMillis), zone)
    val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(suggestion.endMillis), zone)
    val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[start.dayOfWeek.value - 1]
    val timeText = if (suggestion.allDay) {
        "${start.monthValue}月${start.dayOfMonth}日$week 全天"
    } else {
        "${start.monthValue}月${start.dayOfMonth}日$week %02d:%02d - %02d:%02d".format(
            start.hour, start.minute, end.hour, end.minute
        )
    }

    val bitmap = remember(suggestion.imagePath) {
        if (suggestion.imagePath.isBlank()) null
        else runCatching { BitmapFactory.decodeFile(suggestion.imagePath) }.getOrNull()
    }

    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = suggestion.title.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (suggestion.location.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "📍 ${suggestion.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Chip(suggestion.source.label, BlueDark)
                Chip(if (suggestion.engine == "llm") "大模型" else "本地规则", Green)
                Chip("置信度 ${(suggestion.confidence * 100).toInt()}%", if (suggestion.confidence >= 0.8) Green else Orange)
            }
            if (bitmap != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "来源图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            if (suggestion.sourceText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "原文:" + suggestion.sourceText.take(180).replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("加入日程") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onReject) { Text("忽略") }
            }
        }
    }
}
