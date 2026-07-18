package org.impfai.hermes.engine

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 閱讀進度存儲（v1.9.1：續讀）——本機 JSON 文件
 * （filesDir/reading_progress.json），與批注同款原子寫惯例。
 * 錨點為「書 · 章節 · 段序」：段序對字號/簡繁切換穩定（重排不變），
 * 打開書時無顯式章節/定位即自動恢復到上次位置。
 */
class ReadingProgressStore(private val context: Context) {

    @Serializable
    data class Progress(
        val bookId: String = "",
        val section: String = "",
        val paraIndex: Int = 0,
        /** 本章節內近似進度 0..1（書架展示用，按段序/段總數）。 */
        val percent: Float = 0f,
        val updatedAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, "reading_progress.json")

    @Volatile private var cache: Map<String, Progress>? = null

    private suspend fun load(): Map<String, Progress> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                try {
                    if (file.exists())
                        json.decodeFromString<List<Progress>>(file.readText())
                            .associateBy { it.bookId }
                    else emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }.also { cache = it }
        }
    }

    suspend fun get(bookId: String): Progress? = load()[bookId]

    suspend fun all(): Map<String, Progress> = load()

    suspend fun save(p: Progress) {
        if (p.bookId.isBlank()) return
        val next = load() + (p.bookId to p)
        mutex.withLock {
            cache = next
            withContext(Dispatchers.IO) {
                try {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(next.values.toList()))
                    tmp.renameTo(file)      // 原子替換，斷電不半寫
                } catch (_: Exception) {
                    // 進度寫失敗靜默：不影響閱讀主流程
                }
            }
        }
    }
}
