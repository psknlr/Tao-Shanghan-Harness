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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.impfai.hermes.R
import org.impfai.hermes.core.audit.AuditLog
import org.impfai.hermes.core.settings.AppSettings
import org.impfai.hermes.data.ServerStatus
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.rememberContainer

/**
 * 「我的」頁（外部評審建議五/七/九落地）：
 * - 身份與角色前置——普通用戶先選「我是誰」，服務端地址/令牌歸入
 *   「服務端接入」小節（工程配置不再是頁面第一印象）；
 * - 訪問令牌經 Android Keystore 加密存儲，Keystore 不可用時明示降級；
 * - 諮詢審計記錄（本機）：每次智能體問答/方證匹配的證據軌跡可回看。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val loaded: Boolean = false,
        val baseUrl: String = "",
        val token: String = "",
        val role: String = "student",
        val simplified: Boolean = true,
        val offlineOnly: Boolean = false,
        val secureStorage: Boolean = true,
        val testing: Boolean = false,
        val testResult: String = "",
        val testOk: Boolean = false,
        val saved: Boolean = false,
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
                offlineOnly = s.offlineOnly, secureStorage = s.secureTokenStorage,
            )
            loadAudit()
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
    var showAudit by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        // 身份前置（評審建議九的改進式落地：完整賬號體系依賴服務端
        // OIDC，先把「我是誰」從工程配置里拆出來放到第一位）
        SectionCard(stringResource(R.string.settings_identity)) {
            Text(stringResource(R.string.settings_identity_caption),
                style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppSettings.ROLES.forEach { r ->
                    FilterChip(
                        selected = state.role == r,
                        onClick = { vm.edit(role = r); vm.save() },
                        label = { Text(AppSettings.ROLE_LABELS[r] ?: r) },
                    )
                }
            }
            Text(stringResource(R.string.settings_identity_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.settings_server)) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = { vm.edit(baseUrl = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text("https://hermes.example.org/") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = { vm.edit(token = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_token)) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save) {
                    Text(if (state.saved) stringResource(R.string.settings_saved)
                    else stringResource(R.string.settings_save))
                }
                OutlinedButton(onClick = vm::test, enabled = !state.testing) {
                    Text(if (state.testing) stringResource(R.string.settings_testing)
                    else stringResource(R.string.settings_test))
                }
            }
            if (state.testResult.isNotBlank()) {
                NoticeBar(state.testResult, warning = !state.testOk)
            }
            if (state.baseUrl.startsWith("http://")) {
                Text(
                    stringResource(R.string.settings_http_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 令牌存儲安全狀態（評審建議五）
            Text(
                if (state.secureStorage) stringResource(R.string.settings_token_secure)
                else stringResource(R.string.settings_token_insecure),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.secureStorage)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.settings_no_provider_keys),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(stringResource(R.string.settings_display)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_simplified),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_simplified_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.simplified, onCheckedChange = vm::setSimplified)
            }
        }

        SectionCard(stringResource(R.string.settings_offline)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_offline_only),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_offline_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.offlineOnly, onCheckedChange = vm::setOfflineOnly)
            }
        }

        // 諮詢審計記錄（評審建議七：本機審計軌跡）
        SectionCard(stringResource(R.string.settings_audit)) {
            Text(
                stringResource(R.string.settings_audit_note, state.auditCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showAudit = !showAudit
                    if (showAudit) vm.loadAudit()
                }) {
                    Text(if (showAudit) stringResource(R.string.settings_audit_hide)
                    else stringResource(R.string.settings_audit_show))
                }
                if (state.auditCount > 0) {
                    TextButton(onClick = vm::clearAudit) {
                        Text(stringResource(R.string.settings_audit_clear),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (showAudit) {
                if (state.auditEntries.isEmpty()) {
                    Text(stringResource(R.string.settings_audit_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.auditEntries.forEach { e ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${e.caseId} · ${if (e.kind == "agent") "智能体" else "方证匹配"}" +
                                (e.effectiveRole ?: e.requestedRole)
                                    .takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(e.input,
                            style = MaterialTheme.typography.bodySmall,
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

        SectionCard(stringResource(R.string.settings_about)) {
            Text("伤寒Hermes v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_about_dev,
                stringResource(R.string.developer_name)),
                style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_about_arch),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
