package org.impfai.hermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.impfai.hermes.AppContainer
import org.impfai.hermes.R
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.EvidenceCardData
import org.impfai.hermes.core.model.TraceStepView
import org.impfai.hermes.core.model.evidenceGradeForLayer
import org.impfai.hermes.core.model.humanizeTrace
import org.impfai.hermes.core.model.starsText
import org.impfai.hermes.core.settings.AppSettings
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.ui.common.CitationBadge
import org.impfai.hermes.ui.common.LayerBadge
import org.impfai.hermes.ui.common.SafetyNoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

/**
 * 智能體屏（外部評審建議二/三/十落地）：
 * - 任務狀態：後端 agent_trace 是真實執行記錄，回答卡渲染「執行過程」
 *   清單（工具調用→反思→引用核驗→假設分層）；等待期間顯示誠實的
 *   服務端流水線說明 + 已用時，不偽造分步打勾動畫；
 * - Evidence Card：證據條文以結構化卡片呈現（原文摘錄·出處·證據分層
 *   ·星級等級·點擊回源），本地語料回查摘錄，離線也能核對原文；
 * - 會話模式：模式=角色請求（服務端真實裁定面），深度=max_steps。
 */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Bot(
        val data: AgentData,
        val evidence: List<EvidenceCardData> = emptyList(),
        val trace: List<TraceStepView> = emptyList(),
    ) : ChatItem
    data class Failure(val message: String) : ChatItem
}

class AgentViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val items: List<ChatItem> = emptyList(),
        val loading: Boolean = false,
        val simplified: Boolean = true,
        val role: String = "student",
        val mode: String = "",
        val depth: Int = AppSettings.DEFAULT_AGENT_DEPTH,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                simplified = s.simplifiedDisplay, role = s.requestedRole,
                mode = s.agentMode, depth = s.agentDepth)
        }
    }

    fun setMode(mode: String) {
        _state.value = _state.value.copy(mode = mode)
        viewModelScope.launch { container.settings.setAgentMode(mode) }
    }

    fun setDepth(depth: Int) {
        _state.value = _state.value.copy(depth = depth)
        viewModelScope.launch { container.settings.setAgentDepth(depth) }
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isBlank() || _state.value.loading) return
        _state.value = _state.value.copy(
            items = _state.value.items + ChatItem.User(q), loading = true)
        viewModelScope.launch {
            val st = _state.value
            val item = when (val r = container.repo.agent(
                q, roleOverride = st.mode.takeIf { it.isNotBlank() },
                maxSteps = st.depth)) {
                is RepoResult.Data -> ChatItem.Bot(
                    r.value,
                    evidence = resolveEvidence(r.value.evidenceClauseIds),
                    trace = humanizeTrace(r.value.agentTrace),
                )
                is RepoResult.Error -> ChatItem.Failure("${r.code}: ${r.message}")
            }
            _state.value = _state.value.copy(
                items = _state.value.items + item, loading = false)
        }
    }

    /** 證據條文 → 本地語料回查（摘錄/出處/分層），查不到只給跳轉。 */
    private suspend fun resolveEvidence(ids: List<String>): List<EvidenceCardData> {
        if (ids.isEmpty()) return emptyList()
        container.localStore.ensureLoaded()
        return ids.distinct().take(8).map { id ->
            val c = container.localStore.byId(id)
                ?: id.toIntOrNull()?.let { container.localStore.byNumber(it) }
            if (c == null) {
                EvidenceCardData(clauseId = id)
            } else {
                EvidenceCardData(
                    clauseId = c.clauseId,
                    clauseNumber = c.clauseNumber,
                    chapter = c.chapter,
                    sixChannel = c.sixChannel,
                    layer = c.layer,
                    excerpt = c.cleanText,
                    grade = evidenceGradeForLayer(c.layer),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(onOpenClause: (String) -> Unit) {
    val container = rememberContainer()
    val vm: AgentViewModel = viewModel { AgentViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size, state.loading) {
        // 列表首項是模式選擇，末項可能是 loading 卡——按實際總數滾動
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ModeSelector(
                    mode = state.mode, depth = state.depth, role = state.role,
                    enabled = !state.loading,
                    onMode = vm::setMode, onDepth = vm::setDepth,
                )
            }
            items(state.items.size) { i ->
                when (val item = state.items[i]) {
                    is ChatItem.User -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    is ChatItem.Bot -> BotCard(item, state.simplified, onOpenClause)
                    is ChatItem.Failure -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(item.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }
            if (state.loading) {
                item { RunningCard() }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.agent_input_hint)) },
                maxLines = 3,
            )
            IconButton(
                onClick = { vm.send(input); input = "" },
                enabled = !state.loading && input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.agent_send))
            }
        }
    }
}

/** 模式 = 角色請求；深度 = max_steps。上方常駐、對話中禁改。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSelector(
    mode: String,
    depth: Int,
    role: String,
    enabled: Boolean,
    onMode: (String) -> Unit,
    onDepth: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSettings.AGENT_MODES.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    enabled = enabled,
                    onClick = { onMode(m) },
                    label = {
                        Text(
                            AppSettings.AGENT_MODE_LABELS[m] ?: m,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSettings.AGENT_DEPTHS.forEach { d ->
                FilterChip(
                    selected = depth == d,
                    enabled = enabled,
                    onClick = { onDepth(d) },
                    label = {
                        Text("${AppSettings.AGENT_DEPTH_LABELS[d]}·${d}步",
                            style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
        Text(
            stringResource(R.string.agent_mode_caption,
                if (mode.isBlank()) AppSettings.ROLE_LABELS[role] ?: role
                else AppSettings.AGENT_MODE_LABELS[mode] ?: mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 等待卡：誠實呈現——服務端多步執行不可中途觀測（無 SSE），
 * 只顯示真實流水線說明與已用時，完成後由真 trace 補上執行過程。
 */
@Composable
private fun RunningCard() {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    Card {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.agent_running, elapsed),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.agent_running_pipeline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotCard(
    item: ChatItem.Bot,
    simplified: Boolean,
    onOpenClause: (String) -> Unit,
) {
    val data = item.data
    if (data.refused) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("服务端安全闸门已拒答" +
                    (data.refusedIntents.takeIf { it.isNotEmpty() }
                        ?.let { "（${it.joinToString("、")}）" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text((data.message ?: "").display(simplified),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                if (item.trace.isNotEmpty()) {
                    TraceSection(item.trace)
                }
                if (data.safetyNotice.isNotBlank()) {
                    SafetyNoticeBar(data.safetyNotice.display(simplified))
                }
            }
        }
        return
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CitationBadge(data.citationReport)
                val rate = (data.claims?.get("claim_grounding_rate") as? JsonPrimitive)
                    ?.doubleOrNull
                rate?.let {
                    Text("溯源率 ${(it * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                data.backend?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }

            // 結論
            Text(
                (data.answer ?: data.message ?: "").display(simplified),
                style = MaterialTheme.typography.bodyMedium,
            )
            data.clarification?.let {
                Text(stringResource(R.string.agent_clarification_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val unsupported = data.citationReport?.unsupported.orEmpty()
            if (unsupported.isNotEmpty()) {
                Text("未获证据支持的引用：${unsupported.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }

            // Evidence Cards（評審建議三）
            if (item.evidence.isNotEmpty()) {
                Text(stringResource(R.string.agent_evidence_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                item.evidence.forEach { ev ->
                    EvidenceCard(ev, simplified, onOpenClause)
                }
            }

            // 執行過程（評審建議二：真實 agent_trace，非動畫）
            if (item.trace.isNotEmpty()) {
                TraceSection(item.trace)
            }

            if (data.toolsUsed.isNotEmpty()) {
                Text("工具：${data.toolsUsed.distinct().joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (data.safetyNotice.isNotBlank()) {
                SafetyNoticeBar(data.safetyNotice.display(simplified))
            }
        }
    }
}

/** 單條證據卡：出處 · 分層徽章 · 星級 · 原文摘錄 · 點擊回源。 */
@Composable
private fun EvidenceCard(
    ev: EvidenceCardData,
    simplified: Boolean,
    onOpenClause: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenClause(ev.clauseId) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    ev.clauseNumber?.let { "《伤寒论》第 $it 条" } ?: ev.clauseId,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                LayerBadge(ev.layer)
                Spacer(Modifier.weight(1f))
                Text(
                    ev.grade.starsText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ev.grade.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ev.chapter.isNotBlank()) {
                    Text(
                        ev.chapter.display(simplified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (ev.excerpt.isNotBlank()) {
                Text(
                    "「${ev.excerpt.display(simplified)}」",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 3,
                )
            } else {
                Text(stringResource(R.string.agent_evidence_remote_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 執行過程折疊區：真實 trace 步驟，⚠ 標記反思/攔截/預算類事件。 */
@Composable
private fun TraceSection(trace: List<TraceStepView>) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.agent_trace_title, trace.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            trace.forEach { step ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (step.warning) Icons.Filled.Warning
                        else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).padding(top = 1.dp),
                        tint = if (step.warning) MaterialTheme.colorScheme.error
                        else Color(0xFF2E7D32),
                    )
                    Column {
                        Text(step.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium)
                        if (step.detail.isNotBlank()) {
                            Text(step.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
