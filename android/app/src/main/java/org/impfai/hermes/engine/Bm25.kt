package org.impfai.hermes.engine

import kotlin.math.ln

/**
 * Okapi BM25 —— 移植自 backend/hermes_shanghan/rag/bm25.py。
 * 常數與公式逐項一致：k1=1.5, b=0.75,
 * idf = ln(1 + (N - df + 0.5) / (df + 0.5)),
 * score += idf * tf * (k1+1) / (tf + k1 * (1 - b + b * dl/avgdl))。
 */
class Bm25Index(
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
) {
    private val docIds = ArrayList<String>()
    private val docLen = ArrayList<Int>()
    private val df = HashMap<String, Int>()
    private val postings = HashMap<String, MutableList<IntArray>>() // (docIdx, tf)
    private var avgdl = 0.0
    private var finalized = false

    fun add(docId: String, text: String) {
        check(!finalized) { "index already finalized" }
        val toks = TextNorm.tokenize(text)
        val idx = docIds.size
        docIds.add(docId)
        docLen.add(toks.size)
        val counts = HashMap<String, Int>()
        for (t in toks) counts[t] = (counts[t] ?: 0) + 1
        for ((t, c) in counts) {
            df[t] = (df[t] ?: 0) + 1
            postings.getOrPut(t) { ArrayList() }.add(intArrayOf(idx, c))
        }
    }

    fun finalizeIndex() {
        avgdl = if (docLen.isEmpty()) 0.0 else docLen.sum().toDouble() / docLen.size
        finalized = true
    }

    fun search(query: String, topK: Int = 10): List<Pair<String, Double>> {
        if (docIds.isEmpty()) return emptyList()
        val qToks = TextNorm.tokenize(query).toSet()
        val n = docIds.size
        val scores = HashMap<Int, Double>()
        for (t in qToks) {
            val plist = postings[t] ?: continue
            val dfT = df[t] ?: continue
            val idf = ln(1 + (n - dfT + 0.5) / (dfT + 0.5))
            for (p in plist) {
                val i = p[0]
                val tf = p[1].toDouble()
                val dl = (docLen[i].takeIf { it > 0 } ?: 1).toDouble()
                val denom = tf + k1 * (1 - b + b * dl / avgdl)
                scores[i] = (scores[i] ?: 0.0) + idf * tf * (k1 + 1) / denom
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { docIds[it.key] to it.value }
    }
}
