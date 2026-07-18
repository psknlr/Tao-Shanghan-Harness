package org.impfai.hermes.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 極簡 DOCX 導出器（純手寫 OOXML，零第三方依賴）：
 * 標題/正文段落/表格/內嵌 PNG 圖表。生成的包結構：
 * [Content_Types].xml, _rels/.rels, word/document.xml,
 * word/styles.xml, word/_rels/document.xml.rels, word/media/chartN.png
 */
object DocxWriter {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Para(val text: String, val italic: Boolean = false) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data class Image(val bitmap: Bitmap, val caption: String) : Block
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun para(text: String, style: String? = null,
                     italic: Boolean = false): String {
        val pPr = style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: ""
        val rPr = if (italic) "<w:rPr><w:i/></w:rPr>" else ""
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${esc(text)}" +
            "</w:t></w:r></w:p>"
    }

    private fun table(header: List<String>, rows: List<List<String>>): String {
        fun cell(t: String, bold: Boolean) =
            "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr><w:p><w:r>" +
                (if (bold) "<w:rPr><w:b/></w:rPr>" else "") +
                "<w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r></w:p></w:tc>"
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblBorders>")
        for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$b w:val=\"single\" w:sz=\"4\" w:color=\"999999\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr>")
        sb.append("<w:tr>")
        header.forEach { sb.append(cell(it, true)) }
        sb.append("</w:tr>")
        for (r in rows) {
            sb.append("<w:tr>")
            r.forEach { sb.append(cell(it, false)) }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        sb.append(para(""))
        return sb.toString()
    }

    /** inline 圖片（EMU：1px≈9525EMU@96dpi；縮放到頁寬 ~15cm 內）。 */
    private fun image(relId: String, idNum: Int, w: Int, h: Int,
                      caption: String): String {
        val maxW = 5_400_000L      // ~14.3cm
        var cx = w * 9525L
        var cy = h * 9525L
        if (cx > maxW) { cy = cy * maxW / cx; cx = maxW }
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" " +
            "distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$idNum\" name=\"chart$idNum\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$idNum\" name=\"chart$idNum\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$relId\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
            "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>" +
            para(caption, style = null, italic = true)
    }

    fun write(out: OutputStream, title: String, blocks: List<Block>) {
        val images = blocks.filterIsInstance<Block.Image>()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun entry(name: String, text: String) = entry(name, text.toByteArray())

            val imageTypes = if (images.isEmpty()) ""
            else "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            entry("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    imageTypes +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "</Types>")
            entry("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>")

            val rels = StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            images.forEachIndexed { i, _ ->
                rels.append("<Relationship Id=\"rImg$i\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                    "Target=\"media/chart$i.png\"/>")
            }
            rels.append("</Relationships>")
            entry("word/_rels/document.xml.rels", rels.toString())

            entry("word/styles.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>" +
                    "<w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr></w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/>" +
                    "<w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>" +
                    "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/>" +
                    "<w:rPr><w:b/><w:sz w:val=\"27\"/></w:rPr></w:style>" +
                    "</w:styles>")

            val body = StringBuilder()
            body.append(para(title, style = "Title"))
            var imgIdx = 0
            for (b in blocks) {
                when (b) {
                    is Block.Heading -> body.append(
                        para(b.text, style = if (b.level <= 1) "Heading1" else "Heading2"))
                    is Block.Para -> body.append(para(b.text, italic = b.italic))
                    is Block.Table -> body.append(table(b.header, b.rows))
                    is Block.Image -> {
                        body.append(image("rImg$imgIdx", imgIdx + 1,
                            b.bitmap.width, b.bitmap.height, b.caption))
                        imgIdx++
                    }
                }
            }
            entry("word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
                    "<w:body>$body<w:sectPr/></w:body></w:document>")

            images.forEachIndexed { i, img ->
                val buf = ByteArrayOutputStream()
                img.bitmap.compress(Bitmap.CompressFormat.PNG, 100, buf)
                entry("word/media/chart$i.png", buf.toByteArray())
            }
        }
    }
}
