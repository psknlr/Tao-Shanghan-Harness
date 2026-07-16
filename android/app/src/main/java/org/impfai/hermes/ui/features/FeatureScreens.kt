package org.impfai.hermes.ui.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.engine.TextNorm
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.SIX_CHANNELS
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

@Composable
internal fun ClauseChips(
    ids: List<String>,
    simplified: Boolean,
    onOpenClause: (String) -> Unit,
    max: Int = 8,
) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ids.distinct().take(max).forEach { cid ->
            SuggestionChip(onClick = { onOpenClause(cid) },
                label = {
                    Text(cid.removePrefix("SHL_SONGBEN_").trimStart('0')
                        .let { if (cid.contains("AUX")) cid else "第${it}条" },
                        style = MaterialTheme.typography.labelSmall)
                })
        }
    }
}

@Composable
internal fun FeatureScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (padding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
    }) { padding -> content(padding) }
}

// ---------------------------------------------------------------- 六經教學
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeachScreen(onOpenClause: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    var rules by remember {
        mutableStateOf<List<LocalClauseStore.SixChannelRule>>(emptyList())
    }
    var channel by remember { mutableStateOf("太陽病") }
    var simplified by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        simplified = container.settings.current().simplifiedDisplay
        rules = container.localStore.sixChannelRules()
    }

    FeatureScaffold("六经教学", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIX_CHANNELS.forEach { ch ->
                    FilterChip(selected = channel == ch, onClick = { channel = ch },
                        label = { Text(ch.display(simplified)) })
                }
            }
            if (rules.isEmpty()) {
                NoticeBar("六经规则库未内置（VIP 版提供）", warning = true)
                return@Column
            }
            val rule = rules.firstOrNull { it.sixChannel == channel } ?: return@Column
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SectionCard("一、纲领") {
                        Text(rule.outlineText.display(simplified),
                            style = MaterialTheme.typography.bodyLarge)
                        ClauseChips(listOf(rule.outlineClauseId), simplified, onOpenClause)
                        if (rule.resolutionTime.isNotBlank()) {
                            Text("欲解时：${rule.resolutionTime.display(simplified)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SectionCard("二、总说") {
                        Text(rule.summary.display(simplified),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (rule.subtypes.isNotEmpty()) {
                    item {
                        SectionCard("三、内部结构（亚型）") {
                            rule.subtypes.forEach { st ->
                                Column(Modifier.padding(bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(st.name.display(simplified),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold)
                                    if (st.anchorFormulas.isNotEmpty()) {
                                        Text("主方：${st.anchorFormulas.joinToString("、")
                                            .display(simplified)}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    ClauseChips(st.evidenceClauses, simplified,
                                        onOpenClause, max = 6)
                                }
                            }
                        }
                    }
                }
                if (rule.mainFormulas.isNotEmpty()) {
                    item {
                        SectionCard("四、主要方剂") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                rule.mainFormulas.forEach { mf ->
                                    SuggestionChip(onClick = {},
                                        label = { Text(
                                            "${mf.formula.display(simplified)} ×${mf.clauseCount}",
                                            style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                        }
                    }
                }
                if (rule.mistreatmentClauses.isNotEmpty()) {
                    item {
                        SectionCard("五、误治变证条文") {
                            ClauseChips(rule.mistreatmentClauses, simplified,
                                onOpenClause, max = 12)
                        }
                    }
                }
                if (rule.contraindicationClauses.isNotEmpty()) {
                    item {
                        SectionCard("六、禁忌法度条文") {
                            ClauseChips(rule.contraindicationClauses, simplified,
                                onOpenClause, max = 12)
                        }
                    }
                }
                item {
                    SectionCard("七、核心条文") {
                        ClauseChips(rule.coreClauses, simplified, onOpenClause, max = 16)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 方證鑒別
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DifferentialScreen(onOpenClause: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    var rules by remember {
        mutableStateOf<List<LocalClauseStore.DifferentialRule>>(emptyList())
    }
    var filter by remember { mutableStateOf("") }
    var simplified by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        simplified = container.settings.current().simplifiedDisplay
        rules = container.localStore.differentialRules()
    }

    FeatureScaffold("方证鉴别", onBack) { padding ->
        val q = TextNorm.normalizeQuery(filter)
        val shown = rules.filter { r ->
            filter.isBlank() || r.formulas.any {
                TextNorm.foldVariants(it).contains(q)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("按方名筛选，如：桂枝汤（共 ${rules.size} 组）") },
                    singleLine = true,
                )
            }
            if (rules.isEmpty()) {
                item { NoticeBar("鉴别规则库未内置（VIP 版提供）", warning = true) }
            }
            items(shown.size, key = { shown[it].ruleId }) { i ->
                val r = shown[i]
                val open = r.ruleId in expanded
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            r.formulas.joinToString("  ⇄  ").display(simplified),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { expanded =
                                    if (open) expanded - r.ruleId else expanded + r.ruleId },
                        )
                        if (r.sharedFeatures.isNotEmpty()) {
                            Text("共见：${r.sharedFeatures.joinToString("、")
                                .display(simplified)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (open) {
                            if (r.keyDiscriminators.isNotEmpty()) {
                                SectionCard("鉴别眼目") {
                                    r.keyDiscriminators.forEach {
                                        Text("· ${it.display(simplified)}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (r.contrastTable.isNotEmpty()) {
                                SectionCard("对比表") {
                                    r.contrastTable.forEach { row ->
                                        val axis = (row["axis"] as? JsonPrimitive)
                                            ?.content ?: ""
                                        Text(axis.display(simplified),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary)
                                        r.formulas.forEach { f ->
                                            val v = (row[f] as? JsonPrimitive)?.content
                                            if (!v.isNullOrBlank()) {
                                                Text("${f.display(simplified)}：" +
                                                    v.display(simplified),
                                                    style = MaterialTheme
                                                        .typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                            r.compositionDiff?.let { cd ->
                                SectionCard("组成差异") {
                                    cd.forEach { (k, v) ->
                                        val items = (v as? JsonArray)
                                            ?.mapNotNull {
                                                (it as? JsonPrimitive)?.content
                                            } ?: emptyList()
                                        if (items.isNotEmpty()) {
                                            Text("${k.display(simplified)}：" +
                                                items.joinToString("、")
                                                    .display(simplified),
                                                style = MaterialTheme
                                                    .typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            ClauseChips(r.supportingClauses, simplified,
                                onOpenClause, max = 10)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 誤治傳變
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistreatScreen(onOpenClause: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    var rules by remember {
        mutableStateOf<List<LocalClauseStore.MistreatmentRule>>(emptyList())
    }
    var type by remember { mutableStateOf("") }
    var simplified by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        simplified = container.settings.current().simplifiedDisplay
        rules = container.localStore.mistreatmentRules()
    }

    FeatureScaffold("误治传变", onBack) { padding ->
        val types = rules.map { it.mistreatmentType }.distinct()
        val shown = rules.filter { type.isBlank() || it.mistreatmentType == type }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        FilterChip(selected = type == t,
                            onClick = { type = if (type == t) "" else t },
                            label = { Text(t.display(simplified)) })
                    }
                }
            }
            if (rules.isEmpty()) {
                item { NoticeBar("误治规则库未内置（VIP 版提供）", warning = true) }
            }
            items(shown.size, key = { shown[it].ruleId }) { i ->
                val r = shown[i]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            r.path.joinToString("  →  ").display(simplified),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (r.manifestations.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.manifestations.take(10).forEach {
                                    SuggestionChip(onClick = {},
                                        label = { Text(it.display(simplified),
                                            style = MaterialTheme
                                                .typography.labelSmall) })
                                }
                            }
                        }
                        if (r.rescueFormulas.isNotEmpty()) {
                            Text("救逆：${r.rescueFormulas.joinToString("、")
                                .display(simplified)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                        ClauseChips(r.supportingClauses, simplified, onOpenClause)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 溯源工作台
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceScreen(onOpenClause: (String) -> Unit, onBack: () -> Unit) {
    val container = rememberContainer()
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("quote") }
    var simplified by remember { mutableStateOf(true) }
    var exact by remember {
        mutableStateOf<List<LocalClauseStore.LocalClause>>(emptyList())
    }
    var nearest by remember {
        mutableStateOf<List<Pair<LocalClauseStore.LocalClause, Double>>>(emptyList())
    }
    var termHits by remember {
        mutableStateOf<List<LocalClauseStore.LocalClause>>(emptyList())
    }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        simplified = container.settings.current().simplifiedDisplay
    }

    suspend fun run() {
        val store = container.localStore
        store.ensureLoaded()
        val q = TextNorm.foldVariants(
            TextNorm.s2t(input.trim())).replace(Regex("\\s+"), "")
        if (q.isBlank()) return
        val all = store.allClauses()
        if (mode == "quote") {
            exact = all.filter {
                TextNorm.foldVariants(it.cleanText).replace(Regex("\\s+"), "")
                    .contains(q)
            }.take(10)
            nearest = if (exact.isNotEmpty()) emptyList()
            else all.map { c ->
                c to dice(q, TextNorm.foldVariants(c.cleanText))
            }.sortedByDescending { it.second }.take(5)
        } else {
            termHits = all.filter {
                TextNorm.foldVariants(it.cleanText).contains(q)
            }
        }
        searched = true
    }

    FeatureScaffold("溯源工作台（端侧简版）", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = mode == "quote",
                            onClick = { mode = "quote"; searched = false },
                            label = { Text("引文核验") })
                        FilterChip(selected = mode == "term",
                            onClick = { mode = "term"; searched = false },
                            label = { Text("术语谱系") })
                    }
                    val scope = rememberCoroutineScope()
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(if (mode == "quote")
                                "粘贴一句引文，核验是否《伤寒论》原文"
                            else "输入术语，如：往来寒热")
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            scope.launch { run() }
                        }),
                    )
                    Text("端侧简版：逐字核验 + 字二元相似度定位；" +
                        "历代引文网络/注家谱系需 Hermes 服务端。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (searched && mode == "quote") {
                item {
                    if (exact.isNotEmpty()) {
                        NoticeBar("✓ 逐字存在于《伤寒论》（异体字折叠后）——共 ${exact.size} 处")
                    } else {
                        NoticeBar("✗ 未逐字找到；以下为最相近条文（字二元 Dice 相似度）",
                            warning = true)
                    }
                }
                items((exact.ifEmpty { nearest.map { it.first } }).size) { i ->
                    val c = exact.ifEmpty { nearest.map { it.first } }[i]
                    val sim = nearest.getOrNull(i)?.second
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(c.clauseNumber?.let { "第 $it 条" } ?: c.clauseId,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        onOpenClause(c.clauseId) })
                                sim?.let {
                                    Text("相似度 ${"%.2f".format(it)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant)
                                }
                            }
                            Text(c.cleanText.display(simplified),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4)
                        }
                    }
                }
            }
            if (searched && mode == "term") {
                item {
                    val byChannel = termHits.groupBy { it.sixChannel ?: "（其他）" }
                    SectionCard("「${input.trim()}」分布：${termHits.size} 条") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            byChannel.entries.sortedByDescending { it.value.size }
                                .forEach { (ch, l) ->
                                    SuggestionChip(onClick = {},
                                        label = { Text(
                                            "${ch.display(simplified)} ×${l.size}",
                                            style = MaterialTheme
                                                .typography.labelSmall) })
                                }
                        }
                    }
                }
                items(termHits.take(20).size) { i ->
                    val c = termHits[i]
                    Card(Modifier.fillMaxWidth()
                        .clickable { onOpenClause(c.clauseId) }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(c.clauseNumber?.let { "第 $it 条" } ?: c.clauseId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(c.cleanText.display(simplified),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

private fun dice(a: String, b: String): Double {
    fun bigrams(s: String): Set<String> {
        val chars = s.filter { it.code in 0x3400..0x9FFF }
        if (chars.length < 2) return chars.map { it.toString() }.toSet()
        return (0 until chars.length - 1).map { chars.substring(it, it + 2) }.toSet()
    }
    val sa = bigrams(a); val sb = bigrams(b)
    if (sa.isEmpty() || sb.isEmpty()) return 0.0
    return 2.0 * (sa intersect sb).size / (sa.size + sb.size)
}
