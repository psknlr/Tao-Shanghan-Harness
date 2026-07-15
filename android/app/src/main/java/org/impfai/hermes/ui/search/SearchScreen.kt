package org.impfai.hermes.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.core.model.ResultOrigin
import org.impfai.hermes.core.model.SearchHit
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.ui.common.LayerBadge
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.OriginBadge
import org.impfai.hermes.ui.common.SIX_CHANNELS
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

class SearchViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val channel: String = "",
        val loading: Boolean = false,
        val hits: List<SearchHit> = emptyList(),
        val origin: ResultOrigin? = null,
        val notice: String = "",
        val error: String = "",
        val searched: Boolean = false,
        val simplified: Boolean = true,
    )

    private val _state = MutableStateFlow(
        UiState(
            query = savedState.get<String>("query") ?: "",
            channel = savedState.get<String>("channel") ?: "",
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(simplified = s.simplifiedDisplay)
            if (_state.value.query.isNotBlank() || _state.value.channel.isNotBlank()) {
                search()
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun onChannelToggle(ch: String) {
        _state.value = _state.value.copy(
            channel = if (_state.value.channel == ch) "" else ch)
        search()
    }

    fun search() {
        val st = _state.value
        if (st.query.isBlank() && st.channel.isBlank()) return
        viewModelScope.launch {
            _state.value = st.copy(loading = true, error = "", notice = "")
            // 空查詢 + 頻道過濾：用頻道名做查詢詞（服務端/本地都按 BM25 命中提綱條文）
            val effectiveQuery = st.query.ifBlank { st.channel }
            when (val r = container.repo.search(
                effectiveQuery, st.channel.takeIf { it.isNotBlank() })) {
                is RepoResult.Data -> _state.value = _state.value.copy(
                    loading = false, hits = r.value.hits, origin = r.origin,
                    notice = r.notice ?: "", searched = true)
                is RepoResult.Error -> _state.value = _state.value.copy(
                    loading = false, hits = emptyList(), origin = null,
                    error = "${r.code}: ${r.message}", searched = true)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpenClause: (String) -> Unit) {
    val container = rememberContainer()
    val vm: SearchViewModel = viewModel {
        SearchViewModel(container, createSavedStateHandle())
    }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.width(0.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("症状、脉象、方名、第12条…") },
            trailingIcon = {
                IconButton(onClick = vm::search) {
                    Icon(Icons.Filled.Search, contentDescription = "检索")
                }
            },
            singleLine = true,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SIX_CHANNELS.forEach { ch ->
                FilterChip(
                    selected = state.channel == ch,
                    onClick = { vm.onChannelToggle(ch) },
                    label = { Text(ch.display(state.simplified)) },
                )
            }
        }

        if (state.loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        if (state.error.isNotBlank()) {
            NoticeBar(state.error, warning = true)
        }
        if (state.notice.isNotBlank()) {
            NoticeBar(state.notice)
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        ) {
            if (state.searched && !state.loading && state.hits.isEmpty()
                && state.error.isBlank()) {
                item {
                    Text("未找到相关条文", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.hits, key = { it.clauseId + it.matchSource }) { hit ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenClause(hit.clauseId) },
                ) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                hit.clauseNumber?.let { "第 $it 条" } ?: hit.clauseId,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            LayerBadge(hit.layer, hit.layerLabel.display(state.simplified))
                            state.origin?.let { OriginBadge(it) }
                            Spacer(Modifier.weight(1f))
                            hit.sixChannel?.takeIf { it.isNotBlank() }?.let {
                                Text(it.display(state.simplified),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(
                            hit.text.display(state.simplified),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                        )
                        if (hit.formulas.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                hit.formulas.forEach { f ->
                                    SuggestionChip(
                                        onClick = { onOpenClause(hit.clauseId) },
                                        label = {
                                            Text(f.display(state.simplified),
                                                style = MaterialTheme.typography.labelSmall)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
