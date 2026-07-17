package org.impfai.hermes.core.model

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** agent_trace 人類可讀化 / 證據等級 / 今日條文——展示層純函數單測。 */
class AgentPresentationTest {

    @Test
    fun `tool_call step is humanized with tool name and arguments`() {
        val step = buildJsonObject {
            put("step", 2)
            put("kind", "tool_call")
            put("tool", "shanghan_search")
            put("arguments", buildJsonObject { put("query", "太陽中風") })
        }
        val v = humanizeTraceStep(step)
        assertEquals(2, v.step)
        assertTrue(v.label.contains("shanghan_search"))
        assertTrue(v.detail.contains("太陽中風"))
        assertFalse(v.warning)
    }

    @Test
    fun `reflection step is a warning and lists unsupported ids`() {
        val step = buildJsonObject {
            put("step", 5)
            put("kind", "reflection")
            put("round", 1)
            put("unsupported", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("SHL_SONGBEN_0999")) })
        }
        val v = humanizeTraceStep(step)
        assertTrue(v.warning)
        assertTrue(v.label.contains("1"))
        assertTrue(v.detail.contains("SHL_SONGBEN_0999"))
    }

    @Test
    fun `citation_check with unsupported flags warning`() {
        val ok = humanizeTraceStep(buildJsonObject {
            put("step", 7)
            put("kind", "citation_check")
            put("verified", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("SHL_SONGBEN_0035"))
            })
            put("unsupported", buildJsonArray {})
        })
        assertFalse(ok.warning)
        assertTrue(ok.detail.contains("1"))

        val bad = humanizeTraceStep(buildJsonObject {
            put("step", 7)
            put("kind", "citation_check")
            put("verified", buildJsonArray {})
            put("unsupported", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("SHL_SONGBEN_0001"))
            })
        })
        assertTrue(bad.warning)
    }

    @Test
    fun `unknown kind is passed through, not dropped`() {
        val v = humanizeTraceStep(buildJsonObject {
            put("step", 9)
            put("kind", "future_kind")
        })
        assertEquals("future_kind", v.label)
        assertEquals(9, v.step)
    }

    @Test
    fun `humanizeTrace keeps step order and count`() {
        val steps = listOf(
            buildJsonObject { put("step", 1); put("kind", "tool_scope") },
            buildJsonObject { put("step", 2); put("kind", "final") },
        )
        val views = humanizeTrace(steps)
        assertEquals(2, views.size)
        assertEquals(1, views[0].step)
        assertEquals(2, views[1].step)
    }

    @Test
    fun `evidence grade maps corpus layers, not model self-rating`() {
        assertEquals(EvidenceGrade(5, "原文直接证据"), evidenceGradeForLayer("A"))
        assertEquals(4, evidenceGradeForLayer("B").stars)
        assertEquals(3, evidenceGradeForLayer("C").stars)
        assertEquals(2, evidenceGradeForLayer("D").stars)
        assertEquals(1, evidenceGradeForLayer("E").stars)
        assertEquals(1, evidenceGradeForLayer("").stars)
        assertEquals("★★★★★", evidenceGradeForLayer("A").starsText())
        assertEquals("★★★☆☆", evidenceGradeForLayer("C").starsText())
    }

    @Test
    fun `daily clause index is deterministic and in range`() {
        assertEquals(0, dailyClauseIndex(0, 398))
        assertEquals(398 - 1, dailyClauseIndex(397, 398))
        assertEquals(0, dailyClauseIndex(398, 398))
        // 負 epochDay（1970 前的設備時鐘）也不可越界
        assertEquals(397, dailyClauseIndex(-1, 398))
        // 空語料兜底
        assertEquals(0, dailyClauseIndex(42, 0))
        // 連續兩天指向不同條文
        val d1 = dailyClauseIndex(20_000, 398)
        val d2 = dailyClauseIndex(20_001, 398)
        assertTrue(d1 != d2)
    }
}
