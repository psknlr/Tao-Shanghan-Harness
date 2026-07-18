package org.impfai.hermes.ui.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
import org.impfai.hermes.engine.SkillStore
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.rememberContainer

private val CATEGORY_LABELS = mapOf(
    "catalog" to "总目录", "six_channels" to "六经",
    "formula_patterns" to "方证", "mistreatment" to "误治传变",
    "contraindications" to "禁忌法度", "therapy" to "治法",
    "transformation" to "传变", "differential" to "方证鉴别",
    "clause_explainer" to "条文解释", "variants" to "版本异文",
    "paper_writer" to "论文写作", "patient_education" to "患者教育",
)

class SkillsViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val entries: List<SkillStore.SkillEntry> = emptyList(),
        val filter: String = "",
        val selected: SkillStore.SkillEntry? = null,
        val doc: SkillStore.SkillDoc? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = false, entries = container.skillStore.list())
        }
    }

    fun setFilter(q: String) {
        _state.value = _state.value.copy(filter = q)
    }

    fun open(entry: SkillStore.SkillEntry) {
        viewModelScope.launch {
            _state.value = _state.value.copy(selected = entry, doc = null)
            _state.value = _state.value.copy(doc = container.skillStore.read(entry))
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(selected = null, doc = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val vm: SkillsViewModel = viewModel { SkillsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.selected?.let {
                            "${CATEGORY_LABELS[it.category] ?: it.category} · ${it.name}"
                        } ?: "Skill 库（${state.entries.size} 个已内置）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selected != null) vm.closeDetail() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val sel = state.selected
        if (sel != null) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    val doc = state.doc
                    if (doc == null) {
                        Row(Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        SectionCard("SKILL.md") {
                            Text(
                                doc.markdown,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "附：rules.jsonl ${doc.rulesCount} 条 · " +
                                    "examples.jsonl ${doc.examplesCount} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        val filtered = state.entries.filter { e ->
            state.filter.isBlank() || e.name.contains(state.filter) ||
                e.category.contains(state.filter) ||
                (CATEGORY_LABELS[e.category] ?: "").contains(state.filter)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.filter,
                    onValueChange = vm::setFilter,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("筛选 Skill（方证 / 六经 / 误治…）") },
                    singleLine = true,
                )
            }
            if (state.loading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(filtered, key = { it.path }) { e ->
                Card(Modifier.fillMaxWidth().clickable { vm.open(e) }) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                CATEGORY_LABELS[e.category] ?: e.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("hermes.shanghan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}
