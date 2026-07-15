package org.impfai.hermes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.engine.LocalFormulaMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 端側引擎金標準對照（Robolectric：真實 APK 資產）。
 * 期望值取自 Python 端相同輸入的實測輸出（tests/test_server.py）：
 *  - search("往來寒熱 胸脅苦滿") 首位 = SHL_SONGBEN_0096（小柴胡湯條）
 *  - match(惡寒/發熱/無汗/身疼痛 + 浮緊) 首位 = 麻黃湯
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = HermesApp::class)
class LocalEngineTest {

    private val store = LocalClauseStore(ApplicationProvider.getApplicationContext())

    @Test
    fun corpus_loads_681_records() = runBlocking {
        store.ensureLoaded()
        val (total, canonical) = store.stats()
        assertEquals(681, total)
        assertEquals(398, canonical)
    }

    @Test
    fun golden_search_wanglaihanre() = runBlocking {
        // 服務端同查詢實測首位 = SHL_SONGBEN_0136（大柴胡湯條）——
        // 端側 BM25 與 Python 排序一致（樣本見 docs/ANDROID.md）
        val hits = store.search("往來寒熱 胸脅苦滿", topK = 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("SHL_SONGBEN_0136", hits.first().clauseId)
    }

    @Test
    fun golden_search_simplified_input() = runBlocking {
        // 簡體輸入經 s2t + 異體字折疊後命中繁體語料，排序同繁體輸入
        val hits = store.search("往来寒热 胸胁苦满", topK = 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("SHL_SONGBEN_0136", hits.first().clauseId)
    }

    @Test
    fun clause_number_fast_path() = runBlocking {
        assertEquals("SHL_SONGBEN_0012",
            store.search("第12条", topK = 5).first().clauseId)
        assertEquals("SHL_SONGBEN_0012",
            store.search("12", topK = 5).first().clauseId)
    }

    @Test
    fun golden_match_mahuangtang() = runBlocking {
        val res = LocalFormulaMatcher.match(
            store,
            symptomsRaw = listOf("恶寒", "发热", "无汗", "身疼痛"),
            pulseRaw = listOf("浮紧"),
            sixChannel = null,
        )
        assertTrue(res.matchedFormulaPatterns.isNotEmpty())
        assertEquals("麻黃湯", res.matchedFormulaPatterns.first().formula)
        assertTrue(res.assistiveOnly)
        assertTrue(res.matchedFormulaPatterns.first().evidence.isNotEmpty())
    }

    @Test
    fun vip_clause_detail_holography() = runBlocking {
        val d = store.clauseDetail("96")!!
        assertEquals("SHL_SONGBEN_0096", d.clauseId)
        assertTrue(d.text.contains("往來寒熱"))
        if (BuildConfig.VIP) {
            // VIP 包：注家/異文/關係/歸納規則全息離線
            assertTrue("VIP 注家缺失", d.commentaries.isNotEmpty())
            assertTrue("VIP 異文缺失", d.variants.isNotEmpty())
            assertTrue("VIP 關係缺失", d.relations.isNotEmpty())
            assertTrue("VIP 規則缺失", d.initialRules.isNotEmpty())
        }
    }

    @Test
    fun vip_skills_available() = runBlocking {
        val skills = org.impfai.hermes.engine.SkillStore(
            ApplicationProvider.getApplicationContext()).list()
        if (BuildConfig.VIP) {
            assertEquals(139, skills.size)
        } else {
            assertTrue(skills.isEmpty())
        }
    }
}
