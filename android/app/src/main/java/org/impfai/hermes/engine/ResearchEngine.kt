package org.impfai.hermes.engine

/**
 * 端側科研挖掘（原 research 模塊的確定性統計子集）：
 * 主題相關條文 → 症狀/藥物頻次、藥對共現、六經分佈、方劑頻次。
 * 全部為語料計數，毫秒級；LLM 僅在論文潤色時可選介入。
 */
object ResearchEngine {

    data class Report(
        val topic: String,
        val relatedClauseIds: List<String>,
        val symptomFreq: List<Pair<String, Int>>,
        val herbFreq: List<Pair<String, Int>>,
        val herbPairFreq: List<Pair<String, Int>>,
        val channelDist: List<Pair<String, Int>>,
        val formulaFreq: List<Pair<String, Int>>,
        val totalClauses: Int,
    )

    suspend fun mine(store: LocalClauseStore, topicRaw: String): Report {
        store.ensureLoaded()
        val topic = TextNorm.normalizeQuery(topicRaw)
        val related = ArrayList<LocalClauseStore.LocalClause>()
        // 主題含方名 → 該方條文；否則文本/證候要素包含主題詞
        val hits = store.search(topic, topK = 60)
        val hitIds = hits.map { it.clauseId }.toSet()
        val all = store.allClauses()
        for (c in all) {
            val folded = TextNorm.foldVariants(c.cleanText)
            if (c.clauseId in hitIds || folded.contains(topic) ||
                c.formulaNames.any { TextNorm.foldVariants(it).contains(topic) }
            ) {
                related.add(c)
            }
        }
        val symptom = HashMap<String, Int>()
        val herb = HashMap<String, Int>()
        val pair = HashMap<String, Int>()
        val channel = HashMap<String, Int>()
        val formula = HashMap<String, Int>()
        for (c in related) {
            c.symptoms.forEach { symptom[it] = (symptom[it] ?: 0) + 1 }
            c.sixChannel?.takeIf { it.isNotBlank() }?.let {
                channel[it] = (channel[it] ?: 0) + 1
            }
            c.formulaNames.forEach { formula[it] = (formula[it] ?: 0) + 1 }
            for (fb in c.formulaBlocks) {
                val herbs = fb.composition.map { it.herb }.filter { it.isNotBlank() }
                herbs.forEach { herb[it] = (herb[it] ?: 0) + 1 }
                for (i in herbs.indices) for (j in i + 1 until herbs.size) {
                    val key = listOf(herbs[i], herbs[j]).sorted()
                        .joinToString("·")
                    pair[key] = (pair[key] ?: 0) + 1
                }
            }
        }
        fun top(m: Map<String, Int>, n: Int) =
            m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key }).take(n).map { it.key to it.value }
        return Report(
            topic = topicRaw.trim(),
            relatedClauseIds = related.map { it.clauseId },
            symptomFreq = top(symptom, 12),
            herbFreq = top(herb, 12),
            herbPairFreq = top(pair, 10),
            channelDist = top(channel, 8),
            formulaFreq = top(formula, 10),
            totalClauses = related.size,
        )
    }
}
