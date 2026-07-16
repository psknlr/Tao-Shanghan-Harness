package org.impfai.hermes.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.impfai.hermes.AppContainer
import org.impfai.hermes.engine.LibraryStore
import org.impfai.hermes.ui.common.NoticeBar
import org.impfai.hermes.ui.common.display
import org.impfai.hermes.ui.common.rememberContainer
import org.impfai.hermes.ui.features.FeatureScaffold

/**
 * 古籍庫書架（v1.4 Kindle 式）：收藏置頂 + 最近閱讀 + 分類書架 +
 * 全文檢索。狀態全部在 ViewModel（backstack 存續）——從閱讀器返回
 * 時檢索結果仍在（修復 v1.3 返回清零）。
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val ready: Boolean? = null,
        val tab: Int = 0,
        val simplified: Boolean = true,
        val nBooks: Int = 0,
        val categories: Map<String, Int> = emptyMap(),
        val query: String = "",
        val category: String = "",
        val results: List<LibraryStore.Unit_> = emptyList(),
        val favorites: Set<String> = emptySet(),
        val recents: List<LibraryStore.Unit_> = emptyList(),
        val grepQuery: String = "",
        val grepHits: List<LibraryStore.GrepHit> = emptyList(),
        val grepRunning: Boolean = false,
        val grepProgress: Float = 0f,
        val grepSearched: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val s = container.settings.current()
            val ok = container.libraryStore.ensureCatalog()
            if (!ok) {
                _state.value = _state.value.copy(
                    ready = false, simplified = s.simplifiedDisplay)
                return@launch
            }
            val (nBooks, _, cats) = container.libraryStore.stats()
            _state.value = _state.value.copy(
                ready = true, simplified = s.simplifiedDisplay,
                nBooks = nBooks, categories = cats,
                favorites = s.libraryFavorites,
            )
            refreshShelf()
        }
    }

    /** 書架排序：收藏 → 體量大的經典。 */
    private suspend fun refreshShelf() {
        val st = _state.value
        val s = container.settings.current()
        val found = container.libraryStore
            .searchCatalog(st.query, st.category, limit = 60)
        val sorted = found.sortedByDescending { it.id in s.libraryFavorites }
        val recents = s.libraryRecents.mapNotNull {
            container.libraryStore.unit(it)
        }
        _state.value = _state.value.copy(
            results = sorted, favorites = s.libraryFavorites, recents = recents)
    }

    fun setTab(i: Int) { _state.value = _state.value.copy(tab = i) }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun search() = viewModelScope.launch { refreshShelf() }

    fun setCategory(c: String) {
        _state.value = _state.value.copy(
            category = if (_state.value.category == c) "" else c)
        search()
    }

    fun toggleFavorite(bookId: String) {
        viewModelScope.launch {
            container.settings.toggleLibraryFavorite(bookId)
            refreshShelf()
        }
    }

    /** 閱讀返回後刷新最近列表（VM 存續，檢索結果不動）。 */
    fun onResume() = viewModelScope.launch { refreshShelf() }

    fun setGrepQuery(q: String) {
        _state.value = _state.value.copy(grepQuery = q)
    }

    fun grep() {
        val q = _state.value.grepQuery
        if (q.isBlank() || _state.value.grepRunning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                grepRunning = true, grepSearched = false)
            val hits = container.libraryStore.grep(q) { done, total ->
                _state.value = _state.value.copy(
                    grepProgress = if (total == 0) 1f
                    else done.toFloat() / total)
            }
            _state.value = _state.value.copy(
                grepHits = hits, grepRunning = false, grepSearched = true)
        }
    }
}

private val COVER_COLORS = listOf(
    Color(0xFF2E5E4E), Color(0xFF7A4E7E), Color(0xFF9C6B30),
    Color(0xFF3F5E8C), Color(0xFF8C4A3F), Color(0xFF4E6E32),
)

private fun coverColor(u: LibraryStore.Unit_): Color =
    COVER_COLORS[((u.category.hashCode() % COVER_COLORS.size) +
        COVER_COLORS.size) % COVER_COLORS.size]

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenBook: (bookId: String, section: String) -> Unit,
) {
    val container = rememberContainer()
    val vm: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    // 從閱讀器返回：刷新收藏/最近（不清檢索狀態）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.onResume() }

    FeatureScaffold("古籍库 · 书架", onBack) { padding ->
        when (state.ready) {
            null -> Row(Modifier.fillMaxWidth().padding(32.dp),
                horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            false -> Column(Modifier.padding(padding).padding(16.dp)) {
                NoticeBar("全量古籍库未内置本包。VIP-full 版已预装 803 部；" +
                    "轻量包可连接 Hermes 服务端使用全库。", warning = true)
            }
            true -> Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = state.tab) {
                    Tab(state.tab == 0, onClick = { vm.setTab(0) },
                        text = { Text("书架") })
                    Tab(state.tab == 1, onClick = { vm.setTab(1) },
                        text = { Text("全文检索") })
                }
                when (state.tab) {
                    0 -> Bookshelf(vm, state, onOpenBook)
                    1 -> GrepTab(vm, state, onOpenBook)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bookshelf(
    vm: LibraryViewModel,
    state: LibraryViewModel.UiState,
    onOpenBook: (String, String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("书名/作者/朝代/分类（${state.nBooks} 部）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.categories.entries.take(10).forEach { (cat, n) ->
                    FilterChip(selected = state.category == cat,
                        onClick = { vm.setCategory(cat) },
                        label = { Text("${cat.display(state.simplified)} $n") })
                }
            }
        }
        if (state.recents.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("继续阅读", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.recents.forEach { u ->
                            BookCover(u, state, vm, compact = true) {
                                onOpenBook(u.id, "")
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                if (state.favorites.isEmpty()) "全部书籍（点 ☆ 收藏置顶）"
                else "书架（收藏置顶）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.results.forEach { u ->
                    BookCover(u, state, vm) { onOpenBook(u.id, "") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 書封：分類色裝幀 + 竪排感標題 + 收藏星標。 */
@Composable
private fun BookCover(
    u: LibraryStore.Unit_,
    state: LibraryViewModel.UiState,
    vm: LibraryViewModel,
    compact: Boolean = false,
    onOpen: () -> Unit,
) {
    val w = if (compact) 88.dp else 100.dp
    val h = if (compact) 118.dp else 136.dp
    val base = coverColor(u)
    Column(Modifier.width(w)) {
        Box(
            Modifier
                .width(w).height(h)
                .background(
                    Brush.verticalGradient(listOf(base, base.copy(alpha = 0.75f))),
                    RoundedCornerShape(6.dp))
                .clickable { onOpen() },
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    u.title.display(state.simplified),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5EFE0),
                    textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                if (u.dynasty.isNotBlank() || u.author.isNotBlank()) {
                    Text(
                        listOf(u.dynasty, u.author).filter { it.isNotBlank() }
                            .joinToString(" · ").display(state.simplified),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xCCF5EFE0),
                        textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = { vm.toggleFavorite(u.id) },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    if (u.id in state.favorites) Icons.Filled.Star
                    else Icons.Filled.StarBorder,
                    contentDescription = "收藏",
                    tint = if (u.id in state.favorites) Color(0xFFF3CE56)
                    else Color(0x88FFFFFF),
                )
            }
        }
        Text(
            u.category.display(state.simplified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun GrepTab(
    vm: LibraryViewModel,
    state: LibraryViewModel.UiState,
    onOpenBook: (String, String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.grepQuery,
                onValueChange = vm::setGrepQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("全库原文检索，如：奔豚 / 四逆汤") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.grep() }),
            )
        }
        item {
            Text("稀字倒排索引剪枝候选书 → 逐书流式验证（与服务端同算法）；" +
                "打开结果后返回，本页检索结果保留",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.grepRunning) {
            item {
                LinearProgressIndicator(progress = { state.grepProgress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        if (state.grepSearched && state.grepHits.isEmpty()) {
            item { NoticeBar("全库未检得该词") }
        }
        items(state.grepHits.size) { i ->
            val h = state.grepHits[i]
            Card(Modifier.fillMaxWidth()
                .clickable { onOpenBook(h.unit.id, h.section) }) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (h.unit.title + (h.section.takeIf { it.isNotBlank() }
                            ?.let { " · $it" } ?: ""))
                            .display(state.simplified),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("…${h.excerpt.display(state.simplified)}…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
