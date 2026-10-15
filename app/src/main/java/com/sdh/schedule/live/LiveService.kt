package com.sdh.schedule.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sdh.schedule.MainActivity
import com.sdh.schedule.R
import com.sdh.schedule.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「实时听写」前台服务。
 *
 * 为什么必须是前台服务:
 *   - Android 9+ 息屏后不允许后台随便用麦克风;开了前台服务(通知栏常驻)才能一直听;
 *   - 也顺便满足 Android 14 对 `microphone` 类型前台服务的要求。
 *
 * 工作方式:
 *   系统语音识别(SpeechRecognizer)一次只识别一句话,结束后**立刻重新开始**,
 *   于是形成"连续听写";每完成一句就丢给 QuestionDetector 看是不是提问,
 *   是的话再丢给 LlmAsk 拿答案(界面里实时看到)。
 */
class LiveService : Service() {

    companion object {
        private const val TAG = "LiveService"
        const val ACTION_START = "com.sdh.schedule.live.START"
        const val ACTION_STOP = "com.sdh.schedule.live.STOP"
        private const val CH = "ch_live"
        private const val NOTIF_ID = 7101
        private const val RESTART_DELAY_MS = 350L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var shouldRun = false
    private var restartCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "实时听写", NotificationManager.IMPORTANCE_LOW).apply {
                description = "实时把语音转成文字并回答问题(常驻通知,可随时停止)"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setContentTitle("正在听写")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(R.drawable.ic_action_close, "停止", stop)
            .build()
    }

    private fun startListening() {
        if (shouldRun) return
        shouldRun = true
        LiveState.running.value = true

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            LiveState.status.value = "没有麦克风权限,无法听写"
            stopListening()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            LiveState.status.value = "这台手机没有可用的语音识别服务(系统未提供)。可以手动输入问题,同样能问答。"
            stopListening()
            return
        }

        startForeground(NOTIF_ID, notification("正在听…"))
        LiveState.status.value = ""
        createRecognizer()
        restartCount = 0
        listenOnce()
    }

    private fun createRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) LiveState.partial.value = text
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    LiveState.partial.value = ""
                    if (text.isNotEmpty()) {
                        LiveState.sentences.value = LiveState.sentences.value + text
                        handleNewText(text)
                    }
                    restartSoon()
                }

                override fun onError(error: Int) {
                    LiveState.partial.value = ""
                    when (error) {
                        // 没听清 / 静默超时 / 引擎忙 → 直接继续听,不打扰用户
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> restartSoon()
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            LiveState.status.value = "麦克风权限被拒绝"
                            stopListening()
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            LiveState.status.value = "语音识别需要网络(系统引擎),当前网络不可用"
                            restartSoon()
                        }
                        else -> restartSoon()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /** 一次识别结束就重新开一次,形成连续听写 */
    private fun restartSoon() {
        if (!shouldRun) return
        // 连续失败太多次就停下来,避免死循环烧电
        if (restartCount++ > 200) {
            LiveState.status.value = "语音识别连续失败,已停止"
            stopListening()
            return
        }
        handler.postDelayed({
            if (shouldRun) listenOnce()
        }, RESTART_DELAY_MS)
    }

    private fun listenOnce() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        runCatching { r.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening 失败", it)
                restartSoon()
            }
    }

    /** 新识别出一句 → 看是不是问题 → 是就问大模型 */
    private fun handleNewText(text: String) {
        val asked = LiveState.qa.value.map { it.question }
        val fresh = QuestionDetector.pickNewQuestions(text, asked)
        if (fresh.isEmpty()) return
        for (q in fresh) {
            val item = LiveState.Qa(question = q, thinking = true)
            LiveState.qa.value = LiveState.qa.value + item
            scope.launch {
                val answer = withContext(Dispatchers.IO) {
                    runCatching { LlmAsk.ask(applicationContext, q, LiveState.transcriptText()) }
                        .getOrElse { "出错了:${it.message}" }
                }
                LiveState.qa.value = LiveState.qa.value.map {
                    if (it.question == q && it.at == item.at) {
                        it.copy(answer = answer, thinking = false)
                    } else it
                }
                // 通知里显示最近一个问题(方便息屏时瞄一眼)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, notification("问:${q.take(18)}"))
            }
        }
    }

    private fun stopListening() {
        shouldRun = false
        LiveState.running.value = false
        LiveState.partial.value = ""
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        shouldRun = false
        LiveState.running.value = false
        scope.cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }
}
