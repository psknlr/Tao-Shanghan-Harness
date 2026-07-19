package org.impfai.hermes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.12.1 智能體回答 Markdown 渲染的解析面。 */
class MarkdownTest {

    @Test
    fun `heading without space after hashes`() {
        // 大模型實際輸出樣式：###5.治法（# 後無空格）
        val blocks = Markdown.parse("###5.治法\n正文段落")
        assertEquals(Markdown.Block.Heading(3, "5.治法"), blocks[0])
        assertEquals(Markdown.Block.Paragraph("正文段落"), blocks[1])
    }

    @Test
    fun `table with separator row parses header and rows`() {
        val md = """
            | 方证 | 主症 | 治法 |
            | --- | --- | --- |
            | 桂枝汤证 | 汗出恶风 | 解肌祛风 |
            | 麻黄汤证 | 无汗而喘 | 发汗解表 |
        """.trimIndent()
        val t = Markdown.parse(md).single() as Markdown.Block.Table
        assertEquals(listOf("方证", "主症", "治法"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("桂枝汤证", "汗出恶风", "解肌祛风"), t.rows[0])
    }

    @Test
    fun `table without separator keeps all rows as data`() {
        val md = "| 桂枝汤 | 三两 |\n| 芍药 | 三两 |"
        val t = Markdown.parse(md).single() as Markdown.Block.Table
        assertTrue(t.header.isEmpty())
        assertEquals(2, t.rows.size)
    }

    @Test
    fun `unclosed code fence is tolerated for streaming`() {
        val blocks = Markdown.parse("说明：\n```\n桂枝 三两")
        assertEquals(Markdown.Block.Paragraph("说明："), blocks[0])
        assertEquals("桂枝 三两", (blocks[1] as Markdown.Block.Code).text)
    }

    @Test
    fun `bullet and ordered lists`() {
        val md = "- 恶寒\n- 发热\n\n1. 辛温解表\n2、调和营卫"
        val blocks = Markdown.parse(md)
        assertEquals(listOf("恶寒", "发热"),
            (blocks[0] as Markdown.Block.Bullets).items)
        val o = blocks[1] as Markdown.Block.Ordered
        assertEquals("1." to "辛温解表", o.items[0])
        assertEquals("2、" to "调和营卫", o.items[1])
    }

    @Test
    fun `dosage line is not misparsed as ordered list`() {
        // 「3.5克」不是列表序號——序號後必須還有正文才成列表
        val blocks = Markdown.parse("桂枝用量\n3.5")
        assertTrue(blocks.all { it is Markdown.Block.Paragraph })
    }

    @Test
    fun `quote divider and paragraph merge`() {
        val md = "> 太阳之为病\n> 脉浮\n\n---\n\n首行\n次行"
        val blocks = Markdown.parse(md)
        assertEquals("太阳之为病\n脉浮", (blocks[0] as Markdown.Block.Quote).text)
        assertEquals(Markdown.Block.Divider, blocks[1])
        assertEquals(Markdown.Block.Paragraph("首行\n次行"), blocks[2])
    }

    @Test
    fun `inline bold italic code and link`() {
        val spans = Markdown.parseInline("宜**桂枝汤**主之，`SHL_0012`见*注*")
        assertEquals(6, spans.size)
        assertEquals(Markdown.Span("桂枝汤", bold = true), spans[1])
        assertEquals(Markdown.Span("SHL_0012", code = true), spans[3])
        assertTrue(spans[5].italic)
        val link = Markdown.parseInline("详见[伤寒论](https://x)条文")
        assertEquals(Markdown.Span("伤寒论", link = true), link[1])
    }

    @Test
    fun `unclosed inline markers stay literal`() {
        assertEquals(listOf(Markdown.Span("**未闭合 与 `残缺")),
            Markdown.parseInline("**未闭合 与 `残缺"))
        assertEquals("宜桂枝汤主之", Markdown.plain("宜**桂枝汤**主之"))
    }
}
