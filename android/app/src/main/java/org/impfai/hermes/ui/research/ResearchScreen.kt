package org.impfai.hermes.ui.research

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.impfai.hermes.BuildConfig
import org.impfai.hermes.core.llm.DirectLlm
import org.impfai.hermes.engine.Charts
import org.impfai.hermes.engine.DocxWriter
import org.impfai.hermes.engine.PaperTheory
import org.impfai.hermes.engine.ResearchEngine
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer
import org.impfai.hermes.ui.features.ClauseChips
import org.impfai.hermes.ui.features.FeatureScaffold

/**
 * 科研挖掘（端側統計）+ 論文草稿（模板 + 可選 AI 潤色）+ 圖表預覽 +
 * DOCX 導出（SAF 保存到本地）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchScreen(onOpenClause: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("") }
    var mining by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ResearchEngine.Report?>(null) }
    var chartSym by remember { mutableStateOf<Bitmap?>(null) }
    var chartHerb by remember { mutableStateOf<Bitmap?>(null) }
    var aiIntro by remember { mutableStateOf("") }
    var aiDiscussion by remember { mutableStateOf("") }
    var polishing by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }
    var simplified by remember { mutableStateOf(true) }

    fun mine() {
        if (topic.isBlank() || mining) return
        scope.launch {
            mining = true; aiIntro = ""; aiDiscussion = ""; exportMsg = ""
            simplified = container.settings.current().simplifiedDisplay
            val r = ResearchEngine.mine(container.localStore, topic)
            report = r
            withContext(Dispatchers.Default) {
                chartSym = Charts.barChart(
                    "图1 「${r.topic}」相关条文症状频次", r.symptomFreq)
                chartHerb = Charts.barChart(
                    "图2 「${r.topic}」相关方剂药物频次", r.herbFreq,
                    altColor = true)
            }
            mining = false
        }
    }

    /** 完整篇章結構（v1.5）：題名/摘要/關鍵詞/引言/方法/結果/討論/
     *  結論/附錄——中醫理論深度由 PaperTheory 模板 + 可選 AI 撰寫承擔。 */
    fun buildBlocks(r: ResearchEngine.Report): List<DocxWriter.Block> {
        val b = ArrayList<DocxWriter.Block>()
        val mainChannel = r.channelDist.firstOrNull()?.first ?: "太陽病"
        val topSymptoms = r.symptomFreq.map { it.first }
        val topHerbs = r.herbFreq.map { it.first }
        b += DocxWriter.Block.Para(
            "医哲未来人工智能研究院（IMPF-AI）· 伤寒Hermes 端侧生成", italic = true)
        b += DocxWriter.Block.Heading(1, "摘要")
        b += DocxWriter.Block.Para(PaperTheory.abstractTemplate(
            r.topic, r.totalClauses, topSymptoms, topHerbs, mainChannel))
        b += DocxWriter.Block.Para("关键词：${r.topic}；伤寒论；六经辨证；" +
            topSymptoms.take(2).joinToString("；") + "；文献计量")
        b += DocxWriter.Block.Heading(1, "一、引言")
        b += DocxWriter.Block.Para(
            aiIntro.ifBlank { PaperTheory.introTemplate(r.topic, r.totalClauses) })
        b += DocxWriter.Block.Heading(1, "二、材料与方法")
        b += DocxWriter.Block.Para(PaperTheory.methodsTemplate(r.topic))
        b += DocxWriter.Block.Heading(1, "三、结果")
        b += DocxWriter.Block.Heading(2, "3.1 症状频次")
        b += DocxWriter.Block.Table(listOf("症状", "频次"),
            r.symptomFreq.map { listOf(it.first, it.second.toString()) })
        chartSym?.let { b += DocxWriter.Block.Image(it, "图1 症状频次") }
        b += DocxWriter.Block.Heading(2, "3.2 药物频次")
        b += DocxWriter.Block.Table(listOf("药物", "频次"),
            r.herbFreq.map { listOf(it.first, it.second.toString()) })
        chartHerb?.let { b += DocxWriter.Block.Image(it, "图2 药物频次") }
        b += DocxWriter.Block.Heading(2, "3.3 药对共现（同方内）")
        b += DocxWriter.Block.Table(listOf("药对", "共现次数"),
            r.herbPairFreq.map { listOf(it.first, it.second.toString()) })
        b += DocxWriter.Block.Heading(2, "3.4 六经分布")
        b += DocxWriter.Block.Table(listOf("六经", "条文数"),
            r.channelDist.map { listOf(it.first, it.second.toString()) })
        b += DocxWriter.Block.Heading(1, "四、讨论")
        if (aiDiscussion.isNotBlank()) {
            b += DocxWriter.Block.Para(aiDiscussion)
            b += DocxWriter.Block.Para(
                "（以上讨论由直连大模型基于本文统计与证据条文生成，" +
                    "已经本地引用核验，仍须作者逐条回源审定。）", italic = true)
        } else {
            PaperTheory.discussionSkeleton(r.topic, r.channelDist,
                r.herbPairFreq, topSymptoms).forEach {
                b += DocxWriter.Block.Para(it)
            }
        }
        b += DocxWriter.Block.Heading(1, "五、结论")
        b += DocxWriter.Block.Para(
            PaperTheory.conclusionTemplate(r.topic, mainChannel))
        b += DocxWriter.Block.Heading(1, "附录：证据条文")
        b += DocxWriter.Block.Para(r.relatedClauseIds.joinToString("、"))
        b += DocxWriter.Block.Para(
            "声明：本文为古籍文献计量研究文稿，不构成诊疗建议。", italic = true)
        return b
    }

    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        val r = report ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    DocxWriter.write(out,
                        "《伤寒论》「${r.topic}」方证计量研究（端侧草稿）",
                        buildBlocks(r))
                }
                withContext(Dispatchers.Main) { exportMsg = "已导出 DOCX ✓" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    exportMsg = "导出失败：${e.message}"
                }
            }
        }
    }

    FeatureScaffold("科研挖掘 · 论文生成", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = topic, onValueChange = { topic = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("研究主题：方名或术语，如 桂枝汤 / 往来寒热") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { mine() }),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { mine() }, enabled = !mining) {
                            Text(if (mining) "统计中…" else "开始挖掘")
                        }
                        report?.let {
                            OutlinedButton(onClick = {
                                exportLauncher.launch(
                                    "伤寒论_${it.topic}_研究草稿.docx")
                            }) { Text("导出 DOCX") }
                        }
                    }
                    if (exportMsg.isNotBlank()) NoticeBar(exportMsg)
                }
            }
            if (mining) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            report?.let { r ->
                item {
                    SectionCard("相关条文 ${r.totalClauses} 条") {
                        ClauseChips(r.relatedClauseIds, simplified,
                            onOpenClause, max = 12)
                    }
                }
                item {
                    SectionCard("六经分布 / 高频方剂") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            r.channelDist.forEach { (ch, n) ->
                                SuggestionChip(onClick = {},
                                    label = { Text("${ch.display(simplified)} ×$n",
                                        style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        Text(r.formulaFreq.joinToString("、") {
                            "${it.first.display(simplified)}×${it.second}"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                chartSym?.let { bmp ->
                    item {
                        SectionCard("图1 · 症状频次（预览，随 DOCX 导出）") {
                            Image(bmp.asImageBitmap(), contentDescription = "症状频次图",
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                chartHerb?.let { bmp ->
                    item {
                        SectionCard("图2 · 药物频次（预览，随 DOCX 导出）") {
                            Image(bmp.asImageBitmap(), contentDescription = "药物频次图",
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    SectionCard("药对共现 Top") {
                        r.herbPairFreq.forEach { (p, n) ->
                            Text("$p  ×$n", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (BuildConfig.VIP) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                if (polishing) return@OutlinedButton
                                scope.launch {
                                    polishing = true
                                    val s = container.settings.current()
                                    val topClauses = r.relatedClauseIds.take(5)
                                        .mapNotNull { id ->
                                            container.localStore.byId(id)?.let {
                                                "[$id] ${it.cleanText}"
                                            }
                                        }.joinToString("\n")
                                    val draft = "主题：《伤寒论》「${r.topic}」\n" +
                                        "相关条文 ${r.totalClauses} 条\n症状频次：" +
                                        r.symptomFreq.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "\n药物频次：" +
                                        r.herbFreq.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "\n药对共现：" +
                                        r.herbPairFreq.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "\n六经分布：" +
                                        r.channelDist.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "\n代表条文：\n" + topClauses
                                    val res = DirectLlm.complete(
                                        s.llmProvider, s.llmApiKey, s.llmBaseUrl,
                                        s.llmModel,
                                        system = "你是中医经方文献研究论文写作专家，" +
                                            "深谙六经辨证理论。基于给定统计数据与" +
                                            "条文原文，撰写论文的【引言】（400字内，" +
                                            "含研究背景、理论源流、研究意义）与" +
                                            "【讨论】（600字内，含病机阐释、配伍" +
                                            "法度、传变规律三层论述）。只使用给定" +
                                            "数据与条文，引用条文用其方括号 ID，" +
                                            "不得虚构文献，不给临床用药建议。" +
                                            "输出格式：【引言】…【讨论】…",
                                        user = draft, maxTokens = 3000)
                                    res.onSuccess { full ->
                                        val di = full.indexOf("【讨论】")
                                        if (di > 0) {
                                            aiIntro = full.substring(0, di)
                                                .removePrefix("【引言】").trim()
                                            aiDiscussion = full.substring(di)
                                                .removePrefix("【讨论】").trim()
                                        } else {
                                            aiDiscussion = full.trim()
                                        }
                                    }.onFailure {
                                        exportMsg = "AI 撰写失败：${it.message}"
                                    }
                                    polishing = false
                                }
                            }) {
                                Text(if (polishing) "撰写中…"
                                else "✦ AI 撰写引言与讨论（直连大模型）")
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { showPaper = !showPaper }) {
                            Text(if (showPaper) "收起全文预览" else "▤ 全文预览（完整篇章）")
                        }
                        if (showPaper) {
                            SectionCard("《伤寒论》「${r.topic}」方证计量研究") {
                                buildBlocks(r).forEach { blk ->
                                    when (blk) {
                                        is DocxWriter.Block.Heading -> Text(
                                            blk.text,
                                            style = if (blk.level <= 1)
                                                MaterialTheme.typography.titleSmall
                                            else MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary)
                                        is DocxWriter.Block.Para -> Text(blk.text,
                                            style = MaterialTheme.typography.bodySmall)
                                        is DocxWriter.Block.Table -> Text(
                                            blk.rows.joinToString("；") {
                                                it.joinToString(" ")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant)
                                        is DocxWriter.Block.Image -> Text(
                                            "〔${blk.caption}——见上方图表预览，" +
                                                "随 DOCX 导出〕",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text("端侧确定性统计（毫秒级）；深度研究循环/学术计量网络" +
                        "需 Hermes 服务端。草稿不构成诊疗建议。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal)
                }
            }
        }
    }
}

