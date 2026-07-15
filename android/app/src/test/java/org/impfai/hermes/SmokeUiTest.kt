package org.impfai.hermes

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 全屏冒煙測試（Robolectric + 真實 Compose + 真實 APK 資產）：
 * 閃退回歸防線——任何屏幕組合期崩潰、離線檢索失效都會在這裡紅。
 * VIP 變體默認純端側（offlineOnly=true），測試零網絡、確定性。
 *
 * 注意：Robolectric 下 ComposeTestRule.waitUntil 不泵主循環，必須
 * waitForIdle + 輪詢（實測 waitUntil 直接 20s 超時而樹早已就緒）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = HermesApp::class)
class SmokeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            rule.waitForIdle()
            val found = rule.onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            if (found) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "未在 ${timeoutMs}ms 內找到文本: $text\n" +
                        rule.onRoot().printToString(maxDepth = 10).take(4000))
            }
            Thread.sleep(150)   // 讓 IO 線程（語料解析）推進
        }
    }

    @Test
    fun app_launches_and_all_tabs_render() {
        waitForText("伤寒Hermes")
        rule.onNodeWithText("检索").performClick()
        waitForText("症状、脉象、方名")
        rule.onNodeWithText("辨证").performClick()
        waitForText("方证匹配")
        rule.onNodeWithText("智能体").performClick()
        // VIP 默認直連大模型；standard 默認服務端
        waitForText(if (BuildConfig.VIP) "直连模式" else "围绕《伤寒论》")
        rule.onNodeWithText("我的").performClick()
        waitForText("显示")
        rule.onNodeWithText("首页").performClick()
        waitForText("六经")
    }

    @Test
    fun offline_search_via_keyboard_returns_results_and_opens_clause() {
        waitForText("伤寒Hermes")
        rule.onNodeWithText("检索").performClick()
        waitForText("症状、脉象、方名")
        // 鍵盤搜索鍵（v1.1 未接線的路徑）
        rule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextInput("往来寒热 胸胁苦满")
        rule.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        // 端側 BM25 首位命中大柴胡湯條（第 136 条，與服務端排序一致）
        waitForText("第 136 条")
        rule.onAllNodes(hasText("第 136 条", substring = false))
            .onFirst().performClick()
        waitForText("原文")
        if (BuildConfig.VIP) {
            // VIP 離線全息：九注家隨包（首次加載 5MB 規則庫）
            waitForText("注家", timeoutMs = 60_000)
        }
    }

    @Test
    fun formula_library_tab_renders_offline() {
        waitForText("伤寒Hermes")
        rule.onNodeWithText("辨证").performClick()
        waitForText("方证匹配")
        rule.onNodeWithText("方剂库").performClick()
        waitForText("筛选方名")
    }

    @Test
    fun vip_skills_browser_opens() {
        if (!BuildConfig.VIP) return
        waitForText("VIP 知识库已内置")
        // 卡片在首頁折疊區下方：先滾動進視口再點（坐標注入在視口外
        // 會落到底部導航欄上——Robolectric 實測）
        rule.onNodeWithText("VIP 知识库已内置")
            .performScrollTo()
            .performClick()
        waitForText("Skill 库（139 个已内置）")
        waitForText("方证")   // 分類標籤（formula_patterns）
    }
}
