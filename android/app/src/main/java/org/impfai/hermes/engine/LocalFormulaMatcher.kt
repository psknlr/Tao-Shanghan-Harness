package org.impfai.hermes.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.impfai.hermes.core.model.MatchData
import org.impfai.hermes.core.model.MatchedPattern

/**
 * 端側方證匹配 —— 移植自 backend/hermes_shanghan/apps/doctor.py
 * FormulaMatcher.match（常數逐項一致）：
 *
 *   核心證命中 +2.0 ｜ 兼證 +1.0 ｜ 近似核心證（字 Jaccard≥0.6）+1.5
 *   提綱證（六經提綱條文症狀）+1.0 ｜ 反證 −2.5
 *   核心脈 +2.0 ｜ 兼脈 +1.0（「脈」前綴剝除後雙向包含）
 *   證據厚度加分 min(0.3, 0.05×支持條文數)
 *   歸一化 norm = clamp(score / (2×(症狀數+脈數)), 0..1)
 *   排序 (-norm, -raw, -支持條文數)，match_score = round(norm, 2)
 *
 * 與服務端的已知差距：反證對錶取 lexicon.CONTRADICTORY_SYMPTOMS 快照；
 * 服務端 governed() 的角色治理由 UI 常駐免責聲明承擔（端側為學習研究模式）。
 */
object LocalFormulaMatcher {

    // lexicon.CONTRADICTORY_SYMPTOMS 快照（textutil 規範化後的繁體形）
    private val CONTRADICTORY = listOf(
        "汗出" to "無汗", "自汗出" to "無汗", "惡寒" to "不惡寒",
        "惡風" to "不惡風", "渴" to "不渴", "發熱" to "不發熱",
        "能食" to "不能食", "小便利" to "小便不利",
        "小便自利" to "小便不利", "下利" to "不大便", "但欲寐" to "不得眠",
    )

    // config.CHANNEL_OUTLINE_CLAUSE：六經提綱條文號
    private val OUTLINE_CLAUSE = mapOf(
        "太陽病" to 1, "陽明病" to 180, "少陽病" to 263,
        "太陰病" to 273, "少陰病" to 281, "厥陰病" to 326,
    )

    private fun charJaccard(a: String, b: String): Double {
        val sa = a.toSet(); val sb = b.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        return (sa intersect sb).size.toDouble() / (sa union sb).size
    }

    private fun contradicts(finding: String, patternTerms: List<String>): String? {
        for ((a, b) in CONTRADICTORY) {
            if (finding == a && b in patternTerms) return b
            if (finding == b && a in patternTerms) return a
        }
        return null
    }

    suspend fun match(
        store: LocalClauseStore,
        symptomsRaw: List<String>,
        pulseRaw: List<String>,
        sixChannel: String?,
        topK: Int = 5,
    ): MatchData {
        store.ensureLoaded()
        val symptoms = symptomsRaw.mapNotNull {
            it.trim().takeIf(String::isNotEmpty)?.let(TextNorm::normalizeQuery)
        }
        val pulse = pulseRaw.mapNotNull {
            it.trim().takeIf(String::isNotEmpty)?.let(TextNorm::normalizeQuery)
        }
        // 提綱證表：channel → 提綱條文的 symptoms
        val outlineSymptoms = OUTLINE_CLAUSE.mapNotNull { (ch, num) ->
            store.byNumber(num)?.let { ch to it.symptoms }
        }.toMap()

        data class Cand(
            val norm: Double, val raw: Double,
            val rule: LocalClauseStore.FormulaRule,
            val hits: List<String>, val conflicts: List<String>,
        )

        val results = ArrayList<Cand>()
        for (r in store.formulaRules()) {
            if (r.releaseLevel == "rejected") continue
            if (!sixChannel.isNullOrBlank() && sixChannel !in r.sixChannelScope) continue
            var score = 0.0
            val hits = ArrayList<String>()
            val conflicts = ArrayList<String>()
            val patternSyms = r.coreSymptoms + r.associatedSymptoms
            for (s in symptoms) {
                var matched = false
                for (cs in r.coreSymptoms) {
                    if (s == cs || s in cs || cs in s) {
                        score += 2.0; hits.add("核心证：$cs"); matched = true; break
                    }
                }
                if (!matched) {
                    for (asym in r.associatedSymptoms) {
                        if (s == asym || s in asym || asym in s) {
                            score += 1.0; hits.add("兼证：$asym"); matched = true; break
                        }
                    }
                }
                if (!matched) {
                    for (cs in r.coreSymptoms) {
                        if (s.length >= 3 && cs.length >= 3 &&
                            charJaccard(s, cs) >= 0.6
                        ) {
                            score += 1.5; hits.add("近似核心证：$cs≈$s")
                            matched = true; break
                        }
                    }
                }
                if (!matched) {
                    val ch = r.sixChannelScope.firstOrNull {
                        s in outlineSymptoms[it].orEmpty()
                    }
                    if (ch != null) {
                        score += 1.0; hits.add("提纲证：$s（$ch）"); matched = true
                    }
                }
                if (!matched) {
                    contradicts(s, patternSyms)?.let { contra ->
                        score -= 2.5
                        conflicts.add("所述「$s」与本方证之「$contra」相反")
                    }
                }
            }
            for (p in pulse) {
                val body = p.trimStart('脈')
                var matched = false
                for (cp in r.corePulse) {
                    if (body == cp || body in cp || cp in body) {
                        score += 2.0; hits.add("核心脉：$cp"); matched = true; break
                    }
                }
                if (!matched) {
                    for (ap in r.associatedPulse) {
                        if (body == ap || body in ap || ap in body) {
                            score += 1.0; hits.add("兼脉：$ap"); break
                        }
                    }
                }
            }
            if (score <= 0) continue
            score += minOf(0.3, 0.05 * r.supportingClauses.size)
            val denom = (2.0 * (symptoms.size + pulse.size)).takeIf { it > 0 } ?: 1.0
            val norm = (score / denom).coerceIn(0.0, 1.0)
            results.add(Cand(norm, score, r, hits, conflicts))
        }

        results.sortWith(
            compareByDescending<Cand> { it.norm }
                .thenByDescending { it.raw }
                .thenByDescending { it.rule.supportingClauses.size }
        )

        val matches = results.take(topK).map { c ->
            val evidence = c.rule.supportingClauses.take(3).mapNotNull { cid ->
                store.byId(cid)?.let { cl ->
                    buildJsonObject {
                        put("book", cl.bookTitle)
                        put("chapter", cl.chapter)
                        put("clause_id", cl.clauseId)
                        cl.clauseNumber?.let { put("clause_number", it) }
                        put("text", cl.cleanText)
                    }
                }
            }
            MatchedPattern(
                formula = c.rule.formula,
                matchScore = Bm25Index.roundHalfEven(c.norm, 2),
                sixChannel = c.rule.sixChannelScope.joinToString("、"),
                corePattern = c.rule.corePattern,
                coreReason = if (c.hits.isNotEmpty())
                    c.hits.take(6).joinToString("、") { it.substringAfter("：") } +
                        "与${c.rule.corePattern}（${c.rule.formula}）相关度较高。"
                else "",
                matchedFindings = c.hits,
                conflicts = c.conflicts.map { JsonPrimitive(it) },
                contraindications = c.rule.contraindications.take(3)
                    .map { JsonPrimitive("${it.condition}（${it.clauseId}）") },
                sourceLevel = c.rule.sourceLevel,
                releaseLevel = c.rule.releaseLevel,
                interpretationWarning = c.rule.interpretationWarning,
                evidence = evidence,
            )
        }
        return MatchData(
            matchedFormulaPatterns = matches,
            matchCount = matches.size,
            mode = "local",
            assistiveOnly = true,
            safetyNotice = "端侧确定性匹配（本地规则库 · 学习研究辅助）：" +
                "以上仅为古籍方证规则的机械比对结果，不构成诊断或处方建议，" +
                "如何用药请务必由执业中医师当面判断。",
        )
    }
}
