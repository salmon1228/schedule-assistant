package com.sdh.schedule

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.DocumentText
import com.sdh.schedule.parse.ParseCoordinator
import com.sdh.schedule.ui.AppTheme
import kotlin.concurrent.thread

/**
 * 处理系统分享:文字 / 图片 / 文档(PDF、Word、Excel、PPT、txt)都能直接识别成日程。
 *
 * 例:
 *   - 微信里长按消息 → 分享 → 日程助手
 *   - 邮件里点开附件 → 分享 → 日程助手(会议通知 PDF)
 *   - 文件管理器里长按 .docx / .pdf → 分享 → 日程助手
 */
class ShareActivity : ComponentActivity() {

    private val loadingState = mutableStateOf(true)
    private val messageState = mutableStateOf("正在识别…")
    private val detailState = mutableStateOf("")
    private val countState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(applicationContext)

        val sharedText = extractText(intent)
        val imageUri: Uri? = extractImageUri(intent)
        val docUri: Uri? = if (imageUri == null) extractDocumentUri(intent) else null

        if (sharedText.isBlank() && imageUri == null && docUri == null) {
            loadingState.value = false
            messageState.value = "没有收到可识别的内容"
        }

        setContent {
            val loading by loadingState
            val message by messageState
            val detail by detailState
            val createdCount by countState
            AppTheme(darkMode = Repo.settings.darkMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShareScreen(
                        loading = loading,
                        message = message,
                        detail = detail,
                        createdCount = createdCount,
                        onOpenApp = {
                            startActivity(
                                Intent(this@ShareActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            finish()
                        },
                        onClose = { finish() }
                    )
                }
            }
        }

        if (sharedText.isNotBlank() || imageUri != null || docUri != null) {
            thread(name = "share-parse") {
                var result = ParseCoordinator.ProcessResult()
                var extra = ""
                when {
                    docUri != null -> {
                        val mime = intent.type
                        val name = DocumentText.displayName(this, docUri)
                        result = runCatching {
                            ParseCoordinator.processDocument(applicationContext, docUri, mime, name)
                        }.getOrElse { ParseCoordinator.ProcessResult(note = "读取文件失败:${it.message}") }
                        extra = "文件:$name"
                    }

                    imageUri != null -> {
                        val bitmap: Bitmap? = runCatching {
                            contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                        result = runCatching {
                            ParseCoordinator.process(
                                context = applicationContext,
                                source = EventSource.SHARE_IMAGE,
                                sourcePackage = "系统分享",
                                text = sharedText,
                                bitmap = bitmap
                            )
                        }.getOrElse { ParseCoordinator.ProcessResult(note = "识别失败:${it.message}") }
                    }

                    else -> {
                        result = runCatching {
                            ParseCoordinator.process(
                                context = applicationContext,
                                source = EventSource.SHARE_TEXT,
                                sourcePackage = "系统分享",
                                text = sharedText
                            )
                        }.getOrElse { ParseCoordinator.ProcessResult(note = "识别失败:${it.message}") }
                    }
                }

                val finalResult = result
                runOnUiThread {
                    loadingState.value = false
                    countState.value = finalResult.suggestionIds.size + finalResult.autoCreated
                    detailState.value = listOf(extra, finalResult.note)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    messageState.value = when {
                        finalResult.suggestionIds.isNotEmpty() -> "识别完成 ✅"
                        finalResult.autoCreated > 0 -> "已自动加入 ${finalResult.autoCreated} 条日程 ✅"
                        finalResult.note.isNotBlank() -> "没有识别出日程"
                        imageUri != null -> "图片里没有识别出明确的时间信息"
                        else -> "没有识别出日程(文字里需要包含时间,例如「明天14:00开会」)"
                    }
                }
            }
        }
    }

    private fun extractText(intent: Intent?): String {
        if (intent == null) return ""
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            else -> ""
        }
    }

    private fun streamUri(intent: Intent?): Uri? {
        if (intent == null) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun isImage(intent: Intent?): Boolean {
        val type = intent?.type.orEmpty()
        if (type.startsWith("image/")) return true
        val name = streamUri(intent)?.lastPathSegment.orEmpty().lowercase()
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")
    }

    private fun extractImageUri(intent: Intent?): Uri? {
        val uri = streamUri(intent) ?: return null
        return if (isImage(intent)) uri else null
    }

    /** 非图片的文件(PDF / Word / Excel / PPT / txt) */
    private fun extractDocumentUri(intent: Intent?): Uri? {
        val uri = streamUri(intent) ?: return null
        return if (isImage(intent)) null else uri
    }
}

@Composable
private fun ShareScreen(
    loading: Boolean,
    message: String,
    detail: String,
    createdCount: Int,
    onOpenApp: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("日程助手", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (createdCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已生成 $createdCount 条待确认日程,去「待确认」页确认即可。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onOpenApp, modifier = Modifier.weight(1f)) { Text("打开应用") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    }
}
