package org.impfai.hermes.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 智能體展示層純函數（無 Android 依賴，JVM 可測）。
 *
 * 外部評審建議二/三的落地原則：客戶端**不虛構**任務進度——後端
 * `agent_trace` 是真實執行記錄（tool_call/reflection/citation_check…），
 * 這裡只做人類可讀化；證據等級同樣不憑空評星，而是映射語料既有的
 * 證據分層（A 原文直述 … E 模型推理）。
 */

// ---------------------------------------------------------------- trace

data class TraceStepView(
    val step: Int,
    val label: String,
    val detail: String = "",
    val warning: Boolean = false,
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()

/** 單步 agent_trace → 人類可讀行；未知 kind 原樣透傳（不丟棄服務端事實）。 */
fun humanizeTraceStep(step: JsonObject): TraceStepView {
    val n = step.int("step") ?: 0
    return when (step.str("kind")) {
        "tool_scope" -> TraceStepView(
            n, "按角色裁剪工具面",
            "角色 ${step.str("role") ?: "?"} · 可用工具 ${step.strList("tools").size} 个",
        )
        "tool_call" -> TraceStepView(
            n, "调用工具 ${step.str("tool") ?: "?"}",
            (step["arguments"] as? JsonObject)
                ?.entries?.joinToString("、") { (k, v) ->
                    "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}"
                } ?: "",
        )
        "red_flag_triage" -> TraceStepView(
            n, "红旗症状分诊",
            "命中 ${step.strList("flags").joinToString("、")}", warning = true,
        )
        "safety_block" -> TraceStepView(
            n, "安全闸门拦截",
            step.strList("intents").joinToString("、"), warning = true,
        )
        "reflection" -> TraceStepView(
            n, "引用核验未过 · 反思重答（第 ${step.int("round") ?: "?"} 轮）",
            step.strList("unsupported").takeIf { it.isNotEmpty() }
                ?.let { "无法核实：${it.joinToString("、")}" } ?: "",
            warning = true,
        )
        "hypotheses" -> TraceStepView(
            n, "生成平行证型假设",
            "共 ${step.int("n") ?: 0} 个" + if (
                (step["needs_clarification"] as? JsonPrimitive)?.contentOrNull == "true"
            ) " · 需补充四诊信息" else "",
        )
        "claim_binding" -> TraceStepView(
            n, "断言→证据逐句绑定",
            ((step["grounding_rate"] as? JsonPrimitive)?.doubleOrNull)
                ?.let { "溯源率 ${(it * 100).toInt()}%（${step.int("n_claims") ?: 0} 句）" }
                ?: "",
        )
        "citation_check" -> {
            val verified = step.strList("verified")
            val unsupported = step.strList("unsupported")
            TraceStepView(
                n, "引用核验",
                buildString {
                    append("已核验 ${verified.size} 条")
                    if (unsupported.isNotEmpty()) {
                        append(" · ${unsupported.size} 条未获支持")
                    }
                },
                warning = unsupported.isNotEmpty(),
            )
        }
        "final" -> TraceStepView(
            n, "生成回答", step.str("backend")?.let { "后端 $it" } ?: "")
        "final_forced" -> TraceStepView(
            n, "推理步数用尽 · 按已获证据成稿", warning = true)
        "tool_budget_exhausted" -> TraceStepView(
            n, "工具调用预算用尽",
            "已用 ${step.int("used") ?: "?"} 次", warning = true)
        "tool_budget_denied" -> TraceStepView(
            n, "工具调用被预算拒绝",
            step.str("tool") ?: "", warning = true)
        else -> TraceStepView(n, step.str("kind") ?: "步骤", "")
    }
}

fun humanizeTrace(steps: List<JsonObject>): List<TraceStepView> =
    steps.map(::humanizeTraceStep)

// ---------------------------------------------------------------- evidence

data class EvidenceGrade(val stars: Int, val label: String)

/**
 * 證據等級 = 語料證據分層的直接映射（不是模型自評分）：
 * A 原文直述 > B 版本異文 > C 注家闡釋 > D 後世歸納 > E/未知 模型推理。
 */
fun evidenceGradeForLayer(layer: String): EvidenceGrade = when (layer) {
    "A" -> EvidenceGrade(5, "原文直接证据")
    "B" -> EvidenceGrade(4, "版本异文证据")
    "C" -> EvidenceGrade(3, "注家阐释")
    "D" -> EvidenceGrade(2, "后世归纳")
    else -> EvidenceGrade(1, "模型推理/未分级")
}

fun EvidenceGrade.starsText(): String =
    "★".repeat(stars) + "☆".repeat(5 - stars)

/** 智能體回答中的單條證據卡（excerpt 為空 = 本地語料查不到，僅給跳轉）。 */
data class EvidenceCardData(
    val clauseId: String,
    val clauseNumber: Int? = null,
    val chapter: String = "",
    val sixChannel: String? = null,
    val layer: String = "",
    val excerpt: String = "",
    val grade: EvidenceGrade = evidenceGradeForLayer(""),
)

// ---------------------------------------------------------------- home

/** 「今日條文」確定性選取：同一天全球一致，語料變更時自動適配。 */
fun dailyClauseIndex(epochDay: Long, size: Int): Int {
    if (size <= 0) return 0
    return ((epochDay % size) + size).toInt() % size
}
