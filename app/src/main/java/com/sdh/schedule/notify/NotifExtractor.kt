package com.sdh.schedule.notify

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/** 从一条通知里抽出来的原始素材 */
data class NotifContent(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val bitmap: Bitmap?,
    val imageUri: Uri?,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean,
    val key: String,
    val postTime: Long
) {
    val fullText: String
        get() = buildString {
            if (title.isNotBlank()) append(title)
            if (body.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(body)
            }
        }.trim()

    fun textHash(): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(fullText.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 通知内容抽取。要尽可能把"大段文字"和"图片"都抠出来:
 * 标题 / 正文 / BigText / 多行文本 / 聊天消息(含图片消息) / 大图。
 */
object NotifExtractor {

    fun extract(context: Context, sbn: StatusBarNotification): NotifContent? {
        val n: Notification = sbn.notification ?: return null
        val extras: Bundle = n.extras ?: Bundle.EMPTY

        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val builder = ArrayList<String>()
        if (bigText.isNotBlank()) {
            builder.add(bigText)
        } else if (text.isNotBlank()) {
            builder.add(text)
        }
        if (subText.isNotBlank() && subText != title) builder.add(subText)

        // 多行文本样式
        extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.forEach { line ->
            val s = line?.toString().orEmpty().trim()
            if (s.isNotEmpty()) builder.add(s)
        }

        // 聊天消息(MessagingStyle):正文 + 可能的图片消息
        var imageUri: Uri? = null
        val messages = extras.getParcelableArray(NotificationCompat.EXTRA_MESSAGES)
        if (messages != null) {
            for (p in messages) {
                val b = p as? Bundle ?: continue
                val msgText = b.getCharSequence("text")?.toString().orEmpty().trim()
                if (msgText.isNotEmpty()) builder.add(msgText)
                val sender = b.getCharSequence("sender")?.toString().orEmpty()
                if (sender.isNotEmpty() && msgText.isNotEmpty()) builder.add("(来自$sender)")
                val uri = firstUri(b)
                if (uri != null && imageUri == null) imageUri = uri
            }
        }

        // 大图(聊天图片通知 / 截图通知通常放在 android.picture)
        val picture: Bitmap? = extras.getParcelable(NotificationCompat.EXTRA_PICTURE)
        var bitmap = picture

        if (bitmap == null) {
            // 有些 ROM 把图放在大图标里
            val largeIcon: Bitmap? = extras.getParcelable(NotificationCompat.EXTRA_LARGE_ICON_BIG)
            if (largeIcon != null && !largeIcon.isRecycled) bitmap = largeIcon
        }

        if (imageUri == null) imageUri = scanForImageUri(extras)

        val body = builder.joinToString("\n").trim()

        return NotifContent(
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title.trim(),
            body = body,
            bitmap = bitmap,
            imageUri = imageUri,
            isOngoing = sbn.isOngoing,
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            postTime = sbn.postTime
        )
    }

    /** 在 Bundle 里找第一个 Uri(image/type 相关) */
    private fun firstUri(bundle: Bundle): Uri? {
        for (key in bundle.keySet()) {
            if (key != "uri" && key != "type" && key != "mimeType" && key != "dataUri") continue
            val v = bundle.get(key)
            if (v is Uri) return v
            if (v is String && v.startsWith("content://")) return Uri.parse(v)
        }
        return null
    }

    /** 兜底:扫描 extras 里所有看起来像图片的 Uri */
    private fun scanForImageUri(extras: Bundle): Uri? {
        for (key in extras.keySet()) {
            if (key != "android.imageUri" && !key.contains("imageUri", ignoreCase = true)) continue
            val v = extras.get(key)
            if (v is Uri) return v
            if (v is String && v.startsWith("content://")) return Uri.parse(v)
        }
        return null
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(ai).toString()
    } catch (_: Throwable) {
        pkg
    }
}
