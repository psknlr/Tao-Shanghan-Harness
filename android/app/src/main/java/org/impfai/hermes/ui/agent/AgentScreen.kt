package org.impfai.hermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import androidx.compose.material3.FilterChip
import org.impfai.hermes.AppContainer
import org.impfai.hermes.BuildConfig
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.ui.common.CitationBadge
import org.impfai.hermes.ui.common.SafetyNoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Bot(val data: AgentData) : ChatItem
    data class Failure(val message: String) : ChatItem

    /** 直連流式中間態：步驟時間線 + 增量文本。 */
    data class Streaming(
        val steps: List<String>,
        val partial: String,
    ) : ChatItem
}

class AgentViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val items: List<ChatItem> = emptyList(),
        val loading: Boolean = false,
        val simplified: Boolean = true,
        val role: String = "student",
        /** "server"=Hermes 服務端；"direct"=VIP 直連大模型（BYOK）。
         *  VIP 默認直連——純端側版本不依賴 Hermes 服務端。 */
        val source: String = if (BuildConfig.VIP) "direct" else "server",
        val directReady: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                simplified = s.simplifiedDisplay, role = s.requestedRole,
                directReady = s.llmApiKey.isNotBlank())
        }
    }

    fun setSource(source: String) {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                source = source, directReady = s.llmApiKey.isNotBlank())
        }
    }

    private fun replaceLast(item: ChatItem) {
        _state.value = _state.value.copy(
            items = _state.value.items.dropLast(1) + item)
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isBlank() || _state.value.loading) return
        _state.value = _state.value.copy(
            items = _state.value.items + ChatItem.User(q), loading = true)
        viewModelScope.launch {
            if (_state.value.source == "direct") {
                // 直連：流式——思考/檢索步驟時間線 + 增量文本
                var steps = listOf<String>()
                var partial = ""
                _state.value = _state.value.copy(
                    items = _state.value.items + ChatItem.Streaming(steps, ""))
                val result = container.repo.directAgentStream(q) { ev ->
                    when (ev) {
                        is org.impfai.hermes.data.HermesRepository
                            .StreamEvent.Step -> steps = steps + ev.label
                        is org.impfai.hermes.data.HermesRepository
                            .StreamEvent.Delta -> partial += ev.text
                    }
                    viewModelScope.launch {
                        replaceLast(ChatItem.Streaming(steps, partial))
                    }
                }
                val item = when (result) {
                    is RepoResult.Data -> ChatItem.Bot(result.value)
                    is RepoResult.Error ->
                        ChatItem.Failure("${result.code}: ${result.message}")
                }
                replaceLast(item)
                _state.value = _state.value.copy(loading = false)
            } else {
                val result = container.repo.agent(q)
                val item = when (result) {
                    is RepoResult.Data -> ChatItem.Bot(result.value)
                    is RepoResult.Error ->
                        ChatItem.Failure("${result.code}: ${result.message}")
                }
                _state.value = _state.value.copy(
                    items = _state.value.items + item, loading = false)
            }
        }
    }
}

@Composable
fun AgentScreen(onOpenClause: (String) -> Unit, prefill: String = "") {
    val container = rememberContainer()
    val vm: AgentViewModel = viewModel { AgentViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // 條文頁「AI 解讀」帶入的預填問題；prefill 變化（換條文）時重置輸入
    var input by remember(prefill) { mutableStateOf(prefill) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size, state.loading) {
        // 列表首項是說明文字，末項可能是 loading 指示器——按實際總數滾動
        //（審查發現 #10：按 items.size-1 會停在倒數第二條）
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
                Column(Modifier.padding(top = 12.dp)) {
                    if (BuildConfig.VIP) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = state.source == "server",
                                onClick = { vm.setSource("server") },
                                label = { Text("Hermes 服务端") },
                            )
                            FilterChip(
                                selected = state.source == "direct",
                                onClick = { vm.setSource("direct") },
                                label = { Text("直连大模型") },
                            )
                        }
                        if (state.source == "direct" && !state.directReady) {
                            Text(
                                "尚未配置模型 API Key —— 请到「我的 → 直连大模型」设置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        if (state.source == "direct")
                            "直连模式：本地 BM25 先取证据条文 → 大模型作答 → " +
                                "本地 CitationGuard 核验引用（密钥仅存本机）。"
                        else
                            "围绕《伤寒论》条文提问；回答由服务端智能体生成，" +
                                "引用经 CitationGuard 核验（当前角色请求：${state.role}）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    is ChatItem.Bot -> BotCard(item.data, state.simplified, onOpenClause)
                    is ChatItem.Streaming -> Card {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.steps.forEach { s ->
                                Text(s, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (item.partial.isNotBlank()) {
                                Text(item.partial.display(state.simplified) + " ▌",
                                    style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp))
                                    Text("生成中…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme
                                            .colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
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
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                    }
                }
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
                placeholder = { Text("如：太阳中风的病机是什么？") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) { vm.send(input); input = "" }
                }),
            )
            IconButton(
                onClick = { vm.send(input); input = "" },
                enabled = !state.loading && input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotCard(
    data: AgentData,
    simplified: Boolean,
    onOpenClause: (String) -> Unit,
) {
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
            Text(
                (data.answer ?: data.message ?: "").display(simplified),
                style = MaterialTheme.typography.bodyMedium,
            )
            data.clarification?.let {
                Text("（智能体请求补充信息，请提供更完整的四诊描述）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val unsupported = data.citationReport?.unsupported.orEmpty()
            if (unsupported.isNotEmpty()) {
                Text("未获证据支持的引用：${unsupported.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (data.evidenceClauseIds.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    data.evidenceClauseIds.distinct().take(8).forEach { cid ->
                        SuggestionChip(
                            onClick = { onOpenClause(cid) },
                            label = { Text(cid, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            if (data.toolsUsed.isNotEmpty()) {
                Text("工具：${data.toolsUsed.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (data.safetyNotice.isNotBlank()) {
                SafetyNoticeBar(data.safetyNotice.display(simplified))
            }
        }
    }
}
