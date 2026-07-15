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
            (context.assets.list("skills") ?: emptyArray()).isNotEmpty()
        } catch (_: Exception) {
            false
        }

    private fun hasSkillMd(dir: String): Boolean =
        try {
            context.assets.list(dir)?.contains("SKILL.md") == true
        } catch (_: Exception) {
            false
        }

    suspend fun list(): List<SkillEntry> = withContext(Dispatchers.IO) {
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
