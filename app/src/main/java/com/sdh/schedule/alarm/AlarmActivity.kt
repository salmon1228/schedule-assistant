package com.sdh.schedule.alarm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sdh.schedule.data.Repo
import com.sdh.schedule.notify.Notify

/** 闹钟提醒:全屏响铃页面,关掉才停 */
class AlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHEN_TEXT = "when_text"
        private const val AUTO_STOP_MS = 5 * 60_000L
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var savedAlarmVolume: Int = -1
    private val handler = Handler(Looper.getMainLooper())

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        Repo.init(applicationContext)

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "日程提醒" }
        val whenText = intent.getStringExtra(EXTRA_WHEN_TEXT).orEmpty()

        startRinging()

        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(ActionReceiver.ACTION_STOP_ALARM_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 兜底:5 分钟后自动停,避免一直响
        handler.postDelayed({ finish() }, AUTO_STOP_MS)

        setContent {
            MaterialTheme {
                AlarmScreen(
                    title = title,
                    whenText = whenText,
                    onClose = {
                        stopRinging()
                        if (eventId > 0L) Notify.cancelAlarm(this, eventId)
                        finish()
                    },
                    onSnooze = {
                        stopRinging()
                        if (eventId > 0L) {
                            Repo.snooze(eventId, 10)
                            Notify.cancelAlarm(this, eventId)
                            Notify.cancelReminder(this, eventId)
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startRinging() {
        val am = getSystemService(AudioManager::class.java)
        // 闹钟音量太小就临时拉满(退出时还原)
        try {
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
                if (cur < max) {
                    savedAlarmVolume = cur
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (_: Throwable) {
        }

        try {
            vibrator = getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 700, 500)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (_: Throwable) {
        }
    }

    private fun stopRinging() {
        runCatching { if (ringtone?.isPlaying == true) ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        try {
            if (savedAlarmVolume >= 0) {
                val am = getSystemService(AudioManager::class.java)
                am?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stopReceiver) }
        stopRinging()
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    title: String,
    whenText: String,
    onClose: () -> Unit,
    onSnooze: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101426)) {
        Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "⏰", fontSize = 64.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (whenText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(text = whenText, color = Color(0xFF9AA7D9), fontSize = 17.sp)
                }
                Spacer(Modifier.height(48.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("稍后10分钟")
                    }
                    Button(
                        onClick = onClose,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4361EE))
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
