package org.impfai.hermes.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨語言一致性測試（Python textutil.py ↔ Kotlin TextNorm）。
 * 期望值取自 Python 實現的實際輸出。
 */
class TextNormTest {

    @Test
    fun foldVariants_mapsCollationGlyphs() {
        assertEquals("胸脅苦滿", TextNorm.foldVariants("胸脇苦滿"))
        assertEquals("心下痞硬", TextNorm.foldVariants("心下痞鞕"))
        assertEquals("咳", TextNorm.foldVariants("欬"))
        assertEquals("澀", TextNorm.foldVariants("濇"))
        assertEquals("項背強几几", TextNorm.foldVariants("項背強幾幾"))
    }

    @Test
    fun s2t_domainVocabulary() {
        assertEquals("惡寒發熱", TextNorm.s2t("恶寒发热"))
        assertEquals("桂枝湯", TextNorm.s2t("桂枝汤"))
        assertEquals("脈浮緊", TextNorm.s2t("脉浮紧"))
        assertEquals("無汗而喘", TextNorm.s2t("无汗而喘"))
    }

    @Test
    fun normalizeQuery_composesS2tAndFolding() {
        // 簡體輸入 → s2t 得「幾」→ 折疊到「几」（十九輪不變量）
        assertEquals("項背強几几", TextNorm.normalizeQuery(" 项背强几几 "))
        assertEquals("胸脅苦滿", TextNorm.normalizeQuery("胸胁苦满"))
    }

    @Test
    fun t2s_displayOnly() {
        assertEquals("恶寒发热", TextNorm.t2s("惡寒發熱"))
        assertEquals("证", TextNorm.t2s("證"))
    }

    @Test
    fun tokenize_unigramsAndBigrams() {
        // Python: tokenize("太陽病") == ["太","陽","病","太陽","陽病"]
        assertEquals(listOf("太", "陽", "病", "太陽", "陽病"),
            TextNorm.tokenize("太陽病"))
        // 非 CJK 字符被丟棄
        assertEquals(listOf("太", "陽", "太陽"), TextNorm.tokenize("太 abc 陽 123"))
    }

    @Test
    fun tokenize_cjkRangeMatchesPythonRegex() {
        // Python 正則 [㐀-鿿] = U+3400..U+9FFF
        assertTrue(TextNorm.tokenize("㐀").isNotEmpty())
        assertTrue(TextNorm.tokenize("鿿").isNotEmpty())
        assertTrue(TextNorm.tokenize("가").isEmpty()) // 諺文在碼段外
    }
}
