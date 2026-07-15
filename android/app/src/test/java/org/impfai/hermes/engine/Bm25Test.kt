package org.impfai.hermes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25Test {

    private fun index(): Bm25Index {
        val idx = Bm25Index()
        idx.add("C1", "太陽之為病，脈浮，頭項強痛而惡寒。")
        idx.add("C12", "太陽中風，陽浮而陰弱，嗇嗇惡寒，淅淅惡風，翕翕發熱，桂枝湯主之。")
        idx.add("C35", "太陽病，頭痛發熱，身疼腰痛，惡風無汗而喘者，麻黃湯主之。")
        idx.add("C96", "傷寒五六日中風，往來寒熱，胸脅苦滿，嘿嘿不欲飲食，小柴胡湯主之。")
        idx.finalizeIndex()
        return idx
    }

    @Test
    fun search_ranksExactPhraseFirst() {
        val idx = index()
        val hits = idx.search("往來寒熱 胸脅苦滿", topK = 4)
        assertTrue(hits.isNotEmpty())
        assertEquals("C96", hits.first().first)
    }

    @Test
    fun search_formulaNameHitsItsClause() {
        val idx = index()
        assertEquals("C12", idx.search("桂枝湯", topK = 2).first().first)
        assertEquals("C35", idx.search("麻黃湯", topK = 2).first().first)
    }

    @Test
    fun search_emptyIndexAndNoOverlap() {
        val empty = Bm25Index()
        empty.finalizeIndex()
        assertTrue(empty.search("太陽").isEmpty())
        assertTrue(index().search("xyz").isEmpty())
    }

    @Test
    fun scores_positiveAndDescending() {
        val hits = index().search("太陽病 惡寒", topK = 4)
        assertTrue(hits.all { it.second > 0 })
        assertEquals(hits.map { it.second }.sortedDescending(),
            hits.map { it.second })
    }
}
