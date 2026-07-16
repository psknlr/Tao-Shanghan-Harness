package org.impfai.hermes.ui.clause

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.core.model.ClauseDetail
import org.impfai.hermes.core.model.ResultOrigin
import org.impfai.hermes.data.RepoResult
import org.impfai.hermes.ui.common.LayerBadge
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.OriginBadge
import org.impfai.hermes.ui.common.SafetyNoticeBar
import org.impfai.hermes.ui.common.SectionCard
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer

class ClauseViewModel(
    private val container: AppContainer,
    private val clauseRef: String,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val detail: ClauseDetail? = null,
        val origin: ResultOrigin? = null,
        val notice: String = "",
        val error: String = "",
        val favorite: Boolean = false,
        val simplified: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            when (val r = container.repo.clause(clauseRef)) {
                is RepoResult.Data -> _state.value = UiState(
                    loading = false,
                    detail = r.value,
                    origin = r.origin,
                    notice = r.notice ?: "",
                    favorite = r.value.clauseId in settings.favorites,
                    simplified = settings.simplifiedDisplay,
                )
                is RepoResult.Error -> _state.value = UiState(
                    loading = false, error = "${r.code}: ${r.message}",
                    simplified = settings.simplifiedDisplay,
                )
            }
        }
    }

    fun toggleFavorite() {
        val id = _state.value.detail?.clauseId ?: return
        viewModelScope.launch {
            // 以持久化後的真值回寫，不做盲反轉（審查發現 #8：
            // init 快照可能已過期，盲反轉會與 DataStore 實際狀態脫節）
            container.settings.toggleFavorite(id)
            val nowFavorite = id in container.settings.current().favorites
            _state.value = _state.value.copy(favorite = nowFavorite)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClauseScreen(
    clauseRef: String,
    onOpenClause: (String) -> Unit,
    onBack: () -> Unit,
    onAskAi: (question: String) -> Unit = {},
    onOpenBook: (bookTitle: String, locate: String) -> Unit = { _, _ -> },
) {
    val container = rememberContainer()
    val vm: ClauseViewModel = viewModel(key = "clause-$clauseRef") {
        ClauseViewModel(container, clauseRef)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val d = state.detail
    val simplified = state.simplified

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        d?.clauseNumber?.let { "第 $it 条" }
                            ?: d?.clauseId ?: clauseRef,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (state.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "收藏",
                            tint = if (state.favorite) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Row(Modifier.fillMaxWidth().padding(padding).padding(32.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.error.isNotBlank()) {
                item { NoticeBar(state.error, warning = true) }
                return@LazyColumn
            }
            if (d == null) return@LazyColumn

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.notice.isNotBlank()) NoticeBar(state.notice)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (d.layerLabel.isNotBlank()) {
                            Text(
                                d.layerLabel.display(simplified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        state.origin?.let { OriginBadge(it) }
                        Text(
                            listOfNotNull(
                                d.chapter.takeIf { it.isNotBlank() },
                                d.sixChannel?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").display(simplified),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("原文") {
                    Text(
                        d.text.display(simplified),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 18.sp, lineHeight = 30.sp),
                    )
                    TextButton(onClick = {
                        val label = d.clauseNumber?.let { "第${it}条" } ?: d.clauseId
                        onAskAi("请解读《伤寒论》$label：「${d.text}」" +
                            "——病机、方证要点与相近条文的鉴别。")
                    }) { Text("✦ AI 解读 / 围绕本条对话") }
                }
            }

            items(d.formulaBlocks.size) { i ->
                val fb = d.formulaBlocks[i]
                SectionCard("方剂 · ${fb.formulaName.display(simplified)}") {
                    if (fb.composition.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            fb.composition.forEach { c ->
                                Row {
                                    Text(c.herb.display(simplified),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(end = 8.dp))
                                    Text(c.doseProcessing.display(simplified),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (fb.preparation.isNotBlank()) {
                        Text("煎法：${fb.preparation.display(simplified)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (fb.administration.isNotBlank()) {
                        Text("服法：${fb.administration.display(simplified)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    fb.postNotes.forEach {
                        Text(it.display(simplified), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            d.entities?.let { e ->
                if (e.symptoms.isNotEmpty() || e.pulse.isNotEmpty()) {
                    item {
                        SectionCard("证候要素") {
                            if (e.symptoms.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    e.symptoms.forEach {
                                        SuggestionChip(onClick = {}, label = {
                                            Text(it.display(simplified),
                                                style = MaterialTheme.typography.labelSmall)
                                        })
                                    }
                                }
                            }
                            if (e.pulse.isNotEmpty()) {
                                Text("脉：${e.pulse.joinToString("、").display(simplified)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (e.negatedFindings.isNotEmpty()) {
                                Text(
                                    "否定语境：${e.negatedFindings.joinToString("、").display(simplified)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (d.variants.isNotEmpty()) {
                item {
                    SectionCard("版本异文（B 层）") {
                        d.variants.forEach { v ->
                            Column(Modifier.padding(bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(v.book.display(simplified),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold)
                                Text(v.text.display(simplified),
                                    style = MaterialTheme.typography.bodySmall)
                                v.differences.take(4).forEach { diff ->
                                    Text("· ${diff.display(simplified)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (d.commentaries.isNotEmpty()) {
                item {
                    SectionCard("注家（C 层）") {
                        d.commentaries.forEach { c ->
                            Column(Modifier.padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${c.commentator} · ${c.book}".display(simplified),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(c.text.display(simplified),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (d.initialRules.isNotEmpty()) {
                item {
                    SectionCard("归纳规则") {
                        d.initialRules.forEach { r ->
                            Column(Modifier.padding(bottom = 6.dp)) {
                                Text(
                                    "${r.type} · ${r.strength} · ${r.release}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(r.interpretation.display(simplified),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            if (d.relations.isNotEmpty()) {
                item {
                    SectionCard("条文关系") {
                        d.relations.forEach { rel ->
                            val target = rel.clauseId
                            when {
                                // 條文 → 條文
                                target.startsWith("SHL_") -> TextButton(
                                    onClick = { onOpenClause(target) }) {
                                    Text("${rel.relationType} → $target"
                                        .display(simplified),
                                        style = MaterialTheme
                                            .typography.labelMedium)
                                }
                                // "書名:pNNN" 類注家/文獻引用 → 古籍庫開卷
                                //（v1.4 修復：此前誤當條文 id 導致 NOT_FOUND）
                                target.contains(":") -> TextButton(
                                    onClick = {
                                        // 帶條文文字定位：開卷直達包含段落
                                        onOpenBook(target.substringBefore(":"),
                                            d.text.take(14))
                                    }) {
                                    Text(("${rel.relationType} → " +
                                        "${target.substringBefore(":")} 开卷 ▸")
                                        .display(simplified),
                                        style = MaterialTheme
                                            .typography.labelMedium)
                                }
                                else -> Text(
                                    "${rel.relationType} → $target"
                                        .display(simplified),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            d.roleProjection?.let {
                item {
                    NoticeBar(
                        "患者模式投影：处方组成/剂量等可执行诊疗信息已由服务端移除",
                        warning = true,
                    )
                }
            }

            if (d.safetyNotice.isNotBlank()) {
                item { SafetyNoticeBar(d.safetyNotice.display(simplified)) }
            }
        }
    }
}
