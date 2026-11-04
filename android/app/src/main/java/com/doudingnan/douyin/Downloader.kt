package com.doudingnan.douyin

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    fun downloadVideo(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, sanitize(fileName) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Douyin")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建视频文件")
        try {
            copy(url, resolver.openOutputStream(uri)!!, onProgress)
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun downloadImage(context: Context, url: String, fileName: String): Uri {
        val resolver = context.contentResolver
        val isWebp = url.contains(".webp", ignoreCase = true)
        val extension = if (isWebp) ".webp" else ".jpg"
        val mime = if (isWebp) "image/webp" else "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, sanitize(fileName) + extension)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Douyin")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建图片文件")
        try {
            copy(url, resolver.openOutputStream(uri)!!) { _, _ -> }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun copy(url: String, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", DouyinParser.DESKTOP_UA)
            conn.setRequestProperty("Referer", "https://www.douyin.com/")
            if (conn.responseCode !in 200..299) {
                throw IOException("下载失败 HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_").trim().take(60).ifBlank { "douyin" }
}
