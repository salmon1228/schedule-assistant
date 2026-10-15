@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sdh.schedule.R
import com.sdh.schedule.data.Note
import com.sdh.schedule.data.Repo
import com.sdh.schedule.live.LiveService
import androidx.compose.runtime.rememberCoroutineScope
import com.sdh.schedule.live.LlmAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sdh.schedule.live.LiveState

/**
 * 「实时听写 / 随问随答」页。
 *
 * 用法:点开始 → 系统语音识别持续把听到的话转成文字 → 一旦识别出"这是个问题",
 * 就自动问一次大模型并把答案显示在下面(也支持手动输入问题)。
 *
 * 说明:
 *  - 语音识别用的是**手机系统的识别服务**(免费、无需额外 Key);能不能用取决于机型,
 *    国行小米一般可以,系统没提供时会提示,此时可以手动输入问题,问答功能照常;
 *  - 答案来自你在「设置 → 解析引擎」里配置的大模型,没配 Key 会提示去配。
 */
@Composable
fun LiveAssistantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by LiveState.running.collectAsState()
    val sentences by LiveState.sentences.collectAsState()
    val partial by LiveState.partial.collectAsState()
    val qa by LiveState.qa.collectAsState()
    val status by LiveState.status.collectAsState()

    var manual by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }

    BackHandler { onBack() }

    // 麦克风 + 通知权限:点开始时申请
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasMic(context)
        if (micOk) startLive(context) else LiveState.status.value = "没有麦克风权限,无法听写"
    }

    val scroll = rememberScrollState()
    LaunchedEffect(sentences.size, qa.size, partial) {
        // 有新内容就滚到底部
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("实时听写 · 随问随答", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    if (running) "正在听…(可息屏,通知栏常驻)" else "点右边按钮开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (running) {
                Button(onClick = { stopLive(context) }) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            } else {
                Button(onClick = {
                    if (hasMic(context)) startLive(context)
                    else {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        permLauncher.launch(perms.toTypedArray())
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始")
                }
            }
        }

        if (status.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp)
        ) {
            // ---- 实时文字 ----
            if (sentences.isNotEmpty() || partial.isNotBlank()) {
                Text("听到的内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        sentences.takeLast(30).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                        if (partial.isNotBlank()) {
                            Text(
                                partial,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ---- 问答 ----
            if (qa.isNotEmpty()) {
                Text("问题与回答", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                qa.asReversed().forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("问:${item.question}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(6.dp))
                            when {
                                item.thinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("正在问大模型…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                item.error.isNotBlank() -> Text(
                                    item.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                else -> Text(item.answer, style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    val n = Note(
                                        title = item.question.take(30),
                                        content = "问:${item.question}\n\n答:${item.answer}\n\n(来自实时听写)"
                                    )
                                    Repo.addNote(n)
                                    savedHint = "已存进便签"
                                }) { Text("存成便签") }
                                TextButton(onClick = {
                                    LiveState.qa.value = LiveState.qa.value.filterNot { it.at == item.at }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (sentences.isEmpty() && qa.isEmpty() && !running) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("怎么用", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "1. 点右上角「开始」→ 允许麦克风权限\n" +
                                "2. 把手机放桌上就行,它会持续把听到的话转成文字\n" +
                                "3. 一旦识别出「这是个问题」,自动问一次大模型,答案显示在下面\n" +
                                "4. 想留档就点「存成便签」;不想听就点「停止」\n\n" +
                                "• 语音识别用的是手机**系统自带**的识别服务,免费、不用额外配 Key\n" +
                                "• 答案来自你在「设置 → 解析引擎」配的大模型;没配 Key 会提示\n" +
                                "• 手动输入问题也可以直接问(系统没有语音服务时用这个)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }

        // ---- 底部:手动提问 ----
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (savedHint.isNotBlank()) {
                    Text(savedHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        placeholder = { Text("直接输入一个问题(或补充说明)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = manual.isNotBlank() && !asking,
                        onClick = {
                            val q = manual.trim()
                            if (q.isNotEmpty()) {
                                asking = true
                                savedHint = ""
                                LiveState.qa.value = LiveState.qa.value + LiveState.Qa(question = q, thinking = true)
                                val at = LiveState.qa.value.last().at
                                scope.launch {
                                    val ans = withContext(Dispatchers.IO) {
                                        runCatching { LlmAsk.ask(context, q, LiveState.transcriptText()) }
                                            .getOrElse { "出错了:${it.message}" }
                                    }
                                    LiveState.qa.value = LiveState.qa.value.map {
                                        if (it.at == at) it.copy(answer = ans, thinking = false) else it
                                    }
                                    asking = false
                                    manual = ""
                                }
                            }
                        }
                    ) { Text("问") }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        LiveState.reset()
                        savedHint = ""
                    }) { Icon(Icons.Default.Clear, contentDescription = "清空") }
                }
            }
        }
    }
}

private fun hasMic(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun startLive(context: android.content.Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, LiveService::class.java).setAction(LiveService.ACTION_START)
    )
}

private fun stopLive(context: android.content.Context) {
    context.startService(Intent(context, LiveService::class.java).setAction(LiveService.ACTION_STOP))
    LiveState.running.value = false
}

