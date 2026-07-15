package org.impfai.hermes.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * API v1 合同 DTO。字段名與 backend /api/v1 響應一一對應（樣本採自
 * 真實服務端，見 docs/ANDROID.md）。全部字段帶默認值 + ignoreUnknownKeys，
 * 服務端加字段不會破壞客戶端（合同測試不變量）。
 */

// ---------------------------------------------------------------- envelope
@Serializable
data class Envelope<T>(
    @SerialName("request_id") val requestId: String = "",
    @SerialName("api_version") val apiVersion: String = "",
    val data: T? = null,
    val error: ApiError? = null,
    val meta: EnvelopeMeta? = null,
)

@Serializable
data class ApiError(
    val code: String = "INTERNAL_ERROR",
    val message: String = "",
    val retryable: Boolean = false,
    val details: JsonObject? = null,
)

@Serializable
data class EnvelopeMeta(
    val backend: String? = null,
    @SerialName("effective_role") val effectiveRole: String? = null,
    @SerialName("role_ceiling") val roleCeiling: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
)

// ---------------------------------------------------------------- meta
@Serializable
data class HealthData(
    val ok: Boolean = false,
    val ready: Boolean = false,
    val backend: String = "",
)

@Serializable
data class ReadyzCheck(val check: String = "", val ok: Boolean = false, val detail: String = "")

/** /readyz 是免鑒權裸探針，不走 v1 信封。 */
@Serializable
data class Readyz(
    val ready: Boolean = false,
    val checks: List<ReadyzCheck> = emptyList(),
    val hint: String = "",
)

@Serializable
data class WhoAmI(
    @SerialName("principal_id") val principalId: String = "",
    @SerialName("tenant_id") val tenantId: String = "",
    @SerialName("role_ceiling") val roleCeiling: String = "",
    @SerialName("effective_role") val effectiveRole: String? = null,
    @SerialName("request_id") val requestId: String = "",
)

// ---------------------------------------------------------------- search
@Serializable
data class SearchRequest(
    val query: String,
    @SerialName("top_k") val topK: Int = 8,
    @SerialName("six_channel") val sixChannel: String? = null,
    val formula: String? = null,
    val field: String? = null,
    val expand: Boolean = false,
    val role: String? = null,
)

@Serializable
data class SearchHit(
    @SerialName("clause_id") val clauseId: String = "",
    @SerialName("clause_number") val clauseNumber: Int? = null,
    val book: String = "",
    val chapter: String = "",
    @SerialName("six_channel") val sixChannel: String? = null,
    val text: String = "",
    @SerialName("text_type") val textType: String = "",
    val layer: String = "",
    @SerialName("layer_label") val layerLabel: String = "",
    val formulas: List<String> = emptyList(),
    val score: Double = 0.0,
    @SerialName("match_source") val matchSource: String = "",
)

@Serializable
data class SearchData(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val count: Int = 0,
    // 服務端「軟錯誤」（HTTP 200 + {"error": ...}）——倉庫層據此判定失敗
    @SerialName("error") val errorMessage: String? = null,
)

// ---------------------------------------------------------------- clause
@Serializable
data class Entities(
    val symptoms: List<String> = emptyList(),
    @SerialName("negated_findings") val negatedFindings: List<String> = emptyList(),
    val pulse: List<String> = emptyList(),
    val formulas: List<String> = emptyList(),
    @SerialName("disease_patterns") val diseasePatterns: List<String> = emptyList(),
    val therapy: List<String> = emptyList(),
    val contraindications: List<String> = emptyList(),
    val mistreatment: List<String> = emptyList(),
    val prognosis: List<String> = emptyList(),
)

@Serializable
data class HerbDose(
    val herb: String = "",
    @SerialName("dose_processing") val doseProcessing: String = "",
)

@Serializable
data class FormulaBlock(
    @SerialName("formula_name") val formulaName: String = "",
    val composition: List<HerbDose> = emptyList(),
    val preparation: String = "",
    val administration: String = "",
    @SerialName("post_notes") val postNotes: List<String> = emptyList(),
    @SerialName("raw_text") val rawText: String = "",
)

@Serializable
data class InitialRule(
    val id: String = "",
    val type: String = "",
    val strength: String = "",
    val release: String = "",
    val interpretation: String = "",
)

@Serializable
data class ClauseRelation(
    @SerialName("relation_type") val relationType: String = "",
    @SerialName("clause_id") val clauseId: String = "",
    val description: String = "",
    val confidence: Double = 0.0,
)

@Serializable
data class ClauseVariant(
    val book: String = "",
    val text: String = "",
    val similarity: Double = 0.0,
    val differences: List<String> = emptyList(),
)

@Serializable
data class Commentary(
    val commentator: String = "",
    val book: String = "",
    val chapter: String = "",
    val text: String = "",
)

@Serializable
data class ClauseDetail(
    @SerialName("clause_id") val clauseId: String = "",
    @SerialName("clause_number") val clauseNumber: Int? = null,
    val chapter: String = "",
    @SerialName("six_channel") val sixChannel: String? = null,
    @SerialName("layer_label") val layerLabel: String = "",
    val text: String = "",
    val entities: Entities? = null,
    // 患者投影可能整鍵移除 formula_blocks / historical_citations——默認值兜底
    @SerialName("formula_blocks") val formulaBlocks: List<FormulaBlock> = emptyList(),
    @SerialName("initial_rules") val initialRules: List<InitialRule> = emptyList(),
    val relations: List<ClauseRelation> = emptyList(),
    val variants: List<ClauseVariant> = emptyList(),
    val commentaries: List<Commentary> = emptyList(),
    @SerialName("commentary_analysis") val commentaryAnalysis: JsonObject? = null,
    @SerialName("historical_citations") val historicalCitations: JsonObject? = null,
    val mode: String = "",
    @SerialName("safety_notice") val safetyNotice: String = "",
    @SerialName("_role_projection") val roleProjection: JsonObject? = null,
    @SerialName("error") val errorMessage: String? = null,
)

// ---------------------------------------------------------------- match
@Serializable
data class MatchRequest(
    val symptoms: List<String>,
    val pulse: List<String> = emptyList(),
    @SerialName("six_channel") val sixChannel: String? = null,
    @SerialName("top_k") val topK: Int = 5,
    val role: String? = null,
)

@Serializable
data class MatchedPattern(
    val formula: String = "",
    @SerialName("match_score") val matchScore: Double = 0.0,
    @SerialName("six_channel") val sixChannel: String? = null,
    @SerialName("core_pattern") val corePattern: String = "",
    @SerialName("core_reason") val coreReason: String = "",
    @SerialName("matched_findings") val matchedFindings: List<String> = emptyList(),
    val conflicts: List<JsonElement> = emptyList(),
    val contraindications: List<JsonElement> = emptyList(),
    @SerialName("source_level") val sourceLevel: String = "",
    @SerialName("release_level") val releaseLevel: String = "",
    @SerialName("interpretation_warning") val interpretationWarning: String = "",
    val evidence: List<JsonElement> = emptyList(),
)

@Serializable
data class MatchData(
    val input: JsonObject? = null,
    @SerialName("matched_formula_patterns")
    val matchedFormulaPatterns: List<MatchedPattern> = emptyList(),
    @SerialName("match_count") val matchCount: Int = 0,
    val mode: String = "",
    @SerialName("safety_notice") val safetyNotice: String = "",
    @SerialName("assistive_only") val assistiveOnly: Boolean = false,
    @SerialName("_role_projection") val roleProjection: JsonObject? = null,
    @SerialName("error") val errorMessage: String? = null,
)

// ---------------------------------------------------------------- agent
@Serializable
data class AgentRequest(
    val question: String,
    @SerialName("max_steps") val maxSteps: Int = 5,
    val role: String? = null,
)

@Serializable
data class CitationReport(
    val cited: List<String> = emptyList(),
    val verified: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    @SerialName("outside_evidence") val outsideEvidence: List<String> = emptyList(),
    @SerialName("quote_mismatches") val quoteMismatches: List<JsonElement> = emptyList(),
    @SerialName("attribution_warnings") val attributionWarnings: List<JsonElement> = emptyList(),
    @SerialName("has_any_citation") val hasAnyCitation: Boolean = false,
    val ok: Boolean = false,
)

/** 拒答與正答共用一個 DTO：字段全部可空/帶默認。 */
@Serializable
data class AgentData(
    val refused: Boolean = false,
    @SerialName("refused_intents") val refusedIntents: List<String> = emptyList(),
    val message: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val backend: String? = null,
    @SerialName("tools_used") val toolsUsed: List<String> = emptyList(),
    @SerialName("evidence_clause_ids") val evidenceClauseIds: List<String> = emptyList(),
    @SerialName("citation_report") val citationReport: CitationReport? = null,
    val claims: JsonObject? = null,
    @SerialName("reflection_rounds") val reflectionRounds: Int = 0,
    @SerialName("agent_trace") val agentTrace: List<JsonObject> = emptyList(),
    val hypotheses: List<JsonObject> = emptyList(),
    val decision: String? = null,
    val clarification: JsonObject? = null,
    val mode: String = "",
    @SerialName("safety_notice") val safetyNotice: String = "",
    @SerialName("_role_projection") val roleProjection: JsonObject? = null,
    @SerialName("error") val errorMessage: String? = null,
)

// ---------------------------------------------------------------- misc
@Serializable
data class FormulasData(val formulas: List<String> = emptyList())

@Serializable
data class ChannelsData(val channels: List<String> = emptyList())

@Serializable
data class TeachRequest(val channel: String, val role: String? = null)

@Serializable
data class DomainInfo(
    @SerialName("domain_id") val domainId: String = "",
    @SerialName("display_name") val displayName: String = "",
    val status: String = "",
    val executable: Boolean = false,
    val capabilities: List<String> = emptyList(),
    @SerialName("evidence_levels") val evidenceLevels: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
data class DomainsData(val domains: List<DomainInfo> = emptyList())

@Serializable
data class ContentPackage(
    val id: String = "",
    val files: Int = 0,
    @SerialName("raw_size") val rawSize: Long = 0,
    val size: Long = 0,
    val sha256: String = "",
    val url: String = "",
    val required: Boolean = false,
)

@Serializable
data class ManifestData(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("content_version") val contentVersion: String = "",
    @SerialName("corpus_fingerprint") val corpusFingerprint: String = "",
    @SerialName("minimum_app_version") val minimumAppVersion: String = "",
    val packages: List<ContentPackage> = emptyList(),
)

/** 結果來源標記：本地離線結果與服務端結果必須可區分展示。 */
enum class ResultOrigin { LOCAL_CORPUS, SERVER }
