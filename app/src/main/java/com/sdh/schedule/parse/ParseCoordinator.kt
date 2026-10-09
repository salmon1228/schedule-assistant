package com.sdh.schedule.parse

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.sdh.schedule.data.EventSource
import com.sdh.schedule.data.Repo
import com.sdh.schedule.data.SettingsStore
import com.sdh.schedule.data.Suggestion
import com.sdh.schedule.notify.Notify
import java.io.File
import java.io.FileOutputStream

/**
 * 解析调度中心:决定「用本地规则还是用大模型」,并负责去重、建日程/建候选、通知、记日志。
 *
 * 引擎选择策略(对应设置里的"解析引擎: 自动"):
 *   自动模式 = 配了 API Key 且当前能联网 → 大模型;否则 → 本地规则。
 *   大模型调用失败(超时/欠费/限流)会**自动回退**本地规则,不会让日程凭空消失。
 *   图片:本地 OCR 可用则先 OCR 再按文本处理,否则用多模态大模型直接看图。
 */
object ParseCoordinator {

    private const val TAG = "ParseCoordinator"

    /** 低于该置信度的解析结果直接丢弃 */
    const val MIN_CONFIDENCE = 0.45

    data class Outcome(
        val events: List<ParsedEvent>,
        val engine: String,
        val note: String = "",
        /** 同一段文字里抽出来的待办 */
        val todos: List<com.sdh.schedule.data.ParsedTodo> = emptyList()
    )

    /** 处理结果:不仅告诉界面"建了几条",还要说清"为什么没建" */
    data class ProcessResult(
        val suggestionIds: List<Long> = emptyList(),
        val autoCreated: Int = 0,
        val engine: String = "none",
        val note: String = "",
        /** 本次自动加入的待办数量 */
        val todosAdded: Int = 0
    )

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 当前会使用哪个引擎。
     * 自动模式 = **优先云端大模型**,只有「没配 Key / 当前离线 / 请求失败」才回退本地规则。
     */
    fun enginePlan(context: Context, hasImage: Boolean): String {
        val s = Repo.settings
        val online = isNetworkAvailable(context)
        val llmReady = s.llmConfigured && online
        return when (s.llmMode) {
            SettingsStore.MODE_LOCAL_ONLY -> if (hasImage) "local_ocr" else "rule"
            SettingsStore.MODE_LLM_ONLY -> if (llmReady) "llm" else "none"
            // AUTO:云端优先;本地仅作兜底(无 Key / 离线 / 请求失败)
            else -> if (llmReady) "llm" else if (hasImage) "local_ocr" else "rule"
        }
    }

    fun engineLabelForUi(context: Context): String {
        val s = Repo.settings
        val online = isNetworkAvailable(context)
        return when {
            s.llmMode == SettingsStore.MODE_LOCAL_ONLY -> "本地规则(已强制)"
            s.llmMode == SettingsStore.MODE_LLM_ONLY && !s.llmConfigured -> "未配置 Key(强制大模型模式)"
            s.llmMode == SettingsStore.MODE_LLM_ONLY && !online -> "离线(强制大模型模式,当前不可用)"
            !s.llmConfigured -> "本地规则(未配置大模型 Key,配好后优先走云端)"
            !online -> "本地规则(当前离线,联网后优先走云端)"
            else -> "云端大模型(${s.llmModel})优先"
        }
    }

    // ---------------- 文本 ----------------

    /**
     * 文本里如果有链接,先把网页正文抓回来一起解析
     * (消息常常只给一个报名页/公告链接,时间信息在网页里)
     */
    private fun enrichWithLinks(context: Context, text: String): Pair<String, String> {
        if (!Repo.settings.fetchLinks) return text to ""
        val urls = LinkFetcher.findUrls(text)
        if (urls.isEmpty()) return text to ""
        if (!isNetworkAvailable(context)) return text to "有链接但当前离线,未抓取正文"
        val sb = StringBuilder(text)
        var ok = 0
        for (u in urls) {
            val page = LinkFetcher.fetchText(u) ?: continue
            ok++
            sb.append("\n\n【链接正文 ").append(u.take(120)).append("】\n").append(page)
        }
        return sb.toString() to if (ok > 0) "已抓取 $ok 个链接的正文" else "链接正文抓取失败"
    }

    fun parseText(context: Context, text: String): Outcome {
        val now = System.currentTimeMillis()
        val zone = Repo.zone
        val (enriched, linkNote) = enrichWithLinks(context, text)
        val base = parseTextInternal(context, enriched, now, zone)
        return if (linkNote.isBlank()) base else base.copy(
            note = listOf(base.note, linkNote).filter { it.isNotBlank() }.joinToString(" · ")
        )
    }

    private fun parseTextInternal(context: Context, text: String, now: Long, zone: java.time.ZoneId): Outcome {
        val plan = enginePlan(context, hasImage = false)
        if (plan == "llm") {
            // 云端优先:失败先重试一次,仍然失败才回退本地规则
            var lastError: String? = null
            repeat(2) { attempt ->
                try {
                    val result = LlmParser.parseText(context, Repo.settings, text, now, zone)
                    return Outcome(
                        result.events, "llm",
                        if (attempt > 0) "云端第 ${attempt + 1} 次尝试成功" else "",
                        result.todos
                    )
                } catch (t: Throwable) {
                    lastError = t.message
                    Log.w(TAG, "云端大模型解析失败(第 ${attempt + 1} 次)", t)
                    if (attempt == 0) runCatching { Thread.sleep(700) }
                }
            }
            val events = ChineseDateParser.parseAll(text, now, zone)
            return Outcome(events, "rule", "云端大模型请求失败,已回退本地规则($lastError)", TodoExtractor.extract(text, now, zone))
        }
        if (plan == "rule") {
            return Outcome(
                polishTitles(ChineseDateParser.parseAll(text, now, zone), text),
                "rule",
                "本地规则(云端不可用)",
                TodoExtractor.extract(text, now, zone)
            )
        }
        return Outcome(emptyList(), "none", "当前模式不允许解析(请检查大模型 Key 与网络)")
    }

    /** 只当得了标签的词(OCR 片段或只有一行的通知里,标题很容易被解析成这些) */
    private val LABEL_ONLY = setOf(
        "时间", "地点", "日期", "内容", "主题", "备注", "通知", "安排", "日程",
        "会议", "详情", "说明", "地址", "活动", "事项", "安排:", "时间:", "地点:"
    )

    /**
     * 本地规则很容易把「时间:明天下午3点半」这种 OCR 片段解析成一个叫"时间"的日程。
     * 这里做一次兜底:标题若是纯标签,就换成原文里第一句有实义的内容。
     */
    fun polishTitles(events: List<ParsedEvent>, rawText: String): List<ParsedEvent> =
        events.map { e ->
            val bare = e.title.trim().trimEnd('：', ':', '，', ',').trim()
            if (bare in LABEL_ONLY || bare.length <= 1) {
                val better = rawText.lines()
                    .map { it.trim() }
                    .firstOrNull { line ->
                        line.length in 2..40 &&
                            line.trimEnd('：', ':', '，', ',').trim() !in LABEL_ONLY &&
                            !line.matches(Regex("^[\\s\\d:：.\\-/年月日号点半分]+$"))
                    }
                e.copy(title = (better ?: "新日程").take(60))
            } else {
                e
            }
        }

    // ---------------- 图片 ----------------

    /**
     * 图片识别:**优先云端多模态大模型**,只有「请求失败 / 没配 Key / 离线」才用本机离线 OCR 兜底。
     */
    fun parseImage(context: Context, bitmap: Bitmap, hintText: String = ""): Outcome {        val now = System.currentTimeMillis()
        val zone = Repo.zone
        val s = Repo.settings
        val online = isNetworkAvailable(context)
        val plan = enginePlan(context, hasImage = true)
        var llmError: String? = null

        // 1) 云端优先
        if (plan == "llm") {
            repeat(2) { attempt ->
                try {
                    val result = LlmParser.parseImage(context, s, bitmap, hintText, now, zone)
                    return Outcome(
                        result.events, "llm",
                        "多模态大模型" + if (attempt > 0) "(第 ${attempt + 1} 次尝试成功)" else "",
                        result.todos
                    )
                } catch (t: Throwable) {
                    llmError = t.message
                    Log.w(TAG, "云端图片识别失败(第 ${attempt + 1} 次)", t)
                    if (attempt == 0) runCatching { Thread.sleep(700) }
                }
            }
        }

        // 2) 云端失败/不可用 → 本机离线 OCR 兜底(强制大模型模式除外)
        val allowLocalFallback = s.llmMode != SettingsStore.MODE_LLM_ONLY
        if (allowLocalFallback && LocalOcr.isAvailable()) {
            val ocrText = LocalOcr.recognize(bitmap)
            if (!ocrText.isNullOrBlank()) {
                val merged = if (hintText.isBlank()) ocrText else "$hintText\n$ocrText"
                val events = polishTitles(ChineseDateParser.parseAll(merged, now, zone), merged)
                val why = when {
                    llmError != null -> "云端识别失败,已用本机 OCR + 本地规则兜底($llmError)"
                    !s.llmConfigured -> "未配置大模型 Key,用本机 OCR + 本地规则"
                    !online -> "当前离线,用本机 OCR + 本地规则"
                    else -> "用本机 OCR + 本地规则"
                }
                return Outcome(events, "rule", why, TodoExtractor.extract(merged, now, zone))
            }
        }

        // 3) 都不行,给出明确原因
        val reason = when {
            s.llmMode == SettingsStore.MODE_LLM_ONLY && !s.llmConfigured -> "强制大模型模式,但还没配置 API Key"
            s.llmMode == SettingsStore.MODE_LLM_ONLY && !online -> "强制大模型模式,但当前离线"
            llmError != null -> "云端识别失败:$llmError"
            !s.llmConfigured -> "图片识别需要配置大模型 Key"
            !online -> "图片识别需要联网"
            else -> "图片里没识别出可用的文字信息"
        }
        return Outcome(emptyList(), "none", reason)
    }

    // ---------------- 文档(Word / PDF / Excel / PPT / txt) ----------------

    /**
     * 处理用户分享进来的文档。
     * 文字类(docx/txt/pptx…)直接抽正文;PDF 渲染成图片走多模态识别(扫描件也能认)。
     */
    fun processDocument(
        context: Context,
        uri: android.net.Uri,
        mime: String?,
        name: String?
    ): ProcessResult {
        Repo.init(context)
        Notify.ensureChannels(context)
        val label = name?.ifBlank { null } ?: "文档"
        val doc = DocumentText.extract(context, uri, mime, name)

        if (doc.text.isBlank() && doc.pages.isEmpty()) {
            Repo.log(EventSource.IMPORT, label, "", "none", "文档没有可用内容", doc.note)
            return ProcessResult(note = doc.note.ifBlank { "这个文件里没读到内容" })
        }

        val ids = ArrayList<Long>()
        var autoCreated = 0
        var engine = "none"
        val notes = ArrayList<String>()
        if (doc.note.isNotBlank()) notes.add(doc.note)

        if (doc.text.isNotBlank()) {
            val r = process(
                context = context,
                source = EventSource.IMPORT,
                sourcePackage = label,
                text = doc.text,
                bitmap = null
            )
            ids.addAll(r.suggestionIds)
            autoCreated += r.autoCreated
            if (r.engine != "none") engine = r.engine
            if (r.note.isNotBlank()) notes.add(r.note)
        }

        if (doc.pages.isNotEmpty()) {
            // 没有本地 OCR 也没配大模型 Key 时,提前说清楚,别让用户白等
            if (!Repo.settings.llmConfigured && !LocalOcr.isAvailable()) {
                notes.add("PDF 是扫描件,需要大模型 Key 或本地 OCR 才能识别")
                Repo.log(EventSource.IMPORT, label, "", "none", "PDF 需要 OCR 或大模型", doc.note)
                return ProcessResult(ids, autoCreated, engine, notes.joinToString(" · "))
            }
            doc.pages.forEachIndexed { index, page ->
                val r = process(
                    context = context,
                    source = EventSource.IMPORT,
                    sourcePackage = "$label 第${index + 1}页",
                    text = "请识别这一页里的日程信息(来自 $label 第${index + 1}页)",
                    bitmap = page,
                    imagePath = ""
                )
                ids.addAll(r.suggestionIds)
                autoCreated += r.autoCreated
                if (r.engine != "none") engine = r.engine
            }
            if (ids.isEmpty() && autoCreated == 0) notes.add("PDF 页面里没有识别出明确的时间信息")
        }

        return ProcessResult(ids, autoCreated, engine, notes.filter { it.isNotBlank() }.joinToString(" · "))
    }

    // ---------------- 一条素材的完整处理流程 ----------------

    /**
     * 处理一条来自通知/分享/截图的素材。
     * 返回本次产生的「待确认日程」id 列表(自动建成的日程不在其中)。
     */
    fun process(
        context: Context,
        source: EventSource,
        sourcePackage: String,
        text: String,
        bitmap: Bitmap? = null,
        imagePath: String = ""
    ): ProcessResult {
        Repo.init(context)
        Notify.ensureChannels(context)
        val clean = sanitize(text)
        val outcome = if (bitmap != null) parseImage(context, bitmap, clean) else parseText(context, clean)

        // 待办:不管有没有日程,都先把"要做的事"加进清单(低风险,可一键撤销)
        val todosAdded = saveTodos(context, source, sourcePackage, clean, outcome.todos, outcome.engine)

        if (outcome.events.isEmpty()) {
            val reason = when {
                todosAdded > 0 -> "已加入 $todosAdded 条待办"
                else -> outcome.note.ifBlank { "这段内容里没有可识别的时间信息" }
            }
            Repo.log(source, sourcePackage, clean, outcome.engine, if (todosAdded > 0) "已加待办" else "无日程", reason)
            return ProcessResult(engine = outcome.engine, note = reason, todosAdded = todosAdded)
        }

        // 同一条内容短时间内不重复提示(通知经常被更新)
        if (clean.isNotBlank() && Repo.isDuplicateText(clean)) {
            Repo.log(source, sourcePackage, clean, outcome.engine, "重复忽略")
            return ProcessResult(engine = outcome.engine, note = "这条内容刚刚已经识别过了")
        }

        val savedImagePath = if (bitmap != null && imagePath.isBlank()) saveImage(context, bitmap) else imagePath
        val now = System.currentTimeMillis()
        val ids = ArrayList<Long>()
        var autoCreated = 0
        val autoThreshold = Repo.settings.autoCreateThreshold.toDouble()
        val autoCreate = Repo.settings.autoCreate
        // 原文里的链接保留进备注,方便回头点开(解析器会把 URL 当噪声去掉)
        val urls = LinkFetcher.findUrls(clean)

        for (parsed in outcome.events.take(3)) {
            // 已经过去很久的直接忽略(留 6 小时容错,覆盖"昨天深夜的通知")
            if (parsed.startMillis < now - 6 * 3600_000L) continue
            if (parsed.confidence < MIN_CONFIDENCE) continue

            val noteWithLink = listOf(
                parsed.notes,
                if (urls.isNotEmpty()) "链接:" + urls.first() else ""
            ).filter { it.isNotBlank() }.joinToString("\n")

            val suggestion = Suggestion(
                title = parsed.title,
                note = noteWithLink,
                location = parsed.location,
                startMillis = parsed.startMillis,
                endMillis = parsed.endMillis,
                allDay = parsed.allDay,
                repeat = parsed.repeat,
                repeatWeekday = parsed.repeatWeekday,
                reminderMinutesBefore = parsed.reminderMinutesBefore,
                confidence = parsed.confidence,
                engine = outcome.engine,
                source = source,
                sourceText = clean,
                sourcePackage = sourcePackage,
                imagePath = savedImagePath
            )

            if (autoCreate && parsed.confidence >= autoThreshold) {
                Repo.addEvent(suggestion.toEvent(Repo.settings.defaultAccount, Repo.settings.defaultReminderMinutes))
                autoCreated++
                Repo.log(source, sourcePackage, clean, outcome.engine, "自动加入日程", "${parsed.title} 置信度=${"%.2f".format(parsed.confidence)}")
            } else {
                val id = Repo.addSuggestion(suggestion)
                ids.add(id)
                if (Repo.settings.notifySuggestion) Notify.showSuggestion(context, suggestion, id)
                Repo.log(source, sourcePackage, clean, outcome.engine, "待确认", "${parsed.title} 置信度=${"%.2f".format(parsed.confidence)}")
            }
        }
        if (ids.isEmpty() && autoCreated == 0) {
            Repo.log(source, sourcePackage, clean, outcome.engine, "已忽略", outcome.note)
            return ProcessResult(
                engine = outcome.engine,
                note = outcome.note.ifBlank {
                    if (todosAdded > 0) "已加入 $todosAdded 条待办" else "识别到的时间已经过去了"
                },
                todosAdded = todosAdded
            )
        }
        return ProcessResult(ids, autoCreated, outcome.engine, outcome.note, todosAdded)
    }

    /**
     * 待办抽取的来源白名单:聊天 / 工作类。
     * 实测放开抽取时误报主要来自电商(确认收货)、天气(注意防范)、地图(路况提示),
     * 而真正有用的待办几乎都来自钉钉/微信/短信这类"有人跟我说了件事"的通知。
     */
    private val TODO_SOURCE_WHITELIST = listOf(
        "com.alibaba.android.rimet",   // 钉钉
        "com.tencent.mm",              // 微信
        "com.tencent.mobileqq",        // QQ
        "com.tencent.tim",             // TIM
        "com.tencent.wework",          // 企业微信
        "com.ss.android.lark",         // 飞书
        "com.android.mms",             // 短信
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "cn.com.yunma.school.app",     // 常用校园 App
        "com.tencent.androidqqmail",   // QQ 邮箱
        "com.android.email",
        "com.google.android.gm"
    )

    /**
     * 落库待办:过滤低置信度、非聊天来源、重复,直接加入待办清单并给一条带「撤销」的通知。
     * 待办风险低(只是清单里多一行),所以不走去「待确认」那套流程。
     */
    private fun saveTodos(
        context: Context,
        source: EventSource,
        sourcePackage: String,
        rawText: String,
        todos: List<com.sdh.schedule.data.ParsedTodo>,
        engine: String
    ): Int {
        if (todos.isEmpty()) return 0
        val settings = Repo.settings

        // 1) 来源限制:默认只从聊天/工作类通知里抽
        if (settings.todoOnlyFromChatApps) {
            val pkg = sourcePackage.substringBefore('(').trim()
            val allowed = TODO_SOURCE_WHITELIST.any { pkg == it || pkg.startsWith(it) }
            if (!allowed) {
                Log.i(TAG, "待办来源不在白名单,跳过:$sourcePackage")
                return 0
            }
        }

        // 2) 同一个 App 每天最多加几条
        val cap = settings.todoDailyCapPerApp
        if (cap > 0) {
            val since = System.currentTimeMillis() - 24 * 3600_000L
            val pkg = sourcePackage.substringBefore('(').trim()
            val todayCount = Repo.todos.value.count {
                it.sourcePackage.startsWith(pkg) && it.createdAt >= since
            }
            if (todayCount >= cap) {
                Log.i(TAG, "该来源今天已加 $todayCount 条待办,达到上限($cap),跳过:$sourcePackage")
                return 0
            }
        }

        var added = 0
        for (t in todos.take(com.sdh.schedule.parse.TodoExtractor.MAX_PER_TEXT)) {
            if (t.confidence < 0.6) continue
            val title = t.title.trim().take(60)
            if (title.length < 2) continue
            if (Repo.hasRecentTodo(title)) continue
            val todo = com.sdh.schedule.data.Todo(
                title = title,
                note = t.note,
                dueMillis = t.dueMillis,
                source = source,
                sourceText = rawText,
                sourcePackage = sourcePackage
            )
            val id = Repo.addTodo(todo)
            added++
            if (settings.notifySuggestion) Notify.showTodoAdded(context, todo, id)
            Repo.log(source, sourcePackage, rawText, engine, "已加待办", title)
        }
        return added
    }

    /** 清掉通知里的常见噪声前缀,避免污染标题 */
    fun sanitize(raw: String): String {
        var s = raw.trim()
        // 去掉开头的 [群名] / 【通知】 这类前缀(后面还有正文时才去)
        val prefix = Regex("^[\\[【(（][^\\]】)）]{1,20}[\\]】)）]\\s*")
        val m = prefix.find(s)
        if (m != null && s.length - m.value.length > 6) s = s.substring(m.value.length)
        // 去掉 "张三: " 这种发送者前缀
        val sender = Regex("^[\\u4e00-\\u9fa5A-Za-z0-9_\\-]{1,16}[:：]\\s*")
        val m2 = sender.find(s)
        if (m2 != null && s.length - m2.value.length > 8) s = s.substring(m2.value.length)
        return s.trim()
    }

    private fun saveImage(context: Context, bitmap: Bitmap): String {
        return try {
            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val f = File(dir, "src_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "保存图片失败", t)
            ""
        }
    }
}
