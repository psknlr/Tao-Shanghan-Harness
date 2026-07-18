package org.impfai.hermes.core.audit

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
 * 客戶端審計日誌（外部評審建議七）。
 *
 * 定位：**本機**諮詢審計軌跡——記錄「誰在什麼時候問了什麼、用了哪些
 * 證據、引用是否核驗通過、哪個後端/模型作答」。權威審計仍在服務端
 * （請求鑑權、角色裁定、run 記錄都在那裡）；本日誌解決的是移動端
 * 自查與教學復盤：斷網後仍可回看每次諮詢用了什麼證據。
 *
 * 實現約束：
 * - 純 JVM（java.io + kotlinx），無 Android 依賴，可直接單測；
 * - JSONL 追加寫，超出 [maxEntries] 時裁剪最舊條目；
 * - 全部方法不拋出——審計失敗絕不能反向弄壞諮詢主流程。
 */
class AuditLog(
    private val dir: File,
    private val maxEntries: Int = 500,
) {

    @Serializable
    data class Entry(
        /** 病例/諮詢編號，形如 20260717-093012-483（UTC 時間派生）。 */
        val caseId: String = "",
        /** ISO-8601 UTC 時間戳。 */
        val ts: String = "",
        /** agent | match */
        val kind: String = "",
        /** 用戶輸入（問題原文或四診要素拼接）。 */
        val input: String = "",
        /** 客戶端請求的角色。 */
        val requestedRole: String = "",
        /** 服務端裁定的生效角色（信封 meta 回顯）。 */
        val effectiveRole: String? = null,
        /** 作答後端/模型標識（服務端回傳，如 local / 模型名）。 */
        val backend: String = "",
        /** 本次回答實際使用的證據條文 id。 */
        val evidence: List<String> = emptyList(),
        /** 人類可讀結果摘要：已核验 N 条 / 匹配 N 方 / 拒答 / 错误码。 */
        val verdict: String = "",
        /** 服務端安全閘門是否拒答。 */
        val refused: Boolean = false,
        /** OK 或合同錯誤碼（OFFLINE/POLICY_DENIED…）。 */
        val resultCode: String = "OK",
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(dir, "audit.jsonl")

    suspend fun record(entry: Entry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                dir.mkdirs()
                file.appendText(json.encodeToString(Entry.serializer(), entry) + "\n")
                val lines = file.readLines().filter { it.isNotBlank() }
                if (lines.size > maxEntries) {
                    file.writeText(
                        lines.takeLast(maxEntries).joinToString("\n") + "\n")
                }
            } catch (_: Exception) {
                // 審計不可用時靜默：主流程（諮詢/匹配）優先
            }
        }
    }

    /** 最近 [limit] 條（新→舊）。 */
    suspend fun recent(limit: Int = 50): List<Entry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!file.exists()) return@withLock emptyList()
                file.readLines().asReversed().asSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        try {
                            json.decodeFromString(Entry.serializer(), it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .take(limit)
                    .toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!file.exists()) 0 else file.readLines().count { it.isNotBlank() }
            } catch (_: Exception) {
                0
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                file.delete()
            } catch (_: Exception) {
            }
            Unit
        }
    }

    companion object {
        private val CASE_FMT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        private val TS_FMT = DateTimeFormatter.ISO_INSTANT

        fun newCaseId(now: Instant = Instant.now()): String = CASE_FMT.format(now)

        fun timestamp(now: Instant = Instant.now()): String = TS_FMT.format(now)
    }
}
