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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.material3.FilterChip
import org.impfai.hermes.AppContainer
import org.impfai.hermes.BuildConfig
import org.impfai.hermes.R
import org.impfai.hermes.core.chat.ChatHistoryStore
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.CitationReport
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

sealed interface ChatItem {
    data class User(val text: String) : ChatItem

    /** 回答 + 已解析證據卡（本地語料回查）+ 人類可讀執行過程。 */
    data class Bot(
        val data: AgentData,
        val evidence: List<EvidenceCardData> = emptyList(),
        val trace: List<TraceStepView> = emptyList(),
    ) : ChatItem
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
        /** 當前會話 id（空 = 尚未持久化的新會話）。 */
        val sessionId: String = "",
        /** 歷史會話列表（歷史面板數據）。 */
        val history: List<ChatHistoryStore.Session> = emptyList(),
        /** 會話模式（角色請求映射，僅服務端通道生效）。 */
        val mode: String = "",
        /** 推理深度 max_steps（僅服務端通道生效）。 */
        val depth: Int = AppSettings.DEFAULT_AGENT_DEPTH,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                simplified = s.simplifiedDisplay, role = s.requestedRole,
                directReady = s.llmApiKey.isNotBlank(),
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

    // —— 聊天記錄（會話持久化 + 歷史查看）——

    fun refreshHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                history = container.chatHistory.list())
        }
    }

    /** 新對話：清屏開新會話（舊會話已持久化，歷史裡可回看/續聊）。 */
    fun newChat() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(items = emptyList(), sessionId = "")
    }

    /** 載入歷史會話：消息回放進聊天框，繼續提問會續寫同一會話。 */
    fun loadSession(id: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            val sess = container.chatHistory.load(id) ?: return@launch
            val items = sess.messages.map { m ->
                when (m.role) {
                    "user" -> ChatItem.User(m.text)
                    "failure" -> ChatItem.Failure(m.text)
                    else -> botItem(restoreAgentData(m))
                }
            }
            _state.value = _state.value.copy(
                items = items, sessionId = sess.id)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            container.chatHistory.delete(id)
            if (_state.value.sessionId == id) {
                _state.value = _state.value.copy(items = emptyList(), sessionId = "")
            }
            refreshHistory()
        }
    }

    /** 歷史消息 → 最小 AgentData（引用狀態按存檔檔位重建，不虛構明細）。 */
    private fun restoreAgentData(m: ChatHistoryStore.Message): AgentData {
        val report = when (m.citation) {
            "verified" -> CitationReport(
                cited = m.evidence, verified = m.evidence,
                hasAnyCitation = true, ok = true)
            "partial" -> CitationReport(
                cited = m.evidence, hasAnyCitation = true, ok = false)
            else -> null
        }
        return AgentData(
            refused = m.citation == "refused",
            message = if (m.citation == "refused") m.answer else null,
            answer = m.answer.takeIf { m.citation != "refused" },
            backend = m.backend.takeIf { it.isNotBlank() },
            evidenceClauseIds = m.evidence,
            citationReport = report,
        )
    }

    /** 每輪交互後全量落盤當前會話（覆蓋寫，含標題與更新時間）。 */
    private suspend fun persistSession() {
        val st = _state.value
        val msgs = st.items.mapNotNull { item ->
            when (item) {
                is ChatItem.User -> ChatHistoryStore.Message(
                    role = "user", text = item.text)
                is ChatItem.Failure -> ChatHistoryStore.Message(
                    role = "failure", text = item.message)
                is ChatItem.Bot -> {
                    val d = item.data
                    val report = d.citationReport
                    ChatHistoryStore.Message(
                        role = "bot",
                        answer = d.answer ?: d.message ?: "",
                        backend = d.backend ?: "",
                        evidence = d.evidenceClauseIds,
                        citation = when {
                            d.refused -> "refused"
                            report != null && report.ok -> "verified"
                            report != null && report.hasAnyCitation -> "partial"
                            else -> "none"
                        },
                    )
                }
                is ChatItem.Streaming -> null
            }
        }
        if (msgs.isEmpty()) return
        val id = st.sessionId.ifBlank { ChatHistoryStore.newSessionId() }
        if (st.sessionId.isBlank()) {
            _state.value = _state.value.copy(sessionId = id)
        }
        val existing = container.chatHistory.load(id)
        container.chatHistory.save(ChatHistoryStore.Session(
            id = id,
            createdTs = existing?.createdTs ?: ChatHistoryStore.timestamp(),
            updatedTs = ChatHistoryStore.timestamp(),
            title = st.items.filterIsInstance<ChatItem.User>()
                .firstOrNull()?.text?.take(40) ?: "对话",
            source = st.source,
            messages = msgs,
        ))
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

    private suspend fun botItem(data: AgentData): ChatItem.Bot = ChatItem.Bot(
        data,
        evidence = resolveEvidence(data.evidenceClauseIds),
        trace = humanizeTrace(data.agentTrace),
    )

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
                    is RepoResult.Data -> botItem(result.value).let { bot ->
                        // 直連回答無 agent_trace：把流式步驟時間線收進
                        // 執行過程折疊區（完畢後折疊、點擊再展開）
                        if (bot.trace.isEmpty() && steps.isNotEmpty()) {
                            bot.copy(trace = steps.mapIndexed { i, label ->
                                org.impfai.hermes.core.model.TraceStepView(
                                    i + 1, label)
                            })
                        } else bot
                    }
                    is RepoResult.Error ->
                        ChatItem.Failure("${result.code}: ${result.message}")
                }
                replaceLast(item)
                _state.value = _state.value.copy(loading = false)
                persistSession()
            } else {
                val st = _state.value
                val result = container.repo.agent(
                    q, roleOverride = st.mode.takeIf { it.isNotBlank() },
                    maxSteps = st.depth)
                val item = when (result) {
                    is RepoResult.Data -> botItem(result.value)
                    is RepoResult.Error ->
                        ChatItem.Failure("${result.code}: ${result.message}")
                }
                _state.value = _state.value.copy(
                    items = _state.value.items + item, loading = false)
                persistSession()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(onOpenClause: (String) -> Unit, prefill: String = "") {
    val container = rememberContainer()
    val vm: AgentViewModel = viewModel { AgentViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // 條文頁「AI 解讀」帶入的預填問題；prefill 變化（換條文）時重置輸入
    var input by remember(prefill) { mutableStateOf(prefill) }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size, state.loading) {
        // 列表首項是說明文字，末項可能是 loading 指示器——按實際總數滾動
        //（審查發現 #10：按 items.size-1 會停在倒數第二條）
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }

    // 流式跟隨：增量文本/新步驟到達時貼住底部（方便看最新輸出）；
    // 用戶上滑離開直播邊緣（>800px）即停止跟隨，不搶閱讀位置
    val streamSig = (state.items.lastOrNull() as? ChatItem.Streaming)
        ?.let { it.steps.size * 1_000_000 + it.partial.length } ?: -1
    LaunchedEffect(streamSig) {
        if (streamSig < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
            ?: return@LaunchedEffect
        if (lastVisible.index < info.totalItemsCount - 1) return@LaunchedEffect
        val bottomGap = (lastVisible.offset + lastVisible.size) -
            info.viewportEndOffset
        if (bottomGap < 800 && !listState.isScrollInProgress) {
            listState.scrollToItem(
                info.totalItemsCount - 1, scrollOffset = 1_000_000)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    // 會話工具條：歷史查看 + 新對話（聊天記錄持久化在本機）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("对话",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            vm.refreshHistory(); showHistory = true
                        }) { Text("🕘 历史") }
                        TextButton(
                            onClick = vm::newChat,
                            enabled = !state.loading && state.items.isNotEmpty(),
                        ) { Text("＋ 新对话") }
                    }
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
                    // 服務端通道：會話模式（角色面）+ 推理深度（max_steps）
                    // ——只映射服務端真實存在的檔位（評審建議十）
                    if (state.source == "server") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppSettings.AGENT_MODES.forEach { m ->
                                FilterChip(
                                    selected = state.mode == m,
                                    enabled = !state.loading,
                                    onClick = { vm.setMode(m) },
                                    label = { Text(
                                        AppSettings.AGENT_MODE_LABELS[m] ?: m,
                                        style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppSettings.AGENT_DEPTHS.forEach { d ->
                                FilterChip(
                                    selected = state.depth == d,
                                    enabled = !state.loading,
                                    onClick = { vm.setDepth(d) },
                                    label = { Text(
                                        "${AppSettings.AGENT_DEPTH_LABELS[d]}·${d}步",
                                        style = MaterialTheme.typography.labelMedium) },
                                )
                            }
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
                    is ChatItem.Bot -> BotCard(item, state.simplified, onOpenClause)
                    is ChatItem.Streaming -> Card {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp),
                                    strokeWidth = 2.dp)
                                Text("执行过程 · 进行中（完成后自动折叠）",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
            if (state.loading && state.source == "server") {
                // 服務端多步執行不可中途觀測（無 SSE）：只顯示真實流水線
                // 說明與已用時，完成後由真 trace 補上執行過程（直連通道
                // 另有 Streaming 卡實時顯示步驟）
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

    // —— 歷史會話面板（本機持久化：殺進程/換頁不丟）——
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("历史对话（${state.history.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                if (state.history.isEmpty()) {
                    Text("暂无历史对话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(
                    Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.history.size) { i ->
                        val sess = state.history[i]
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                vm.loadSession(sess.id)
                                showHistory = false
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (sess.id == state.sessionId)
                                    MaterialTheme.colorScheme.primaryContainer
                                        .copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceVariant
                                    .copy(alpha = 0.5f)),
                        ) {
                            Row(
                                Modifier.padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f).padding(vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(sess.title.ifBlank { "对话" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1)
                                    Text(
                                        ChatHistoryStore.shortTime(
                                            sess.updatedTs.ifBlank { sess.createdTs }) +
                                            " · ${sess.messages.size} 条" +
                                            if (sess.source == "direct")
                                                " · 直连" else " · 服务端",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.deleteSession(sess.id) }) {
                                    Icon(Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
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

            // Evidence Card（評審建議三）：原文摘錄·出處·分層·星級·點擊回源
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

/** 服務端等待卡：誠實呈現流水線說明與已用時，不偽造分步動畫。 */
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
