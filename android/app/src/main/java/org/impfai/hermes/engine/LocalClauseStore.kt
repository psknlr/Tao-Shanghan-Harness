package org.impfai.hermes.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.impfai.hermes.core.model.ClauseDetail
import org.impfai.hermes.core.model.Entities
import org.impfai.hermes.core.model.FormulaBlock
import org.impfai.hermes.core.model.HerbDose
import org.impfai.hermes.core.model.SearchHit

/**
 * 離線知識層：APK 內置語料（構建期從 backend/data/shanghan 複製）。
 *
 * 檢索算法移植自 backend/hermes_shanghan/rag/clause_rag.py 的確定性
 * 子集：條文號直查（score 99.0）、BM25 歸一（top 基準 10.0）、輔助篇
 * ×0.7 降權、方名命中 +3.0、方證條文 +0.5、(-score, clause_id) 排序。
 * 症狀/脈象覆蓋加分依賴服務端 EntityExtractor，離線層不復制
 * （Phase 4 金標準對照時一併移植）——離線結果一律標記 LOCAL_CORPUS。
 *
 * 681 條記錄 / <1MB，內存索引即可；Room+FTS5 留待古籍全庫離線
 * （見 docs/ANDROID.md 對原方案第六節的修改說明）。
 */
class LocalClauseStore(private val context: Context) {

    @Serializable
    data class LocalFormulaBlock(
        @SerialName("formula_name") val formulaName: String = "",
        val composition: List<HerbDose> = emptyList(),
        val preparation: String = "",
        val administration: String = "",
        @SerialName("raw_text") val rawText: String = "",
    )

    @Serializable
    data class LocalClause(
        @SerialName("clause_id") val clauseId: String,
        @SerialName("book_title") val bookTitle: String = "",
        val chapter: String = "",
        @SerialName("six_channel") val sixChannel: String? = null,
        @SerialName("clause_number") val clauseNumber: Int? = null,
        @SerialName("clean_text") val cleanText: String = "",
        @SerialName("text_type") val textType: String = "",
        val layer: String = "",
        @SerialName("formula_names") val formulaNames: List<String> = emptyList(),
        val symptoms: List<String> = emptyList(),
        val pulse: List<String> = emptyList(),
        @SerialName("formula_blocks") val formulaBlocks: List<LocalFormulaBlock> = emptyList(),
    )

    @Serializable
    data class FormulaRule(
        @SerialName("formula_pattern_rule_id") val ruleId: String = "",
        val formula: String = "",
        @SerialName("formula_family") val formulaFamily: String = "",
        @SerialName("six_channel_scope") val sixChannelScope: List<String> = emptyList(),
        @SerialName("core_pattern") val corePattern: String = "",
        @SerialName("core_symptoms") val coreSymptoms: List<String> = emptyList(),
        @SerialName("core_pulse") val corePulse: List<String> = emptyList(),
        val contraindications: List<String> = emptyList(),
        val composition: List<HerbDose> = emptyList(),
        @SerialName("administration_notes") val administrationNotes: List<String> = emptyList(),
        @SerialName("supporting_clauses") val supportingClauses: List<String> = emptyList(),
        @SerialName("source_level") val sourceLevel: String = "",
        @SerialName("interpretation_warning") val interpretationWarning: String = "",
        @SerialName("consensus_score") val consensusScore: Double = 0.0,
        @SerialName("release_level") val releaseLevel: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var loaded = false
    private var clauses: List<LocalClause> = emptyList()
    private var byId: Map<String, LocalClause> = emptyMap()
    private var byNumber: Map<Int, LocalClause> = emptyMap()
    private var canonical: List<LocalClause> = emptyList()
    private var rules: List<FormulaRule> = emptyList()
    private val index = Bm25Index()

    val layerLabels = mapOf(
        "A" to "原文直述", "B" to "版本異文", "C" to "注家解釋",
        "D" to "後世類方歸納", "E" to "模型推理",
    )

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                clauses = context.assets.open("shanghan/clauses.jsonl")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter { it.isNotBlank() }
                            .map { json.decodeFromString<LocalClause>(it) }
                            .toList()
                    }
                rules = context.assets.open("shanghan/formula_pattern_rules.jsonl")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter { it.isNotBlank() }
                            .map { json.decodeFromString<FormulaRule>(it) }
                            .toList()
                    }
                byId = clauses.associateBy { it.clauseId }
                canonical = clauses
                    .filter { it.textType == "original_clause" && it.clauseNumber != null }
                    .sortedBy { it.clauseNumber }
                byNumber = canonical.associateBy { it.clauseNumber!! }
                // 索引文本 = 正文 + 方劑塊原文（與 ClauseRAG 一致）
                for (c in clauses) {
                    val blockText = c.formulaBlocks.joinToString("\n") { it.rawText }
                    index.add(c.clauseId, c.cleanText + "\n" + blockText)
                }
                index.finalizeIndex()
            }
            loaded = true
        }
    }

    fun stats(): Pair<Int, Int> =
        clauses.size to clauses.count { it.textType == "original_clause" }

    fun byId(id: String): LocalClause? = byId[id]

    fun byNumber(n: Int): LocalClause? = byNumber[n]

    fun formulaRules(): List<FormulaRule> = rules

    /** 「今日條文」：按天確定性輪換核心條文（首頁產品化，評審建議八）。 */
    fun dailyHit(epochDay: Long): SearchHit? {
        if (canonical.isEmpty()) return null
        val c = canonical[
            org.impfai.hermes.core.model.dailyClauseIndex(epochDay, canonical.size)]
        return toHit(c, 0.0, "daily")
    }

    private val clauseNumQuery = Regex("第?(\\d{1,3})[條条]")

    suspend fun search(query: String, topK: Int = 8, sixChannel: String? = null): List<SearchHit> {
        ensureLoaded()
        val norm = TextNorm.normalizeQuery(query)
        if (norm.isBlank()) return emptyList()

        // 條文號直查（與 ClauseRAG 一致：score 99.0）
        clauseNumQuery.find(norm)?.let { m ->
            byNumber[m.groupValues[1].toInt()]?.let { c ->
                return listOf(toHit(c, 99.0, "clause_number"))
            }
        }
        // 有意偏離 Python：純數字（如「12」）也直查——Python 端 CJK 分詞
        // 對純數字查詢只會返回空；移動端輸入數字期望直達條文
        norm.toIntOrNull()?.let { n ->
            byNumber[n]?.let { c -> return listOf(toHit(c, 99.0, "clause_number")) }
        }

        val scored = index.search(norm, topK * 5)
        if (scored.isEmpty()) return emptyList()
        val bmMax = scored.first().second.takeIf { it > 0 } ?: 1.0
        val out = ArrayList<Pair<LocalClause, Double>>()
        for ((cid, bm) in scored) {
            val c = byId[cid] ?: continue
            if (sixChannel != null && c.sixChannel != sixChannel) continue
            var score = 10.0 * bm / bmMax
            if (c.textType != "original_clause") score *= 0.7
            // 已知差距：Python 走 lexicon.canonical_formula 別名歸一
            //（「桂枝湯」的各種別名同享 +3.0），離線層只做規範化後精確
            // 匹配——lexicon 移植歸入 Phase 4 金標準對照
            if (c.formulaNames.any { TextNorm.foldVariants(it) == norm }) score += 3.0
            if (c.formulaNames.isNotEmpty()) score += 0.5
            out.add(c to score)
        }
        return out
            .sortedWith(compareByDescending<Pair<LocalClause, Double>> { it.second }
                .thenBy { it.first.clauseId })
            .take(topK)
            // Python _hit 展示 round(score, 3)
            .map { (c, s) -> toHit(c, Bm25Index.roundHalfEven(s, 3), "local_bm25") }
    }

    private fun toHit(c: LocalClause, score: Double, source: String) = SearchHit(
        clauseId = c.clauseId,
        clauseNumber = c.clauseNumber,
        book = c.bookTitle,
        chapter = c.chapter,
        sixChannel = c.sixChannel,
        text = c.cleanText,
        textType = c.textType,
        layer = c.layer,
        layerLabel = layerLabels[c.layer] ?: "",
        formulas = c.formulaNames,
        score = score,
        matchSource = source,
    )

    /** 離線條文詳情（無注家/異文——那些證據面需要服務端）。 */
    suspend fun clauseDetail(ref: String): ClauseDetail? {
        ensureLoaded()
        val c = ref.toIntOrNull()?.let { byNumber[it] } ?: byId[ref] ?: return null
        return ClauseDetail(
            clauseId = c.clauseId,
            clauseNumber = c.clauseNumber,
            chapter = c.chapter,
            sixChannel = c.sixChannel,
            layerLabel = layerLabels[c.layer] ?: "",
            text = c.cleanText,
            entities = Entities(
                symptoms = c.symptoms,
                pulse = c.pulse,
                formulas = c.formulaNames,
            ),
            formulaBlocks = c.formulaBlocks.map {
                FormulaBlock(
                    formulaName = it.formulaName,
                    composition = it.composition,
                    preparation = it.preparation,
                    administration = it.administration,
                    rawText = it.rawText,
                )
            },
        )
    }
}
