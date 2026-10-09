@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sdh.schedule.R
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.DocumentText
import com.sdh.schedule.parse.ParseCoordinator
import java.io.File
import kotlin.concurrent.thread

/**
 * 右下角「+」弹出的操作面板 —— 新建与各种导入都集中在这里,不再满屏散落入口。
 *
 * 全部走系统选择器(照片选择器 / 文件选择器),不需要任何存储权限;
 * 图片与扫描件用本机离线 OCR,不配 API Key 也能识别。
 */
@Composable
fun AddSheet(
    onDismiss: () -> Unit,
    onManualCreate: () -> Unit,
    onNewNote: () -> Unit = {},
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    fun describe(result: ParseCoordinator.ProcessResult): String {
        val count = result.suggestionIds.size + result.autoCreated
        return when {
            count > 0 -> "识别完成:生成 $count 条" +
                if (result.autoCreated > 0) "(其中 ${result.autoCreated} 条已自动加入日程)" else "(去「待确认」确认)"
            result.note.isNotBlank() -> "没识别出日程:${result.note}"
            else -> "没有识别出日程"
        }
    }

    fun runImport(block: () -> ParseCoordinator.ProcessResult) {
        if (busy) return
        busy = true
        thread(name = "import-recognize") {
            val result = runCatching { block() }
                .getOrElse { ParseCoordinator.ProcessResult(note = "识别失败:${it.message}") }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                Repo.reload()
                onResult(describe(result))
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runImport {
                val bmp = decodeSampled(context, uri, 2000)
                if (bmp == null) ParseCoordinator.ProcessResult(note = "读不出这张图片")
                else ParseCoordinator.process(context, EventSource.SHARE_IMAGE, "相册导入", "", bmp)
            }
        }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val uri = pendingPhotoUri
        if (ok && uri != null) {
            runImport {
                val bmp = decodeSampled(context, uri, 2000)
                if (bmp == null) ParseCoordinator.ProcessResult(note = "读不出这张照片")
                else ParseCoordinator.process(context, EventSource.SHARE_IMAGE, "拍照导入", "", bmp)
            }
        }
    }

    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runImport {
                val name = DocumentText.displayName(context, uri)
                ParseCoordinator.processDocument(context, uri, context.contentResolver.getType(uri), name)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "新建 / 导入",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在识别…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            SheetAction(
                icon = Icons.Default.Add,
                title = "手动新建日程",
                subtitle = "和以前一样,自己填标题和时间",
                enabled = !busy
            ) {
                onDismiss()
                onManualCreate()
            }
            SheetAction(
                icon = Icons.Default.Edit,
                title = "新建便签",
                subtitle = "随手记点什么(和日程相互独立)",
                enabled = !busy
            ) {
                onDismiss()
                onNewNote()
            }
            SheetAction(
                painterRes = R.drawable.ic_image,
                title = "相册图片识别",
                subtitle = "截图、海报、会议通知 —— 本机离线识别,不用联网",
                enabled = !busy
            ) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            SheetAction(
                painterRes = R.drawable.ic_camera,
                title = "拍照识别",
                subtitle = "直接拍一张,立刻转成日程",
                enabled = !busy
            ) {
                val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                pendingPhotoUri = uri
                takePhoto.launch(uri)
            }
            SheetAction(
                painterRes = R.drawable.ic_doc,
                title = "文件识别",
                subtitle = "PDF / Word / Excel / PPT / txt",
                enabled = !busy
            ) {                pickDoc.launch(
                    arrayOf(
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "text/plain"
                    )
                )
            }
            SheetAction(
                icon = Icons.Default.Edit,
                title = "粘贴文字识别",
                subtitle = "把聊天记录、公告文字贴进来",
                enabled = !busy,
                showDivider = false
            ) {
                showTextDialog = true
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "在微信/邮件里直接「分享 → 日程助手」也行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showTextDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("粘贴文字识别") },
            text = {
                Column {
                    Text(
                        "把通知、聊天记录、公告内容粘进来,自动识别成日程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("例如:明天下午3点半在望京SOHO和张总吃饭") },
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    showTextDialog = false
                    onDismiss()
                    if (t.isNotEmpty()) {
                        runImport {
                            ParseCoordinator.process(context, EventSource.MANUAL, "手动粘贴", t)
                        }
                    }
                }) { Text("识别") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector? = null,
    painterRes: Int? = null,
    title: String,
    subtitle: String,
    enabled: Boolean,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            } else if (painterRes != null) {
                Icon(
                    painter = painterResource(painterRes),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 64.dp),
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 大图按需采样解码,避免几千万像素的照片直接 OOM */
private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (t: Throwable) {
        null
    }
}
