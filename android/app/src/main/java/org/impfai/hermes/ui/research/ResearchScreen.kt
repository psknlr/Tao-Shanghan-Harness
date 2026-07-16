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
    var polished by remember { mutableStateOf("") }
    var polishing by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf("") }
    var simplified by remember { mutableStateOf(true) }

    fun mine() {
        if (topic.isBlank() || mining) return
        scope.launch {
            mining = true; polished = ""; exportMsg = ""
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

    fun buildBlocks(r: ResearchEngine.Report): List<DocxWriter.Block> {
        val b = ArrayList<DocxWriter.Block>()
        b += DocxWriter.Block.Para(
            "研发者：医哲未来人工智能研究院（IMPF-AI）· 伤寒Hermes 端侧研究草稿",
            italic = true)
        b += DocxWriter.Block.Heading(1, "摘要")
        b += DocxWriter.Block.Para(
            "本文基于《伤寒论》宋本 398 条核心条文语料，围绕「${r.topic}」" +
                "检得相关条文 ${r.totalClauses} 条，统计其证候分布、用药频次与" +
                "药对共现结构，并给出六经分布概览。全部统计为端侧确定性计算，" +
                "证据条文可逐条回源。" +
                (polished.takeIf { it.isNotBlank() }?.let { "" } ?: ""))
        if (polished.isNotBlank()) {
            b += DocxWriter.Block.Heading(1, "AI 润色稿（直连大模型生成，仅供参考）")
            b += DocxWriter.Block.Para(polished)
        }
        b += DocxWriter.Block.Heading(1, "方法")
        b += DocxWriter.Block.Para(
            "以「${r.topic}」为检索词，经简繁归一与异体字折叠后，" +
                "在条文正文、证候要素与方名字段上做包含匹配 + BM25 检索合并；" +
                "对命中条文统计症状/药物频次、同方药对共现与六经归属。")
        b += DocxWriter.Block.Heading(1, "结果")
        b += DocxWriter.Block.Heading(2, "1. 症状频次")
        b += DocxWriter.Block.Table(listOf("症状", "频次"),
            r.symptomFreq.map { listOf(it.first, it.second.toString()) })
        chartSym?.let { b += DocxWriter.Block.Image(it, "图1 症状频次") }
        b += DocxWriter.Block.Heading(2, "2. 药物频次")
        b += DocxWriter.Block.Table(listOf("药物", "频次"),
            r.herbFreq.map { listOf(it.first, it.second.toString()) })
        chartHerb?.let { b += DocxWriter.Block.Image(it, "图2 药物频次") }
        b += DocxWriter.Block.Heading(2, "3. 药对共现（同方内）")
        b += DocxWriter.Block.Table(listOf("药对", "共现次数"),
            r.herbPairFreq.map { listOf(it.first, it.second.toString()) })
        b += DocxWriter.Block.Heading(2, "4. 六经分布")
        b += DocxWriter.Block.Table(listOf("六经", "条文数"),
            r.channelDist.map { listOf(it.first, it.second.toString()) })
        b += DocxWriter.Block.Heading(1, "讨论（骨架）")
        b += DocxWriter.Block.Para(
            "（1）高频证候与核心病机的对应关系；（2）高频药物与药对提示的" +
                "配伍结构；（3）六经分布反映的传变路径。以上论点须逐条" +
                "回源到证据条文后方可成文——本草稿仅提供计量骨架。")
        b += DocxWriter.Block.Heading(1, "证据条文（附录）")
        b += DocxWriter.Block.Para(r.relatedClauseIds.joinToString("、"))
        b += DocxWriter.Block.Para(
            "声明：本文为古籍文献计量研究草稿，不构成诊疗建议。", italic = true)
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
                                    val draft = "主题：《伤寒论》「${r.topic}」；" +
                                        "相关条文${r.totalClauses}条；症状频次：" +
                                        r.symptomFreq.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "；药物频次：" +
                                        r.herbFreq.joinToString("、") {
                                            "${it.first}${it.second}"
                                        } + "；六经分布：" +
                                        r.channelDist.joinToString("、") {
                                            "${it.first}${it.second}"
                                        }
                                    val res = DirectLlm.complete(
                                        s.llmProvider, s.llmApiKey, s.llmBaseUrl,
                                        s.llmModel,
                                        system = "你是中医文献计量论文写作助手。" +
                                            "基于给定统计数据撰写 300 字以内的" +
                                            "学术摘要与讨论要点，只使用给定数据，" +
                                            "不得虚构数字或文献，不给临床建议。",
                                        user = draft)
                                    polished = res.getOrElse { "润色失败：${it.message}" }
                                    polishing = false
                                }
                            }) {
                                Text(if (polishing) "润色中…"
                                else "AI 润色摘要/讨论（直连大模型）")
                            }
                            if (polished.isNotBlank()) {
                                SectionCard("AI 润色稿（随 DOCX 导出）") {
                                    Text(polished,
                                        style = MaterialTheme.typography.bodySmall)
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

