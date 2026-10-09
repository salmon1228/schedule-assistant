package com.sdh.schedule.ui

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.sdh.schedule.notify.ScheduleNotificationListener

/** 权限与环境向导:小米/国行 ROM 上后台限制多,这里一步步引导用户开权限 */
@Composable
fun PermissionWizardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val listenerOn = remember(refresh) { ScheduleNotificationListener.isEnabled(context) }
    val notifOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactAlarmOn = remember(refresh) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else true
    }
    val batteryOk = remember(refresh) {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回")
            }
            Text("权限向导", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { refresh++ }) { Text("刷新") }
        }
        Text(
            text = "国产 ROM(小米/华为/OPPO 等)对后台管得很严,下面 4 项都开齐,提醒和自动识别才稳。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        )

        WizardStep(
            index = 1,
            title = "通知使用权(自动识别日程的关键)",
            desc = "开启后,App 才能读取微信/短信/钉钉等发来的通知,从中提取日程。",
            done = listenerOn,
            buttonText = "去开启",
            onOpen = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    runCatching {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                ComponentName(context, ScheduleNotificationListener::class.java).flattenToString())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        )

        WizardStep(
            index = 2,
            title = "通知权限 / 通知重要性(提醒要能弹窗+响)",
            desc = "没有这个权限提醒不会出现。若提醒不弹窗也没声音,再进「通知管理」把本应用的「通知重要性」设为「重要」。",
            done = notifOn,
            buttonText = "去开启",
            onOpen = {
                runCatching {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
            }
        )

        WizardStep(
            index = 3,
            title = "精确闹钟(准点提醒)",
            desc = "不开启的话,提醒时间可能被系统推迟几分钟到几十分钟。",
            done = exactAlarmOn,
            buttonText = "去开启",
            onOpen = {
                runCatching {
                    val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}"))
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    }
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        )

        WizardStep(
            index = 4,
            title = "后台运行 / 电池不优化",
            desc = "否则锁屏一段时间后,闹钟和监听都会被系统冻结。",
            done = batteryOk,
            buttonText = "去设置",
            onOpen = { openBatterySettings(context) }
        )

        WizardStep(
            index = 5,
            title = "全屏通知(闹钟提醒在锁屏弹出)",
            desc = "Android 14 起,闹钟要全屏响铃必须在系统里允许本应用的「全屏通知」。",
            done = null,
            buttonText = "去设置",
            onOpen = {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 34) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )

        WizardStep(
            index = 6,
            title = "自启动(小米/华为必开)",
            desc = "在「设置 → 应用设置 → 应用管理 → 日程助手 → 自启动」里打开;顺便把「省电策略」设为「无限制」。",
            done = null,
            buttonText = "去应用详情",
            onOpen = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun WizardStep(
    index: Int,
    title: String,
    desc: String,
    done: Boolean?,
    buttonText: String,
    onOpen: () -> Unit
) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            if (done == true) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(22.dp))
            } else if (done == false) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Orange, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    text = "$index",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    if (done == true) {
                        Text("已开启", style = MaterialTheme.typography.bodySmall, color = Green)
                    } else if (done == false) {
                        Text("未开启", style = MaterialTheme.typography.bodySmall, color = Orange)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpen) { Text(buttonText) }
            }
        }
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
            @Suppress("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } else {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 供列表页复用的"打开通知使用权"入口 */
fun openNotificationListenerSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * 打开本应用的系统详情页(权限管理)。
 * 用途:让用户去开 MIUI 的「全屏通知」权限 —— 不开的话「闹钟提醒」不会全屏弹出来
 * (实测:`USE_FULL_SCREEN_INTENT: ignore`)。
 */
fun openAppDetailSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
