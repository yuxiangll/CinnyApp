package top.s0cl.cinny.platform

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

/**
 * M4：解码后的图片 LRU 缓存（按 mxc URL / 事件 ID）。
 * 避免滚动时间线时重复下载与解码。
 */
object ImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    private const val MAX_ENTRIES = 24

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}
