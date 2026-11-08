package com.doudingnan.douyin

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.doudingnan.douyin.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var current: MediaItem? = null
    private var parsingId: String? = null
    private val logs = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setupWebView()

        binding.parseButton.setOnClickListener {
            startParse(binding.inputBox.text.toString())
        }
        binding.downloadButton.setOnClickListener { downloadVideo() }
        binding.bundleButton.setOnClickListener { downloadImages() }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (text.isBlank()) return
        binding.inputBox.setText(text)
        startParse(text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DouyinParser.DESKTOP_UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        binding.webView.addJavascriptInterface(Bridge(), "DyBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (url.contains("aweme/v1/web/aweme/detail")) return forwardDetail(request)
                if (request.isForMainFrame && url.startsWith("https://www.douyin.com/video/")) {
                    return injectHookIntoMainPage(url)
                }
                return null
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(HOOK_JS, null)
            }
        }
    }

    /** 把 hook 脚本直接插进主文档 HTML，保证早于抖音自己的脚本执行。 */
    private fun injectHookIntoMainPage(url: String): WebResourceResponse? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", DouyinParser.DESKTOP_UA)
                conn.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                conn.setRequestProperty("Accept-Encoding", "identity")
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { conn.setRequestProperty("Cookie", it) }

                if (conn.responseCode !in 200..299) {
                    appendLog("主文档 HTTP ${conn.responseCode}")
                    return null
                }
                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val script = "<script>$HOOK_JS</script>"
                val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
                val injected = if (headMatch != null) {
                    val end = headMatch.range.last + 1
                    html.substring(0, end) + script + html.substring(end)
                } else {
                    script + html
                }
                appendLog("主文档已注入 hook (${html.length} 字节)")
                WebResourceResponse("text/html", "UTF-8", injected.byteInputStream(Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            appendLog("注入失败: ${e.message}")
            null
        }
    }

    /** 用原生请求重放详情接口，拿到响应体后既解析又回填给 WebView。 */
    private fun forwardDetail(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = request.method.ifBlank { "GET" }
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                request.requestHeaders.forEach { (key, value) ->
                    runCatching { conn.setRequestProperty(key, value) }
                }
                if (conn.getRequestProperty("User-Agent").isNullOrEmpty()) {
                    conn.setRequestProperty("User-Agent", DouyinParser.DESKTOP_UA)
                }
                if (conn.getRequestProperty("Referer").isNullOrEmpty()) {
                    conn.setRequestProperty("Referer", "https://www.douyin.com/")
                }
                if (conn.getRequestProperty("Accept").isNullOrEmpty()) {
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                }
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { conn.setRequestProperty("Cookie", it) }

                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                DouyinParser.parseAwemeDetail(body)?.let { item ->
                    appendLog("详情接口命中")
                    runOnUiThread { onVideoParsed(item) }
                } ?: appendLog("详情接口无数据 (${body.length} 字节)")
                WebResourceResponse("application/json", "UTF-8", body.byteInputStream())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onDetail(json: String) {
            DouyinParser.parseAwemeDetail(json)?.let { item ->
                runOnUiThread { onVideoParsed(item) }
            }
        }
    }

    private fun startParse(text: String) {
        val raw = text.trim()
        if (raw.isEmpty()) {
            setStatus("请先粘贴抖音分享内容", true)
            return
        }
        val url = DouyinParser.extractUrl(raw)
        if (url == null) {
            setStatus("没有找到链接，请粘贴完整的分享内容", true)
            return
        }
        if (!url.contains("douyin.com", ignoreCase = true)) {
            setStatus("请粘贴抖音作品链接", true)
            return
        }

        current = null
        parsingId = null
        logs.clear()
        binding.resultCard.visibility = View.GONE
        setBusy(true)
        setStatus("解析中，请稍候…")

        lifecycleScope.launch {
            try {
                val expanded = withContext(Dispatchers.IO) { DouyinParser.expandShortUrl(url) }
                val (kind, id) = DouyinParser.detect(expanded)
                    ?: throw IllegalStateException("无法识别这个链接，请粘贴抖音作品分享内容")
                if (kind == "note") {
                    val item = withContext(Dispatchers.IO) { DouyinParser.parseNote(id) }
                    showResult(item)
                } else {
                    parseVideo(id)
                }
            } catch (e: Exception) {
                showError(e.message ?: "解析失败")
            }
        }
    }

    private fun parseVideo(videoId: String) {
        parsingId = videoId
        appendLog("解析视频 $videoId")
        setStatus("正在加载作品页…")
        binding.webView.loadUrl("https://www.douyin.com/video/$videoId")
        binding.webView.postDelayed({ fallbackFromPage(videoId) }, 20000)
    }

    private fun fallbackFromPage(videoId: String) {
        if (parsingId != videoId) return
        binding.webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):''})()"
        ) { raw ->
            if (parsingId != videoId) return@evaluateJavascript
            val src = raw?.trim('"').orEmpty()
            if (src.startsWith("http")) {
                onVideoParsed(MediaItem("video", "抖音视频", "", "", null, src, emptyList()))
            } else {
                parsingId = null
                setBusy(false)
                appendLog("页面兜底未取到视频地址")
                setStatus("视频解析失败：页面没有返回数据，请稍后重试", true)
            }
        }
    }

    private fun onVideoParsed(item: MediaItem) {
        parsingId = null
        showResult(item)
    }

    private fun showResult(item: MediaItem) {
        current = item
        setBusy(false)
        setStatus("")

        binding.titleText.text = item.title
        binding.authorText.text = buildString {
            if (item.author.isNotBlank()) append("@").append(item.author)
            if (item.type == "video" && item.durationSec != null) {
                if (isNotEmpty()) append(" · ")
                val seconds = item.durationSec.toInt()
                append(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60))
            }
            if (item.type == "images") {
                if (isNotEmpty()) append(" · ")
                append("${item.images.size} 张图片")
            }
        }

        val isVideo = item.type == "video"
        binding.downloadButton.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.downloadButton.text = "保存视频"
        binding.downloadButton.isEnabled = true
        binding.bundleButton.visibility = if (isVideo) View.GONE else View.VISIBLE
        binding.bundleButton.text = "保存全部图片"
        binding.bundleButton.isEnabled = true
        binding.coverImage.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.coverImage.setImageDrawable(null)

        binding.imageGrid.removeAllViews()
        if (isVideo) {
            if (item.cover.isNotBlank()) loadImage(item.cover, binding.coverImage)
        } else {
            renderImages(item)
        }

        binding.resultCard.visibility = View.VISIBLE
    }

    private fun renderImages(item: MediaItem) {
        val available = resources.displayMetrics.widthPixels - dp(36 + 28)
        val cellWidth = available / 2 - dp(10)
        val cellHeight = cellWidth * 4 / 3
        item.images.forEachIndexed { index, url ->
            val frame = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellWidth
                    height = cellHeight
                    setMargins(0, 0, dp(10), dp(10))
                }
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumb)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            val badge = TextView(this).apply {
                text = "保存"
                setTextColor(Color.WHITE)
                textSize = 12f
                setBackgroundColor(Color.parseColor("#CC0B0B0F"))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(6), 0, 0, dp(6))
                }
            }
            frame.addView(image)
            frame.addView(badge)
            frame.setOnClickListener { saveSingleImage(item, index, url) }
            binding.imageGrid.addView(frame)
            loadImage(url, image)
        }
    }

    private fun loadImage(url: String, target: ImageView) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.setRequestProperty("User-Agent", DouyinParser.DESKTOP_UA)
                        conn.setRequestProperty("Referer", "https://www.douyin.com/")
                        conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (bitmap != null) target.setImageBitmap(bitmap)
        }
    }

    private fun downloadVideo() {
        val item = current ?: return
        if (item.type != "video") return
        binding.downloadButton.isEnabled = false
        binding.downloadButton.text = "保存中… 0%"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Downloader.downloadVideo(this@MainActivity, item.videoUrl, item.title) { done, total ->
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            runOnUiThread { binding.downloadButton.text = "保存中… $percent%" }
                        }
                    }
                }
                binding.downloadButton.text = "已保存到 Movies/Douyin"
                setStatus("已保存到相册 Movies/Douyin")
            } catch (e: Exception) {
                binding.downloadButton.text = "保存视频"
                setStatus("保存失败：${e.message ?: "未知错误"}", true)
            } finally {
                binding.downloadButton.isEnabled = true
            }
        }
    }

    private fun downloadImages() {
        val item = current ?: return
        if (item.type != "images") return
        binding.bundleButton.isEnabled = false
        lifecycleScope.launch {
            var success = 0
            var failed = 0
            item.images.forEachIndexed { index, url ->
                binding.bundleButton.text = "保存中… ${index + 1}/${item.images.size}"
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        Downloader.downloadImage(
                            this@MainActivity, url, item.title + "_" + (index + 1)
                        )
                    }
                }
                if (result.isSuccess) success++ else failed++
            }
            binding.bundleButton.text = "保存全部图片"
            binding.bundleButton.isEnabled = true
            setStatus(
                if (failed == 0) "已保存 $success 张到 Pictures/Douyin"
                else "已保存 $success 张，失败 $failed 张",
                failed > 0
            )
        }
    }

    private fun saveSingleImage(item: MediaItem, index: Int, url: String) {
        setStatus("正在保存第 ${index + 1} 张…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Downloader.downloadImage(
                        this@MainActivity, url, item.title + "_" + (index + 1)
                    )
                }
            }
            setStatus(
                if (result.isSuccess) "已保存第 ${index + 1} 张到 Pictures/Douyin"
                else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                result.isFailure
            )
        }
    }

    private fun showError(message: String) {
        parsingId = null
        setBusy(false)
        setStatus(message, true)
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.parseButton.isEnabled = !busy
    }

    private fun setStatus(message: String, isError: Boolean = false) {
        val text = if (isError && logs.isNotEmpty()) {
            message + "\n\n--- 调试信息 ---\n" + logs.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
        } else {
            message
        }
        binding.statusText.text = text
        binding.statusText.setTextColor(
            if (isError) Color.parseColor("#FF7B8A")
            else ContextCompat.getColor(this, R.color.muted)
        )
    }

    private fun appendLog(message: String) {
        runOnUiThread { logs.append(message).append('\n') }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val HOOK_JS = """
(function () {
  if (window.__dyHooked) return;
  window.__dyHooked = true;
  function hit(url, text) {
    try {
      if (url && url.indexOf('aweme/v1/web/aweme/detail') >= 0) {
        window.DyBridge.onDetail(text);
      }
    } catch (e) {}
  }
  if (window.fetch) {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url);
      return originalFetch.apply(this, arguments).then(function (resp) {
        try { resp.clone().text().then(function (t) { hit(url, t); }).catch(function () {}); } catch (e) {}
        return resp;
      });
    };
  }
  var originalOpen = XMLHttpRequest.prototype.open;
  var originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__dyUrl = url;
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var self = this;
    this.addEventListener('load', function () {
      try { hit(self.__dyUrl, self.responseText); } catch (e) {}
    });
    return originalSend.apply(this, arguments);
  };
})();
"""
    }
}
