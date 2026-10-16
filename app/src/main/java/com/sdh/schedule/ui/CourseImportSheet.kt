@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdh.schedule.data.Course
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.CourseExtractor
import com.sdh.schedule.parse.CourseTextParser
import com.sdh.schedule.parse.IcsImport
import com.sdh.schedule.parse.SlotTimes
import com.sdh.schedule.parse.WakeupImport
import com.sdh.schedule.parse.DocumentText
import com.sdh.schedule.parse.LocalOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入课程表:把一个学期的课表一次性弄进来。
 *
 * 现实用法:钉钉里的课表截图 / 学校教务平台的课表图片或导出的文件 / 手动粘贴的文字,
 * 丢进来 → 大模型(或本地规则)解析成课程列表 → 预览确认 → 写入课表。
 */
@Composable
fun CourseImportSheet(
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    onOpenWeb: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<List<CourseTextParser.ParsedCourse>?>(null) }
    var showTextInput by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    /** 导入 ics / 备份时,把节次时间和学期起始对齐(以 ics 为准替换,不再与旧值合并) */
    fun applyMeta(p: CourseFilePick) {
        if (p.slotTimes.isNotEmpty()) {
            Repo.settings.slotTimes = SlotTimes.applyImport(Repo.settings.slotTimes, p.slotTimes)
        }
        WakeupImport.semesterStartEpochDay(p.semesterStart)?.let {
            Repo.settings.semesterStartEpochDay = it
        }
    }

    fun handleText(text: String, hint: String = "") {
        if (text.isBlank() && hint.isBlank()) return
        busy = true
        status = "正在识别课程表…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { CourseExtractor.fromText(context, text.ifBlank { hint }) }
            parsed = r.courses
            status = if (r.courses.isEmpty()) r.note else "识别到 ${r.courses.size} 门课 · ${r.note}"
            busy = false
        }
    }

    fun handleImage(uri: Uri) {
        busy = true
        status = "正在识别图片里的课程表…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) CourseExtractor.Result(emptyList(), "none", "图片读不出来")
                else CourseExtractor.fromImage(context, bmp)
            }
            parsed = r.courses
            status = if (r.courses.isEmpty()) r.note else "识别到 ${r.courses.size} 门课 · ${r.note}"
            busy = false
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { handleImage(it) }
    }

    /**
     * WakeUp 导出文件(.ics 或 .wakeup_schedule 备份)—— **两种格式都用同一个入口,自动识别**。
     * (之前这里写死了"按备份解析",用户选 .ics 就会报 JSON 错 —— 实测踩过)
     */
    val wakeupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        status = "正在读取文件…"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { readCourseFile(context, uri) }.getOrNull() }
            if (res == null) {
                status = "这个文件读不出来(格式不支持,或者文件是空的)"
                busy = false
            } else {
                parsed = res.courses
                // 节次时间/学期起始对齐(否则课表格子上的时间跟学校对不上)
                applyMeta(res)
                status = res.status
                busy = false
            }
        }
    }

    /** 文件导入:自动识别 ics / WakeUp 备份 / 普通文档(和蓝色卡片走同一套逻辑) */
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        status = "正在读取文件…"
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { readCourseFile(context, uri) }.getOrNull() }
            if (res == null) {
                status = "读不出这个文件的内容(格式不支持,或者文件是空的)"
                busy = false
                return@launch
            }
            parsed = res.courses
            applyMeta(res)
            status = res.status
            busy = false
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingPhotoUri?.let { handleImage(it) }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 面板内容可能超过一屏(识别结果预览 + 各种入口),必须能滚,否则底部入口点不到
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("导入课程表", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "最准的是文件:如果你在用 WakeUp课程表,在它里面导出课表(.ics),用下面的「文件」选进来 ——" +
                    "课程名/老师/教室/星期/节次/周次都能精确还原。没有导出文件时再考虑截图(截图靠 OCR,会有误差)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(status.ifBlank { "处理中…" }, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(14.dp))
            } else if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed.isNullOrEmpty()) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }

            // 识别结果预览
            parsed?.takeIf { it.isNotEmpty() }?.let { list ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            "识别结果(${list.size} 门)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        list.forEach { c ->
                            Text(
                                text = "${weekdayShort(c.weekday)} 第${c.startSlot}-${c.startSlot + c.slotCount - 1}节 · ${c.name}" +
                                    (if (c.location.isNotBlank()) " @${c.location}" else "") +
                                    " · 第${c.startWeek}-${c.endWeek}周" +
                                    (when (c.weekParity) {
                                        1 -> "单周"
                                        2 -> "双周"
                                        else -> ""
                                    }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 明确两个按钮,不用勾选框 —— 用户要的是"覆盖原课表"一眼可见
                Text(
                    "写入方式:覆盖 = 先清空现有 ${Repo.courses.value.size} 门课再导入;追加 = 保留现有课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Button(
                        onClick = {
                            val n = importParsedCourses(list, replace = true)
                            onDone("已覆盖原课表,导入 $n 门课")
                        }
                    ) { Text("覆盖原课表(${list.size} 门)") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            val n = importParsedCourses(list, replace = false)
                            onDone("已追加 $n 门课")
                        }
                    ) { Text("追加") }
                    TextButton(onClick = { parsed = null; status = "" }) { Text("重新识别") }
                }
                Spacer(Modifier.height(6.dp))
            }

            // 入口
            Text(
                "从哪里导入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportChip("相册图片") {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                ImportChip("拍照") {
                    val dir = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
                    val f = java.io.File(dir, "course_${System.currentTimeMillis()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f
                    )
                    pendingPhotoUri = uri
                    takePhoto.launch(uri)
                }
                ImportChip("文件(.ics 等)") {
                    pickFile.launch(arrayOf("*/*"))
                }
                ImportChip("粘贴文字") { showTextInput = true }
            }
            Spacer(Modifier.height(10.dp))
            // 教务系统网页导入(WebView 配置参考 WakeUp 逆向结果:必须允许弹窗 + 桌面 UA)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onOpenWeb()
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "从学校教务系统导入(内置浏览器)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            "在 App 里打开学校教务系统,你自己登录 → 打开课表页 → 一键提取。" +
                                "⚠️ 学校若走 WebVPN,可能仍打不开;那就用上面的「文件」或「相册图片」",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // WakeUp 备份文件导入:格式确定,能 100% 还原(最准的一条路)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BlueSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { wakeupPicker.launch(arrayOf("*/*")) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "从 WakeUp课程表 导入(.ics / 备份文件)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            "在 WakeUp课程表 里「导出/分享」课表,把导出的文件(.ics 或备份文件)选进来 —— " +
                                "课程名/老师/教室/星期/节次/周次都能**精确还原**,不靠 OCR、不需要登录任何系统。",
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

    if (showTextInput) {
        var text by remember { mutableStateOf(pasteText) }
        AlertDialog(
            onDismissRequest = { showTextInput = false },
            title = { Text("粘贴课表文字") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; pasteText = it },
                    label = { Text("把课表内容粘进来") },
                    placeholder = { Text("例如:\n高等数学 周一 1-2节 1-16周 A301 张老师\n大学物理 周三 3-4节 1-8周 实验楼B203") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTextInput = false
                    handleText(text)
                }) { Text("识别") }
            },
            dismissButton = { TextButton(onClick = { showTextInput = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ImportChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

private fun weekdayShort(w: Int): String =
    arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[(w - 1).coerceIn(0, 6)]

/**
 * 把识别出来的课程写入课表。
 *
 * @param replace true = **覆盖原课表**(先清空现有课程再导入);false = 追加
 * @return 写入的课程数
 */
internal fun importParsedCourses(
    list: List<CourseTextParser.ParsedCourse>,
    replace: Boolean
): Int {
    if (replace) {
        // 先清空(硬删除,确保不残留旧课)
        Repo.courses.value.toList().forEach { Repo.deleteCourse(it.id) }
    }
    list.forEach { c ->
        Repo.addCourse(
            Course(
                name = c.name,
                teacher = c.teacher,
                location = c.location,
                weekday = c.weekday,
                startSlot = c.startSlot,
                slotCount = c.slotCount,
                startWeek = c.startWeek,
                endWeek = c.endWeek,
                weekParity = c.weekParity,
                reminderMinutes = Repo.settings.courseDefaultReminder
            )
        )
    }
    return list.size
}









/** 文件导入的结果(ics / WakeUp 备份 / 普通文档 统一用这个结构) */
private data class CourseFilePick(
    val courses: List<CourseTextParser.ParsedCourse>,
    val status: String,
    val slotTimes: List<Pair<Int, String>> = emptyList(),
    val semesterStart: String? = null
)

/**
 * 读一个文件并**自动判断格式**:
 *   .ics(iCalendar)      → IcsImport        (课程名/教室/老师/星期/节次/周次 全都精确)
 *   .wakeup_schedule     → WakeupImport     (WakeUp 自己的备份格式)
 *   其它(Excel/Word/PDF/文本) → 抽正文后走大模型或本地规则
 *
 * 注意:两个入口(蓝色卡片 / 「文件」chip)都用这个函数 —— 之前卡片那条路写死了按备份解析,
 * 用户选 .ics 会报「Value VERSION of type String cannot be converted to JSONArray」(实测踩过)。
 */
private fun readCourseFile(context: android.content.Context, uri: Uri): CourseFilePick? {
    val raw = context.contentResolver.openInputStream(uri)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

    // ① ics
    if (IcsImport.looksLikeIcs(raw)) {
        val r = IcsImport.parse(raw)
        return CourseFilePick(
            courses = r.courses,
            status = if (r.courses.isEmpty()) "ics 里没解析出课程:${r.note}"
            else "识别到 ${r.courses.size} 门课 · ${r.note}",
            slotTimes = r.slotTimes,
            semesterStart = r.semesterStart
        )
    }
    // ② WakeUp 备份
    if (WakeupImport.looksLikeBackup(raw)) {
        val r = WakeupImport.parse(raw)
        return CourseFilePick(
            courses = r.courses,
            status = if (r.courses.isEmpty()) "WakeUp 备份里没解析出课程:${r.note}"
            else "识别到 ${r.courses.size} 门课 · ${r.note}",
            slotTimes = r.slotTimes,
            semesterStart = r.semesterStart
        )
    }
    // ③ 普通文档:抽正文
    val name = DocumentText.displayName(context, uri)
    val mime = context.contentResolver.getType(uri)
    val ex = DocumentText.extract(context, uri, mime, name)
    val body = if (ex.text.isBlank() && ex.pages.isNotEmpty()) {
        LocalOcr.recognize(ex.pages.first()).orEmpty()
    } else ex.text
    if (body.isBlank()) return null
    val r = CourseExtractor.fromText(context, body)
    return CourseFilePick(
        courses = r.courses,
        status = if (r.courses.isEmpty()) r.note else "识别到 ${r.courses.size} 门课 · ${r.note}"
    )
}

