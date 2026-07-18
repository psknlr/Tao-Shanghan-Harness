package org.impfai.hermes.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.impfai.hermes.core.model.EvidenceGrade
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.core.model.evidenceGradeForLayer

/**
 * 統一取證檢索（v1.10 全庫取證；v1.12 修復並擴容）。
 *
 * v1.12 三處關鍵改造：
 * 1. **詞項抽取**：自然語言問題不再整句字面匹配（「针灸甲乙经的学术
 *    特色」以前作為一個串 indexOf 全庫必然零命中）——按停用字/標點
 *    切詞後逐項並行檢索；
 * 2. **書名直配**：詞項命中書目（如「针灸甲乙经」→《針灸甲乙經》）
 *    時直接取該書卷首與相關段落作證據；
 * 3. **分層配額合併**：總池 50 條，各證據層保底配額——條文層 BM25
 *    不再因層級星高擠掉全部古籍書證。
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

    suspend fun search(query: String, topK: Int = TOTAL_POOL): List<UnifiedHit> =
        coroutineScope {
            val terms = extractTerms(query)
            val clauseJob = async {
                clauseStore.search(query, topK = CLAUSE_QUOTA)
            }
            val titleJob = async { titleMatches(terms) }
            val grepJobs = terms.map { t ->
                async { libraryStore.grepFast(t, limit = 12) }
            }
            val clauses = clauseJob.await().map { it.toUnified() }
            val library = (titleJob.await() +
                grepJobs.awaitAll().flatten()
                    .filterNot { it.unit.title.contains("傷寒論") }
                    .map { it.toUnified() })
                .distinctBy { it.ref }
            mergeTiered(clauses, library, topK)
        }

    /** 詞項命中書目 → 卷首開卷取證（如「针灸甲乙经」→ 該書卷首）。 */
    private suspend fun titleMatches(terms: List<String>): List<UnifiedHit> {
        val out = ArrayList<UnifiedHit>()
        for (term in terms) {
            if (out.size >= 2) break
            if (term.length < 3) continue   // 短詞不做書名直配（誤配率高）
            val canonTerm = TextNorm.searchCanon(term)
            val unit = libraryStore.searchCatalog(term, limit = 3)
                .firstOrNull {
                    val t = TextNorm.searchCanon(it.title)
                    t.contains(canonTerm) || canonTerm.contains(t)
                } ?: continue
            val opening = libraryStore.read(unit.id, "", 0, maxChars = 600)
                .text.lines().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("<") }
                .joinToString("").take(200)
            if (opening.isNotBlank()) {
                out.add(UnifiedHit(
                    sourceType = "library",
                    ref = "《${unit.title}·卷首》",
                    text = opening,
                    grade = gradeForCategory(unit.category),
                    book = unit.title,
                    section = "",
                ))
            }
        }
        return out
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
        const val TOTAL_POOL = 50
        const val CLAUSE_QUOTA = 15
        /** 回填階段條文總量硬帽——證據池不能只靠傷寒論。 */
        const val CLAUSE_MAX = 20

        /** 停用字/口語提問詞：切詞分隔符（不進入檢索詞項）。 */
        private val STOP_CHARS =
            "的之与與和及對对于於在是什么麼吗嗎呢如何怎样樣哪些请請问問" +
                "谈談述论論析简簡试試帮幫我你了吧啊呀"
        private val STOP_TERMS = setOf(
            "什么", "如何", "哪些", "为什么", "是不是", "有没有")

        /**
         * 自然語言問題 → 檢索詞項（≤6 個，2..10 字）：按非 CJK 與
         * 停用字切段；超長段再取首尾 4 字窗口增召回。純函數可測。
         */
        fun extractTerms(query: String): List<String> {
            val segs = query
                .split(Regex("[^\\u3400-\\u9FFF]+|[$STOP_CHARS]"))
                .map { it.trim() }
                .filter { it.length >= 2 && it !in STOP_TERMS }
            val out = LinkedHashSet<String>()
            for (seg in segs) {
                if (seg.length <= 10) out.add(seg)
                else {
                    out.add(seg.take(6)); out.add(seg.takeLast(6))
                }
                if (seg.length >= 6) {
                    out.add(seg.take(4)); out.add(seg.takeLast(4))
                }
                if (out.size >= 6) break
            }
            if (out.isEmpty()) {
                query.filter { it.code in 0x3400..0x9FFF }
                    .take(8).takeIf { it.length >= 2 }?.let { out.add(it) }
            }
            return out.take(6).toList()
        }

        /**
         * 分層配額合併（總池 [total]）：條文層 ≤15；古籍層按星級保底
         * （5★15 / 4★10 / 3★6 / 2★4），餘位按星級從剩餘命中回填——
         * 條文 A 層星高不再擠掉全部書證。純函數可測。
         */
        fun mergeTiered(
            clauses: List<UnifiedHit>,
            library: List<UnifiedHit>,
            total: Int = TOTAL_POOL,
        ): List<UnifiedHit> {
            val quotas = mapOf(5 to 15, 4 to 10, 3 to 6, 2 to 4, 1 to 2)
            val picked = ArrayList<UnifiedHit>()
            val leftovers = ArrayList<UnifiedHit>()
            picked += clauses.take(minOf(CLAUSE_QUOTA, total))
            leftovers += clauses.drop(minOf(CLAUSE_QUOTA, total))
            val byStar = library.groupBy { it.grade.stars }
            for (star in 5 downTo 1) {
                val hits = byStar[star] ?: continue
                val quota = quotas[star] ?: 0
                picked += hits.take(quota)
                leftovers += hits.drop(quota)
            }
            // 回填：按星級補位，但條文總量硬帽 CLAUSE_MAX——否則 5★
            // 條文餘量會在回填階段重新洪泛，書證仍被擠掉
            var clauseCount = picked.count { it.sourceType == "clause" }
            val fill = leftovers.sortedByDescending { it.grade.stars }
            for (h in fill) {
                if (picked.size >= total) break
                if (h.sourceType == "clause") {
                    if (clauseCount >= CLAUSE_MAX) continue
                    clauseCount++
                }
                picked += h
            }
            return picked.distinctBy { it.ref }.take(total)
        }

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
