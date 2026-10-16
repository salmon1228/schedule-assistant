@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sdh.schedule.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.viewinterop.AndroidView
import com.sdh.schedule.data.Repo
import com.sdh.schedule.parse.CourseExtractor
import com.sdh.schedule.parse.CourseTextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从学校教务系统导入课表(内置浏览器,由用户自己登录)。
 *
 * ⚠️ WebView 的每一项配置都不是随便写的 —— 参考了 WakeUp课程表 逆向出来的可用配置:
 *   ① `setSupportMultipleWindows(true)` —— **必须允许弹窗**。
 *      很多教务/统一身份认证门户用 window.open 打开子页(WebVPN 门户尤其如此),
 *      早期版本这里设成了 false,结果页面一直白屏(实测踩过)。
 *   ② **写死桌面版 UA** —— 教务系统多为桌面布局,手机 UA 会被门户判定为不支持的设备。
 *   ③ `mixedContentMode = ALWAYS_ALLOW` —— 教务系统 http/https 混用很常见,默认会被拦。
 *   ④ 第三方 Cookie —— 统一身份认证(SSO)跳转依赖它。
 *   ⑤ `onCreateWindow` 里把新窗口接回当前 WebView,否则点了"登录"什么也不会发生。
 *
 * 账号密码只在你和学校网站之间,App 不接触、不保存。
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/99.0.4844.88 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CourseWebImportScreen(
    onBack: () -> Unit,
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = Repo.settings

    var urlInput by remember { mutableStateOf(appSettings.eduSystemUrl.ifBlank { SUGGESTED_URL }) }
    var currentUrl by remember { mutableStateOf(urlInput) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<List<CourseTextParser.ParsedCourse>?>(null) }
    var rawPreview by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    /** 从当前页面抓课表:多策略(最大表格 → 关键词区块 → 整个 body 文本),再交给解析器 */
    fun extractFromPage() {
        val w = webRef ?: return
        busy = true
        status = "正在读取页面…"
        w.evaluateJavascript(EXTRACT_JS) { result ->
            val text = runCatching {
                org.json.JSONTokener(result).nextValue().let { if (it is String) it else result }
            }.getOrElse { result }
                .replace("\\n", "\n").replace("\\u003c", "<").trim('"')
            rawPreview = text
            if (text.isBlank()) {
                status = "没抓到内容 —— 页面可能还没加载完,或者需要先登录。可以点「看抓到的内容」确认"
                busy = false
                return@evaluateJavascript
            }
            scope.launch {
                val r = withContext(Dispatchers.IO) { CourseExtractor.fromText(context, text) }
                parsed = r.courses
                status = if (r.courses.isEmpty()) {
                    "没解析出课程:${r.note}"
                } else {
                    "识别到 ${r.courses.size} 门课 · ${r.note}"
                }
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        // ---- 顶栏:返回 + 网址 + 打开 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回")
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("学校教务系统网址") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                val u = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                appSettings.eduSystemUrl = u
                currentUrl = u
                pendingUrl = u
            }) { Text("打开") }
        }

        // ---- 工具行 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (loading) "加载中…" else currentUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = { webRef?.reload() }) { Text("刷新") }
            TextButton(onClick = {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(currentUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                status = "已交给系统浏览器。看完课表后截图 → 回课程表页 → 导入 → 相册图片"
            }) { Text("系统浏览器") }
        }

        // ---- 操作提示 ----
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    "① 在下面登录教务系统(账号密码只在你和学校之间)  ② 打开「我的课表」  ③ 点下面的「提取这页课表」",
                    style = MaterialTheme.typography.bodySmall
                )
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (parsed.isNullOrEmpty() && !busy) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 网页:独占剩余空间 ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .heightIn(min = 200.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)
                        // ★ 关键三条(见文件头注释)
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = DESKTOP_UA
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                // 门户用 window.open 开子页时,把内容接回当前 WebView
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = this@apply
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                if (url != null) {
                                    currentUrl = url
                                    urlInput = url
                                }
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                loading = false
                                if (url != null) {
                                    currentUrl = url
                                    urlInput = url
                                }
                                // 页面加载完但内容为空 → 明确告诉用户,别只看到白屏
                                v?.evaluateJavascript(
                                    "(document.body && document.body.innerText ? document.body.innerText.trim().length : 0)"
                                ) { n ->
                                    val len = n?.trim('"')?.toIntOrNull() ?: 0
                                    if (len < 10 && status.isBlank()) {
                                        status = "页面看着是空的:可能还没登录、被 WebVPN 拦了,或需要弹窗(已开启)。" +
                                            "可以点「系统浏览器」用手机浏览器打开,再截图用「相册图片」导入"
                                    }
                                }
                            }

                            override fun onReceivedError(
                                v: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    status = "打不开:${error?.description ?: "未知错误"}。" +
                                        "学校若走 VPN,可以点「系统浏览器」在浏览器里看课表"
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                v: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                        webRef = this
                    }
                },
                update = { w ->
                    pendingUrl?.let { u ->
                        pendingUrl = null
                        w.loadUrl(u)
                    }
                }
            )
        }

        // ---- 底部:提取 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { extractFromPage() },
                enabled = !busy && !loading,
                modifier = Modifier.weight(1f)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("提取这页课表")
            }
            TextButton(onClick = { showRaw = true }, enabled = rawPreview.isNotBlank()) { Text("看抓到的内容") }
        }

        // ---- 识别结果:预览 + 覆盖/追加 ----
        parsed?.takeIf { it.isNotEmpty() }?.let { list ->
            Surface(
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "识别结果(${list.size} 门)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 130.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        list.forEach { c ->
                            Text(
                                "周${c.weekday} 第${c.startSlot}-${c.startSlot + c.slotCount - 1}节 · ${c.name}" +
                                    (if (c.location.isNotBlank()) " @${c.location}" else ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "写入方式:覆盖 = 先清空现有 ${Repo.courses.value.size} 门课;追加 = 保留现有课程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val n = importParsedCourses(list, replace = true)
                            onDone("已覆盖原课表,从教务系统导入 $n 门课")
                        }) { Text("覆盖原课表(${list.size} 门)") }
                        OutlinedButton(onClick = {
                            val n = importParsedCourses(list, replace = false)
                            onDone("已追加 $n 门课")
                        }) { Text("追加") }
                    }
                }
            }
        }
    }

    if (showRaw) {
        AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("抓到的内容(前 2000 字)") },
            text = { Text(rawPreview.take(2000), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showRaw = false }) { Text("知道了") } }
        )
    }
}

/** 默认打开的网址(可在设置里改) */
private const val SUGGESTED_URL = "https://jw.hziee.edu.cn/"

/**
 * 页面提取脚本:多策略,哪个能拿到内容用哪个
 *   ① 页面上最大的 table(保留行列/合并单元格信息,课表通常就是它)
 *   ② class/id 里带 course/kb/timetable/schedule 等关键词的区块
 *   ③ 整个 body 的文本兜底
 */
private const val EXTRACT_JS = """
(function () {
  try {
    function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
    // ① 最大表格
    var tables = Array.prototype.slice.call(document.querySelectorAll('table'));
    if (tables.length) {
      var best = null, bestLen = 0;
      tables.forEach(function (t) {
        var txt = (t.innerText || '').length;
        if (txt > bestLen) { bestLen = txt; best = t; }
      });
      if (best && bestLen > 30) {
        var lines = [];
        var rows = best.querySelectorAll('tr');
        for (var i = 0; i < rows.length; i++) {
          var cells = rows[i].querySelectorAll('td,th');
          var arr = [];
          for (var j = 0; j < cells.length; j++) arr.push(clean(cells[j].innerText));
          if (arr.join('').length > 1) lines.push(arr.join(' | '));
        }
        if (lines.length > 2) return ' [表格] ' + lines.join('\n');
      }
    }
    // ② 关键词区块
    var kw = ['course', 'kebiao', 'kb-', 'timetable', 'schedule', 'curriculum', 'lesson', 'grid', 'week', 'skb', 'jxk', 'pkb'];
    var nodes = document.querySelectorAll('div,section,ul,article');
    var bestNode = null, bestScore = 0;
    nodes.forEach(function (n) {
      var idc = ((n.id || '') + ' ' + (n.className || '')).toLowerCase();
      var score = 0;
      kw.forEach(function (k) { if (idc.indexOf(k) >= 0) score += 10; });
      var len = (n.innerText || '').length;
      if (len > 60 && len < 20000) score += 1;
      if (score > bestScore) { bestScore = score; bestNode = n; }
    });
    if (bestNode && bestScore >= 10) return ' [区块] ' + clean(bestNode.innerText).replace(/\n{2,}/g, '\n');
    // ③ 兜底
    var body = document.body ? document.body.innerText : '';
    return ' [整页] ' + (body || '');
  } catch (e) {
    return ' [出错] ' + e;
  }
})()
"""
