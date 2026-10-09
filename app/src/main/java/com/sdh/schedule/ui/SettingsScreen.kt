@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.SettingsStore
import com.sdh.schedule.notify.ScheduleNotificationListener
import com.sdh.schedule.parse.ParseCoordinator

/** 设置页:解析引擎、通知监听、提醒默认值、权限向导、导入日志 */
@Composable
fun SettingsScreen(
    onOpenWizard: () -> Unit,
    pendingCount: Int,
    onOpenSuggestions: () -> Unit = {},
    onOpenTimetableSettings: () -> Unit = {},
    onOpenSelfCheck: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val s = Repo.settings
    val revision by s.revision.collectAsState()
    val events by Repo.events.collectAsState()
    val logs = remember(revision) { Repo.logs.value }

    var inputDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showThreshold by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 导出:系统"创建文档";恢复:系统"打开文档" —— 都不需要存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val r = com.sdh.schedule.util.Backup.writeTo(context, uri)
            backupMsg = r.fold({ "已导出:$it" }, { "导出失败:${it.message}" })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val r = com.sdh.schedule.util.Backup.importFrom(context, uri)
            backupMsg = r.fold({ "恢复完成:$it" }, { "恢复失败:${it.message}" })
        }
    }

    val fullScreenOk = remember {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() ?: true
        } else true
    }

    val listenerOn = remember(revision) { ScheduleNotificationListener.isEnabled(context) }
    val engine = remember(revision, events.size) { ParseCoordinator.engineLabelForUi(context) }

    // 触发系统文件选择器
    androidx.compose.runtime.LaunchedEffect(exporting) {
        if (exporting) {
            exportLauncher.launch(com.sdh.schedule.util.Backup.suggestedFileName())
            exporting = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(importing) {
        if (importing) {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            importing = false
        }
    }

    // 导出/恢复结果用 Toast 提示
    androidx.compose.runtime.LaunchedEffect(backupMsg) {
        backupMsg?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            backupMsg = null
        }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.KeyboardArrowLeft,
                                contentDescription = "返回"
                            )
                        }
                    }
                    Text("设置", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "共 ${events.size} 条日程" + if (pendingCount > 0) ",$pendingCount 条待确认" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 运行状态 ----
        item { GroupTitle("运行状态") }
        item {
            SectionCard {
                SettingRow(
                    title = "功能自检",
                    subtitle = "一键检查:节假日数据 / 农历 / 通知权限 / 通知使用权 / 精确闹钟 / 待办抽取 …",
                    value = "点一下",
                    onClick = onOpenSelfCheck
                )
                SettingRow(title = "当前解析引擎", value = engine, showArrow = false)
                SettingRow(
                    title = "待确认日程",
                    subtitle = "从通知/图片/文件识别出来的,确认后才进日程表",
                    value = if (pendingCount > 0) "$pendingCount 条" else "无",
                    onClick = onOpenSuggestions
                )
                SettingRow(
                    title = "课程表设置",
                    subtitle = "学期开始日期 / 节次时间 / 上课提醒",
                    value = "",
                    onClick = onOpenTimetableSettings
                )
                SettingRow(
                    title = "通知使用权",
                    value = if (listenerOn) "已开启" else "未开启",
                    onClick = { openNotificationListenerSettings(context) }
                )
                SettingRow(
                    title = "全屏通知权限(闹钟弹窗)",
                    subtitle = "想让「闹钟提醒」全屏弹出来并响铃,需要 MIUI 里允许本应用「全屏通知」;不开也能正常收到提醒",
                    value = if (fullScreenOk) "已允许" else "未允许",
                    onClick = { openAppDetailSettings(context) }
                )
                SettingRow(
                    title = "权限向导",
                    subtitle = "通知使用权 / 通知权限 / 精确闹钟 / 后台白名单",
                    value = "",
                    showDivider = false,
                    onClick = onOpenWizard
                )
            }
        }

        // ---- 解析引擎 ----
        item { GroupTitle("解析引擎(把通知/图片变成日程)") }
        item {
            SectionCard {
                SettingRow(
                    title = "模式",
                    value = when (s.llmMode) {
                        SettingsStore.MODE_LLM_ONLY -> "只用云端大模型"
                        SettingsStore.MODE_LOCAL_ONLY -> "只用本地规则"
                        else -> "自动(云端优先)"
                    },
                    onClick = { inputDialog = "mode" to "" }
                )
                SettingRow(
                    title = "接口地址",
                    value = s.llmBaseUrl,
                    onClick = { inputDialog = "base" to s.llmBaseUrl }
                )
                SettingRow(
                    title = "API Key",
                    value = if (s.llmApiKey.isBlank()) "未配置" else "已配置(${s.llmApiKey.take(4)}…${s.llmApiKey.takeLast(2)})",
                    onClick = { inputDialog = "key" to s.llmApiKey }
                )
                SettingRow(
                    title = "文本模型",
                    value = s.llmModel,
                    onClick = { inputDialog = "model" to s.llmModel }
                )
                SettingRow(
                    title = "图片模型(多模态)",
                    subtitle = "留空则图片用文本模型;国内可选 qwen-vl-max / glm-4v / doubao-vision 等",
                    value = s.llmVisionModel.ifBlank { "未设置" },
                    onClick = { inputDialog = "vision" to s.llmVisionModel }
                )
                SettingRow(
                    title = "超时(秒)",
                    value = s.llmTimeoutSeconds.toString(),
                    showDivider = false,
                    onClick = { inputDialog = "timeout" to s.llmTimeoutSeconds.toString() }
                )
            }
        }
        item {
            SectionCard {
                SwitchRow(
                    title = "高置信度自动加入日程",
                    subtitle = "关闭时,识别结果都进「待确认」等你点一下",
                    checked = s.autoCreate,
                    onCheckedChange = { s.autoCreate = it }
                )
                SettingRow(
                    title = "自动加入的置信度阈值",
                    value = "%.2f".format(s.autoCreateThreshold),
                    showDivider = false,
                    onClick = { showThreshold = true }
                )
            }
        }

        // ---- 通知监听 ----
        item { GroupTitle("通知监听") }
        item {
            SectionCard {
                SwitchRow(
                    title = "开启通知监听",
                    subtitle = "总开关;仍需在上面的权限向导里授予「通知使用权」",
                    checked = s.listenerSwitch,
                    onCheckedChange = { s.listenerSwitch = it }
                )
                SwitchRow(
                    title = "忽略常驻通知",
                    subtitle = "音乐播放器、下载进度这类通知不解析",
                    checked = s.ignoreOngoing,
                    onCheckedChange = { s.ignoreOngoing = it }
                )
                SettingRow(
                    title = "最小文本长度",
                    subtitle = "短于此长度的通知不解析(避免把聊天短语做成日程)",
                    value = "${s.minTextLength} 字",
                    onClick = { inputDialog = "minlen" to s.minTextLength.toString() }
                )
                SettingRow(
                    title = "包名白名单",
                    subtitle = "留空 = 所有应用;多个用逗号分隔,例如 com.tencent.mm",
                    value = if (s.packageWhitelist.isBlank()) "全部" else "已设置",
                    onClick = { inputDialog = "wl" to s.packageWhitelist }
                )
                SettingRow(
                    title = "包名黑名单",
                    subtitle = "这些应用的通知不解析,多个用逗号分隔",
                    value = if (s.packageBlacklist.isBlank()) "无" else "已设置",
                    onClick = { inputDialog = "bl" to s.packageBlacklist }
                )
                SwitchRow(
                    title = "识别到日程时发通知",
                    subtitle = "以聊天消息的形式提醒你去确认",
                    checked = s.notifySuggestion,
                    onCheckedChange = { s.notifySuggestion = it }
                )
                SwitchRow(
                    title = "解析系统截图通知",
                    subtitle = "截图后系统会发一条带图片的通知,开启后自动识别其中的日程",
                    checked = s.watchScreenshots,
                    onCheckedChange = { s.watchScreenshots = it }
                )
                SwitchRow(
                    title = "抓取链接里的正文",
                    subtitle = "消息只给了一个链接时(报名页/公告),联网抓网页正文一起解析;链接也会存进备注",
                    checked = s.fetchLinks,
                    onCheckedChange = { s.fetchLinks = it }
                )
                SwitchRow(
                    title = "内置过滤系统类通知",
                    subtitle = "系统时钟、系统更新、桌面、设置这类通知天然不是日程 —— 实测时钟自己的「闹钟」曾被当成新日程",
                    checked = s.builtinFilter,
                    onCheckedChange = { s.builtinFilter = it }
                )
                SwitchRow(
                    title = "待办只从聊天/工作类通知里抽",
                    subtitle = "钉钉 / 微信 / QQ / 短信 / 飞书。实测放开抽取会误把京东「确认收货」、天气「注意防范雷电」当成待办",
                    checked = s.todoOnlyFromChatApps,
                    onCheckedChange = { s.todoOnlyFromChatApps = it }
                )
                SettingRow(
                    title = "单个应用每天最多加几条待办",
                    subtitle = "防止某个群一天刷出十几条待办;0 = 不限",
                    value = if (s.todoDailyCapPerApp <= 0) "不限" else "${s.todoDailyCapPerApp} 条",
                    showDivider = false,
                    onClick = { inputDialog = "cap" to s.todoDailyCapPerApp.toString() }
                )
            }
        }

        // ---- 数据与备份 ----
        item { GroupTitle("数据与备份") }
        item {
            SectionCard {
                SettingRow(
                    title = "导出备份(JSON)",
                    subtitle = "把日程/便签/课程/待办导成一个文件,可再导回来。换手机或重装前建议导一份",
                    value = com.sdh.schedule.util.Backup.stats(context),
                    onClick = { exporting = true }
                )
                SettingRow(
                    title = "从备份恢复",
                    subtitle = "追加式导入:已存在的(标题+时间相同)不会重复添加;不覆盖现有数据",
                    value = "",
                    onClick = { importing = true }
                )
                SettingRow(
                    title = "数据库大小",
                    subtitle = "导入日志最多保留 400 条;如果这里涨得很大,说明该清日志了",
                    value = com.sdh.schedule.util.Backup.dbSizeText(context),
                    showDivider = false,
                    onClick = { }
                )
            }
        }

        // ---- 提醒 ----
        item { GroupTitle("提醒") }
        item {
            SectionCard {
                SettingRow(
                    title = "立即测试提醒",
                    subtitle = "马上发一条和真实提醒完全一样的通知(同渠道/同声音),用来确认到底有没有声音",
                    value = "点一下",
                    onClick = { com.sdh.schedule.notify.Notify.showTestReminder(context) }
                )
                SettingRow(
                    title = "提醒铃声",
                    subtitle = "手机静音/振动时,只有「闹钟音」这条还能响",
                    value = when (s.reminderSound) {
                        SettingsStore.SOUND_SYSTEM -> "跟随系统通知音"
                        SettingsStore.SOUND_SILENT -> "静音(只显示通知)"
                        else -> "闹钟音(推荐)"
                    },
                    onClick = { inputDialog = "sound" to "" }
                )
                SwitchRow(
                    title = "让 AI 自动判断提醒时间",
                    subtitle = "原文没写提醒时,由大模型按任务类型决定(面试提前1小时、会议30分钟、截止提前1天…)",
                    checked = s.aiDecideReminder,
                    onCheckedChange = { s.aiDecideReminder = it }
                )
                SwitchRow(
                    title = "默认开启闹钟提醒",
                    subtitle = "到点全屏响铃,走闹钟音量,静音也不漏",
                    checked = s.defaultAlarmReminder,
                    showDivider = false,
                    onCheckedChange = { s.defaultAlarmReminder = it }
                )
            }
        }

        // ---- 新建日程默认值 ----
        item { GroupTitle("新建日程的默认值") }
        item {
            SectionCard {
                SettingRow(
                    title = "默认提醒",
                    value = reminderLabel(s.defaultReminderMinutes),
                    onClick = { inputDialog = "defremind" to s.defaultReminderMinutes.toString() }
                )
                SwitchRow(
                    title = "默认开启倒计时提醒",
                    checked = s.defaultCountdownReminder,
                    onCheckedChange = { s.defaultCountdownReminder = it }
                )
                SettingRow(
                    title = "默认日历账户",
                    value = s.defaultAccount,
                    showDivider = false,
                    onClick = { inputDialog = "account" to s.defaultAccount }
                )
            }
        }

        // ---- 其他 ----
        item { GroupTitle("其他") }
        item {
            SectionCard {
                SettingRow(
                    title = "深色模式",
                    value = when (s.darkMode) {
                        "dark" -> "深色"
                        "light" -> "浅色"
                        else -> "跟随系统"
                    },
                    onClick = { inputDialog = "dark" to s.darkMode }
                )
                SettingRow(
                    title = "导入日志",
                    subtitle = "每条被解析过的通知都留了痕,方便排查为什么没建上日程",
                    value = "${logs.size} 条",
                    onClick = { showLogs = true }
                )
                SettingRow(
                    title = "关于",
                    value = "v1.0",
                    showDivider = false,
                    onClick = { showAbout = true }
                )
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }

    // ---- 输入对话框 ----
    inputDialog?.let { (key, initial) ->
        InputDialog(
            key = key,
            initial = initial,
            onDismiss = { inputDialog = null },
            onConfirm = { value ->
                applySetting(s, key, value)
                inputDialog = null
            }
        )
    }

    if (showThreshold) {
        var th by remember { mutableStateOf(s.autoCreateThreshold) }
        AlertDialog(
            onDismissRequest = { showThreshold = false },
            title = { Text("自动加入阈值") },
            text = {
                Column {
                    Text("置信度 ≥ ${"%.2f".format(th)} 时自动加入日程")
                    Slider(
                        value = th,
                        onValueChange = { th = it },
                        valueRange = 0.5f..1.0f,
                        steps = 9
                    )
                    Text(
                        "建议 0.85:低于这个值还是让你确认一下更稳妥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { s.autoCreateThreshold = th; showThreshold = false }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showThreshold = false }) { Text("取消") } }
        )
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("导入日志(最近 100 条)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (logs.isEmpty()) {
                        Text("暂无记录")
                    } else {
                        logs.take(100).forEach { log ->
                            val t = java.time.LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(log.atMillis),
                                java.time.ZoneId.systemDefault()
                            )
                            Text(
                                text = "[%02d/%02d %02d:%02d] %s · %s · %s".format(
                                    t.monthValue, t.dayOfMonth, t.hour, t.minute,
                                    log.source.label, log.engine, log.outcome
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = log.rawText.take(80).replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { Repo.clearLogs(); showLogs = false }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showLogs = false }) { Text("关闭") } }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 日程助手") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("一个本地优先的日程 App:")
                    Spacer(Modifier.height(6.dp))
                    Text("· 手动建日程:对标小米日历(全天/起止时间/重复/提醒/闹钟/倒计时/账户/时区)")
                    Text("· 通知自动建日程:大段文字用中文语义解析,图片用多模态大模型识别")
                    Text("· 提醒:聊天式通知(像微信消息)+ 闹钟响铃 + 倒计时,全部本地完成")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "没有服务器、没有账号,数据只存在你手机里的 SQLite。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } }
        )
    }
}

@Composable
private fun InputDialog(
    key: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val (title, hint, isNumber, isSecret) = when (key) {
        "mode" -> Quad("解析引擎模式", "AUTO / LLM_ONLY / LOCAL_ONLY", false, false)
        "base" -> Quad("接口地址", "https://api.deepseek.com/v1", false, false)
        "key" -> Quad("API Key", "sk-...", false, true)
        "model" -> Quad("文本模型", "deepseek-chat", false, false)
        "vision" -> Quad("图片模型", "qwen-vl-max", false, false)
        "timeout" -> Quad("超时(秒)", "25", true, false)
        "minlen" -> Quad("最小文本长度", "12", true, false)
        "wl" -> Quad("包名白名单", "逗号分隔,留空=全部", false, false)
        "bl" -> Quad("包名黑名单", "逗号分隔", false, false)
        "defremind" -> Quad("默认提醒(分钟)", "10;0 表示准时", true, false)
        "account" -> Quad("默认日历账户", "本地日历", false, false)
        "dark" -> Quad("深色模式", "system / light / dark", false, false)
        "sound" -> Quad("提醒铃声", "alarm / system / silent", false, false)
        else -> Quad(key, "", false, false)
    }
    if (key == "mode" || key == "dark" || key == "account" || key == "sound") {
        val options = when (key) {
            "sound" -> listOf(
                SettingsStore.SOUND_ALARM to "闹钟音(推荐):走闹钟音量,手机静音/振动时也能响",
                SettingsStore.SOUND_SYSTEM to "跟随系统通知音:安静,但静音时不会响",
                SettingsStore.SOUND_SILENT to "静音:只显示通知,不出声"
            )
            "mode" -> listOf(
                SettingsStore.MODE_AUTO to "自动:优先云端大模型,API 请求失败/无 Key/离线 才用本地规则",
                SettingsStore.MODE_LLM_ONLY to "只用云端大模型(不用本地兜底)",
                SettingsStore.MODE_LOCAL_ONLY to "只用本地规则(完全离线)"
            )
            "dark" -> listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
            else -> listOf("本地日历" to "本地日历", "工作" to "工作", "个人" to "个人", "家庭" to "家庭")
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (value, label) ->
                        TextButton(
                            onClick = { onConfirm(value) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Text(label)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text
                )
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private data class Quad(val title: String, val hint: String, val number: Boolean, val secret: Boolean)

private fun applySetting(s: SettingsStore, key: String, value: String) {
    when (key) {
        "mode" -> if (value.isNotBlank()) s.llmMode = value
        "base" -> s.llmBaseUrl = value
        "key" -> s.llmApiKey = value
        "model" -> if (value.isNotBlank()) s.llmModel = value
        "vision" -> s.llmVisionModel = value
        "timeout" -> value.toIntOrNull()?.let { if (it in 5..120) s.llmTimeoutSeconds = it }
        "minlen" -> value.toIntOrNull()?.let { if (it in 0..500) s.minTextLength = it }
        "wl" -> s.packageWhitelist = value
        "bl" -> s.packageBlacklist = value
        "defremind" -> value.toIntOrNull()?.let { s.defaultReminderMinutes = it }
        "account" -> if (value.isNotBlank()) s.defaultAccount = value
        "dark" -> s.darkMode = value
        "sound" -> if (value.isNotBlank()) s.reminderSound = value
    }
}


