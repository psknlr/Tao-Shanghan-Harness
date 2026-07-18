package org.impfai.hermes.engine

import org.impfai.hermes.core.model.EvidenceGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.12 檢索修復的純函數面：切詞 / 分層配額 / 全量繁簡折算。 */
class UnifiedRetrieverTest {

    @Test
    fun `full t2s maps chars missing from domain table`() {
        // 領域 s2t 小表覆蓋不到的常用字——「耳鳴」修復的根因
        assertEquals("耳鸣", S2TFull.t2s("耳鳴"))
        assertEquals("鼻鸣", S2TFull.t2s("鼻鳴"))
        assertEquals('聋', S2TFull.t2s('聾'))
        // 未收錄字原樣
        assertEquals("abc。", S2TFull.t2s("abc。"))
    }

    @Test
    fun `searchCanon unifies simplified query with traditional text`() {
        val q = TextNorm.searchCanon("耳鸣")
        val line = TextNorm.searchCanon("病人耳鳴目眩者，少陽中風也。")
        assertTrue(line.contains(q))
        // 一字對一字：長度不變（偏移安全前提）
        assertEquals("病人耳鳴目眩者，少陽中風也。".length, line.length)
    }

    @Test
    fun `extractTerms splits natural-language question into search terms`() {
        val terms = UnifiedRetriever.extractTerms("针灸甲乙经的学术特色")
        assertTrue("书名词项须保留: $terms", terms.contains("针灸甲乙经"))
        assertTrue(terms.contains("学术特色"))
        // 短關鍵詞直傳
        assertEquals(listOf("耳鸣"), UnifiedRetriever.extractTerms("耳鸣"))
        // 停用問句詞不進詞項
        val t2 = UnifiedRetriever.extractTerms("请问太阳中风是什么")
        assertTrue(t2.none { it.contains("请问") || it.contains("什么") })
        assertTrue(t2.any { it.contains("太阳中风") })
        // 上限 6
        assertTrue(UnifiedRetriever.extractTerms(
            "桂枝汤 麻黄汤 白虎汤 承气汤 四逆汤 理中丸 真武汤 小柴胡汤").size <= 6)
    }

    private fun clause(n: Int) = UnifiedRetriever.UnifiedHit(
        "clause", "SHL_%04d".format(n), "条文$n", EvidenceGrade(5, "原文直接证据"),
        clauseId = "SHL_%04d".format(n))

    private fun lib(n: Int, stars: Int, label: String) =
        UnifiedRetriever.UnifiedHit(
            "library", "《书$stars-$n》", "书证$n", EvidenceGrade(stars, label),
            book = "书$stars-$n")

    @Test
    fun `mergeTiered caps clause flood and reserves library quotas`() {
        val clauses = (1..40).map { clause(it) }         // 條文洪泛
        val library = (1..20).map { lib(it, 4, "历代要籍") } +
            (1..10).map { lib(100 + it, 2, "医案纪实") }
        val merged = UnifiedRetriever.mergeTiered(clauses, library, 50)
        assertEquals(50, merged.size)
        val nClause = merged.count { it.sourceType == "clause" }
        // 條文層不再擠掉書證：書證至少拿到配額+回填
        assertTrue("clause=$nClause", nClause <= 25)
        assertTrue(merged.count { it.grade.stars == 4 } >= 10)
        assertTrue(merged.count { it.grade.label == "医案纪实" } >= 4)
    }

    @Test
    fun `mergeTiered fills from leftovers when a tier is scarce`() {
        val clauses = (1..5).map { clause(it) }
        val library = (1..8).map { lib(it, 3, "论说文献") }
        val merged = UnifiedRetriever.mergeTiered(clauses, library, 50)
        // 池不足 50：全部保留、無重複
        assertEquals(13, merged.size)
        assertEquals(13, merged.distinctBy { it.ref }.size)
    }

    @Test
    fun `gradeForCategory tiers by bibliographic layer`() {
        assertEquals(5, UnifiedRetriever.gradeForCategory("針灸 內經").stars)
        assertEquals(4, UnifiedRetriever.gradeForCategory("針灸").stars)
        assertEquals(2, UnifiedRetriever.gradeForCategory("醫案").stars)
        assertEquals(3, UnifiedRetriever.gradeForCategory("綜合").stars)
    }
}
