package org.impfai.hermes.core.model

/**
 * 輕量 Markdown 解析（v1.12.1 智能體回答排版）。
 *
 * 大模型輸出常見 `###5.治法`（# 後無空格）、`| 方证 | 主症 |` 表格、
 * `**加粗**` 等——此前整段按純文本渲染，閱讀體驗差。此解析器覆蓋
 * 聊天回答實際出現的子集：標題/表格/無序·有序列表/引用/圍欄代碼/
 * 分隔線/段落 + 行內加粗·斜體·行內代碼·鏈接。
 *
 * 特性約束：
 * - 純函數、無依賴（UI 層另行渲染）——可直接單測；
 * - 流式容錯：未閉合的 ``` 圍欄、殘缺表格行都能穩定渲染，不拋錯；
 * - 不支持嵌套結構（列表內表格等）——聊天回答不出現，捨棄換簡單。
 */
object Markdown {

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        /** 無序列表：每項一條（行內樣式各自解析）。 */
        data class Bullets(val items: List<String>) : Block()
        /** 有序列表：保留各項自帶的序號文本（1. / 2、…）。 */
        data class Ordered(val items: List<Pair<String, String>>) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val text: String, val lang: String = "") : Block()
        /** 表格：header 可為空（無分隔行時全部視為數據行）。 */
        data class Table(
            val header: List<String>,
            val rows: List<List<String>>,
        ) : Block()
        data object Divider : Block()
    }

    /** 行內片段：粗體/斜體/行內代碼/鏈接標記。 */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: Boolean = false,
    )

    private val reHeading = Regex("^(#{1,6})\\s*(.*)$")
    private val reDivider = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val reBullet = Regex("^\\s*[-*•]\\s+(.*)$")
    private val reOrdered = Regex("^\\s*(\\d{1,2}[.、)])\\s*(.*)$")
    private val reTableSep = Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$")

    private fun isTableRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.count { it == '|' } >= 2
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split("|").map { it.trim() }

    /**
     * 有序列表項判定。「3.5克」之類劑量誤判防護：序號後必須還有正文，
     * 且 `.` 分隔時正文不能以數字開頭（「1.治法」是列表、「3.5克」不是）。
     */
    private fun orderedItem(line: String): Pair<String, String>? {
        val m = reOrdered.matchEntire(line) ?: return null
        val no = m.groupValues[1]
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return null
        if (no.endsWith(".") && rest.first().isDigit()) return null
        return no to rest
    }

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val lines = text.lines()
        val para = StringBuilder()
        fun flushPara() {
            val p = para.toString().trim()
            if (p.isNotEmpty()) blocks.add(Block.Paragraph(p))
            para.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // 圍欄代碼：未閉合（流式中途）也收尾為代碼塊
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushPara()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    body.appendLine(lines[i]); i++
                }
                if (i < lines.size) i++   // 吃掉閉合圍欄
                blocks.add(Block.Code(body.toString().trimEnd('\n'), lang))
                continue
            }
            if (trimmed.isEmpty()) { flushPara(); i++; continue }
            val h = reHeading.matchEntire(trimmed)
            if (h != null) {
                flushPara()
                blocks.add(Block.Heading(h.groupValues[1].length,
                    h.groupValues[2].trim()))
                i++; continue
            }
            if (reDivider.matches(trimmed) && trimmed.length >= 3) {
                flushPara(); blocks.add(Block.Divider); i++; continue
            }
            if (isTableRow(trimmed)) {
                flushPara()
                val rawRows = ArrayList<String>()
                while (i < lines.size && isTableRow(lines[i].trim())) {
                    rawRows.add(lines[i].trim()); i++
                }
                val hasSep = rawRows.size >= 2 &&
                    reTableSep.matches(rawRows[1]) && "-" in rawRows[1]
                val header = if (hasSep) splitRow(rawRows[0]) else emptyList()
                val dataRows = (if (hasSep) rawRows.drop(2) else rawRows)
                    .filterNot { reTableSep.matches(it) && "-" in it }
                    .map { splitRow(it) }
                blocks.add(Block.Table(header, dataRows))
                continue
            }
            val b = reBullet.matchEntire(line)
            if (b != null) {
                flushPara()
                val items = ArrayList<String>()
                while (i < lines.size) {
                    val m = reBullet.matchEntire(lines[i]) ?: break
                    items.add(m.groupValues[1].trim()); i++
                }
                blocks.add(Block.Bullets(items))
                continue
            }
            if (orderedItem(line) != null) {
                flushPara()
                val items = ArrayList<Pair<String, String>>()
                while (i < lines.size) {
                    val it = orderedItem(lines[i]) ?: break
                    items.add(it); i++
                }
                blocks.add(Block.Ordered(items))
                continue
            }
            if (trimmed.startsWith(">")) {
                flushPara()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.appendLine(lines[i].trim()
                        .removePrefix(">").trim())
                    i++
                }
                blocks.add(Block.Quote(quote.toString().trimEnd('\n')))
                continue
            }
            if (para.isNotEmpty()) para.append('\n')
            para.append(trimmed)
            i++
        }
        flushPara()
        return blocks
    }

    /** 去除行內標記後的純文本（DOCX 導出等不支持行內樣式的落地）。 */
    fun plain(text: String): String =
        parseInline(text).joinToString("") { it.text }

    /**
     * 行內解析：`**粗**`、`*斜*`、`` `代碼` ``、`[文](url)`（僅保留
     * 顯示文本並打鏈接標記）。未閉合標記按字面輸出——流式安全。
     */
    fun parseInline(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(Span(plain.toString())); plain.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flushPlain()
                        out.add(Span(text.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else { plain.append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flushPlain()
                        out.add(Span(text.substring(i + 1, end), code = true))
                        i = end + 1
                    } else { plain.append(text[i]); i++ }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && '\n' !in text.substring(i + 1, end)) {
                        flushPlain()
                        out.add(Span(text.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else { plain.append(text[i]); i++ }
                }
                text[i] == '[' -> {
                    val mid = text.indexOf("](", i + 1)
                    val end = if (mid > 0) text.indexOf(')', mid + 2) else -1
                    if (mid > i + 1 && end > mid) {
                        flushPlain()
                        out.add(Span(text.substring(i + 1, mid), link = true))
                        i = end + 1
                    } else { plain.append(text[i]); i++ }
                }
                else -> { plain.append(text[i]); i++ }
            }
        }
        flushPlain()
        return out
    }
}
