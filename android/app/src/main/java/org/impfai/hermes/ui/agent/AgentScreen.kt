package org.impfai.hermes.ui.agent

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import androidx.compose.material3.FilterChip
import org.impfai.hermes.AppContainer
import org.impfai.hermes.BuildConfig
import org.impfai.hermes.R
import org.impfai.hermes.core.chat.ChatHistoryStore
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.CitationReport
import org.impfai.hermes.core.model.DirectEvidenceItem
import org.impfai.hermes.core.model.EvidenceCardData
import org.impfai.hermes.core.model.EvidenceGrade
import org.impfai.hermes.core.model.TraceStepView
import org.impfai.hermes.core.model.evidenceGradeForLayer
import org.impfai.hermes.core.model.humanizeTrace
import org.impfai.hermes.core.model.splitThink
import org.impfai.hermes.core.model.starsText
import org.impfai.hermes.core.settings.AppSettings
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.engine.DocxWriter
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
        /** 直連通道深度思考（多輪檢索+Skill 指引+評估補檢）。 */
        val deepThink: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                simplified = s.simplifiedDisplay, role = s.requestedRole,
                directReady = s.llmApiKey.isNotBlank(),
                mode = s.agentMode, depth = s.agentDepth,
                deepThink = s.deepThink)
        }
    }

    fun setDeepThink(on: Boolean) {
        _state.value = _state.value.copy(deepThink = on)
        viewModelScope.launch { container.settings.setDeepThink(on) }
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

    /** 一鍵導出：當前對話 → DOCX 塊（問答分節 + 證據表 + 免責聲明）。 */
    fun exportBlocks(): List<DocxWriter.Block> {
        val st = _state.value
        val b = ArrayList<DocxWriter.Block>()
        b += DocxWriter.Block.Heading(1, "伤寒Hermes 智能体对话记录")
        b += DocxWriter.Block.Para(
            "导出时间：" + ChatHistoryStore.shortTime(
                ChatHistoryStore.timestamp()) + "（UTC） · 通道：" +
                (if (st.source == "direct") "VIP 直连大模型" else "Hermes 服务端") +
                " · 消息 " + st.items.count { it !is ChatItem.Streaming } + " 条",
            italic = true)
        st.items.forEach { item ->
            when (item) {
                is ChatItem.User -> b += DocxWriter.Block.Heading(
                    2, "问：" + item.text.display(st.simplified))
                is ChatItem.Bot -> {
                    val d = item.data
                    b += DocxWriter.Block.Para(
                        splitThink(d.answer ?: d.message ?: "")
                            .visible.ifBlank { d.answer ?: d.message ?: "" }
                            .display(st.simplified))
                    val report = d.citationReport
                    b += DocxWriter.Block.Para(
                        "引用核验：" + when {
                            d.refused -> "安全闸门拒答"
                            report != null && report.ok ->
                                "已核验 " + report.verified.size + " 条"
                            report != null && report.hasAnyCitation -> "部分核验"
                            else -> "无引用"
                        } + (d.backend?.let { " · 后端 " + it } ?: ""),
                        italic = true)
                    if (item.evidence.isNotEmpty()) {
                        b += DocxWriter.Block.Table(
                            listOf("证据条文", "出处", "证据等级", "原文摘录"),
                            item.evidence.map { ev ->
                                listOf(
                                    if (ev.sourceType == "library")
                                        ("《" + ev.book + "》")
                                            .display(st.simplified)
                                    else ev.clauseNumber
                                        ?.let { "第 " + it + " 条" }
                                        ?: ev.clauseId,
                                    (if (ev.sourceType == "library") ev.section
                                    else ev.chapter).display(st.simplified),
                                    ev.grade.label,
                                    ev.excerpt.display(st.simplified).take(80),
                                )
                            })
                    }
                }
                is ChatItem.Failure -> b += DocxWriter.Block.Para(
                    "[请求失败] " + item.message, italic = true)
                is ChatItem.Streaming -> {}
            }
        }
        b += DocxWriter.Block.Para(
            "免责声明：本对话由 AI 生成，供古籍学习与研究参考，" +
                "不构成诊断或治疗建议；用药请务必咨询执业中医师。",
            italic = true)
        return b
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
            directEvidence = if (m.sources.isEmpty()) emptyList()
            else buildList {
                m.evidence.forEach {
                    add(DirectEvidenceItem(
                        sourceType = "clause", ref = it, clauseId = it))
                }
                m.sources.forEach { ref ->
                    val inner = ref.removePrefix("《").removeSuffix("》")
                    add(DirectEvidenceItem(
                        sourceType = "library", ref = ref,
                        book = inner.substringBefore('·'),
                        section = inner.substringAfter('·', "")))
                }
            },
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
                        sources = d.directEvidence
                            .filter { it.sourceType == "library" }
                            .map { it.ref },
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

    private suspend fun botItem(data: AgentData): ChatItem.Bot {
        // 直連統一取證：證據卡直接來自 _direct_evidence（含歷代書證）；
        // 服務端通道沿用條文 id 回查
        val cards = if (data.directEvidence.isNotEmpty()) {
            container.localStore.ensureLoaded()
            data.directEvidence.take(50).map { d ->
                if (d.sourceType == "clause") {
                    val c = container.localStore.byId(d.clauseId)
                    EvidenceCardData(
                        clauseId = d.clauseId,
                        clauseNumber = c?.clauseNumber,
                        chapter = c?.chapter ?: d.section,
                        sixChannel = c?.sixChannel,
                        layer = c?.layer ?: "",
                        excerpt = d.excerpt.ifBlank { c?.cleanText ?: "" },
                        grade = if (d.label.isBlank())
                            evidenceGradeForLayer(c?.layer ?: "")
                        else EvidenceGrade(d.stars, d.label),
                        sourceType = "clause",
                    )
                } else {
                    EvidenceCardData(
                        clauseId = "",
                        chapter = d.section,
                        excerpt = d.excerpt,
                        grade = EvidenceGrade(
                            d.stars, d.label.ifBlank { "书证" }),
                        sourceType = "library",
                        book = d.book,
                        section = d.section,
                    )
                }
            }
        } else {
            resolveEvidence(data.evidenceClauseIds)
        }
        return ChatItem.Bot(
            data,
            evidence = cards,
            trace = humanizeTrace(data.agentTrace),
        )
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
                val deep = _state.value.deepThink
                val result = container.repo.directAgentStream(
                    q, deepThink = deep) { ev ->
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class)
@Composable
fun AgentScreen(
    onOpenClause: (String) -> Unit,
    prefill: String = "",
    /** 歷代書證點擊 → 閱讀器定位開卷（book, section, locate）。 */
    onOpenBook: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val container = rememberContainer()
    val vm: AgentViewModel = viewModel { AgentViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // 條文頁「AI 解讀」帶入的預填問題；prefill 變化（換條文）時重置輸入
    var input by remember(prefill) { mutableStateOf(prefill) }
    var showHistory by remember { mutableStateOf(false) }
    // 消息操作菜單（點擊/長按問題氣泡）與「選擇文本」彈窗
    var msgMenuFor by remember { mutableStateOf<String?>(null) }
    var selectTextFor by remember { mutableStateOf<String?>(null) }
    val screenClipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 一鍵導出 DOCX（SAF：用戶選保存位置，零存儲權限）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument" +
                ".wordprocessingml.document")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val blocks = vm.exportBlocks()
        scope.launch(Dispatchers.IO) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    DocxWriter.write(out, "伤寒Hermes 智能体对话记录", blocks)
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context,
                    if (ok) "对话已导出为 DOCX" else "导出失败，请重试",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                        TextButton(
                            onClick = {
                                exportLauncher.launch(
                                    "伤寒Hermes对话-" + ChatHistoryStore
                                        .shortTime(ChatHistoryStore.timestamp())
                                        .replace(Regex("[^0-9]"), "") + ".docx")
                            },
                            enabled = !state.loading && state.items.isNotEmpty(),
                        ) { Text("⬇ 导出") }
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
                        if (state.source == "direct") {
                            FilterChip(
                                selected = state.deepThink,
                                enabled = !state.loading,
                                onClick = { vm.setDeepThink(!state.deepThink) },
                                label = { Text(
                                    if (state.deepThink) "🧠 深度思考 · 开"
                                    else "🧠 深度思考",
                                    style = MaterialTheme.typography.labelMedium) },
                            )
                            if (state.deepThink) {
                                Text(
                                    "深研管线：检索规划 → 全库多轮取证 → " +
                                        "Skill 方法指引 → 证据评估补检 → 成稿",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                        // 點擊/長按問題氣泡 → 操作菜單（複製/選擇文本/編輯消息）
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
                                .combinedClickable(
                                    onClick = { msgMenuFor = item.text },
                                    onLongClick = { msgMenuFor = item.text },
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    is ChatItem.Bot -> BotCard(
                        item, state.simplified, onOpenClause, onOpenBook)
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
                                // <think> 流式拆分：思考塊實時展開顯示
                                //（完成後由 BotCard 折疊為「思考過程」）
                                val sp = splitThink(item.partial)
                                if (sp.think.isNotBlank()) {
                                    Column(
                                        Modifier.fillMaxWidth().background(
                                            MaterialTheme.colorScheme
                                                .surfaceVariant.copy(alpha = 0.4f),
                                            RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalArrangement =
                                            Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            if (sp.inThink) "🧠 模型思考中…"
                                            else "🧠 思考过程（完成后折叠）",
                                            style = MaterialTheme
                                                .typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant)
                                        Text(sp.think
                                            .display(state.simplified),
                                            style = MaterialTheme
                                                .typography.labelSmall,
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (sp.visible.isNotBlank() || !sp.inThink) {
                                    SelectionContainer {
                                        Text(sp.visible
                                            .display(state.simplified) + " ▌",
                                            style = MaterialTheme
                                                .typography.bodyMedium)
                                    }
                                }
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

    // —— 消息操作菜單（複製 / 選擇文本 / 編輯消息）——
    msgMenuFor?.let { txt ->
        AlertDialog(
            onDismissRequest = { msgMenuFor = null },
            title = { Text("消息操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(txt, maxLines = 3,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    TextButton(onClick = {
                        screenClipboard.setText(AnnotatedString(txt))
                        Toast.makeText(context, "已复制",
                            Toast.LENGTH_SHORT).show()
                        msgMenuFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("📋 复制") }
                    TextButton(onClick = {
                        selectTextFor = txt; msgMenuFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("🔍 选择文本") }
                    TextButton(onClick = {
                        input = txt; msgMenuFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("✏️ 编辑消息") }
                }
            },
            confirmButton = {
                TextButton(onClick = { msgMenuFor = null }) { Text("取消") }
            },
        )
    }

    // —— 選擇文本（系統選擇柄自由選詞）——
    selectTextFor?.let { txt ->
        AlertDialog(
            onDismissRequest = { selectTextFor = null },
            title = { Text("选择文本") },
            text = { SelectionContainer { Text(txt) } },
            confirmButton = {
                TextButton(onClick = { selectTextFor = null }) { Text("完成") }
            },
        )
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
    onOpenBook: (String, String, String) -> Unit = { _, _, _ -> },
) {
    val data = item.data
    // <think> 拆分：正文與思考過程分離，思考完成後默認折疊
    val split = splitThink(data.answer ?: data.message ?: "")
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
                SelectionContainer {
                    Text((data.message ?: "").display(simplified),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
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

    val clipboard = LocalClipboardManager.current
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
                Spacer(Modifier.weight(1f))
                // 一鍵複製整段回答（僅正文，不含思考過程）
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(
                            split.visible.display(simplified)))
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy,
                        contentDescription = "复制回答",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 思考過程（<think> 標籤內容）：完成後折疊，點擊展開
            if (split.think.isNotBlank()) {
                ThinkSection(split.think, simplified)
            }

            // 結論（SelectionContainer：可長按自由選擇複製）
            SelectionContainer {
                Text(
                    split.visible.ifBlank {
                        (data.answer ?: data.message ?: "")
                    }.display(simplified),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
                    EvidenceCard(ev, simplified, onOpenClause, onOpenBook)
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
    onOpenBook: (String, String, String) -> Unit = { _, _, _ -> },
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            if (ev.sourceType == "library") {
                // 歷代書證：閱讀器定位開卷（摘錄前綴作定位詞）
                onOpenBook(ev.book, ev.section,
                    ev.excerpt.take(12))
            } else {
                onOpenClause(ev.clauseId)
            }
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (ev.sourceType == "library")
                        ("《" + ev.book +
                            (ev.section.takeIf { it.isNotBlank() }
                                ?.let { "·$it" } ?: "") + "》")
                            .display(simplified)
                    else ev.clauseNumber?.let { "《伤寒论》第 $it 条" }
                        ?: ev.clauseId,
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
                SelectionContainer {
                    Text(
                        "「${ev.excerpt.display(simplified)}」",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        maxLines = 3,
                    )
                }
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

/** 思考過程折疊區（<think> 內容）：完成後默認折疊，點擊展開。 */
@Composable
private fun ThinkSection(think: String, simplified: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🧠 思考过程（${think.length} 字）",
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
            SelectionContainer {
                Text(
                    think.display(simplified),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)).padding(8.dp),
                )
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
