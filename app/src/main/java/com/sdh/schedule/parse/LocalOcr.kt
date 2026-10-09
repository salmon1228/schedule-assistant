package com.sdh.schedule.parse

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地中文 OCR(ML Kit 打包版,模型随 APK 一起装,不依赖 Google 服务、可完全离线)。
 *
 * 用途:
 *   - 图片类型的通知里带文字 → 直接在本机识别,不需要 API Key
 *   - PDF(含扫描件)渲染成页图后 → 本机识别
 *
 * 调用方都在后台线程,所以这里用阻塞式写法(CountDownLatch)。
 */
object LocalOcr {

    private const val TAG = "LocalOcr"
    private const val TIMEOUT_SECONDS = 25L

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun isAvailable(): Boolean = true

    /** OCR 出来的一个文字块(带坐标,用于还原表格/网格结构) */
    data class OcrBlock(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val width: Int,
        val height: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    fun recognize(bitmap: Bitmap): String? {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return null
        return try {
            val latch = CountDownLatch(1)
            var text: String? = null
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    text = result.text
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR 失败:${e.message}")
                    latch.countDown()
                }
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "OCR 超时")
                return null
            }
            text?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 异常", t)
            null
        }
    }

    /**
     * 带坐标的 OCR。
     *
     * 为什么需要坐标:课表截图是一张**网格**(行=节次、列=星期),
     * 而 `result.text` 只按阅读顺序给出文字 —— 星期表头和课程格子在文本上离得很远,
     * 解析器无法判断"这门课是星期几第几节"。有了坐标就能把网格还原出来。
     */
    fun recognizeWithBoxes(bitmap: Bitmap): List<OcrBlock> {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return emptyList()
        return try {
            val latch = CountDownLatch(1)
            var blocks: List<OcrBlock> = emptyList()
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val out = ArrayList<OcrBlock>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val t = line.text.trim()
                            if (t.isEmpty()) continue
                            out.add(
                                OcrBlock(
                                    text = t,
                                    left = box.left, top = box.top, right = box.right, bottom = box.bottom,
                                    width = box.width(), height = box.height()
                                )
                            )
                        }
                    }
                    blocks = out
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "带坐标 OCR 失败:${e.message}")
                    latch.countDown()
                }
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "带坐标 OCR 超时")
                return emptyList()
            }
            blocks
        } catch (t: Throwable) {
            Log.w(TAG, "带坐标 OCR 异常", t)
            emptyList()
        }
    }
}
