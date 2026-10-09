@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdh.schedule.util.SelfCheck

/** 自检项 */
private typealias Check = SelfCheck.Check

/**
 * 功能自检:把"各项功能是否正常"直接显示出来。
 * 装机后点一下就能确认所有功能,不用靠猜。
 * 检查逻辑在 [SelfCheck] 里,和 adb 诊断广播共用同一份。
 */
@Composable
fun SelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val checks = remember { SelfCheck.run(context) }
    val okCount = checks.count { it.ok }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text("功能自检", style = MaterialTheme.typography.titleLarge)
                Text(
                    "通过 $okCount / ${checks.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (okCount == checks.size) Green else Orange
                )
            }
        }

        SectionCard {
            checks.forEachIndexed { i, c ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (c.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (c.ok) Green else Orange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = c.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (c.detail.isNotBlank()) {
                            Text(
                                text = c.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (i != checks.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(start = 44.dp),
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}


