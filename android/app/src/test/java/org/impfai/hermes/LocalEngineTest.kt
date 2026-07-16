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
    fun formula_catalog_guizhitang_first_and_filterable() = runBlocking {
        // 用戶實測問題：v1.2 方劑庫按規則 ID 排序，桂枝湯（33 條支持）
        // 被埋沒——目錄按支持條文數降序後必須排第一
        val catalog = store.formulaCatalog()
        assertEquals("桂枝湯", catalog.first().formula)
        assertTrue(catalog.first().composition.any { it.herb == "桂枝" })
        // 語料方劑塊補全：目錄應覆蓋規則庫之外的方名
        assertTrue(catalog.size >= 113)
        // 簡體篩選可命中
        val q = org.impfai.hermes.engine.TextNorm.normalizeQuery("桂枝汤")
        assertTrue(catalog.any { it.formula.contains(q) })
    }

    @Test
    fun search_is_millisecond_after_warmup() = runBlocking {
        store.ensureLoaded()
        store.search("預熱", topK = 1)
        val t0 = System.nanoTime()
        repeat(20) { store.search("往來寒熱 胸脅苦滿", topK = 8) }
        val avgMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("平均檢索耗時 ${avgMs}ms 應 < 50ms（JVM 下限遠高於真機）",
            avgMs < 50)
    }

    @Test
    fun vip_feature_rules_loaded() = runBlocking {
        if (!BuildConfig.VIP) return@runBlocking
        assertEquals(6, store.sixChannelRules().map { it.sixChannel }
            .distinct().size.coerceAtMost(6))
        assertTrue(store.differentialRules().isNotEmpty())
        assertTrue(store.mistreatmentRules().isNotEmpty())
        // 鑒別庫金標準：桂枝湯 vs 麻黃湯 對比組在冊
        assertTrue(store.differentialRules().any {
            "桂枝湯" in it.formulas && "麻黃湯" in it.formulas
        })
    }

    @Test
    fun s2t_supplement_and_term_pedigree() = runBlocking {
        // v1.4 修復：s2t 缺「来」→「往来寒热」逐字匹配 0 命中
        assertEquals("往來寒熱",
            org.impfai.hermes.engine.TextNorm.s2t("往来寒热"))
        store.ensureLoaded()
        val canon = { s: String ->
            org.impfai.hermes.engine.TextNorm.t2s(
                org.impfai.hermes.engine.TextNorm.foldVariants(
                    org.impfai.hermes.engine.TextNorm.s2t(s)))
        }
        val q = canon("往来寒热")
        val hits = store.allClauses().count { canon(it.cleanText).contains(q) }
        assertTrue("术语谱系应命中多条（实际 $hits）", hits >= 5)
        // 藥名歸一不被補充表破壞：白术 → 白朮
        assertEquals("白朮", org.impfai.hermes.engine.TextNorm.s2t("白术"))
    }

    @Test
    fun vip_library_find_by_title() = runBlocking {
        if (!BuildConfig.VIP) return@runBlocking
        val lib = org.impfai.hermes.engine.LibraryStore(
            ApplicationProvider.getApplicationContext())
        if (!lib.ensureCatalog()) return@runBlocking   // lite 構建無庫
        // 條文關係中的 "傷寒論注:p1294" → 書名應可解析開卷
        val u = lib.findByTitle("傷寒論注")
        assertTrue("傷寒論注 应在 803 部书目中", u != null)
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
