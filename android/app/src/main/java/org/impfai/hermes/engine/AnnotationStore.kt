package org.impfai.hermes.engine

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 閱讀批注存儲（劃線 / 筆記 / 書籤）——本機 JSON 文件
 * （filesDir/reader_annotations.json），不上雲、不隨備份外流
 * （allowBackup=false）。錨點為「書 · 章節 · 段序 + 摘錄」，
 * 段落序漂移時以摘錄文本兜底定位。
 */
class AnnotationStore(private val context: Context) {

    enum class Kind { HIGHLIGHT, NOTE, BOOKMARK }

    @Serializable
    data class Annotation(
        val id: String = "",
        val bookId: String = "",
        val bookTitle: String = "",
        val section: String = "",
        val paraIndex: Int = 0,
        val excerpt: String = "",
        val note: String = "",
        val kind: String = "HIGHLIGHT",
        val createdAt: Long = 0,
        // 字句級選區（v1.5）：段內字符偏移；-1 表示整段
        val selStart: Int = -1,
        val selEnd: Int = -1,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, "reader_annotations.json")

    @Volatile private var cache: List<Annotation>? = null

    private suspend fun load(): List<Annotation> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                try {
                    if (file.exists())
                        json.decodeFromString<List<Annotation>>(file.readText())
                    else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }.also { cache = it }
        }
    }

    private suspend fun save(list: List<Annotation>) {
        mutex.withLock {
            cache = list
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(list))
                tmp.renameTo(file)      // 原子替換，斷電不半寫
            }
        }
    }

    suspend fun forBook(bookId: String): List<Annotation> =
        load().filter { it.bookId == bookId }

    suspend fun all(): List<Annotation> = load()

    suspend fun add(
        bookId: String, bookTitle: String, section: String, paraIndex: Int,
        excerpt: String, kind: Kind, note: String = "",
        selStart: Int = -1, selEnd: Int = -1,
    ): Annotation {
        val a = Annotation(
            id = UUID.randomUUID().toString().take(8),
            bookId = bookId, bookTitle = bookTitle, section = section,
            paraIndex = paraIndex, excerpt = excerpt.take(60),
            note = note, kind = kind.name,
            createdAt = System.currentTimeMillis(),
            selStart = selStart, selEnd = selEnd,
        )
        save(load() + a)
        return a
    }

    suspend fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }
}
