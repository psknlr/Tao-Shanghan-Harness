package org.impfai.hermes.core.chat

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 智能體會話持久化（聊天記錄）。
 *
 * 設計：一個會話一個 JSON 文件（dir/<id>.json）——單會話讀寫互不影響，
 * 損壞一個文件不拖垮整個歷史。會話輕量（每條消息只存展示所需字段，
 * 不存 trace 全文），[maxSessions] 上限按更新時間裁剪最舊。
 *
 * 純 JVM（java.io + kotlinx），無 Android 依賴，可直接單測；
 * 全部方法不拋出——歷史記錄失敗絕不能弄壞對話主流程。
 */
class ChatHistoryStore(
    private val dir: File,
    private val maxSessions: Int = 100,
) {

    @Serializable
    data class Message(
        /** user | bot | failure */
        val role: String,
        /** user 的問題原文 / failure 的錯誤信息。 */
        val text: String = "",
        /** bot 的回答正文。 */
        val answer: String = "",
        val backend: String = "",
        val evidence: List<String> = emptyList(),
        /** verified | partial | none | refused | ""（非 bot）。 */
        val citation: String = "",
        val ts: String = "",
    )

    @Serializable
    data class Session(
        val id: String,
        val createdTs: String = "",
        val updatedTs: String = "",
        /** 列表展示標題 = 首個問題（截斷）。 */
        val title: String = "",
        /** server | direct */
        val source: String = "",
        val messages: List<Message> = emptyList(),
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun fileOf(id: String): File =
        File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                dir.mkdirs()
                fileOf(session.id).writeText(
                    json.encodeToString(Session.serializer(), session))
                trimLocked()
            } catch (_: Exception) {
            }
        }
    }

    /** 全部會話，按更新時間新→舊。 */
    suspend fun list(): List<Session> = withContext(Dispatchers.IO) {
        mutex.withLock { listLocked() }
    }

    suspend fun load(id: String): Session? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val f = fileOf(id)
                if (!f.exists()) null
                else json.decodeFromString(Session.serializer(), f.readText())
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                fileOf(id).delete()
            } catch (_: Exception) {
            }
            Unit
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                dir.listFiles()?.forEach { if (it.extension == "json") it.delete() }
            } catch (_: Exception) {
            }
            Unit
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.count { it.extension == "json" } ?: 0
        }
    }

    private fun listLocked(): List<Session> = try {
        (dir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .mapNotNull {
                try {
                    json.decodeFromString(Session.serializer(), it.readText())
                } catch (_: Exception) {
                    null   // 壞文件跳過，不拖垮整個列表
                }
            }
            .sortedByDescending { it.updatedTs.ifBlank { it.createdTs } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun trimLocked() {
        val all = listLocked()
        if (all.size <= maxSessions) return
        all.drop(maxSessions).forEach {
            try {
                fileOf(it.id).delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val ID_FMT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        fun newSessionId(now: Instant = Instant.now()): String =
            "chat-" + ID_FMT.format(now)

        fun timestamp(now: Instant = Instant.now()): String =
            DateTimeFormatter.ISO_INSTANT.format(now)

        /** 列表時間展示：2026-07-18 10:30（UTC→本地由調用方決定，這裡截 ISO）。 */
        fun shortTime(ts: String): String =
            ts.replace("T", " ").take(16)
    }
}
