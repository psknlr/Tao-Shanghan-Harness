package org.impfai.hermes.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.impfai.hermes.core.model.EvidenceGrade
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.core.model.evidenceGradeForLayer

/**
 * 統一取證檢索（v1.10：智能體全庫取證）。
 *
 * 兩路並行：
 * - 傷寒論條文層：內存 BM25（毫秒級），證據等級 = 語料分層 A–E；
 * - 全庫古籍層：[LibraryStore.grepFast]（稀字剪枝 + 並行分片 + 早停 +
 *   LRU 緩存），證據等級 = 分類文獻學分層（經典原文 5 > 要籍 4 >
 *   論說 3 > 醫案 2）。
 * 合併按（等級 desc，路內原序）取 top-k——「按證據等級 top-k 召回」。
 */
class UnifiedRetriever(
    private val clauseStore: LocalClauseStore,
    private val libraryStore: LibraryStore,
) {

    data class UnifiedHit(
        /** clause | library */
        val sourceType: String,
        /** 提示詞中的引用標識：SHL_… 或 《書名·章節》。 */
        val ref: String,
        val text: String,
        val grade: EvidenceGrade,
        /** clause 命中時的條文 id（證據卡回源）。 */
        val clauseId: String = "",
        val book: String = "",
        val section: String = "",
    )

    suspend fun search(query: String, topK: Int = 8): List<UnifiedHit> =
        coroutineScope {
            val clauseJob = async {
                clauseStore.search(query, topK = topK)
            }
            val libraryJob = async {
                libraryStore.grepFast(query, limit = topK)
            }
            val clauses = clauseJob.await().map { it.toUnified() }
            val library = libraryJob.await()
                // 傷寒論本身在條文層已有更優表示，古籍層去重
                .filterNot { it.unit.category.contains("傷寒") &&
                    it.unit.title.contains("傷寒論") }
                .map { it.toUnified() }
            (clauses + library)
                .sortedByDescending { it.grade.stars }
                .take(topK)
        }

    private fun SearchHit.toUnified() = UnifiedHit(
        sourceType = "clause",
        ref = clauseId,
        text = text,
        grade = evidenceGradeForLayer(layer),
        clauseId = clauseId,
        book = "傷寒論",
        section = chapter,
    )

    private fun LibraryStore.GrepHit.toUnified() = UnifiedHit(
        sourceType = "library",
        ref = "《${unit.title}" +
            (section.takeIf { it.isNotBlank() }?.let { "·$it" } ?: "") + "》",
        text = excerpt,
        grade = gradeForCategory(unit.category),
        book = unit.title,
        section = section,
    )

    companion object {
        /**
         * 古籍分類 → 證據等級（文獻學分層，非模型自評）：
         * 經典原文（內經/難經/經論/金匱/傷寒）5★ > 要籍（本草/方書/
         * 溫病/脈診/針灸…）4★ > 論說綜合臨證各科 3★ > 醫案 2★。
         */
        fun gradeForCategory(category: String): EvidenceGrade {
            fun hit(vararg keys: String) = keys.any { it in category }
            return when {
                hit("內經", "難經", "經論", "金匱", "傷寒") ->
                    EvidenceGrade(5, "经典原文")
                hit("本草", "方書", "溫病", "脈法", "診法", "診治",
                    "針灸", "經穴", "炮製") ->
                    EvidenceGrade(4, "历代要籍")
                hit("醫案") -> EvidenceGrade(2, "医案纪实")
                else -> EvidenceGrade(3, "论说文献")
            }
        }
    }
}
