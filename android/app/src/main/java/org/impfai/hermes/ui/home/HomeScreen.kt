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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.R
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.data.ServerStatus
import org.impfai.hermes.ui.common.SIX_CHANNELS
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

/**
 * 首頁（外部評審建議八落地）：從「工具集合」轉為「古籍醫學智能體」——
 * 首屏 = 產品定位 + 四個行動卡（開始諮詢/古籍檢索/方證辨證/今日條文），
 * 服務端狀態降為次要信息行；「今日條文」按天確定性輪換核心條文，
 * 提供每日學習的產品鉤子。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val status: ServerStatus? = null,
        val localTotal: Int = 0,
        val localCanonical: Int = 0,
        val favorites: List<SearchHit> = emptyList(),
        val dailyClause: SearchHit? = null,
        val simplified: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 併發去重用獨立標誌：老實現「if (loading) return」+ 初始
     *  loading=true 會讓 init 的首次刷新直接返回，首頁永卡加載態。 */
    private var inFlight = false

    init { refresh() }

    fun refresh() {
        if (inFlight) return
        inFlight = true
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loading = true)
                val settings = container.settings.current()
                val (total, canonical) = container.repo.localStats()
                val favorites = container.repo.favoriteHits()
                val daily = container.localStore.dailyHit(LocalDate.now().toEpochDay())
                val status = if (settings.offlineOnly) null else container.repo.serverStatus()
                _state.value = UiState(
                    loading = false,
                    status = status,
                    localTotal = total,
                    localCanonical = canonical,
                    favorites = favorites,
                    dailyClause = daily,
                    simplified = settings.simplifiedDisplay,
                )
            } finally {
                inFlight = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenSearch: (query: String, channel: String) -> Unit,
    onOpenClause: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenMatch: () -> Unit,
) {
    val container = rememberContainer()
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 產品定位首屏
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.developer_name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 行動卡 2×2
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionCard(
                Modifier.weight(1f), Icons.Filled.SmartToy,
                stringResource(R.string.home_action_consult),
                stringResource(R.string.home_action_consult_sub),
                onClick = onOpenAgent,
            )
            ActionCard(
                Modifier.weight(1f), Icons.Filled.Search,
                stringResource(R.string.home_action_explore),
                stringResource(R.string.home_action_explore_sub),
                onClick = { onOpenSearch("", "") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionCard(
                Modifier.weight(1f), Icons.AutoMirrored.Filled.MenuBook,
                stringResource(R.string.home_action_match),
                stringResource(R.string.home_action_match_sub),
                onClick = onOpenMatch,
            )
            ActionCard(
                Modifier.weight(1f), Icons.Filled.AutoStories,
                stringResource(R.string.home_action_daily),
                stringResource(R.string.home_action_daily_sub),
                onClick = { state.dailyClause?.let { onOpenClause(it.clauseId) } },
            )
        }

        // 今日條文
        state.dailyClause?.let { hit ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpenClause(hit.clauseId) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoStories, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.home_daily_title,
                                hit.clauseNumber ?: 0),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        hit.text.display(state.simplified),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                    hit.sixChannel?.let {
                        Text(it.display(state.simplified),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // 服務端狀態（降為次要信息行）
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val st = state.status
                    when {
                        state.loading -> Text(stringResource(R.string.home_status_checking),
                            style = MaterialTheme.typography.bodySmall)
                        st == null -> Text(stringResource(R.string.home_status_offline_mode),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                        st.reachable && st.ready -> Text(
                            stringResource(R.string.home_status_online, st.backend) +
                                (st.effectiveRole?.let { " · 生效 $it" } ?: "") +
                                (st.contentVersion.takeIf { it.isNotBlank() }
                                    ?.let { " · 语料 $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32))
                        st.reachable -> Text(
                            stringResource(R.string.home_status_not_ready, st.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        else -> Text(
                            stringResource(R.string.home_status_unreachable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { onOpenSettings() })
                    }
                    Text(
                        stringResource(R.string.home_local_stats,
                            state.localTotal, state.localCanonical),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.home_refresh))
                }
            }
        }

        // 六經入口
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_six_channels),
                style = MaterialTheme.typography.titleSmall,
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
                    Text(stringResource(R.string.home_favorites),
                        style = MaterialTheme.typography.titleSmall,
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
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2)
        }
    }
}
