package org.impfai.hermes.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 全量古籍庫（中醫笈成 803 部 / 843 文本單元）——移植自
 * backend/hermes_shanghan/corpus/library.py 的 Library 類：
 *
 * - 編目檢索：書名/作者/朝代/分類，異體字折疊，title>author 排序；
 * - 全文檢索：字符倒排索引（charindex.json）取查詢中 df 最小的字做
 *   posting 交集剪枝候選書，再逐書流式驗證原文（fold_variants 後
 *   substring），返回帶書名·章節定位的摘錄——與原始代碼同算法；
 * - 章節閱讀：``==…==`` 標題切分，<book> 元數據塊剝除，分頁續讀。
 *
 * 資產（assets/library/）由 tools/prepare_library.md 流程生成：
 * backend `library.fetch()` 官方下載 + sha256 校驗 + 編目 + 索引。
 * 未內置時 available()=false，界面顯示引導。
 */
class LibraryStore(private val context: Context) {

    @Serializable
    data class Unit_(
        val id: String = "",
        val title: String = "",
        val author: String = "",
        val dynasty: String = "",
        val year: String = "",
        val category: String = "",
        val parent: String = "",
        val files: List<String> = emptyList(),
        @SerialName("approx_chars") val approxChars: Long = 0,
        @SerialName("sub_books") val subBooks: List<String> = emptyList(),
    )

    @Serializable
    private data class Catalog(
        @SerialName("n_books") val nBooks: Int = 0,
        @SerialName("n_units") val nUnits: Int = 0,
        val categories: Map<String, Int> = emptyMap(),
        val units: List<Unit_> = emptyList(),
    )

    data class GrepHit(
        val unit: Unit_,
        val section: String,
        val excerpt: String,
    )

    data class Toc(val level: Int, val title: String, val file: String)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()
    @Volatile private var catalog: Catalog? = null
    private var byId: Map<String, Unit_> = emptyMap()
    private var charIndex: Map<String, List<Int>> = emptyMap()
    // 編目檢索的規範化緩存（v1.5：修復簡體輸入檢索不到繁體書名）
    private var canonIndex: List<Triple<String, String, Unit_>> = emptyList()

    private val metaBlock = Regex("<book>[\\s\\S]*?</book>")
    private val heading = Regex("^(={2,6})\\s*(.+?)\\s*\\1\\s*$")

    fun available(): Boolean = try {
        context.assets.open("library/catalog.json").close(); true
    } catch (_: Exception) {
        false
    }

    suspend fun ensureCatalog(): Boolean {
        if (catalog != null) return true
        if (!available()) return false
        mutex.withLock {
            if (catalog != null) return true
            withContext(Dispatchers.IO) {
                catalog = context.assets.open("library/catalog.json").use {
                    @Suppress("OPT_IN_USAGE")
                    json.decodeFromStream<Catalog>(it)
                }
                byId = catalog!!.units.associateBy { it.id }
                canonIndex = catalog!!.units.map {
                    Triple(TextNorm.canon(it.title), TextNorm.canon(it.author), it)
                }
            }
        }
        return true
    }

    private suspend fun ensureCharIndex() {
        if (charIndex.isNotEmpty()) return
        mutex.withLock {
            if (charIndex.isNotEmpty()) return
            withContext(Dispatchers.IO) {
                // charindex.json: {"chars": {"字": [unitOrdinal,...]}}
                val root = context.assets.open("library/charindex.json").use {
                    @Suppress("OPT_IN_USAGE")
                    json.decodeFromStream<JsonObject>(it)
                }
                val chars = root["chars"] as? JsonObject ?: JsonObject(emptyMap())
                charIndex = chars.mapValues { (_, v) ->
                    v.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }
                }
            }
        }
    }

    fun stats(): Triple<Int, Int, Map<String, Int>> {
        val c = catalog ?: return Triple(0, 0, emptyMap())
        return Triple(c.nBooks, c.nUnits, c.categories)
    }

    /** 編目檢索：書名/作者/朝代/分類——規範空間比對（v1.5 修復：
     *  簡體輸入「伤寒」此前匹配不到繁體書名「傷寒論」）。
     *  排序同原始 Library.search：title 3/2 分 > author/朝代/分類 1 分。 */
    suspend fun searchCatalog(query: String, category: String = "",
                              limit: Int = 60): List<Unit_> {
        if (!ensureCatalog()) return emptyList()
        val q = TextNorm.canon(query.trim())
        val cat = TextNorm.canon(category)
        val hits = ArrayList<Pair<Int, Unit_>>()
        for ((cTitle, cAuthor, u) in canonIndex) {
            if (cat.isNotBlank() &&
                !TextNorm.canon(u.category).contains(cat)) continue
            val score = when {
                q.isNotBlank() && cTitle.contains(q) ->
                    if (u.parent.isBlank()) 3 else 2
                q.isNotBlank() && (cAuthor.contains(q) ||
                    TextNorm.canon(u.dynasty).contains(q) ||
                    TextNorm.canon(u.category).contains(q)) -> 1
                q.isBlank() -> 1
                else -> continue
            }
            hits.add(score to u)
        }
        return hits.sortedWith(
            compareByDescending<Pair<Int, Unit_>> { it.first }
                .thenByDescending { it.second.approxChars }
                .thenBy { it.second.id })
            .take(limit).map { it.second }
    }

    data class Located(val section: String, val paraIndex: Int)

    /**
     * 定位包含指定文字的章節與段序（條文關係開卷直達；v1.5 #1）。
     * 段序口徑與 ReaderViewModel.splitParas 一致：本章節內非空行序，
     * 章節標題行本身計為第 0 段。
     */
    suspend fun locate(bookId: String, needleRaw: String): Located? =
        withContext(Dispatchers.IO) {
            ensureCatalog()
            val u = byId[bookId] ?: return@withContext null
            val needle = TextNorm.canon(needleRaw)
            if (needle.isBlank()) return@withContext null
            var section = ""
            var paraInSection = 0
            for (name in u.files) {
                try {
                    context.assets.open("library/books/${u.id}/$name")
                        .bufferedReader(Charsets.UTF_8).useLines { lines ->
                            var inMeta = false
                            for (raw in lines) {
                                val line = raw.trim()
                                if (line == "<book>") { inMeta = true; continue }
                                if (line == "</book>") { inMeta = false; continue }
                                if (inMeta || line.isEmpty()) continue
                                val h = heading.matchEntire(line)
                                if (h != null) {
                                    section = h.groupValues[2]
                                    paraInSection = 0
                                }
                                if (TextNorm.canon(line).contains(needle)) {
                                    return@useLines
                                }
                                paraInSection++
                            }
                            paraInSection = -1     // 本文件未命中
                        }
                    if (paraInSection >= 0) {
                        return@withContext Located(section, paraInSection)
                    }
                    paraInSection = 0
                } catch (_: Exception) { }
            }
            null
        }

    fun unit(id: String): Unit_? = byId[id]

    /** 按書名解析單元（條文關係中的 "傷寒論注:p1294" 類引用跳轉用）：
     *  異體字折疊後精確匹配 title 或 id，其次前綴匹配。 */
    suspend fun findByTitle(title: String): Unit_? {
        if (!ensureCatalog()) return null
        val t = TextNorm.foldVariants(title.trim())
        if (t.isBlank()) return null
        val units = catalog!!.units
        return units.firstOrNull {
            TextNorm.foldVariants(it.title) == t ||
                TextNorm.foldVariants(it.id) == t
        } ?: units.firstOrNull {
            TextNorm.foldVariants(it.title).startsWith(t)
        }
    }

    private fun unitText(u: Unit_): String = buildString {
        for (name in u.files) {
            try {
                context.assets.open("library/books/${u.id}/$name")
                    .bufferedReader(Charsets.UTF_8).use { append(it.readText()) }
                append('\n')
            } catch (_: Exception) { /* 單文件缺失跳過 */ }
        }
    }

    suspend fun toc(id: String): List<Toc> = withContext(Dispatchers.IO) {
        ensureCatalog()
        val u = byId[id] ?: return@withContext emptyList()
        val out = ArrayList<Toc>()
        for (name in u.files) {
            try {
                context.assets.open("library/books/${u.id}/$name")
                    .bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            val m = heading.matchEntire(line.trim()) ?: continue
                            out.add(Toc(7 - m.groupValues[1].length,
                                m.groupValues[2], name))
                        }
                    }
            } catch (_: Exception) { }
        }
        out
    }

    data class ReadResult(val text: String, val truncated: Boolean, val total: Int)

    /** 讀原文（可按章節標題定位；offset 分頁續讀）。 */
    suspend fun read(id: String, section: String = "", offset: Int = 0,
                     maxChars: Int = 4000): ReadResult = withContext(Dispatchers.IO) {
        ensureCatalog()
        val u = byId[id] ?: return@withContext ReadResult("（全库查无此书）", false, 0)
        var text = metaBlock.replace(unitText(u), "")
        if (section.isNotBlank()) {
            val sec = TextNorm.foldVariants(section)
            val lines = text.lines()
            var start = -1
            var end = lines.size
            for ((i, line) in lines.withIndex()) {
                val m = heading.matchEntire(line.trim()) ?: continue
                if (start < 0 &&
                    TextNorm.foldVariants(m.groupValues[2]).contains(sec)) {
                    start = i
                } else if (start >= 0) {
                    end = i; break
                }
            }
            if (start >= 0) text = lines.subList(start, end).joinToString("\n")
        }
        val window = text.drop(offset).take(maxChars)
        ReadResult(window, offset + maxChars < text.length, text.length)
    }

    /** 全文檢索：稀字剪枝 → 流式驗證 → 摘錄（同原始算法）。 */
    suspend fun grep(query: String, category: String = "", limit: Int = 30,
                     onProgress: (done: Int, total: Int) -> Unit = { _, _ -> })
            : List<GrepHit> = withContext(Dispatchers.IO) {
        if (!ensureCatalog()) return@withContext emptyList()
        ensureCharIndex()
        val q = TextNorm.foldVariants(TextNorm.s2t(query.trim()))
        if (q.isBlank()) return@withContext emptyList()
        val units = catalog!!.units
        val cjk = q.filter { it.code in 0x3400..0x9FFF }.map(Char::toString)
        // 候選集：取 df 最小的至多 3 個字做 posting 交集；全字都無索引時退全量
        val postings = cjk.mapNotNull { ch -> charIndex[ch]?.let { ch to it } }
            .sortedBy { it.second.size }
            .take(3)
        var candidates: List<Int> = if (postings.isEmpty()) units.indices.toList()
        else postings.map { it.second.toSet() }
            .reduce { a, b -> a intersect b }.sorted()
        if (category.isNotBlank()) {
            candidates = candidates.filter {
                units[it].category.contains(category)
            }
        }
        val hits = ArrayList<GrepHit>()
        for ((done, idx) in candidates.withIndex()) {
            onProgress(done, candidates.size)
            if (hits.size >= limit) break
            val u = units[idx]
            if (u.files.isEmpty()) continue
            var currentSection = ""
            for (name in u.files) {
                if (hits.size >= limit) break
                try {
                    context.assets.open("library/books/${u.id}/$name")
                        .bufferedReader(Charsets.UTF_8).useLines { lines ->
                            var inMeta = false
                            for (raw in lines) {
                                val line = raw.trim()
                                if (line == "<book>") { inMeta = true; continue }
                                if (line == "</book>") { inMeta = false; continue }
                                if (inMeta) continue
                                heading.matchEntire(line)?.let {
                                    currentSection = it.groupValues[2]
                                    return@let
                                }
                                val folded = TextNorm.foldVariants(line)
                                val pos = folded.indexOf(q)
                                if (pos >= 0) {
                                    val from = (pos - 30).coerceAtLeast(0)
                                    val to = (pos + q.length + 50)
                                        .coerceAtMost(line.length)
                                    hits.add(GrepHit(u, currentSection,
                                        line.substring(from, to)))
                                    if (hits.size >= limit) return@useLines
                                }
                            }
                        }
                } catch (_: Exception) { }
            }
        }
        onProgress(candidates.size, candidates.size)
        hits
    }
}
