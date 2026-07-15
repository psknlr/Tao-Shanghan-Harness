package org.impfai.hermes.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.data.ServerStatus
import org.impfai.hermes.ui.common.SIX_CHANNELS
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val status: ServerStatus? = null,
        val localTotal: Int = 0,
        val localCanonical: Int = 0,
        val favorites: List<SearchHit> = emptyList(),
        val simplified: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { refresh() }

    fun refresh() {
        if (_state.value.loading) return   // init 與進屏 LaunchedEffect 去重
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val settings = container.settings.current()
            val (total, canonical) = container.repo.localStats()
            val favorites = container.repo.favoriteHits()
            val status = if (settings.offlineOnly) null else container.repo.serverStatus()
            _state.value = UiState(
                loading = false,
                status = status,
                localTotal = total,
                localCanonical = canonical,
                favorites = favorites,
                simplified = settings.simplifiedDisplay,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenSearch: (query: String, channel: String) -> Unit,
    onOpenClause: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = rememberContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    // 返回首頁時刷新收藏/狀態（審查發現 #11）；首次組合時 init 已在載入，
    // refresh() 內部去重不會雙跑
    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text("伤寒Hermes", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "《伤寒论》证据分层研读与辨证辅助 · 医哲未来人工智能研究院（IMPF-AI）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 服務端狀態卡
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val st = state.status
                    when {
                        state.loading -> Text("检查服务端状态…",
                            style = MaterialTheme.typography.bodyMedium)
                        st == null -> Text("离线模式（仅本地语料）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        st.reachable && st.ready -> {
                            Text("服务端在线 · ${st.backend}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2E7D32))
                            Text(
                                "角色上限 ${st.roleCeiling}" +
                                    (st.effectiveRole?.let { " · 生效 $it" } ?: "") +
                                    (st.contentVersion.takeIf { it.isNotBlank() }
                                        ?.let { " · 语料 $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        st.reachable -> Text("服务端可达但未就绪：${st.detail}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                        else -> Column {
                            Text("服务端不可达（已切换本地语料）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error)
                            Text("在“我的”页配置服务端地址与访问令牌",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { onOpenSettings() })
                        }
                    }
                    Text(
                        "本地语料：${state.localTotal} 条记录（核心 ${state.localCanonical}/398）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        // 快速檢索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("检索条文：症状、方名、第12条…") },
            trailingIcon = {
                IconButton(onClick = { if (query.isNotBlank()) onOpenSearch(query, "") }) {
                    Icon(Icons.Filled.Search, contentDescription = "检索")
                }
            },
            singleLine = true,
        )

        // 六經入口
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("六经", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SIX_CHANNELS.forEach { ch ->
                    AssistChip(
                        onClick = { onOpenSearch("", ch) },
                        label = { Text(ch.display(state.simplified)) },
                    )
                }
            }
        }

        // 收藏
        if (state.favorites.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text("收藏", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
                state.favorites.forEach { hit ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenClause(hit.clauseId) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                hit.clauseNumber?.let { "第 $it 条" } ?: hit.clauseId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                hit.text.display(state.simplified),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text(
            "本应用是中医古籍学习与研究辅助工具，不构成诊断或治疗建议。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
