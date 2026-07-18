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
import org.impfai.hermes.core.model.ClauseRelation
import org.impfai.hermes.core.model.ClauseVariant
import org.impfai.hermes.core.model.Commentary
import org.impfai.hermes.core.model.Entities
import org.impfai.hermes.core.model.FormulaBlock
import org.impfai.hermes.core.model.HerbDose
import org.impfai.hermes.core.model.InitialRule
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

    /** 規則庫禁忌記錄：帶條文座標的對象（v1.3 修復：此前誤聲明為
     *  List<String>，導致桂枝湯等 23 條帶禁忌的規則整行解析失敗被丟）。 */
    @Serializable
    data class ContraRec(
        @SerialName("clause_id") val clauseId: String = "",
        val condition: String = "",
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
        @SerialName("associated_symptoms") val associatedSymptoms: List<String> = emptyList(),
        @SerialName("associated_pulse") val associatedPulse: List<String> = emptyList(),
        val contraindications: List<ContraRec> = emptyList(),
        val composition: List<HerbDose> = emptyList(),
        @SerialName("administration_notes") val administrationNotes: List<String> = emptyList(),
        @SerialName("supporting_clauses") val supportingClauses: List<String> = emptyList(),
        @SerialName("source_level") val sourceLevel: String = "",
        @SerialName("interpretation_warning") val interpretationWarning: String = "",
        @SerialName("consensus_score") val consensusScore: Double = 0.0,
        @SerialName("release_level") val releaseLevel: String = "",
    )

    // —— VIP 資產原始記錄（backend/data/shanghan 各規則庫的逐行結構）——
    @Serializable
    private data class CommentaryRule(
        @SerialName("clause_id") val clauseId: String = "",
        val commentator: String = "",
        val book: String = "",
        val chapter: String = "",
        @SerialName("commentary_text") val commentaryText: String = "",
    )

    @Serializable
    private data class VariantRule(
        @SerialName("clause_id") val clauseId: String = "",
        @SerialName("variant_book") val variantBook: String = "",
        @SerialName("variant_text") val variantText: String = "",
        val similarity: Double = 0.0,
        @SerialName("notable_differences") val notableDifferences: List<String> = emptyList(),
    )

    @Serializable
    private data class RelationRec(
        @SerialName("source_clause_id") val sourceClauseId: String = "",
        @SerialName("target_clause_id") val targetClauseId: String = "",
        @SerialName("relation_type") val relationType: String = "",
        val description: String = "",
        val confidence: Double = 0.0,
    )

    @Serializable
    data class ScSubtype(
        val name: String = "",
        @SerialName("anchor_formulas") val anchorFormulas: List<String> = emptyList(),
        @SerialName("evidence_clauses") val evidenceClauses: List<String> = emptyList(),
    )

    @Serializable
    data class ScMainFormula(
        val formula: String = "",
        @SerialName("clause_count") val clauseCount: Int = 0,
    )

    @Serializable
    data class SixChannelRule(
        @SerialName("six_channel_rule_id") val ruleId: String = "",
        @SerialName("six_channel") val sixChannel: String = "",
        @SerialName("outline_clause_id") val outlineClauseId: String = "",
        @SerialName("outline_text") val outlineText: String = "",
        val summary: String = "",
        @SerialName("core_clauses") val coreClauses: List<String> = emptyList(),
        val subtypes: List<ScSubtype> = emptyList(),
        @SerialName("main_formulas") val mainFormulas: List<ScMainFormula> = emptyList(),
        @SerialName("contraindication_clauses")
        val contraindicationClauses: List<String> = emptyList(),
        @SerialName("mistreatment_clauses")
        val mistreatmentClauses: List<String> = emptyList(),
        @SerialName("resolution_time") val resolutionTime: String = "",
        @SerialName("release_level") val releaseLevel: String = "",
    )

    @Serializable
    data class DifferentialRule(
        @SerialName("differential_rule_id") val ruleId: String = "",
        val formulas: List<String> = emptyList(),
        @SerialName("six_channels") val sixChannels: List<String> = emptyList(),
        @SerialName("shared_features") val sharedFeatures: List<String> = emptyList(),
        @SerialName("contrast_table")
        val contrastTable: List<kotlinx.serialization.json.JsonObject> = emptyList(),
        @SerialName("key_discriminators")
        val keyDiscriminators: List<String> = emptyList(),
        @SerialName("composition_diff")
        val compositionDiff: kotlinx.serialization.json.JsonObject? = null,
        @SerialName("supporting_clauses")
        val supportingClauses: List<String> = emptyList(),
        @SerialName("release_level") val releaseLevel: String = "",
    )

    @Serializable
    data class MistreatmentRule(
        @SerialName("mistreatment_rule_id") val ruleId: String = "",
        @SerialName("mistreatment_type") val mistreatmentType: String = "",
        @SerialName("resulting_pattern") val resultingPattern: String = "",
        val manifestations: List<String> = emptyList(),
        @SerialName("rescue_formulas") val rescueFormulas: List<String> = emptyList(),
        @SerialName("six_channel_scope") val sixChannelScope: List<String> = emptyList(),
        val path: List<String> = emptyList(),
        @SerialName("supporting_clauses")
        val supportingClauses: List<String> = emptyList(),
        @SerialName("release_level") val releaseLevel: String = "",
    )

    @Serializable
    private data class InitialRuleRec(
        @SerialName("initial_rule_id") val ruleId: String = "",
        @SerialName("clause_id") val clauseId: String = "",
        @SerialName("rule_type") val ruleType: String = "",
        @SerialName("interpretation_level") val interpretationLevel: String = "",
        @SerialName("release_level") val releaseLevel: String = "",
        val interpretation: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    @Volatile private var loaded = false
    private var clauses: List<LocalClause> = emptyList()
    private var byId: Map<String, LocalClause> = emptyMap()
    private var byNumber: Map<Int, LocalClause> = emptyMap()
    private var rules: List<FormulaRule> = emptyList()
    private val index = Bm25Index()

    // VIP 知識庫（standard 包內無這些資產 → 保持空集，界面自動降級）
    @Volatile private var vipLoaded = false
    private var commentariesByClause: Map<String, List<Commentary>> = emptyMap()
    private var variantsByClause: Map<String, List<ClauseVariant>> = emptyMap()
    private var relationsByClause: Map<String, List<ClauseRelation>> = emptyMap()
    private var initialRulesByClause: Map<String, List<InitialRule>> = emptyMap()
    private var sixChannelRulesList: List<SixChannelRule> = emptyList()
    private var differentialRulesList: List<DifferentialRule> = emptyList()
    private var mistreatmentRulesList: List<MistreatmentRule> = emptyList()
    private var formulaCatalogList: List<FormulaRule> = emptyList()

    val layerLabels = mapOf(
        "A" to "原文直述", "B" to "版本異文", "C" to "注家解釋",
        "D" to "後世類方歸納", "E" to "模型推理",
    )

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                // 逐行容錯：單條損壞記錄跳過，不允許整庫加載失敗導致閃退
                clauses = context.assets.open("shanghan/clauses.jsonl")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter { it.isNotBlank() }
                            .mapNotNull {
                                try {
                                    json.decodeFromString<LocalClause>(it)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            .toList()
                    }
                rules = context.assets.open("shanghan/formula_pattern_rules.jsonl")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.filter { it.isNotBlank() }
                            .mapNotNull {
                                try {
                                    json.decodeFromString<FormulaRule>(it)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            .toList()
                    }
                byId = clauses.associateBy { it.clauseId }
                byNumber = clauses
                    .filter { it.textType == "original_clause" && it.clauseNumber != null }
                    .associateBy { it.clauseNumber!! }
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

    /** VIP 知識庫是否隨包內置（探測注家規則資產是否存在）。 */
    fun vipContentAvailable(): Boolean = try {
        context.assets.open("shanghan/commentary_rules.jsonl").close(); true
    } catch (_: Exception) {
        false
    }

    private inline fun <reified T> readJsonlAsset(path: String): List<T> = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull {
                    try {
                        json.decodeFromString<T>(it)
                    } catch (_: Exception) {
                        null    // 單行損壞跳過
                    }
                }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()      // standard 包無此資產
    }

    /** 惰性加載 VIP 知識庫（首次打開條文詳情時，約 5MB JSONL）。 */
    private suspend fun ensureVipLoaded() {
        if (vipLoaded) return
        mutex.withLock {
            if (vipLoaded) return
            withContext(Dispatchers.IO) {
                commentariesByClause = readJsonlAsset<CommentaryRule>(
                    "shanghan/commentary_rules.jsonl")
                    .groupBy({ it.clauseId }, {
                        Commentary(commentator = it.commentator, book = it.book,
                            chapter = it.chapter, text = it.commentaryText)
                    })
                variantsByClause = readJsonlAsset<VariantRule>(
                    "shanghan/variant_rules.jsonl")
                    .groupBy({ it.clauseId }, {
                        ClauseVariant(book = it.variantBook, text = it.variantText,
                            similarity = it.similarity,
                            differences = it.notableDifferences)
                    })
                // 關係與 Python ClauseRAG 同構：雙端建索引，返回對端 id
                val rels = readJsonlAsset<RelationRec>("shanghan/clause_relations.jsonl")
                val relMap = HashMap<String, MutableList<ClauseRelation>>()
                for (r in rels) {
                    relMap.getOrPut(r.sourceClauseId) { ArrayList() }.add(
                        ClauseRelation(r.relationType, r.targetClauseId,
                            r.description, r.confidence))
                    relMap.getOrPut(r.targetClauseId) { ArrayList() }.add(
                        ClauseRelation(r.relationType, r.sourceClauseId,
                            r.description, r.confidence))
                }
                relationsByClause = relMap
                initialRulesByClause = readJsonlAsset<InitialRuleRec>(
                    "shanghan/initial_rules.jsonl")
                    .groupBy({ it.clauseId }, {
                        InitialRule(id = it.ruleId, type = it.ruleType,
                            strength = it.interpretationLevel,
                            release = it.releaseLevel,
                            interpretation = it.interpretation)
                    })
                sixChannelRulesList = readJsonlAsset(
                    "shanghan/six_channel_rules.jsonl")
                differentialRulesList = readJsonlAsset(
                    "shanghan/differential_rules.jsonl")
                mistreatmentRulesList = readJsonlAsset(
                    "shanghan/mistreatment_rules.jsonl")
            }
            vipLoaded = true
        }
    }

    suspend fun sixChannelRules(): List<SixChannelRule> {
        ensureLoaded(); ensureVipLoaded(); return sixChannelRulesList
    }

    suspend fun differentialRules(): List<DifferentialRule> {
        ensureLoaded(); ensureVipLoaded(); return differentialRulesList
    }

    suspend fun mistreatmentRules(): List<MistreatmentRule> {
        ensureLoaded(); ensureVipLoaded(); return mistreatmentRulesList
    }

    /** 方劑總目：規則庫（按支持條文數降序——常用方靠前，桂枝湯 33 條
     *  不再被規則 ID 序埋沒）+ 語料方劑塊補全（有方無規則的也入目）。 */
    suspend fun formulaCatalog(): List<FormulaRule> {
        ensureLoaded()
        if (formulaCatalogList.isNotEmpty()) return formulaCatalogList
        val ruleByName = rules.associateBy { it.formula }
        val fromBlocks = LinkedHashMap<String, FormulaRule>()
        for (c in clauses) {
            for (fb in c.formulaBlocks) {
                val name = fb.formulaName
                if (name.isBlank() || name in ruleByName || name in fromBlocks) continue
                fromBlocks[name] = FormulaRule(
                    ruleId = "BLOCK_$name",
                    formula = name,
                    sixChannelScope = listOfNotNull(c.sixChannel),
                    corePattern = "（語料方劑塊，未歸納方證規則）",
                    composition = fb.composition,
                    administrationNotes = listOfNotNull(
                        fb.administration.takeIf { it.isNotBlank() }),
                    supportingClauses = listOf(c.clauseId),
                    sourceLevel = "corpus_formula_block",
                    releaseLevel = "corpus",
                )
            }
        }
        formulaCatalogList =
            (rules.sortedByDescending { it.supportingClauses.size } +
                fromBlocks.values.toList())
        return formulaCatalogList
    }

    fun byId(id: String): LocalClause? = byId[id]

    fun byNumber(n: Int): LocalClause? = byNumber[n]

    fun formulaRules(): List<FormulaRule> = rules

    fun allClauses(): List<LocalClause> = clauses

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

    /** 離線條文詳情。VIP 包內置全量規則庫時附帶異文/注家/關係/歸納規則
     *（全息離線）；standard 包這些證據面需連接服務端。 */
    suspend fun clauseDetail(ref: String): ClauseDetail? {
        ensureLoaded()
        val c = ref.toIntOrNull()?.let { byNumber[it] } ?: byId[ref] ?: return null
        ensureVipLoaded()
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
            variants = variantsByClause[c.clauseId].orEmpty(),
            commentaries = commentariesByClause[c.clauseId].orEmpty(),
            relations = relationsByClause[c.clauseId].orEmpty().take(12),
            initialRules = initialRulesByClause[c.clauseId].orEmpty(),
        )
    }
}
