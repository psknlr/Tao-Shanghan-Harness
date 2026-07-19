package org.impfai.hermes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.impfai.hermes.core.model.Markdown

/**
 * 智能體回答 Markdown 渲染（v1.12.1）：標題/表格/列表/引用/代碼/
 * 行內樣式。解析是純函數（[Markdown]），此處只做 Compose 落地。
 * 放在 SelectionContainer 內時所有文本仍可自由選擇複製。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MdBlock(block, baseStyle) }
    }
}

@Composable
private fun MdBlock(block: Markdown.Block, baseStyle: TextStyle) {
    when (block) {
        is Markdown.Block.Heading -> {
            // 聊天氣泡尺度的標題階梯（H1 不能像文檔那麼大）
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> baseStyle
            }
            Text(
                inline(block.text),
                style = style,
                fontWeight = FontWeight.Bold,
                color = if (block.level <= 3) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        is Markdown.Block.Paragraph ->
            Text(inline(block.text), style = baseStyle)
        is Markdown.Block.Bullets -> Column(
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item ->
                Row {
                    Text("•  ", style = baseStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                    Text(inline(item), style = baseStyle)
                }
            }
        }
        is Markdown.Block.Ordered -> Column(
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { (no, item) ->
                Row {
                    Text("$no ", style = baseStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                    Text(inline(item), style = baseStyle)
                }
            }
        }
        is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                RoundedCornerShape(2.dp)))
            Text(inline(block.text), style = baseStyle,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp))
        }
        is Markdown.Block.Code -> Box(
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(block.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
        is Markdown.Block.Table -> MdTable(block)
        Markdown.Block.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 方證鑒別類表格：表頭著色加粗，行間細分隔線，整表描邊圓角。 */
@Composable
private fun MdTable(table: Markdown.Block.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val cols = maxOf(table.header.size,
        table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(1.dp),
    ) {
        if (table.header.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))) {
                for (c in 0 until cols) {
                    Text(inline(table.header.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                            .padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            HorizontalDivider(color = border)
        }
        table.rows.forEachIndexed { idx, row ->
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until cols) {
                    Text(inline(row.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                            .padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            if (idx != table.rows.lastIndex) {
                HorizontalDivider(color = border.copy(alpha = 0.5f))
            }
        }
    }
}

/** 行內 Markdown → AnnotatedString（粗/斜/行內碼/鏈接文本）。 */
@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, linkColor) {
        buildAnnotatedString {
            Markdown.parseInline(text).forEach { span ->
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBg else Color.Unspecified,
                    color = if (span.link) linkColor else Color.Unspecified,
                    textDecoration = if (span.link) TextDecoration.Underline
                    else null,
                )
                pushStyle(style)
                append(span.text)
                pop()
            }
        }
    }
}
