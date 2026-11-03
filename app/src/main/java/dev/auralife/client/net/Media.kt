package dev.auralife.client.net

import android.content.Context
import android.graphics.Bitmap
import java.io.File

// /api/media 回 immutable，取一次即缓存
object Media {
    private fun dir(ctx: Context): File = File(ctx.cacheDir, "media").apply { mkdirs() }

    private fun safe(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "x" }

    fun cached(ctx: Context, id: String): File? =
        if (id.isEmpty()) null else File(dir(ctx), safe(id)).takeIf { it.isFile && it.length() > 0 }

    // 无缓存时拉取落盘，空 id 或 404 返回 null
    fun fetch(ctx: Context, api: AuraApi, id: String): File? {
        if (id.isEmpty()) return null
        cached(ctx, id)?.let { return it }
        val bytes = api.mediaBytes(id) ?: return null
        val f = File(dir(ctx), safe(id))
        val ok = runCatching { f.writeBytes(bytes); f }.getOrNull()
        if (ok != null) trim(ctx)
        return ok
    }

    fun bitmap(ctx: Context, api: AuraApi, id: String, maxSide: Int = 1080): Bitmap? {
        val f = fetch(ctx, api, id) ?: return null
        val raw = runCatching { f.readBytes() }.getOrNull() ?: return null
        return decodeBytes(raw, maxSide)
    }

    // 缓存上限 300MB，超出按最旧删除
    private const val MAX_BYTES = 300L * 1024 * 1024

    fun trim(ctx: Context, max: Long = MAX_BYTES) {
        runCatching {
            val files = dir(ctx).listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= max) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= max) return@forEach
                total -= f.length()
                runCatching { f.delete() }
            }
        }
    }
}
