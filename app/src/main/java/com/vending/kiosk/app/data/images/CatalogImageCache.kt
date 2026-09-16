package com.vending.kiosk.app.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CatalogImageCache(context: Context) {

    private val imageCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val localImageCacheDir = File(context.cacheDir, "planograma_images").apply { mkdirs() }
    private val localImageCachePrefs = context.getSharedPreferences(
        "planograma_image_cache",
        Context.MODE_PRIVATE
    )

    fun resolveImageSourceForCache(
        slot: String,
        incomingId: Int,
        remoteUrl: String,
        targetSizePx: Int
    ): String {
        val normalizedUrl = remoteUrl.trim()
        if (normalizedUrl.isBlank()) return ""

        val slotKey = sanitizeCacheSlot(slot)
        val token = if (incomingId > 0) "id:$incomingId|url:$normalizedUrl" else "url:${normalizedUrl.lowercase()}"
        val tokenPrefKey = "slot_${slotKey}_token"
        val pathPrefKey = "slot_${slotKey}_path"
        val storedToken = localImageCachePrefs.getString(tokenPrefKey, null)
        val storedPath = localImageCachePrefs.getString(pathPrefKey, null)
        val storedFile = storedPath?.let { File(it) }

        if (storedToken == token && storedFile?.exists() == true) {
            return storedFile.absolutePath
        }

        if (storedToken != token) {
            storedFile?.takeIf { it.exists() }?.delete()
            localImageCacheDir.listFiles()?.forEach { candidate ->
                if (candidate.name.startsWith("${slotKey}_")) {
                    candidate.delete()
                }
            }
            localImageCachePrefs.edit().remove(pathPrefKey).apply()
        }

        val bitmap = downloadBitmap(normalizedUrl, targetSizePx) ?: return normalizedUrl
        imageCache.put(normalizedUrl, bitmap)

        val targetFile = File(localImageCacheDir, "${slotKey}_${token.hashCode()}.png")
        if (!writeBitmapToFile(bitmap, targetFile)) return normalizedUrl

        imageCache.put(targetFile.absolutePath, bitmap)
        localImageCachePrefs.edit()
            .putString(tokenPrefKey, token)
            .putString(pathPrefKey, targetFile.absolutePath)
            .apply()

        return targetFile.absolutePath
    }

    fun getBitmap(key: String): Bitmap? = imageCache.get(key)

    fun putBitmap(key: String, bitmap: Bitmap) {
        imageCache.put(key, bitmap)
    }

    fun isLocalImagePath(path: String): Boolean = path.isNotBlank() && !isRemoteUrl(path)

    fun loadBitmapFromLocalPath(path: String, targetSizePx: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            decodeSampledBitmap(bytes, targetSizePx)
        }.getOrNull()
    }

    fun downloadBitmap(rawUrl: String, targetSizePx: Int): Bitmap? {
        val primary = rawUrl.trim()
        val alternatives = buildList {
            add(primary)
            if (primary.startsWith("http://", ignoreCase = true)) {
                add(primary.replaceFirst("http://", "https://", ignoreCase = true))
            }
        }

        for (candidate in alternatives) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(candidate).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    doInput = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "BoxiPago-Android/1.0")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) continue
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                decodeSampledBitmap(bytes, targetSizePx)?.let { bitmap ->
                    return bitmap
                }
            } catch (_: Exception) {
                // continue with next alternative
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun sanitizeCacheSlot(raw: String): String {
        return raw.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_")
    }

    private fun isRemoteUrl(path: String): Boolean {
        return path.startsWith("https://", ignoreCase = true) || path.startsWith("http://", ignoreCase = true)
    }

    private fun writeBitmapToFile(bitmap: Bitmap, targetFile: File): Boolean {
        return runCatching {
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.flush()
            }
            true
        }.getOrDefault(false)
    }

    private fun decodeSampledBitmap(data: ByteArray, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (bounds.outWidth / inSampleSize > targetSizePx * 2 || bounds.outHeight / inSampleSize > targetSizePx * 2) {
            inSampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }
}
