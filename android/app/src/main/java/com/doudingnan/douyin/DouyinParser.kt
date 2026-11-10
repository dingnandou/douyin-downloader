package com.doudingnan.douyin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MediaItem(
    val type: String,          // "video" | "images"
    val title: String,
    val author: String,
    val cover: String,
    val durationSec: Double?,
    val videoUrl: String,
    val images: List<String>,
)

object DouyinParser {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"
    const val GOOGLEBOT_UA =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val URL_RE = Regex("""https?://[^\s　，,。；;）)】]+""", RegexOption.IGNORE_CASE)
    private val ITEM_RE =
        Regex("""(?:douyin\.com|iesdouyin\.com)/(?:share/)?(note|video)/(\d+)""", RegexOption.IGNORE_CASE)
    private val LD_JSON_RE = Regex(
        """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun extractUrl(text: String): String? =
        URL_RE.find(text)?.value?.trimEnd('，', ',', '。', '；', ';', '）', ')', '】', ']', '.')

    fun expandShortUrl(url: String): String {
        var current = url
        if (!current.contains("v.douyin.com")) return current
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", DESKTOP_UA)
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    fun detect(url: String): Pair<String, String>? {
        val match = ITEM_RE.find(url) ?: return null
        return match.groupValues[1].lowercase() to match.groupValues[2]
    }

    fun parseNote(noteId: String): MediaItem {
        val conn = URL("https://www.douyin.com/note/$noteId").openConnection() as HttpURLConnection
        val html = try {
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", GOOGLEBOT_UA)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        for (match in LD_JSON_RE.findAll(html)) {
            val obj = runCatching { JSONObject(match.groupValues[1]) }.getOrNull() ?: continue
            if (obj.optString("@type") != "article") continue
            val imageArray = obj.optJSONArray("image") ?: continue
            val images = (0 until imageArray.length())
                .mapNotNull { i -> imageArray.optString(i).takeIf { it.isNotBlank() } }
            if (images.isEmpty()) continue
            return MediaItem(
                type = "images",
                title = obj.optString("headline").ifBlank { "抖音图文" }.trim(),
                author = obj.optJSONObject("author")?.optString("name").orEmpty(),
                cover = images.first(),
                durationSec = null,
                videoUrl = "",
                images = images,
            )
        }
        throw IllegalStateException("图文解析失败：页面里没有找到图片数据")
    }

    fun parseAwemeDetail(detailJson: String): MediaItem? {
        val root = runCatching { JSONObject(detailJson) }.getOrNull() ?: return null
        val detail = root.optJSONObject("aweme_detail") ?: return null
        val desc = detail.optString("desc").trim()
        val author = detail.optJSONObject("author")?.optString("nickname").orEmpty()

        detail.optJSONArray("images")?.let { arr ->
            if (arr.length() > 0) {
                val images = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optJSONArray("url_list")?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                }
                if (images.isNotEmpty()) {
                    return MediaItem(
                        "images", desc.ifBlank { "抖音图文" }, author,
                        images.first(), null, "", images
                    )
                }
            }
        }

        val video = detail.optJSONObject("video") ?: return null
        val playUrl = sequenceOf("play_addr_h264", "play_addr", "download_addr")
            .mapNotNull { key ->
                video.optJSONObject(key)?.optJSONArray("url_list")?.optString(0)
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull() ?: return null
        val cover = video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
        val duration = video.optDouble("duration", 0.0) / 1000.0
        return MediaItem(
            type = "video",
            title = desc.ifBlank { "抖音视频" },
            author = author,
            cover = cover,
            durationSec = duration.takeIf { it > 0 },
            videoUrl = playUrl,
            images = emptyList(),
        )
    }
}
