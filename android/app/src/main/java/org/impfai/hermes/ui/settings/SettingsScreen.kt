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
        val llmSaved: Boolean = false,
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
            )
        }
    }

    fun editLlm(
        provider: String? = null, apiKey: String? = null,
        baseUrl: String? = null, model: String? = null,
    ) {
        _state.value = _state.value.copy(
            llmProvider = provider ?: _state.value.llmProvider,
            llmApiKey = apiKey ?: _state.value.llmApiKey,
            llmBaseUrl = baseUrl ?: _state.value.llmBaseUrl,
            llmModel = model ?: _state.value.llmModel,
            llmSaved = false,
        )
    }

    fun saveLlm() {
        viewModelScope.launch {
            val st = _state.value
            container.settings.setLlm(st.llmProvider, st.llmApiKey,
                st.llmBaseUrl, st.llmModel)
            _state.value = _state.value.copy(llmSaved = true)
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        SectionCard("服务端接入（API 设置）") {
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
                "本应用不保存任何模型供应商密钥（OpenAI/Anthropic 等仅存在于服务端）；" +
                    "此处令牌只是 Hermes 服务端签发的角色绑定访问 Key。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (BuildConfig.VIP) {
            SectionCard("直连大模型（VIP · BYOK）") {
                Text("自带 API Key 直连模型服务商；Key 仅保存在本机" +
                    "（不随云备份、不发送到 Hermes 服务端）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DirectLlm.PROVIDERS.forEach { p ->
                        FilterChip(
                            selected = state.llmProvider == p,
                            onClick = { vm.editLlm(provider = p) },
                            label = { Text(DirectLlm.PROVIDER_LABELS[p] ?: p) },
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
                Button(onClick = vm::saveLlm) {
                    Text(if (state.llmSaved) "已保存" else "保存模型配置")
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
