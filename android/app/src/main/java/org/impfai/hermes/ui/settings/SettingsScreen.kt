package org.impfai.hermes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.BuildConfig
import org.impfai.hermes.core.llm.DirectLlm
import org.impfai.hermes.core.settings.AppSettings
import org.impfai.hermes.data.ServerStatus
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.core.audit.AuditLog
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.rememberContainer

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val loaded: Boolean = false,
        val baseUrl: String = "",
        val token: String = "",
        val role: String = "student",
        val simplified: Boolean = true,
        val offlineOnly: Boolean = false,
        val testing: Boolean = false,
        val testResult: String = "",
        val testOk: Boolean = false,
        val saved: Boolean = false,
        // —— VIP 直連大模型 ——
        val llmProvider: String = "anthropic",
        val llmApiKey: String = "",
        val llmBaseUrl: String = "",
        val llmModel: String = "",
        val llmMaxTokens: String = "8192",
        val llmSaved: Boolean = false,
        val llmTesting: Boolean = false,
        val llmTestResult: String = "",
        val llmTestOk: Boolean = false,
        val secureStorage: Boolean = true,
        val auditCount: Int = 0,
        val auditEntries: List<AuditLog.Entry> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = UiState(
                loaded = true, baseUrl = s.baseUrl, token = s.apiToken,
                role = s.requestedRole, simplified = s.simplifiedDisplay,
                offlineOnly = s.offlineOnly,
                llmProvider = s.llmProvider, llmApiKey = s.llmApiKey,
                llmBaseUrl = s.llmBaseUrl, llmModel = s.llmModel,
                llmMaxTokens = s.llmMaxTokens.toString(),
                secureStorage = s.secureTokenStorage,
            )
            loadAudit()
        }
    }

    fun loadAudit() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                auditCount = container.auditLog.count(),
                auditEntries = container.auditLog.recent(20),
            )
        }
    }

    fun clearAudit() {
        viewModelScope.launch {
            container.auditLog.clear()
            loadAudit()
        }
    }

    fun editLlm(
        provider: String? = null, apiKey: String? = null,
        baseUrl: String? = null, model: String? = null,
        maxTokens: String? = null,
    ) {
        _state.value = _state.value.copy(
            llmProvider = provider ?: _state.value.llmProvider,
            llmApiKey = apiKey ?: _state.value.llmApiKey,
            llmBaseUrl = baseUrl ?: _state.value.llmBaseUrl,
            llmModel = model ?: _state.value.llmModel,
            llmMaxTokens = maxTokens ?: _state.value.llmMaxTokens,
            llmSaved = false,
        )
    }

    private fun maxTokensOrDefault(): Int =
        _state.value.llmMaxTokens.trim().toIntOrNull() ?: 8192

    fun saveLlm() {
        viewModelScope.launch {
            val st = _state.value
            container.settings.setLlm(st.llmProvider, st.llmApiKey,
                st.llmBaseUrl, st.llmModel, maxTokensOrDefault())
            _state.value = _state.value.copy(llmSaved = true)
        }
    }

    fun testLlm() {
        viewModelScope.launch {
            val st = _state.value
            _state.value = st.copy(llmTesting = true, llmTestResult = "")
            container.settings.setLlm(st.llmProvider, st.llmApiKey,
                st.llmBaseUrl, st.llmModel,
                maxTokensOrDefault())   // 先保存再測：測的就是將用的配置
            val r = DirectLlm.testConnection(st.llmProvider, st.llmApiKey,
                st.llmBaseUrl, st.llmModel)
            _state.value = _state.value.copy(
                llmTesting = false, llmSaved = true,
                llmTestOk = r.isSuccess,
                llmTestResult = r.getOrElse { it.message ?: "测试失败" },
            )
        }
    }

    fun edit(baseUrl: String? = null, token: String? = null, role: String? = null) {
        _state.value = _state.value.copy(
            baseUrl = baseUrl ?: _state.value.baseUrl,
            token = token ?: _state.value.token,
            role = role ?: _state.value.role,
            saved = false,
        )
    }

    fun save() {
        viewModelScope.launch {
            val st = _state.value
            container.settings.setServer(st.baseUrl, st.token, st.role)
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun setSimplified(on: Boolean) {
        viewModelScope.launch {
            container.settings.setSimplifiedDisplay(on)
            _state.value = _state.value.copy(simplified = on)
        }
    }

    fun setOfflineOnly(on: Boolean) {
        viewModelScope.launch {
            container.settings.setOfflineOnly(on)
            _state.value = _state.value.copy(offlineOnly = on)
        }
    }

    fun test() {
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, testResult = "")
            // 先保存再測試：測試的就是將要使用的配置
            val st = _state.value
            container.settings.setServer(st.baseUrl, st.token, st.role)
            val status: ServerStatus = container.repo.serverStatus()
            val (ok, text) = when {
                !status.reachable -> false to "无法连接：${status.detail}"
                !status.ready -> false to "服务端可达但数据未就绪（请在服务端运行 pipeline）"
                else -> true to buildString {
                    append("连接成功 · 后端 ${status.backend}")
                    append(" · 角色上限 ${status.roleCeiling.ifBlank { "?" }}")
                    status.effectiveRole?.let { append(" · 生效角色 $it") }
                    if (status.contentVersion.isNotBlank()) {
                        append(" · 语料版本 ${status.contentVersion}")
                    }
                }
            }
            _state.value = _state.value.copy(
                testing = false, testResult = text, testOk = ok, saved = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val container = rememberContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(false) }
    var showLlmKey by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        SectionCard(
            if (BuildConfig.VIP) "Hermes 服务端（可选：VIP 默认纯端侧运行）"
            else "服务端接入（API 设置）"
        ) {
            if (BuildConfig.VIP) {
                Text(
                    "VIP 版全量知识库已内置，默认不连接任何服务器；" +
                        "如需运行中心/深度研究/历代引用溯源等平台能力，" +
                        "可在此配置自建 Hermes 服务端并关闭下方“仅离线模式”。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = { vm.edit(baseUrl = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务端地址") },
                placeholder = { Text("https://hermes.example.org/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = { vm.edit(token = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("访问令牌（HERMES_API_KEYS 角色绑定 Key）") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = "显示/隐藏",
                        )
                    }
                },
            )
            Text("请求角色（真实角色上限由服务端令牌绑定，不可越级）",
                style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppSettings.ROLES.forEach { r ->
                    FilterChip(
                        selected = state.role == r,
                        onClick = { vm.edit(role = r) },
                        label = { Text(AppSettings.ROLE_LABELS[r] ?: r) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save) { Text(if (state.saved) "已保存" else "保存") }
                OutlinedButton(onClick = vm::test, enabled = !state.testing) {
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
            }
            if (state.testResult.isNotBlank()) {
                NoticeBar(state.testResult, warning = !state.testOk)
            }
            if (state.baseUrl.startsWith("http://")) {
                Text(
                    "当前为明文 HTTP 地址：仅限开发调试（Release 版禁止明文流量），" +
                        "生产环境请使用 HTTPS。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                if (state.secureStorage)
                    "此处令牌只是 Hermes 服务端签发的角色绑定访问 Key，" +
                        "已经 Android Keystore 加密存储（EncryptedSharedPreferences）。"
                else
                    "警告：本机 Keystore 不可用，令牌暂以明文存储——" +
                        "请勿在此设备保存高权限令牌。",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.secureStorage)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }

        if (BuildConfig.VIP) {
            SectionCard("直连大模型（VIP · BYOK）") {
                Text("自带 API Key 直连模型服务商；Key 经 Android Keystore " +
                    "加密仅存本机（不随云备份、不发送到 Hermes 服务端）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("一键预设（填好端点与模型名，只需再填 Key）：",
                    style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DirectLlm.PRESETS.forEach { ps ->
                        FilterChip(
                            selected = state.llmBaseUrl == ps.baseUrl &&
                                state.llmProvider == ps.provider,
                            onClick = {
                                vm.editLlm(provider = ps.provider,
                                    baseUrl = ps.baseUrl, model = ps.model)
                            },
                            label = { Text(ps.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.llmApiKey,
                    onValueChange = { vm.editLlm(apiKey = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showLlmKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showLlmKey = !showLlmKey }) {
                            Icon(
                                if (showLlmKey) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = "显示/隐藏",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = state.llmBaseUrl,
                    onValueChange = { vm.editLlm(baseUrl = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL（留空用官方端点）") },
                    placeholder = {
                        Text(DirectLlm.defaultBaseUrl(state.llmProvider))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = state.llmModel,
                    onValueChange = { vm.editLlm(model = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名（留空用默认）") },
                    placeholder = { Text(DirectLlm.defaultModel(state.llmProvider)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.llmMaxTokens,
                    onValueChange = { s ->
                        vm.editLlm(maxTokens = s.filter { it.isDigit() }.take(6))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最大输出 tokens（防长答截断）") },
                    placeholder = { Text("8192") },
                    supportingText = {
                        Text("v1.6：全部模型调用统一使用该上限（1024–65536）；" +
                            "MiniMax-M3 等支持长输出的模型可调大到 32768+")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::saveLlm) {
                        Text(if (state.llmSaved) "已保存" else "保存模型配置")
                    }
                    OutlinedButton(onClick = vm::testLlm,
                        enabled = !state.llmTesting) {
                        Text(if (state.llmTesting) "测试中…" else "测试模型连接")
                    }
                }
                if (state.llmTestResult.isNotBlank()) {
                    NoticeBar(state.llmTestResult, warning = !state.llmTestOk)
                }
                Text(
                    "直连模式流程：本地 BM25 检索证据条文 → 大模型基于证据作答 → " +
                        "本地 CitationGuard 核验引用。核验强度弱于服务端全链路闸门，" +
                        "回答卡片会如实标注。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("显示") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("简体显示", style = MaterialTheme.typography.bodyMedium)
                    Text("原文以繁体为准，仅显示层转换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.simplified, onCheckedChange = vm::setSimplified)
            }
        }

        SectionCard("离线") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("仅离线模式", style = MaterialTheme.typography.bodyMedium)
                    Text("只用 APK 内置语料：条文检索/阅读/方剂库可用；" +
                        "方证匹配、智能体、注家异文等需要服务端",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.offlineOnly, onCheckedChange = vm::setOfflineOnly)
            }
        }

        // 諮詢審計記錄（外部評審建議七：本機審計軌跡；直連模式沒有
        // 服務端審計，這裡是唯一證據記錄）
        SectionCard("咨询审计记录（本机）") {
            Text(
                "共 ${state.auditCount} 条。记录每次智能体问答（服务端/直连）与" +
                    "方证匹配的证据轨迹：问题、角色、后端、证据条文、核验结果。" +
                    "仅保存在本机，用于自查与教学复盘。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showAudit = !showAudit
                    if (showAudit) vm.loadAudit()
                }) {
                    Text(if (showAudit) "收起" else "查看最近记录")
                }
                if (state.auditCount > 0) {
                    TextButton(onClick = vm::clearAudit) {
                        Text("清除全部", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (showAudit) {
                if (state.auditEntries.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.auditEntries.forEach { e ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${e.caseId} · " + when (e.kind) {
                                "agent" -> "智能体·服务端"
                                "direct" -> "智能体·直连"
                                else -> "方证匹配"
                            } + ((e.effectiveRole ?: e.requestedRole)
                                .takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(e.input, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2)
                        Text(
                            buildString {
                                append(e.verdict)
                                if (e.resultCode != "OK") append(" · ${e.resultCode}")
                                if (e.backend.isNotBlank()) append(" · ${e.backend}")
                                if (e.evidence.isNotEmpty()) {
                                    append(" · 证据 ${e.evidence.size} 条")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (e.refused || e.resultCode != "OK")
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        SectionCard("关于") {
            Text("伤寒Hermes v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text("研发者：医哲未来人工智能研究院（IMPF-AI）",
                style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(
                "架构：Android 原生客户端（可信交互端 + 离线知识端）+ " +
                    "Hermes Python 平台（权威推理端、治理端、证据端）。" +
                    "所有临床辅助推理、角色裁定、患者投影与引用核验均在服务端执行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "免责声明：本应用是中医古籍学习与研究辅助工具，不构成诊断或治疗建议；" +
                    "是否属于某种证型、如何用药，请务必由执业中医师当面判断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
