package com.sdh.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.sdh.schedule.data.Repo
import com.sdh.schedule.ui.AppRoot
import com.sdh.schedule.ui.AppTheme
import com.sdh.schedule.ui.NavTarget

class MainActivity : ComponentActivity() {

    /** 通知点进来的跳转目标;界面消费后会被清空(null) */
    private var navTarget by mutableStateOf<NavTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(applicationContext)
        askNotificationPermission()
        runCatching { Repo.rescheduleAll() }

        navTarget = parseNav(intent)

        setContent {
            val revision by Repo.settings.revision.collectAsState()
            // 用 revision 触发重组,让深色模式切换立刻生效
            val darkMode = if (revision >= 0) Repo.settings.darkMode else "system"
            AppTheme(darkMode = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(
                        navTarget = navTarget,
                        onNavHandled = { navTarget = null }
                    )
                }
            }
        }
    }

    /** App 已在后台时点通知走这里 —— 必须重新解析并更新状态,否则跳转会丢掉 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navTarget = parseNav(intent)
    }

    private fun parseNav(intent: Intent?): NavTarget? {
        if (intent == null) return null
        if (intent.getBooleanExtra(EXTRA_OPEN_SUGGESTIONS, false)) return NavTarget.OpenSuggestions
        val id = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, 0L)
        return if (id > 0L) NavTarget.OpenEvent(id) else null
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    companion object {
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
        const val EXTRA_OPEN_SUGGESTIONS = "open_suggestions"
    }
}
