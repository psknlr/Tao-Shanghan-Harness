package org.impfai.hermes.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Skill 庫瀏覽（VIP 包內置 data/skills/shanghanlun 全樹：139 個 Skill，
 * 每個含 SKILL.md + rules.jsonl + examples.jsonl）。
 * standard 包無 skills/ 資產 → available()=false，界面不顯示入口。
 */
class SkillStore(private val context: Context) {

    data class SkillEntry(
        val path: String,       // assets 相對路徑，如 skills/hermes.shanghan.six_channels/taiyang
        val category: String,   // six_channels / formula_patterns / …
        val name: String,       // taiyang / guizhi_tang / …（單層 Skill 同 category）
    )

    data class SkillDoc(
        val markdown: String,
        val rulesCount: Int,
        val examplesCount: Int,
    )

    fun available(): Boolean =
        try {
            context.assets.open("skills_index.txt").close(); true
        } catch (_: Exception) {
            false
        }

    private fun hasSkillMd(dir: String): Boolean =
        try {
            context.assets.list(dir)?.contains("SKILL.md") == true
        } catch (_: Exception) {
            false
        }

    /** 首選構建期索引（AssetManager.list 對子目錄的行為跨環境不一致——
     *  Robolectric 冒煙測試實測只返回文件）；索引缺失時回退目錄遍歷。 */
    suspend fun list(): List<SkillEntry> = withContext(Dispatchers.IO) {
        val indexed = try {
            context.assets.open("skills_index.txt")
                .bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        if (indexed.isNotEmpty()) {
            return@withContext indexed.map { rel ->
                val parts = rel.split('/')
                val category = parts.first().removePrefix("hermes.shanghan.")
                val name = if (parts.size > 1) parts.last() else category
                SkillEntry("skills/$rel", category, name)
            }
        }
        // 回退：目錄遍歷
        val out = ArrayList<SkillEntry>()
        val top = context.assets.list("skills") ?: return@withContext out
        for (entry in top.sorted()) {
            val dir = "skills/$entry"
            val category = entry.removePrefix("hermes.shanghan.")
            if (hasSkillMd(dir)) {
                out.add(SkillEntry(dir, category, category))
                continue
            }
            val children = context.assets.list(dir) ?: continue
            for (child in children.sorted()) {
                val sub = "$dir/$child"
                if (hasSkillMd(sub)) out.add(SkillEntry(sub, category, child))
            }
        }
        out
    }

    // —— 智能體 Skill 檢索（v1.10 深度思考）——

    @Volatile private var titleCache: Map<String, String>? = null

    /** 各 Skill 的中文標題行（SKILL.md 首個非空行），首查構建緩存。 */
    private suspend fun titles(): Map<String, String> {
        titleCache?.let { return it }
        return withContext(Dispatchers.IO) {
            val map = HashMap<String, String>()
            for (e in list()) {
                try {
                    context.assets.open("${e.path}/SKILL.md")
                        .bufferedReader(Charsets.UTF_8).useLines { lines ->
                            map[e.path] = lines.firstOrNull { it.isNotBlank() }
                                ?.removePrefix("#")?.trim()?.take(60) ?: e.name
                        }
                } catch (_: Exception) {
                    map[e.path] = e.name
                }
            }
            map.also { titleCache = it }
        }
    }

    /** 按問題檢索相關 Skill：查詢 CJK 字符與標題/類目重疊計分。 */
    suspend fun search(query: String, topK: Int = 2): List<SkillEntry> {
        if (!available()) return emptyList()
        val q = TextNorm.foldVariants(TextNorm.s2t(query))
            .filter { it.code in 0x3400..0x9FFF }
        if (q.isBlank()) return emptyList()
        val ts = titles()
        return list().map { e ->
            val hay = TextNorm.foldVariants(TextNorm.s2t(
                (ts[e.path] ?: "") + e.category + e.name))
            var score = 0
            // bigram 重疊為主（單字太泛），bigram 命中加倍
            for (i in 0 until q.length - 1) {
                if (hay.contains(q.substring(i, i + 2))) score += 2
            }
            q.forEach { ch -> if (hay.contains(ch)) score += 1 }
            e to score
        }.filter { it.second >= 4 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /** Skill 標題（智能體提示詞展示用）。 */
    suspend fun titleOf(entry: SkillEntry): String =
        titles()[entry.path] ?: entry.name

    suspend fun read(entry: SkillEntry): SkillDoc = withContext(Dispatchers.IO) {
        val md = try {
            context.assets.open("${entry.path}/SKILL.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            "（SKILL.md 缺失）"
        }
        fun countLines(name: String): Int = try {
            context.assets.open("${entry.path}/$name")
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines -> lines.count { it.isNotBlank() } }
        } catch (_: Exception) {
            0
        }
        SkillDoc(md, countLines("rules.jsonl"), countLines("examples.jsonl"))
    }
}
