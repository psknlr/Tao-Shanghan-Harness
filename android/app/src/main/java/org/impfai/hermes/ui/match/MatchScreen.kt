package org.impfai.hermes.ui.match

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.impfai.hermes.AppContainer
import org.impfai.hermes.core.model.MatchData
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.engine.LocalClauseStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.SafetyNoticeBar
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.SIX_CHANNELS
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

/** JSON 雜項字段（conflicts/contraindications/evidence）的顯示降解。 */
internal fun jsonToLabel(e: JsonElement): String = when (e) {
    is JsonPrimitive -> e.contentOrNull ?: e.toString()
    is JsonObject -> e.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .joinToString(" · ").ifBlank { e.toString() }
    else -> e.toString()
}

internal fun evidenceClauseId(e: JsonElement): String? = when (e) {
    is JsonPrimitive -> e.contentOrNull?.takeIf { it.startsWith("SHL_") }
    is JsonObject -> (e["clause_id"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

class MatchViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val symptoms: List<String> = emptyList(),
        val pulse: List<String> = emptyList(),
        val channel: String = "",
        val loading: Boolean = false,
        val result: MatchData? = null,
        val error: String = "",
        val simplified: Boolean = true,
        val rules: List<LocalClauseStore.FormulaRule> = emptyList(),
        val ruleFilter: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            _state.value = _state.value.copy(
                simplified = s.simplifiedDisplay,
                rules = container.repo.formulaRules(),
            )
        }
    }

    fun addSymptom(s: String) {
        val v = s.trim()
        if (v.isNotBlank() && v !in _state.value.symptoms) {
            _state.value = _state.value.copy(symptoms = _state.value.symptoms + v)
        }
    }

    fun removeSymptom(s: String) {
        _state.value = _state.value.copy(symptoms = _state.value.symptoms - s)
    }

    fun addPulse(p: String) {
        val v = p.trim()
        if (v.isNotBlank() && v !in _state.value.pulse) {
            _state.value = _state.value.copy(pulse = _state.value.pulse + v)
        }
    }

    fun removePulse(p: String) {
        _state.value = _state.value.copy(pulse = _state.value.pulse - p)
    }

    fun toggleChannel(ch: String) {
        _state.value = _state.value.copy(
            channel = if (_state.value.channel == ch) "" else ch)
    }

    fun setRuleFilter(q: String) {
        _state.value = _state.value.copy(ruleFilter = q)
    }

    fun match() {
        val st = _state.value
        if (st.symptoms.isEmpty()) {
            _state.value = st.copy(error = "请先添加至少一个症状")
            return
        }
        viewModelScope.launch {
            _state.value = st.copy(loading = true, error = "", result = null)
            when (val r = container.repo.match(st.symptoms, st.pulse, st.channel)) {
                is RepoResult.Data -> _state.value = _state.value.copy(
                    loading = false, result = r.value)
                is RepoResult.Error -> _state.value = _state.value.copy(
                    loading = false, error = "${r.code}: ${r.message}")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchScreen(onOpenClause: (String) -> Unit) {
    val container = rememberContainer()
    val vm: MatchViewModel = viewModel { MatchViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("方证匹配") })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("方剂库") })
        }
        when (tab) {
            0 -> MatchTab(vm, state, onOpenClause)
            1 -> FormulaLibraryTab(vm, state, onOpenClause)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchTab(
    vm: MatchViewModel,
    state: MatchViewModel.UiState,
    onOpenClause: (String) -> Unit,
) {
    var symptomInput by remember { mutableStateOf("") }
    var pulseInput by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = symptomInput,
                        onValueChange = { symptomInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("症状，如：恶寒、发热、无汗") },
                        singleLine = true,
                    )
                    IconButton(onClick = {
                        vm.addSymptom(symptomInput); symptomInput = ""
                    }) { Icon(Icons.Filled.Add, contentDescription = "添加症状") }
                }
                if (state.symptoms.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.symptoms.forEach { s ->
                            InputChip(selected = true, onClick = { vm.removeSymptom(s) },
                                label = { Text(s) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "移除",
                                        modifier = Modifier.padding(0.dp))
                                })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = pulseInput,
                        onValueChange = { pulseInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("脉象，如：浮紧") },
                        singleLine = true,
                    )
                    IconButton(onClick = { vm.addPulse(pulseInput); pulseInput = "" }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加脉象")
                    }
                }
                if (state.pulse.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.pulse.forEach { p ->
                            InputChip(selected = true, onClick = { vm.removePulse(p) },
                                label = { Text(p) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "移除")
                                })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SIX_CHANNELS.forEach { ch ->
                        FilterChip(selected = state.channel == ch,
                            onClick = { vm.toggleChannel(ch) },
                            label = { Text(ch.display(state.simplified)) })
                    }
                }
                Button(onClick = vm::match, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loading) "匹配中…" else "开始匹配（需服务端）")
                }
                if (state.error.isNotBlank()) NoticeBar(state.error, warning = true)
            }
        }

        state.result?.let { res ->
            if (res.assistiveOnly || res.safetyNotice.isNotBlank()) {
                item {
                    SafetyNoticeBar(
                        res.safetyNotice.ifBlank {
                            "辅助学习参考，不构成诊疗建议"
                        }.display(state.simplified))
                }
            }
            if (res.roleProjection != null) {
                item {
                    NoticeBar("患者模式：候选方剂结构已由服务端投影移除", warning = true)
                }
            }
            items(res.matchedFormulaPatterns) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.formula.display(state.simplified),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            ReleaseBadge(p.releaseLevel)
                        }
                        LinearProgressIndicator(
                            progress = { (p.matchScore.toFloat() / 10f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("匹配分 ${"%.2f".format(p.matchScore)}" +
                            (p.sixChannel?.let { " · ${it.display(state.simplified)}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.corePattern.isNotBlank()) {
                            Text(p.corePattern.display(state.simplified),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (p.matchedFindings.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                p.matchedFindings.forEach {
                                    SuggestionChip(onClick = {},
                                        label = { Text("✓ ${it.display(state.simplified)}",
                                            style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                        }
                        p.conflicts.takeIf { it.isNotEmpty() }?.let { cs ->
                            Text("反证：${cs.joinToString("；") { jsonToLabel(it) }
                                .display(state.simplified)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        p.contraindications.takeIf { it.isNotEmpty() }?.let { cs ->
                            Text("禁忌：${cs.joinToString("；") { jsonToLabel(it) }
                                .display(state.simplified)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold)
                        }
                        val evidenceIds = p.evidence.mapNotNull { evidenceClauseId(it) }
                        if (evidenceIds.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                evidenceIds.distinct().take(6).forEach { cid ->
                                    SuggestionChip(onClick = { onOpenClause(cid) },
                                        label = { Text(cid,
                                            style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                        }
                        if (p.interpretationWarning.isNotBlank()) {
                            Text(p.interpretationWarning.display(state.simplified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseBadge(level: String) {
    if (level.isBlank()) return
    val color = when (level) {
        "gold" -> androidx.compose.ui.graphics.Color(0xFFB8860B)
        "silver" -> androidx.compose.ui.graphics.Color(0xFF708090)
        else -> androidx.compose.ui.graphics.Color(0xFF8B5A2B)
    }
    Text(level, style = MaterialTheme.typography.labelSmall, color = color,
        fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaLibraryTab(
    vm: MatchViewModel,
    state: MatchViewModel.UiState,
    onOpenClause: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val filtered = state.rules.filter { r ->
        state.ruleFilter.isBlank() ||
            r.formula.contains(state.ruleFilter) ||
            r.corePattern.contains(state.ruleFilter) ||
            org.impfai.hermes.engine.TextNorm.normalizeQuery(state.ruleFilter)
                .let { q -> r.formula.contains(q) || r.corePattern.contains(q) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.ruleFilter,
                onValueChange = vm::setRuleFilter,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                placeholder = { Text("筛选方名/证候（离线可用，共 ${state.rules.size} 方）") },
                singleLine = true,
            )
        }
        items(filtered, key = { it.ruleId }) { r ->
            val isOpen = r.ruleId in expanded
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (isOpen) expanded - r.ruleId else expanded + r.ruleId
                    },
            ) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.formula.display(state.simplified),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        ReleaseBadge(r.releaseLevel)
                    }
                    Text(
                        (r.sixChannelScope.joinToString("、") + " · " + r.corePattern)
                            .display(state.simplified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isOpen) {
                        if (r.coreSymptoms.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.coreSymptoms.forEach {
                                    SuggestionChip(onClick = {},
                                        label = { Text(it.display(state.simplified),
                                            style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                        }
                        if (r.corePulse.isNotEmpty()) {
                            Text("脉：${r.corePulse.joinToString("、")
                                .display(state.simplified)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.composition.isNotEmpty()) {
                            SectionCard("组成") {
                                r.composition.forEach { c ->
                                    Text("${c.herb} ${c.doseProcessing}"
                                        .display(state.simplified),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        r.administrationNotes.forEach {
                            Text(it.display(state.simplified),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.supportingClauses.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                r.supportingClauses.take(6).forEach { cid ->
                                    SuggestionChip(onClick = { onOpenClause(cid) },
                                        label = { Text(cid,
                                            style = MaterialTheme.typography.labelSmall) })
                                }
                            }
                        }
                        if (r.interpretationWarning.isNotBlank()) {
                            Text(r.interpretationWarning.display(state.simplified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
