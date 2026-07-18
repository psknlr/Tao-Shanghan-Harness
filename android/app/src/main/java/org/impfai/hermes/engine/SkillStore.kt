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
